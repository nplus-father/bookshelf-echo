package wiki.nplus.airadar.ops

import com.rabbitmq.client.GetResponse
import wiki.nplus.airadar.common.Db
import wiki.nplus.airadar.common.ItemRepository
import wiki.nplus.airadar.common.ItemState
import wiki.nplus.airadar.common.Rabbit
import wiki.nplus.airadar.common.RabbitTopology
import wiki.nplus.airadar.common.StageMessage
import java.time.LocalDate
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "dlq" -> dlq(args)
        "republish" -> republish(args)
        "republish-essay" -> republishEssay(args)
        "redrive" -> redrive(args)
        "shortlist" -> shortlist(args)
        else -> usage()
    }
}

private fun shortlist(args: Array<String>) {
    val ttlDays = args.getOrNull(1)?.toIntOrNull() ?: 7
    val repo = ItemRepository(Db.dataSource("ops"))
    val pending = repo.shortlistPending(ttlDays)
    if (pending.isEmpty()) {
        println("shortlist pool is empty (uncomposed picks from the last $ttlDays day(s))")
        return
    }
    pending.forEach { row ->
        println("#${row.itemId}  ${row.shortlistedAt.toLocalDate()}  ${row.title}")
        println("    ${row.url}")
        println("    ${row.rationale}")
    }
    println("${pending.size} pick(s) pending composition (TTL $ttlDays day(s))")
}

private fun redrive(args: Array<String>) {
    val apply = args.contains("--apply")
    val repo = ItemRepository(Db.dataSource("ops"))
    val work = listOf(
        ItemState.ENRICHED to RabbitTopology.MATCH_QUEUE,
        ItemState.MATCHED to RabbitTopology.DIGEST_QUEUE,
        ItemState.DIGESTED to RabbitTopology.PUBLISH_QUEUE,
    ).map { (state, queue) -> Triple(state, queue, repo.itemIdsInState(state)) }

    work.forEach { (state, queue, ids) -> println("${ids.size} item(s) in $state → $queue") }
    val total = work.sumOf { it.third.size }
    if (total == 0) {
        println("nothing to redrive")
        return
    }
    if (!apply) {
        println("dry run — pass --apply to re-queue them")
        return
    }

    val connection = Rabbit.connect("ops")
    val channel = connection.createChannel()
    Rabbit.declareTopology(channel)
    work.forEach { (state, queue, ids) ->
        ids.forEach { id -> Rabbit.publish(channel, "", queue, StageMessage(id).encode()) }
        if (ids.isNotEmpty()) println("re-queued ${ids.size} $state item(s) onto $queue")
    }
    channel.close()
    connection.close()
}

private fun republish(args: Array<String>) {
    val day = args.getOrNull(1)?.let {
        runCatching { LocalDate.parse(it) }.getOrElse { usage() }
    } ?: usage()
    val repo = ItemRepository(Db.dataSource("ops"))
    val itemId = repo.anyItemDigestedOn(day)
    if (itemId == null) {
        println("no digests created on $day — nothing to rebuild")
        exitProcess(1)
    }
    val connection = Rabbit.connect("ops")
    val channel = connection.createChannel()
    Rabbit.declareTopology(channel)
    Rabbit.publish(channel, "", RabbitTopology.PUBLISH_QUEUE, StageMessage(itemId).encode())
    println("queued rebuild of daily/$day.md (via item $itemId)")
    channel.close()
    connection.close()
}

private fun republishEssay(args: Array<String>) {
    val day = args.getOrNull(1)?.let {
        runCatching { LocalDate.parse(it) }.getOrElse { usage() }
    } ?: usage()
    val repo = ItemRepository(Db.dataSource("ops"))
    val itemId = repo.essayItemOn(day)
    if (itemId == null) {
        println("no essay composed on $day — nothing to rebuild")
        exitProcess(1)
    }
    val connection = Rabbit.connect("ops")
    val channel = connection.createChannel()
    Rabbit.declareTopology(channel)
    Rabbit.publish(channel, "", RabbitTopology.PUBLISH_QUEUE, StageMessage(itemId, kind = "essay").encode())
    println("queued rebuild of essays/$day.md (via item $itemId)")
    channel.close()
    connection.close()
}

private fun dlq(args: Array<String>) {
    val connection = Rabbit.connect("ops")
    val channel = connection.createChannel()
    Rabbit.declareTopology(channel)

    when (args.getOrNull(1)) {
        "list" -> {
            val limit = args.getOrNull(2)?.toIntOrNull() ?: 10
            val total = count(channel)
            val peeked = drain(channel, limit) { msg ->
                println("[origin=${origin(msg)}] error=${header(msg, "x-error")} body=${String(msg.body).take(120)}")
            }
            channel.basicNack(0, true, true)
            println("$peeked message(s) shown (of $total in ${RabbitTopology.DLQ})")
        }

        "replay" -> {
            val limit = args.getOrNull(2)?.toIntOrNull() ?: Int.MAX_VALUE
            val moved = drain(channel, limit) { msg ->
                val target = origin(msg) ?: error("message has no ${RabbitTopology.ORIGIN_QUEUE_HEADER} header, cannot replay")
                Rabbit.publish(channel, "", target, String(msg.body))
                channel.basicAck(msg.envelope.deliveryTag, false)
                println("replayed → $target: ${String(msg.body).take(120)}")
            }
            println("$moved message(s) replayed, ${count(channel)} remain in ${RabbitTopology.DLQ}")
        }

        "purge" -> {
            if (args.getOrNull(2) != "--confirm") {
                println("refusing: dlq purge requires --confirm (${count(channel)} message(s) would be dropped)")
                exitProcess(1)
            }
            val purged = channel.queuePurge(RabbitTopology.DLQ).messageCount
            println("purged $purged message(s) from ${RabbitTopology.DLQ}")
        }

        else -> usage()
    }
    channel.close()
    connection.close()
}

private fun drain(channel: com.rabbitmq.client.Channel, limit: Int, action: (GetResponse) -> Unit): Int {
    var n = 0
    while (n < limit) {
        val msg = channel.basicGet(RabbitTopology.DLQ, false) ?: break
        action(msg)
        n++
    }
    return n
}

private fun origin(msg: GetResponse): String? = header(msg, RabbitTopology.ORIGIN_QUEUE_HEADER)

private fun header(msg: GetResponse, name: String): String? = msg.props.headers?.get(name)?.toString()

private fun count(channel: com.rabbitmq.client.Channel): Long =
    channel.messageCount(RabbitTopology.DLQ)

private fun usage(): Nothing {
    println(
        """
        usage: ops <command>
          dlq list [limit]        peek parked messages (non-destructive)
          dlq replay [limit]      move parked messages back to their origin queue
          dlq purge --confirm     drop everything parked
          republish <YYYY-MM-DD>  rebuild that UTC day's digest page from the DB
          republish-essay <YYYY-MM-DD>
                                  re-render that day's essay with the current
                                  EssayRenderer (migrate an old-format essay file)
          redrive [--apply]       re-queue items stranded in ENRICHED/DIGESTED
                                  (reports counts only without --apply)
          shortlist [ttl_days]    list picks awaiting composition (default TTL 7)
        """.trimIndent(),
    )
    exitProcess(2)
}

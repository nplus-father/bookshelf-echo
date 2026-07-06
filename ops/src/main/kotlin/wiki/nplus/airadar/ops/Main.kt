package wiki.nplus.airadar.ops

import com.rabbitmq.client.GetResponse
import wiki.nplus.airadar.common.Rabbit
import wiki.nplus.airadar.common.RabbitTopology
import kotlin.system.exitProcess

/**
 * DLQ operations CLI (ADR-004; runbook: docs/runbooks/dlq-replay.md).
 *
 *   dlq list [limit]        peek parked messages (non-destructive)
 *   dlq replay [limit]      move messages back to their origin queue,
 *                           retry count reset (a replay is a fresh chance)
 *   dlq purge --confirm     drop everything parked
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() != "dlq") usage()
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
            // basicGet consumed them; requeue untouched so `list` stays read-only.
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
    println("usage: ops dlq <list [limit] | replay [limit] | purge --confirm>")
    exitProcess(2)
}

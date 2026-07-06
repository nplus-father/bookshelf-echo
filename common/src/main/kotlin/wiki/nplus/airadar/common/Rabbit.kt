package wiki.nplus.airadar.common

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Envelope
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** A failure worth retrying via the ladder: network timeouts, 5xx, 429. */
class RetryableFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The daily LLM budget is spent (design doc §3.4). Not a fault: the message
 * re-parks in the longest retry tier WITHOUT consuming a retry attempt, so the
 * backlog simply waits for the next budget window.
 */
class BudgetExhausted(message: String) : Exception(message)

object Rabbit {
    private val log = LoggerFactory.getLogger(Rabbit::class.java)

    fun connect(appName: String): Connection = ConnectionFactory().apply {
        host = Config.str("RABBITMQ_HOST", "127.0.0.1")
        port = Config.int("RABBITMQ_PORT", 5672)
        username = Config.str("RABBITMQ_USER", "airadar")
        password = Config.str("RABBITMQ_PASSWORD")
    }.newConnection("ai-radar-$appName")

    fun declareTopology(channel: Channel) {
        channel.exchangeDeclare(RabbitTopology.INGEST_EXCHANGE, "topic", true)
        val quorum = mapOf<String, Any>("x-queue-type" to "quorum")
        RabbitTopology.WORK_QUEUES.forEach { queue ->
            channel.queueDeclare(queue, true, false, false, quorum)
        }
        channel.queueBind(RabbitTopology.INGEST_QUEUE, RabbitTopology.INGEST_EXCHANGE, "item.*")
        channel.queueDeclare(RabbitTopology.DLQ, true, false, false, quorum)
        // Consumer-less wait queues: TTL expiry dead-letters straight back to
        // the origin queue through the default exchange.
        RabbitTopology.WORK_QUEUES.forEach { origin ->
            RabbitTopology.RETRY_TIERS.forEachIndexed { i, tier ->
                channel.queueDeclare(
                    RabbitTopology.retryQueue(origin, i + 1),
                    true,
                    false,
                    false,
                    mapOf(
                        "x-message-ttl" to tier.ttlMillis,
                        "x-dead-letter-exchange" to "",
                        "x-dead-letter-routing-key" to origin,
                    ),
                )
            }
        }
    }

    fun publish(channel: Channel, exchange: String, routingKey: String, body: String, headers: Map<String, Any> = emptyMap()) {
        val props = AMQP.BasicProperties.Builder()
            .deliveryMode(2)
            .contentType("application/json")
            .headers(headers)
            .build()
        channel.basicPublish(exchange, routingKey, props, body.toByteArray())
    }

    /**
     * Blocking consume loop with the ADR-003/004 contract baked in: manual ack
     * as the last step, retryable failures climb the ladder, budget exhaustion
     * re-parks without burning an attempt, anything else goes to the DLQ.
     *
     * If IDLE_EXIT_SECONDS is set the process exits once the queue has been
     * quiet that long — used by integration verification, never in production.
     */
    fun consume(channel: Channel, queue: String, registry: MeterRegistry? = null, handler: (String) -> Unit) {
        channel.basicQos(Config.int("PREFETCH", 8))
        fun outcome(name: String) = registry?.counter("airadar_messages_total", "queue", queue, "outcome", name)?.increment()
        val timer = registry?.let { Timer.builder("airadar_handle_seconds").tag("queue", queue).register(it) }
        val lastActivity = AtomicLong(System.currentTimeMillis())
        Config.int("IDLE_EXIT_SECONDS", 0).takeIf { it > 0 }?.let { idle ->
            thread(isDaemon = true, name = "idle-exit") {
                while (true) {
                    Thread.sleep(1000)
                    if (System.currentTimeMillis() - lastActivity.get() > idle * 1000L) {
                        log.info("idle for {}s, exiting (verification mode)", idle)
                        exitProcess(0)
                    }
                }
            }
        }
        channel.basicConsume(
            queue,
            false,
            object : DefaultConsumer(channel) {
                override fun handleDelivery(tag: String, envelope: Envelope, props: AMQP.BasicProperties, body: ByteArray) {
                    lastActivity.set(System.currentTimeMillis())
                    val message = String(body)
                    val start = System.nanoTime()
                    try {
                        handler(message)
                        outcome("ok")
                    } catch (e: RetryableFailure) {
                        outcome("retry")
                        routeToRetry(queue, message, props, e)
                    } catch (e: BudgetExhausted) {
                        outcome("budget_parked")
                        log.info("budget exhausted, re-parking in longest tier: {}", e.message)
                        val headers = props.headers.orEmpty().mapValues { it.value as Any }
                        publish(channel, "", RabbitTopology.retryQueue(queue, RabbitTopology.RETRY_TIERS.size), message, headers)
                    } catch (e: Exception) {
                        outcome("dlq")
                        log.error("non-retryable failure, parking in DLQ", e)
                        toDlq(queue, message, props, e)
                    }
                    timer?.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS)
                    channel.basicAck(envelope.deliveryTag, false)
                    lastActivity.set(System.currentTimeMillis())
                }

                private fun routeToRetry(origin: String, message: String, props: AMQP.BasicProperties, e: RetryableFailure) {
                    val attempt = (props.headers?.get(RabbitTopology.RETRY_COUNT_HEADER) as? Number)?.toInt()?.plus(1) ?: 1
                    if (attempt > RabbitTopology.MAX_RETRIES) {
                        log.error("retries exhausted after {} attempts, parking in DLQ", attempt - 1, e)
                        toDlq(origin, message, props, e)
                        return
                    }
                    log.warn("retryable failure (attempt {}/{}): {}", attempt, RabbitTopology.MAX_RETRIES, e.message)
                    val headers = mapOf<String, Any>(
                        RabbitTopology.RETRY_COUNT_HEADER to attempt,
                        RabbitTopology.ORIGIN_QUEUE_HEADER to origin,
                    )
                    publish(channel, "", RabbitTopology.retryQueue(origin, attempt), message, headers)
                }

                private fun toDlq(origin: String, message: String, props: AMQP.BasicProperties, e: Exception) {
                    val headers = mapOf<String, Any>(
                        RabbitTopology.ORIGIN_QUEUE_HEADER to origin,
                        "x-error" to (e.message ?: e.javaClass.simpleName).replace(Regex("\\s+"), " ").take(500),
                    )
                    publish(channel, "", RabbitTopology.DLQ, message, headers)
                }
            },
        )
    }
}

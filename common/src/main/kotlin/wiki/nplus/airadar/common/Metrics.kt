package wiki.nplus.airadar.common

import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

/**
 * Per-app Prometheus endpoint, localhost-only like everything else
 * (zero-inbound posture, ADR-006). The public dashboard never scrapes this
 * directly — it consumes the snapshots the publisher commits to the site repo.
 */
object Metrics {
    private val log = LoggerFactory.getLogger(Metrics::class.java)

    fun start(appName: String, defaultPort: Int): MeterRegistry {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val port = Config.int("METRICS_PORT", defaultPort)
        // Bind 127.0.0.1 on the host (zero-inbound, ADR-006); inside a container
        // set METRICS_BIND=0.0.0.0 so Prometheus can scrape over the Docker
        // network — the port is still never published to the host.
        val bind = Config.str("METRICS_BIND", "127.0.0.1")
        if (port > 0) {
            try {
                val server = HttpServer.create(InetSocketAddress(bind, port), 0)
                server.createContext("/metrics") { exchange ->
                    val body = registry.scrape().toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                server.start()
                log.info("{}: /metrics on {}:{}", appName, bind, port)
            } catch (e: java.io.IOException) {
                // Metrics are auxiliary — a port conflict must not kill the consumer.
                log.error("{}: /metrics endpoint disabled, cannot bind {}:{}: {}", appName, bind, port, e.toString())
            }
        }
        return registry
    }
}

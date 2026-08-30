package wiki.nplus.airadar.common

import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

object Metrics {
    private val log = LoggerFactory.getLogger(Metrics::class.java)

    fun start(appName: String, defaultPort: Int): MeterRegistry {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        Pause.register(appName, registry)
        val port = Config.int("METRICS_PORT", defaultPort)
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
                log.error("{}: /metrics endpoint disabled, cannot bind {}:{}: {}", appName, bind, port, e.toString())
            }
        }
        return registry
    }
}

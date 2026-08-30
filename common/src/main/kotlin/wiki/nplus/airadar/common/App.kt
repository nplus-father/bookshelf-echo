package wiki.nplus.airadar.common

import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

object App {
    fun main(name: String, body: () -> Unit) {
        try {
            body()
        } catch (e: Throwable) {
            LoggerFactory.getLogger(name).error("{}: fatal startup failure, exiting(1) for restart", name, e)
            exitProcess(1)
        }
    }
}

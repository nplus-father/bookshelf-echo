package wiki.nplus.airadar.digester

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import wiki.nplus.airadar.common.ItemRepository
import wiki.nplus.airadar.common.LlmCallResult
import java.util.concurrent.TimeUnit

class UsageMeter(private val repo: ItemRepository, private val registry: MeterRegistry) {
    fun <T : LlmCallResult> call(itemId: Long?, purpose: String, llm: LlmClient, block: () -> T): T {
        val started = System.nanoTime()
        val result = try {
            block()
        } catch (e: Throwable) {
            stop(started, purpose, llm.model, "error")
            if (e is UnusableResponse) record(itemId, purpose, llm, e.inputTokens, e.outputTokens)
            throw e
        }
        stop(started, purpose, llm.model, "ok")
        record(itemId, purpose, llm, result.inputTokens, result.outputTokens)
        return result
    }

    fun record(itemId: Long?, purpose: String, llm: LlmClient, inputTokens: Int, outputTokens: Int) =
        record(itemId, purpose, llm.model, inputTokens, outputTokens, llm.cost(inputTokens, outputTokens))

    fun record(itemId: Long?, purpose: String, model: String, inputTokens: Int, outputTokens: Int, costUsd: Double) {
        repo.recordUsage(itemId, purpose, model, inputTokens, outputTokens, costUsd)
        counter("airadar_llm_tokens_total", purpose, model, "type", "input").increment(inputTokens.toDouble())
        counter("airadar_llm_tokens_total", purpose, model, "type", "output").increment(outputTokens.toDouble())
        counter("airadar_llm_cost_usd_total", purpose, model).increment(costUsd)
    }

    private fun counter(name: String, purpose: String, model: String, vararg extra: String) =
        registry.counter(name, *extra, "purpose", purpose, "model", model)

    private fun stop(startedNanos: Long, purpose: String, model: String, outcome: String) =
        Timer.builder("airadar_llm_latency_seconds")
            .tags("purpose", purpose, "model", model, "outcome", outcome)
            .register(registry)
            .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS)
}

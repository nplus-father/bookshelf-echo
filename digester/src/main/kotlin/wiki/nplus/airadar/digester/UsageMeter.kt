package wiki.nplus.airadar.digester

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import wiki.nplus.airadar.common.ItemRepository
import wiki.nplus.airadar.common.LlmCallResult
import java.util.concurrent.TimeUnit

/**
 * The single write path for LLM spend: the `llm_usage` ledger row and the
 * Prometheus counters, booked together.
 *
 * They used to be separate. The digest path incremented the counters inline;
 * the curator and the essayist only ever wrote the ledger. So Grafana's LLM
 * cost panel showed the cheap per-item tier and nothing else, while the
 * pro-tier SELECT and ESSAY calls — the expensive half of the bill — were
 * invisible on it. A cost spike could not be diagnosed from the dashboard at
 * all; it had to be reconstructed from SQL. Going through here makes recording
 * spend without publishing it impossible, and the purpose/model labels let the
 * panel break the bill down by what the money actually bought.
 */
class UsageMeter(private val repo: ItemRepository, private val registry: MeterRegistry) {
    /**
     * Make an LLM call: time it, book its spend, return its result. Use this
     * rather than calling the client directly — going through one door is what
     * keeps latency, the ledger and the counters in agreement.
     *
     * A throwing call is timed too, tagged `outcome=error`. That is the case
     * worth seeing: a Gemini 60s timeout looks identical to a slow success in
     * a counter, and only the latency series separates "the model is degraded"
     * from "we stopped calling it".
     */
    fun <T : LlmCallResult> call(itemId: Long?, purpose: String, llm: LlmClient, block: () -> T): T {
        val started = System.nanoTime()
        val result = try {
            block()
        } catch (e: Throwable) {
            stop(started, purpose, llm.model, "error")
            // A generation we could not use was still a generation we paid for.
            // Only [UnusableResponse] carries usage — a timeout or a 5xx has no
            // billable answer to book, and inventing one would overstate the day.
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

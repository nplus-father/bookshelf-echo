package wiki.nplus.airadar.digester

import java.time.LocalDate

/**
 * Attempt budget for the daily jobs (ADR-009).
 *
 * The curator and the essayist both run on the CURATOR_TICK_MINUTES loop, and
 * both spend money before the row that marks the day done is written. So a
 * DETERMINISTIC failure anywhere after the LLM call — a missing migration, a
 * schema drift, a publish that cannot connect — is retried on the next tick and
 * re-billed on the next tick, every five minutes, until the daily budget
 * breaker happens to trip. That is how a single nightly pro-tier essay became
 * a night of them in 2026-07.
 *
 * The tick retry is still worth having: a Gemini 503 or a brief DB blip really
 * does clear by itself. This only caps how many times a failure that is NOT
 * clearing gets to charge us.
 *
 * In-memory on purpose. The runaway is a loop inside one process; a process
 * restart is a person deciding to try again, and should get fresh attempts.
 */
class DailyAttemptGuard(private val maxPerDay: Int) {
    private var day: LocalDate? = null
    private var used = 0

    /** Consume one attempt for [today]. False means the day's attempts are spent. */
    fun tryConsume(today: LocalDate): Boolean {
        if (day != today) {
            day = today
            used = 0
        }
        if (used >= maxPerDay) return false
        used++
        return true
    }

    /**
     * Give one attempt back — for a failure that never reached a verdict, so
     * charging the day for it caps the wrong thing (2026-08-03: three transport
     * timeouts spent the whole day's attempts without the model ever answering,
     * and the column went missing for a fault that was not ours).
     *
     * Deliberately NOT the general error path: the failure this guard exists to
     * stop is the one that keeps re-billing us, and that one always "fails"
     * too. Callers must bound how often they refund — a refund that is always
     * granted is no guard at all. A refund for any day but the current one is
     * ignored, so a late one cannot hand today free attempts.
     */
    fun refund(today: LocalDate) {
        if (day == today && used > 0) used--
    }
}

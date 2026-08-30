package wiki.nplus.airadar.digester

import java.time.LocalDate

class DailyAttemptGuard(private val maxPerDay: Int) {
    private var day: LocalDate? = null
    private var used = 0

    fun tryConsume(today: LocalDate): Boolean {
        if (day != today) {
            day = today
            used = 0
        }
        if (used >= maxPerDay) return false
        used++
        return true
    }

    fun refund(today: LocalDate) {
        if (day == today && used > 0) used--
    }
}

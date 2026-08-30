package wiki.nplus.airadar.common

object Settings {

    val rabbitmqHost: String get() = Config.str("RABBITMQ_HOST", "127.0.0.1")
    val rabbitmqUser: String get() = Config.str("RABBITMQ_USER", "airadar")

    val rabbitmqPassword: String get() = Config.str("RABBITMQ_PASSWORD")

    val dailyBudgetUsd: Double get() = Config.double("DAILY_LLM_BUDGET_USD", 0.50)

    val dailyDigestLimit: Int get() = Config.int("DAILY_DIGEST_LIMIT", 10)

    val dailyJobMaxAttempts: Int get() = Config.int("DAILY_JOB_MAX_ATTEMPTS", 3)

    val shortlistMaxPerDay: Int get() = Config.int("SHORTLIST_MAX_PER_DAY", 2)

    val shortlistTtlDays: Int get() = Config.int("SHORTLIST_TTL_DAYS", 7)

    val essayChapterChars: Int get() = Config.int("ESSAY_CHAPTER_CHARS", 12000)

    val fetchMaxChars: Int get() = Config.int("FETCH_MAX_CHARS", 20_000)

    val matchMaxAgeDays: Long get() = Config.long("MATCH_MAX_AGE_DAYS", 3)

    val matchNoResonanceDistance: Double get() = Config.double("MATCH_NO_RESONANCE_DISTANCE", 1.10)

    val snapshotIntervalMinutes: Int get() = Config.int("SNAPSHOT_INTERVAL_MINUTES", 60)
}

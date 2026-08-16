package wiki.nplus.airadar.common

/**
 * The knobs that more than one module reads.
 *
 * [Config] is the raw env reader; this is the place a *shared* default is
 * written down exactly once. The distinction matters because the old pattern —
 * every site calling `Config.int("NAME", default)` with its own copy of the
 * number — had already drifted twice by 2026-08-16, silently and in prod:
 *
 *  - `SHORTLIST_MAX_PER_DAY` was 2 in `CuratorJob` and 3 in `SnapshotJob`, so
 *    the public dashboard advertised a limit the curator had not used since
 *    `f0389fe` lowered it. Nothing was wrong at either site; they simply were
 *    not the same site.
 *  - `ESSAY_CHAPTER_CHARS` was 12000 for the essay prompt and 8000 for the
 *    publisher's quote attribution, under a comment insisting all three
 *    readers hold the same number. A quote drawn from past the 8000th
 *    character then found no source and lost its citation, silently.
 *
 * Neither is reachable from here: there is one default per knob, so the
 * question "are they the same?" cannot be asked any more.
 *
 * Deliberately NOT a home for all 75 environment variables. A knob read from
 * one place belongs at that place, next to the comment explaining it; hauling
 * them all in here would trade a duplication smell for a large-class one and
 * put every module's history in a single file. The membership rule is
 * mechanical — more than one file reads it — so it is checkable:
 *
 *     grep -rnoE 'Config\.(str|int|long|double|bool)\("[A-Z_]+"' --include='*.kt' --exclude-dir=test . \
 *       | sed 's/:[0-9]*:Config\.[a-z]*("/ /; s/"$//' | awk '{print $2, $1}' | sort -u \
 *       | awk '{n[$1]++} END{for (v in n) if (n[v] > 1) print v}'
 *
 * Anything that prints and is not below is a knob that has escaped.
 *
 * Accessors are computed, not eagerly initialised: [rabbitmqPassword] has no
 * default and throws when unset, and an object that read it at class-load
 * would take down every app that merely touched an unrelated knob.
 */
object Settings {

    // ---- broker ------------------------------------------------------------
    // Read by Rabbit.connect (AMQP) and by SnapshotJob (the management API).
    // Compose overrides the host to the service name; the default only serves
    // a host-side run.
    val rabbitmqHost: String get() = Config.str("RABBITMQ_HOST", "127.0.0.1")
    val rabbitmqUser: String get() = Config.str("RABBITMQ_USER", "airadar")

    /** No default on purpose — an unset broker password must fail at startup. */
    val rabbitmqPassword: String get() = Config.str("RABBITMQ_PASSWORD")

    // ---- spend gates -------------------------------------------------------
    /**
     * The cost circuit breaker (design doc §3.4). Read by the digester's
     * per-item loop and by both daily jobs — every LLM spender checks the same
     * ceiling — and echoed into the snapshot so the dashboard can show spend
     * against its limit rather than a bare figure.
     */
    val dailyBudgetUsd: Double get() = Config.double("DAILY_LLM_BUDGET_USD", 0.50)

    /**
     * Digest at most this many (highest-value) items per UTC day; 0 = unlimited.
     * The primary volume cap — excess items re-park until the next day.
     */
    val dailyDigestLimit: Int get() = Config.int("DAILY_DIGEST_LIMIT", 10)

    /**
     * How many times a day the curator and the essayist may attempt their run.
     * The tick loop retries a failed daily job every CURATOR_TICK_MINUTES,
     * which is right for a transient failure and ruinous for a deterministic
     * one — without this cap a repeating failure re-buys the pro tier all night
     * (2026-07-18/19, ~$0.44 of overspend across two blank nights).
     */
    val dailyJobMaxAttempts: Int get() = Config.int("DAILY_JOB_MAX_ATTEMPTS", 3)

    // ---- selection (ADR-009) ----------------------------------------------
    /**
     * How many picks the curator may shortlist per day. Lowered 3 → 2 on
     * 2026-08-04: the essayist composes at most one a day and [shortlistTtlDays]
     * expires the rest, so the third pick was structurally unusable — 29 picked
     * over ten days, 8 became essays, and 22 of the 37 unused rows were already
     * past the TTL. Two leaves a real alternative for the day the judge rejects
     * the first. Raising it costs nothing and buys nothing: SELECT is a single
     * call whatever this number is.
     */
    val shortlistMaxPerDay: Int get() = Config.int("SHORTLIST_MAX_PER_DAY", 2)

    /** Uncomposed picks older than this drop out of the pool — news goes stale. */
    val shortlistTtlDays: Int get() = Config.int("SHORTLIST_TTL_DAYS", 7)

    // ---- essay -------------------------------------------------------------
    /**
     * How much of each chapter is book evidence.
     *
     * Three readers and they must agree: the essay prompt retrieves this many
     * characters, GeminiClient truncates to it again when building the prompt,
     * and the publisher pulls the same span back to attribute each quote. The
     * publisher was reading 8000 while the other two read 12000, so a quote
     * from the tail of a chapter matched nothing and lost its citation without
     * a word in the log. One definition is the fix; that is why it lives here.
     *
     * 2 × 6000 was sized for 2.5-pro's context. Since the essay tier moved to
     * 3.x this number, not the model, is the ceiling on depth.
     */
    val essayChapterChars: Int get() = Config.int("ESSAY_CHAPTER_CHARS", 12000)

    // ---- pipeline ----------------------------------------------------------
    /** Cap on a fetched article body; shared by the fetcher and its caller. */
    val fetchMaxChars: Int get() = Config.int("FETCH_MAX_CHARS", 20_000)

    /**
     * News older than this many days is dropped to STALE before any spend.
     * Asked twice: by the matcher (so a doomed item does not buy a query
     * embedding first) and by the digester (for items already parked on
     * digest.q, which were fresh when they were published to it). 0 = off.
     */
    val matchMaxAgeDays: Long get() = Config.long("MATCH_MAX_AGE_DAYS", 3)

    /**
     * The coarse trash filter (ADR-010 as amended) — NOT a relevance score.
     * Live traffic rejected 1.2% at 1.10; do not tune it against live data.
     * The snapshot echoes it so the dashboard can label what it is measuring.
     */
    val matchNoResonanceDistance: Double get() = Config.double("MATCH_NO_RESONANCE_DISTANCE", 1.10)

    /**
     * How often the publisher rewrites its snapshot — and, published inside
     * that snapshot, how a reader tells a fresh file from a stale one.
     */
    val snapshotIntervalMinutes: Int get() = Config.int("SNAPSHOT_INTERVAL_MINUTES", 60)
}

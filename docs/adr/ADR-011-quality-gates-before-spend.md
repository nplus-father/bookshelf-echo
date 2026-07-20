# ADR-011: Quality gates go before the spend, not after

- Status: Accepted
- Date: 2026-07-20
- Supersedes: the essay critic gate introduced in `58942b8` (2026-07-18)

## Context

The daily essay is the most expensive call the pipeline makes: `ESSAY_MODEL` is
pro-tier (\$1.25/\$10 per Mtok) and the prompt carries two chapters at 6 000
chars each plus 6 000 chars of article. Everything else — the digest, the
selection, the relevance judge — is cents.

On 2026-07-18 a **critic gate** was added: after every draft, a cheap-tier
editor scored it against a five-item rubric (quote fidelity, summary pastiche,
non-obviousness, no forced pairing, readability). A fail earned one revision
with the critique fed back, a second fail forfeited the day.

It was retired two days later. Two things went wrong, and only one of them was
a bug.

**The bug.** The critic booked `CRITIC` and `ESSAY_REVISE` into `llm_usage`,
purposes no migration ever added — the CHECK constraint stopped at `JUDGE`. So
every essay run paid for the pro-tier draft, then died on the constraint before
`saveEssay`. No `essays` row meant `essayExistsForDay` stayed false, so the
five-minute tick loop re-ran the whole thing, paying again, until the daily
budget breaker tripped. Two nights produced no essay while spending a night's
pro tier each. The critic's own spend was never recorded, so the ledger
under-reported the real bill.

**The structural problem, which is the reason for this ADR.** A gate placed
*after* the expensive call can only add cost. It cannot avoid the spend that
already happened, and when it fails it buys a second one. The relevance judge
(ADR-010) sits before the essay and is economically sound for exactly the
inverse reason: three cheap verdicts that prevent one pro-tier call pay for
themselves the first time they say no. The critic had the same shape and the
opposite sign, and nobody noticed because the rubric read like an obvious
improvement.

Reviewing the rubric against what already existed made the redundancy plain.
Items 2–4 (pastiche, non-obviousness, no forced pairing) are things the essay
prompt already demands in its writing requirements, and that the essayist's own
skip clause judges — with the full chapters and the full article in hand, which
is strictly more material than the critic ever saw. Item 5 (length, Markdown,
Traditional Chinese) is format, not judgment. Only item 1, quote fidelity, was
load-bearing: an author model genuinely cannot audit its own fabricated quotes.

And item 1 needs no model at all. The chapter text is in memory at that moment.

## Decision

1. **No LLM gate runs after an LLM call it cannot prevent.** A quality check
   that cannot stop the spend must justify itself as *published-quality
   insurance*, not as a safeguard — and must be measured against what the
   generating prompt already asks for. Redundant rubric items are not free;
   they are a second bill plus a revision round.

2. **Quote fidelity is enforced deterministically** (`QuoteVerifier`). The essay
   prompt now requires every direct quote to be a Markdown blockquote, so the
   check is a normalized substring comparison against the chapters, the
   retrieved passages and the article. Free, instant, unit-tested, and
   incapable of looping. A quote that is not in the source forfeits the day and
   consumes the pick, as the critic's second fail did.

   The verifier is deliberately lenient in one direction: inline 「」 is not
   checked, because in Chinese prose it marks emphasis at least as often as
   quotation, and a false positive costs a whole day's column while a missed
   quote costs one flawed paragraph. Making the *format* machine-checkable was
   the move that let the check be plain — the alternative was guessing at
   natural-language quotation, which is what the LLM critic was doing.

3. **All LLM spend is booked through one path** (`UsageMeter`): the `llm_usage`
   ledger row and the Prometheus counters together, labelled by purpose and
   model. Before this, only the digest path incremented the counters, so the
   pro-tier SELECT and ESSAY spend — the expensive half — was absent from the
   dashboard entirely and the cost spike had to be reconstructed from SQL.
   Recording spend without publishing it is now impossible by construction.

4. **Daily jobs cap their attempts** (`DailyAttemptGuard`,
   `DAILY_JOB_MAX_ATTEMPTS`, default 3). The tick loop retrying a failed daily
   job is right for a transient failure and ruinous for a deterministic one.
   This is not specific to the critic: any failure after the LLM call and before
   the "day is done" row — a publish that cannot connect, a schema drift — has
   the same shape.

## Consequences

Each night now costs at most one pro-tier call instead of up to two, plus the
judge's cheap verdicts. The essayist gets one attempt and no revision; a draft
that would have been rewritten is now simply not published, which is the same
outcome the critic reached on a second fail and consistent with 寧缺勿濫.

The accepted loss is real: a draft with a genuinely hollow argument but honest
quotes will now publish where the critic might have caught it. That is a bet
that the essay prompt and the skip clause — which see more material than the
critic did — are the better place to spend that judgment, and that a second
opinion is not worth a pro-tier call plus a revision round. If published quality
degrades measurably, the answer is a better essay prompt or a stronger skip
clause, not a second model grading the first.

A new silent-failure mode arrives with the quote gate: if the model stops using
blockquotes, or quotes loosely, `unverified_quotes` forfeits the day and looks
exactly like the outage this ADR is a response to. `airadar_essay_runs_total`
carries the outcome label so the dashboard can tell them apart; the first nights
after this change need watching.

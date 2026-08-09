# ADR-013: A schema holds the shape, and the ledger holds every billed call

- Status: Accepted
- Date: 2026-08-09
- Amends: [ADR-011](ADR-011-quality-gates-before-spend.md) — its point 3
  ("recording spend without publishing it is now impossible by construction")
  had a second door left open, described below. Everything else stands.

## Context

Three findings from a 2026-08-09 cost review of the whole Gemini bill. Measured
spend across both projects on the key is ~\$9.7/month, of which this pipeline is
85%; the LINE order bot everyone assumed was the expensive one is \$1.3.

**1. The essay tier was failing on its own output.** On 2026-08-08 the essayist
burned two of its three daily attempts like this:

```
WARN essay attempt failed, next tick retries:
  Gemini output was not valid JSON (finishReason=STOP): {"skip": false, ...
```

`finishReason=STOP` means the model believed it had finished. It was not
truncation and not a timeout — `ESSAY_TIMEOUT_SECONDS` had already been raised
to 180 in `4a298b9`. The request set `responseMimeType: application/json`, which
only *asks* for JSON; decoding still runs free, and on an `essay_md` carrying
800–1500 words of Markdown — blockquotes, newlines, 「」 — it gets the escaping
wrong often enough to cost two attempts out of three. The third succeeded, so
the column shipped and nothing looked broken from outside.

**2. Those failures were invisible to the breaker.** `UsageMeter.call` books the
ledger row *after* `block()` returns, so a throwing call recorded nothing. But
Google bills a generation whether or not we can parse it: that night cost three
pro-tier essay calls and `llm_usage` recorded one. `DAILY_LLM_BUDGET_USD` reads
`llm_usage`. This is the same class of bug as ADR-011 §3 — spend that never
reaches the ledger — arriving through the error path instead of the write path.
Worse than the money is the shape of it: the more the expensive tier fails, the
less the breaker thinks the day has cost.

**3. The selection prompt was mostly payload nobody reads.** `SELECT` measured
20k input tokens a run. 92% of it was `library_books`, the `matches.books` blob
passed through whole — eight books per candidate, each carrying a `purchase_url`,
the complete `chapter_titles` list (one ran to 889 chars), and a `guide` whose
tail is an entire 深度概覽. Across two days of candidates: guides 57.8% of the
field, chapter lists 21.3%, Amazon links 10.7%.

## Decision

1. **Every tier sends a `responseSchema`.** Structured output constrains
   decoding itself, so a malformed body is not a thing the model can return.
   Four schemas, one per tier, next to the parsers that read them.

   `propertyOrdering` is load-bearing rather than cosmetic: Gemini generates in
   schema order, so `skip` is declared before `essay_md` — the model commits to
   whether it has something worth saying before it starts saying it, which is
   the order 寧缺勿濫 asks for.

2. **A billed call always reaches the ledger.** `UnusableResponse` carries the
   usage out with the failure, and `UsageMeter.call` books it before rethrowing.
   Only that type — a real generation we could not use, including a SAFETY block
   that bills the prompt. A timeout or a 5xx has no billable answer, and
   inventing usage for one would overstate the day in the other direction.

3. **`SELECT` gets the evidence, not the record.** The books blob is projected
   down to what answers "could this book frame this news": title, author,
   category, distance, the first eight chapter titles as a scope hint, and the
   guide's opening thesis — the paragraph before the `📘 深度概覽` marker, which
   is the one-line pitch the rest of the guide elaborates. The purchase link is
   dropped outright; no ranking decision has ever turned on an Amazon URL.

## Consequences

**Recorded daily spend will go up, and that is the point.** Nothing new is being
bought — failures that were already being billed now appear in `llm_usage` and
on the Grafana cost panel. Judge the change by the daily total, not by the
ledger delta. `DAILY_LLM_BUDGET_USD` is 1.00 against a measured ~\$0.21/day, so
the extra honesty has room; if a night ever does trip the breaker on failures
alone, that is the breaker working for the first time.

**`SELECT` input drops roughly 20k → 4k tokens**, about \$1/month, and the model
reads eight tight book descriptions instead of eight 深度概覽. Better attention,
not just cheaper — though the ranking is now blind to anything only the guide's
tail said, which is the accepted loss.

**Two things a schema cannot do.** It cannot express "skip=false requires
essay_md", so `parseEssay` still checks that. And it can now drift from the
prose in the prompt that describes the same shape; they are edited together or
the model is told one thing and graded on another.

**What to watch.** `airadar_llm_latency_seconds_count{purpose="ESSAY",outcome="error"}`
should go to roughly zero. If it does not, the remaining failures are not
escaping bugs and this ADR fixed the wrong thing.

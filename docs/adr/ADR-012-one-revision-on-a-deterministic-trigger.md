# ADR-012: One revision, triggered by a deterministic check

- Status: Accepted
- Date: 2026-07-27
- Amends: [ADR-011](ADR-011-quality-gates-before-spend.md) — specifically its
  "forfeit the day rather than rewrite" rule. Everything else in ADR-011 stands.

## Context

ADR-011 retired the LLM critic gate and replaced it with `QuoteVerifier`: a
string comparison that checks every Markdown blockquote against the material the
essayist was given. It also decided that a failed check **forfeits the day** —
the pick is consumed, no essay is published, and tomorrow starts on a different
pairing. The reasoning was that a rewrite is a second call at the most expensive
tier we buy, and that the critic's own second call was what made it a money pit.

Two things have changed since:

1. **The trigger is no longer a model's opinion.** The critic re-scored a draft
   against a five-item rubric and could fail it for taste. `QuoteVerifier` fails
   a draft for exactly one reason: a blockquote that does not appear in the
   source text. It cannot fire on a good essay, and it cannot loop — the same
   input always gives the same verdict.
2. **The essay tier now costs more and the budget is bigger.** `ESSAY_MODEL` is
   `gemini-3.1-pro-preview` (2026-07-27) and `DAILY_LLM_BUDGET_USD` went from
   0.30 to 1.00 — the measured spend before that change was ~$0.096/day against
   a $0.30 cap, i.e. the budget was never the binding constraint.

Meanwhile the cost of forfeiting is the whole product for that day. And the
failure is usually narrow: one blockquote out of three or four, in an essay
whose argument is otherwise intact.

## Decision

When `QuoteVerifier` fails, the essayist gets **one** revision, and only if the
day's spend is still under budget at that moment. The revision prompt carries
the offending blockquotes verbatim and asks for those sentences to be fixed —
replaced with text that really is in the sources, or paraphrased out of the
blockquote — while keeping the rest.

The revised draft replaces the original **only if it passes the same check**. If
it fails, the original stands, the day is forfeited exactly as before, and the
pick is consumed.

Config: `ESSAY_REVISE_ON_BAD_QUOTES` (default on) turns the whole thing off.
Outcomes are counted on `airadar_essay_runs_total`: `revised`,
`revision_budget_skipped`, and the pre-existing `unverified_quotes` for a day
that still fails after the revision.

## What keeps this from becoming the critic again

- **One attempt, not a loop.** There is no second revision, ever.
- **A deterministic trigger.** No model decides whether to spend again.
- **A budget check immediately before the second call**, not just at the start
  of the run — the first call has already been paid for by then.
- **The check is unchanged.** The revision must clear the same bar; a rewrite
  cannot lower it.

## Consequences

- Days lost to a single fabricated quote should mostly stop.
- The worst case for one day's essay spend roughly doubles. At one call a day
  against a $1.00 cap, that is affordable; if it ever is not, the budget check
  in front of the revision is what stops it.
- `unverified_quotes` becomes a rarer and more meaningful signal: it now means
  the model could not fix its own quotes when handed the exact list.

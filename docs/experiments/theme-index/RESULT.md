# Result: the theme vector does not discriminate either (2026-08-04)

Null result. An isolated theme vector separates genuine resonance from
coincidence no better than the raw book vector does — which is to say, not at
all. The door ADR-010's amendment left ajar is now closed.

## Numbers

n=30, 9 genuine / 21 coincidence. Baseline precision (call everything genuine)
= 0.30.

| index | signal | genuine mean | coincidence mean | gap | best-cut precision |
| --- | --- | --- | --- | --- | --- |
| raw `book_vectors` | top-1 distance | 0.9720 | 0.9740 | **−0.0020** | 0.40 (2/5) |
| raw `book_vectors` | top1–top2 margin | 0.0128 | 0.0119 | **+0.0008** | 0.33 (9/27) |
| `book_theme_vectors` | top-1 distance | 0.9913 | 0.9970 | **−0.0057** | 0.40 (2/5) |
| `book_theme_vectors` | top1–top2 margin | 0.0134 | 0.0099 | **+0.0035** | 0.50 (3/6) |

The gaps are two to three orders of magnitude smaller than the spread within
each group. The best number in the table — theme margin at 0.50 — is 3 hits out
of 6 predictions at recall 0.33; on n=30 that is a coin flip that landed twice,
not a signal. Nothing clears the bar the protocol set (materially above 0.30
**and** above the raw index's ~0.40, with visibly separated distributions).

## Why, most likely

The caveat in the protocol was the right one: the failure is **embedding-space
density saturation**, not dilution. The hypothesis under test was that the raw
book vector buries the deep-overview text under the title, the category and a
flat list of every chapter title. Isolating the overview does change the
absolute distances (theme distances sit systematically further out, 30-item
mean 0.9953 vs 0.9734) — but it moves both groups together. Distilling the text
compressed the space rather than separating it.

This is the same finding as the 2026-07-16 live calibration, arrived at from
the opposite direction. Absolute cosine distance over a 1,400-book general
library measures how densely the shelf covers a topic, and that is a property
of the shelf, not of the news.

## How the labels were produced — read this before trusting the result

Claude drafted all 30 labels with a stated criterion (*does this book let the
news be understood more deeply — a mechanism, a frame — or does it merely share
a topic word?*, with dictionary-style entries counting as coincidence). Andrew
reviewed all 30 and accepted every one; no label was flipped.

That makes this **a weaker ground truth than 30 independently produced human
labels**, and the weakness runs in a specific direction: the labeller and the
system under test share a family. If this experiment had come back *positive*,
that overlap would be a serious problem — a signal that agrees with an LLM's
notion of relevance is exactly what a correlated labeller would manufacture.

It came back negative, which is the direction the overlap cannot fake: a
correlated labeller should make the signal look *better*, not worse. So the
null result stands. But if anyone later wants to reopen this — with a reranker,
chapter-level distances, multi-book voting — the labels to beat it with must be
independent ones, not these.

## What this closes

- **No fourth automatic signal.** Three have now been tested and died:
  absolute distance (n=5 spike, then 281 live matches), top1–top2 margin, and
  the isolated theme vector. The pattern is consistent enough to stop paying
  for the next one on spec.
- **The LLM relevance judge is the gate, without an asterisk.** ADR-010's
  amendment demoted the distance cutoff to a trash filter and put an LLM in
  front of the essay. That is now the settled design, not an interim one.
- **Cost work moves elsewhere.** The reason to want a pre-LLM signal was to
  narrow the digest tier. That was solved on the same day by a cheaper route —
  giving DIGEST its own model tier (`gemini-2.5-flash`), measured at $0.0174 →
  $0.0063 an item — which needed no new signal at all.

## Cost and leftovers

- Theme vectors: 1,381 books (26 had no usable overview block), 1.92M chars,
  `voyage-3-large`, roughly $0.35.
- Query embeddings: 30, negligible.
- The offline copy is at `nplus.space:/mnt/data/theme-index-exp.db` (1.58 GB)
  with `book_theme_vectors` in it. Nothing depends on it any more — it is kept
  so the numbers above can be re-derived without paying again. Delete it when
  the disk is wanted.

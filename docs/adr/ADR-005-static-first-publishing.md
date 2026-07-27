# ADR-005: Static-first publishing (git as the delivery mechanism)

- Status: Proposed (skeleton — flesh out during M1)
- Date: 2026-07-06
- Amended: 2026-07-27 (metrics split onto their own branch — see below)

## Decision (summary)

All public output — digest markdown and hourly metrics snapshots — is committed
to the site repository and served by GitHub Pages. No runtime backend is
exposed. Content gets version history for free; the fixed-IP host needs zero
inbound ports.

The publisher only writes files into `CONTENT_DIR`; the `site-publisher` sidecar
does the git work on a fixed interval (default 300s). That split keeps the JVM
images git-free and turns bursty per-item writes into at most one push per
interval, so Pages deploy throttling never needs publisher-side retry semantics.
A push that fails is retried by the next loop iteration, and because the loop
rebases onto `origin/main` first and pushes whenever local is ahead, a failed
round self-heals on the next one.

## Amendment (2026-07-27): the metrics snapshot leaves `main`

Content and metrics change on completely different clocks: an essay once a day,
the snapshot once an hour. Committing both to `main` meant **27 commits and 27
Actions + Pages deploys a day, 26 of which contained no content at all**. Three
costs, none of them theoretical:

- `git log` on the site repo no longer showed which day published an essay;
- every deploy is a chance for the GitHub side to fail (2026-07-25: `deploy-pages`
  aborted after ten idle minutes), and that exposure was multiplied by 27;
- the sidecar's rebase surface grew with the commit count — the 07-12 and 07-21
  publishing outages both lived on that path.

So `config/site-publish.sh` now pushes the snapshot to an orphan commit on a
separate `data` branch, which `deploy.yml` (`on: push: branches: [main]`) does
not watch. It uses git plumbing (`hash-object` / `mktree` / `commit-tree`) and
never touches the working tree — `/repo` is the host's checkout, and switching
branches in it would wreck the content loop.

**This does relax "no runtime backend", and the relaxation is deliberate.** The
dashboard page now fetches `raw.githubusercontent.com/.../data/metrics/latest.json`
in the browser. What ADR-005 was protecting is intact — there is still no
inbound port on the host, no server to operate, no runtime state — but the page
is no longer purely static: it depends on a third-party CDN read at view time.
The mitigations are that the build also fetches that file (so the served HTML
carries the last known state for readers without JS, crawlers, and social
previews), a failed fetch leaves that build-time render in place, and the page
states its own snapshot age either way. `site_metrics_last_push_timestamp_seconds`
is the alertable signal for the new failure mode: the branch stops updating
while everything else stays green.

# ADR-005: Static-first publishing (git as the delivery mechanism)

- Status: Proposed (skeleton — flesh out during M1)
- Date: 2026-07-06

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

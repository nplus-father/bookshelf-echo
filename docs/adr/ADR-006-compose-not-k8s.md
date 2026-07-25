# ADR-006: docker-compose on a single host, not Kubernetes

- Status: Proposed (skeleton — flesh out during M2)
- Date: 2026-07-06

## Decision (summary)

The whole stack runs as one docker-compose file on a fixed-IP home server,
every port bound to 127.0.0.1. Kubernetes on a single-node hobby deployment
adds operational surface without any of its benefits — knowing when *not* to
use it is the point. The queue-depth alert signal documented in M2 is exactly
what would drive KEDA-style autoscaling if this ever ran on a cluster.

## Amendment (2026-07-25): metrics ports also bind 172.30.0.1

Monitoring is converging onto the kube-prometheus-stack in k3s (the docker
Prometheus/Grafana pair is being retired — see nplus-infra
`docs/k3s-migration.md` Phase 2). k8s pods cannot reach docker container IPs;
the only working route is a published port on `172.30.0.1`, the
k8s-docker-bridge gateway (same pattern as MySQL for nplus-backend).

So the metrics ports (9101–9105 apps, 15692 rabbitmq, 9187 postgres-exporter)
are now additionally published on `172.30.0.1`. This does not weaken the
zero-inbound posture: the address lives on an internal bridge, is not routable
from the internet, and ufw admits only the pod CIDR (10.42.0.0/16).
"Zero inbound" continues to mean *zero inbound from outside the host*.

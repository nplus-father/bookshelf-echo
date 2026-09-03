---
project: bookshelf-echo
line: own
title: bookshelf-echo · AI 新聞 pipeline 與每日 essay
code:
  repo: nplus-father/bookshelf-echo
  branch: main
deployed:
  - id: site
    url: https://nplus.wiki/bookshelf-echo-site/
    expect: 200
data:
  - id: metrics
    url: https://raw.githubusercontent.com/nplus-father/bookshelf-echo-site/data/metrics/latest.json
    freshness: capturedAt
    max_age_hours: 3
    extract:
      paused: paused
  - id: essay
    url: https://nplus.wiki/bookshelf-echo-site/essay.json
    freshness: date
    max_age_hours: 48
  - id: daily
    url: https://nplus.wiki/bookshelf-echo-site/daily.json
    freshness: date
    max_age_hours: 48
components:
  - id: producers
    kind: worker
    label: producers（hn · arxiv · gh · blogs · reddit）
    paths: [producers/]
    paused_from: { data: metrics, field: paused }
  - id: enricher
    kind: worker
    label: enricher
    paths: [enricher/]
    paused_from: { data: metrics, field: paused }
  - id: matcher
    kind: worker
    label: matcher（resonance gate）
    paths: [matcher/]
    paused_from: { data: metrics, field: paused }
  - id: digester
    kind: worker
    label: digester · curator · essayist
    paths: [digester/]
    paused_from: { data: metrics, field: paused }
  - id: publisher
    kind: worker
    label: publisher（含每小時 metrics 快照）
    paths: [publisher/]
  - id: common
    kind: code
    label: common · config · db
    paths: [common/, config/, db/]
  - id: site
    kind: site
    label: bookshelf-echo-site（GH Pages）
    dir: ../bookshelf-echo-site
    paths: [src/]
    deployed: site
  - id: rabbitmq
    kind: external
    label: RabbitMQ（nplus-infra）
  - id: library-bridge
    kind: external
    label: library-bridge（k3s）
milestones:
  - id: next-steps
    source: docs/next-steps.md
waiting_on: []
next:
  gate: 完成 bookshelf-echo rename cutover（docs/next-steps.md 的 P1）
---

bookshelf-echo 是 queue-based 的 AI 新聞 pipeline：五個 producer 進 RabbitMQ，enricher 去重抓全文，matcher 對書庫做共鳴閘，digester 用 LLM 摘要並每日選稿寫 essay，publisher 出 markdown 與 metrics，sidecar 推到 bookshelf-echo-site 由 GitHub Pages 建站。跑在 nplus.space 主機的 docker compose，只有 outbound。

目前狀態：pipeline 處於宣告暫停（`PIPELINE_PAUSED`），暫停與否由 metrics 快照的 `paused` 欄位講，不用手寫。快照每小時照跑，所以 metrics 應該永遠新鮮；essay 與 daily 兩個 feed 在暫停期間本來就會過期，cockpit 列出來是實情不是故障。

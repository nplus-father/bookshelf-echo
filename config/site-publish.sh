#!/bin/sh
# Sync the pipeline's output — digest markdown + the metrics snapshot — into the
# bookshelf-echo-site checkout and push, so GitHub Actions rebuilds the public site.
# Runs as a compose sidecar; keeps the publisher itself unchanged (no git needed
# in the JRE image).
set -eu

git config --global --add safe.directory /repo
git config --global user.name "bookshelf-echo-bot"
git config --global user.email "bot@nplus.wiki"

REPO_URL="https://x-access-token:${SITE_GIT_TOKEN}@github.com/nplus-father/bookshelf-echo-site.git"
INTERVAL="${SYNC_INTERVAL_SECONDS:-300}"
echo "site-publisher: syncing /src -> /repo/{content,public} every ${INTERVAL}s"

while true; do
  cd /repo
  # 工作區殘留（上一輪 rebase --abort 沒還原乾淨、或別處手動動過）會讓 rebase
  # 每輪都 "cannot rebase: You have unstaged changes" —— 而唯一能清掉殘留的
  # git add -A 在下面，被失敗分支的 continue 跳過，於是永久死鎖
  # （2026-07-21 起的停更事故，卡了三篇 essay）。/repo 是機器工作副本，
  # 內容一律從 origin/main + /src 重建，未提交的修改沒有保留價值：先丟掉。
  git checkout -q -- . 2>/dev/null || true
  git clean -qfd 2>/dev/null || true
  # 先跟上 origin/main — repo 若在別處被 push 過（renovate、手動 fix），
  # 不 rebase 的 push 會 non-fast-forward 永久失敗（2026-07-12 起的停更事故）。
  if git fetch -q "$REPO_URL" main && git rebase -q FETCH_HEAD; then
    :
  else
    git rebase --abort 2>/dev/null || true
    echo "fetch/rebase FAILED at $(date -u +%FT%TZ)"
    sleep "$INTERVAL"
    continue
  fi
  mkdir -p /repo/content/daily /repo/content/weekly /repo/content/essays /repo/public/data/metrics
  cp -r /src/daily/. /repo/content/daily/ 2>/dev/null || true
  cp -r /src/weekly/. /repo/content/weekly/ 2>/dev/null || true
  cp -r /src/essays/. /repo/content/essays/ 2>/dev/null || true
  # metrics snapshot 走 public/ 而非 content/：content/ 只被 Astro 的 markdown
  # collection glob 掃（*.md），JSON 放進去不會被輸出；public/ 會原樣複製進 dist/，
  # 這樣 dashboard 才 fetch 得到 /data/metrics/latest.json。
  cp -r /src/data/metrics/. /repo/public/data/metrics/ 2>/dev/null || true
  git add -A
  if ! git diff --cached --quiet; then
    git commit -q -m "content: auto-publish $(date -u +%FT%TZ)"
  fi
  # rebase 後 local 可能已領先（先前失敗待補推的 commits）—
  # 只要領先就推，不限定「這一輪有新 commit」才推。
  if [ "$(git rev-list --count FETCH_HEAD..HEAD)" -gt 0 ]; then
    if out=$(git push "$REPO_URL" HEAD:main 2>&1); then
      echo "pushed at $(date -u +%FT%TZ)"
    else
      # 失敗原因要進 log，但 token 不能
      echo "push FAILED at $(date -u +%FT%TZ): $(echo "$out" | sed "s|${SITE_GIT_TOKEN}|***|g" | tail -2)"
    fi
  fi
  sleep "$INTERVAL"
done

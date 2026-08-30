#!/bin/sh
set -eu

git config --global --add safe.directory /repo
git config --global user.name "bookshelf-echo-bot"
git config --global user.email "bot@nplus.wiki"

REPO_URL="https://x-access-token:${SITE_GIT_TOKEN}@github.com/nplus-father/bookshelf-echo-site.git"
INTERVAL="${SYNC_INTERVAL_SECONDS:-300}"
SELF_UID="$(id -u)"

TEXTFILE_DIR="${TEXTFILE_DIR:-/textfile}"
METRIC_FILE="$TEXTFILE_DIR/site-publish.prom"

fails=0
foreign=0
deploy_fails=0
metrics_fails=0
last_ok=0
last_push=0
last_deploy_match=0
last_metrics_push=0
if [ -f "$METRIC_FILE" ]; then
  last_ok=$(awk '$1=="site_publish_last_success_timestamp_seconds"{print int($2)}' "$METRIC_FILE" 2>/dev/null || true)
  last_push=$(awk '$1=="site_publish_last_push_timestamp_seconds"{print int($2)}' "$METRIC_FILE" 2>/dev/null || true)
  last_deploy_match=$(awk '$1=="site_deploy_last_match_timestamp_seconds"{print int($2)}' "$METRIC_FILE" 2>/dev/null || true)
  last_metrics_push=$(awk '$1=="site_metrics_last_push_timestamp_seconds"{print int($2)}' "$METRIC_FILE" 2>/dev/null || true)
fi
[ -n "${last_ok:-}" ] || last_ok=0
[ -n "${last_push:-}" ] || last_push=0
[ -n "${last_deploy_match:-}" ] || last_deploy_match=0
[ -n "${last_metrics_push:-}" ] || last_metrics_push=0
[ "$last_metrics_push" -gt 0 ] || last_metrics_push=$(date +%s)
[ "$last_deploy_match" -gt 0 ] || last_deploy_match=$(date +%s)

emit() {
  [ -d "$TEXTFILE_DIR" ] || return 0
  tmp="$METRIC_FILE.$$"
  {
    echo '# HELP site_publish_consecutive_failures 連續失敗的同步輪數（0 = 上一輪走完了）'
    echo '# TYPE site_publish_consecutive_failures gauge'
    echo "site_publish_consecutive_failures $fails"
    echo '# HELP site_publish_last_success_timestamp_seconds 最後一次完整走完同步迴圈'
    echo '# TYPE site_publish_last_success_timestamp_seconds gauge'
    echo "site_publish_last_success_timestamp_seconds $last_ok"
    echo '# HELP site_publish_last_push_timestamp_seconds 最後一次真的把 commit 推上 origin'
    echo '# TYPE site_publish_last_push_timestamp_seconds gauge'
    echo "site_publish_last_push_timestamp_seconds $last_push"
    echo '# HELP site_metrics_last_push_timestamp_seconds 最後一次把 metrics 快照推上 data branch'
    echo '# TYPE site_metrics_last_push_timestamp_seconds gauge'
    echo "site_metrics_last_push_timestamp_seconds $last_metrics_push"
    echo '# HELP site_metrics_consecutive_failures 連續幾輪推不上 data branch（0 = 上一輪成功）'
    echo '# TYPE site_metrics_consecutive_failures gauge'
    echo "site_metrics_consecutive_failures $metrics_fails"
    echo '# HELP site_publish_foreign_files 工作副本裡不屬於本容器 uid、因而改寫不了的檔案數'
    echo '# TYPE site_publish_foreign_files gauge'
    echo "site_publish_foreign_files $foreign"
    if [ -n "${SITE_VERSION_URL:-}" ]; then
      echo '# HELP site_deploy_last_match_timestamp_seconds 最後一次確認線上站台的 commit 等於 origin/main'
      echo '# TYPE site_deploy_last_match_timestamp_seconds gauge'
      echo "site_deploy_last_match_timestamp_seconds $last_deploy_match"
      echo '# HELP site_deploy_check_failures 連續幾輪問不到線上站台的 version.json（0 = 上一輪問到了）'
      echo '# TYPE site_deploy_check_failures gauge'
      echo "site_deploy_check_failures $deploy_fails"
    fi
  } > "$tmp" 2>/dev/null || { rm -f "$tmp" 2>/dev/null || true; return 0; }
  mv -f "$tmp" "$METRIC_FILE" 2>/dev/null || rm -f "$tmp" 2>/dev/null || true
  chmod 644 "$METRIC_FILE" 2>/dev/null || true
  return 0
}

publish_metrics() {
  [ -d /src/data/metrics ] || return 0
  entries=""
  for f in /src/data/metrics/*.json; do
    [ -f "$f" ] || continue
    blob=$(git hash-object -w "$f" 2>/dev/null) || return 1
    entries="${entries}100644 blob ${blob}	$(basename "$f")
"
  done
  [ -n "$entries" ] || return 0
  inner=$(printf '%s' "$entries" | git mktree) || return 1
  root=$(printf '040000 tree %s\tmetrics\n' "$inner" | git mktree) || return 1

  if git fetch -q "$REPO_URL" data 2>/dev/null; then
    prev=$(git rev-parse FETCH_HEAD 2>/dev/null || echo "")
    [ -n "$prev" ] && [ "$(git rev-parse "$prev^{tree}" 2>/dev/null)" = "$root" ] && return 0
  fi

  commit=$(git commit-tree "$root" -m "data: metrics snapshot $(date -u +%FT%TZ)") || return 1
  out=$(git push --force "$REPO_URL" "$commit:refs/heads/data" 2>&1) || {
    echo "metrics push FAILED at $(date -u +%FT%TZ): $(echo "$out" | sed "s|${SITE_GIT_TOKEN}|***|g" | tail -2)"
    return 1
  }
  return 0
}

echo "site-publisher: syncing /src -> /repo/content (main) + metrics (data branch) every ${INTERVAL}s"

while true; do
  cd /repo
  git checkout -q -- . 2>/dev/null || true
  git clean -qfd 2>/dev/null || true
  if git fetch -q "$REPO_URL" main && git rebase -q FETCH_HEAD; then
    git update-ref refs/remotes/origin/main FETCH_HEAD 2>/dev/null || true
    foreign=0
  else
    git rebase --abort 2>/dev/null || true
    fails=$((fails + 1))
    foreign=$(find . -name .git -prune -o -name node_modules -prune -o ! -uid "$SELF_UID" -print 2>/dev/null | wc -l | tr -d ' ') || foreign=0
    [ -n "${foreign:-}" ] || foreign=0
    echo "fetch/rebase FAILED at $(date -u +%FT%TZ)（連續第 ${fails} 次）"
    if [ "$foreign" -gt 0 ]; then
      echo "  ^ 工作副本有 ${foreign} 個檔案不屬於 uid ${SELF_UID}，rebase 改寫不了它們；需在 host 上 chown -R"
    fi
    emit
    sleep "$INTERVAL"
    continue
  fi

  origin_sha=$(git rev-parse FETCH_HEAD 2>/dev/null || echo "")
  if [ -n "${SITE_VERSION_URL:-}" ] && [ -n "$origin_sha" ]; then
    body=$(wget -q -T 20 -O - "${SITE_VERSION_URL}?_=$(date +%s)" 2>/dev/null || echo "")
    deployed_sha=$(printf '%s' "$body" | sed -n 's/.*"sha"[[:space:]]*:[[:space:]]*"\([0-9a-f]\{7,40\}\)".*/\1/p' | head -1)
    if [ -z "$deployed_sha" ]; then
      deploy_fails=$((deploy_fails + 1))
      echo "deploy check FAILED at $(date -u +%FT%TZ)（連續第 ${deploy_fails} 次）：${SITE_VERSION_URL} 取不到或沒有 sha 欄位"
    else
      deploy_fails=0
      if [ "$deployed_sha" = "$origin_sha" ]; then
        last_deploy_match=$(date +%s)
      else
        echo "deploy lagging at $(date -u +%FT%TZ)：線上 ${deployed_sha}，origin/main ${origin_sha}"
      fi
    fi
  fi

  mkdir -p /repo/content/daily /repo/content/weekly /repo/content/essays
  cp -r /src/daily/. /repo/content/daily/ 2>/dev/null || true
  cp -r /src/weekly/. /repo/content/weekly/ 2>/dev/null || true
  cp -r /src/essays/. /repo/content/essays/ 2>/dev/null || true
  rm -rf /repo/public/data/metrics 2>/dev/null || true
  git add -A
  if ! git diff --cached --quiet; then
    git commit -q -m "content: auto-publish $(date -u +%FT%TZ)"
  fi
  if [ "$(git rev-list --count FETCH_HEAD..HEAD)" -gt 0 ]; then
    if out=$(git push "$REPO_URL" HEAD:main 2>&1); then
      echo "pushed at $(date -u +%FT%TZ)"
      git update-ref refs/remotes/origin/main HEAD 2>/dev/null || true
      last_push=$(date +%s)
      last_ok="$last_push"
      fails=0
    else
      echo "push FAILED at $(date -u +%FT%TZ): $(echo "$out" | sed "s|${SITE_GIT_TOKEN}|***|g" | tail -2)"
      fails=$((fails + 1))
    fi
  else
    last_ok=$(date +%s)
    fails=0
  fi

  if publish_metrics; then
    last_metrics_push=$(date +%s)
    metrics_fails=0
  else
    metrics_fails=$((metrics_fails + 1))
    echo "metrics sync FAILED at $(date -u +%FT%TZ)（連續第 ${metrics_fails} 次）"
  fi
  emit
  sleep "$INTERVAL"
done

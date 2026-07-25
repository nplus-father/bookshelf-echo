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
SELF_UID="$(id -u)"

# 這條管線壞掉的樣子是「安靜」：容器 Up、publisher 照常寫檔、dashboard 照常有頁面，
# 只有 push 出不去。兩次停更（07-12、07-21）都是靠人發現沒收到文章才查出來的。
# 所以每輪把成敗寫成 node-exporter textfile 指標，讓 Grafana 去叫（infra 的
# check-drift.sh / backup-all.sh 是同一套模式）。目錄沒掛載就靜默跳過。
TEXTFILE_DIR="${TEXTFILE_DIR:-/textfile}"
METRIC_FILE="$TEXTFILE_DIR/site-publish.prom"

fails=0
foreign=0
deploy_fails=0
# 容器重啟不該讓「上次成功」歸零：0 會讓 time()-0 變成 56 年，alert 立刻誤報。
last_ok=0
last_push=0
last_deploy_match=0
if [ -f "$METRIC_FILE" ]; then
  last_ok=$(awk '$1=="site_publish_last_success_timestamp_seconds"{print int($2)}' "$METRIC_FILE" 2>/dev/null || true)
  last_push=$(awk '$1=="site_publish_last_push_timestamp_seconds"{print int($2)}' "$METRIC_FILE" 2>/dev/null || true)
  last_deploy_match=$(awk '$1=="site_deploy_last_match_timestamp_seconds"{print int($2)}' "$METRIC_FILE" 2>/dev/null || true)
fi
[ -n "${last_ok:-}" ] || last_ok=0
[ -n "${last_push:-}" ] || last_push=0
[ -n "${last_deploy_match:-}" ] || last_deploy_match=0
# last_ok 不同，它在第一輪成功時就會被填上；last_deploy_match 只有在線上站台
# 真的追上 origin/main 那一刻才動，而剛上線這個指標時 version.json 還沒被任何
# 一次 build 寫出去。0 會讓 alert 在部署當下就誤報，所以冷啟動先給一個門檻的
# 寬限期。指標檔存在時走上面的讀回，重啟不會白白重置這段寬限。
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
    echo '# HELP site_publish_foreign_files 工作副本裡不屬於本容器 uid、因而改寫不了的檔案數'
    echo '# TYPE site_publish_foreign_files gauge'
    echo "site_publish_foreign_files $foreign"
    # 沒設 SITE_VERSION_URL 就完全不吐這兩條 —— 檢查沒在跑的時候，「凍結在
    # 容器啟動那刻的時間戳」看起來跟真的停更一模一樣，30 分鐘後就是一次誤報。
    # 沒有序列才是誠實的：對應的 alert 兩側都刻意不設 absent()/noDataState:
    # Alerting，序列不在就不會叫。
    if [ -n "${SITE_VERSION_URL:-}" ]; then
      echo '# HELP site_deploy_last_match_timestamp_seconds 最後一次確認線上站台的 commit 等於 origin/main'
      echo '# TYPE site_deploy_last_match_timestamp_seconds gauge'
      echo "site_deploy_last_match_timestamp_seconds $last_deploy_match"
      echo '# HELP site_deploy_check_failures 連續幾輪問不到線上站台的 version.json（0 = 上一輪問到了）'
      echo '# TYPE site_deploy_check_failures gauge'
      echo "site_deploy_check_failures $deploy_fails"
    fi
  } > "$tmp" 2>/dev/null || { rm -f "$tmp" 2>/dev/null || true; return 0; }
  # 原子寫入：node_exporter 隨時可能在讀，寫一半的檔會被它整個拒收。
  mv -f "$tmp" "$METRIC_FILE" 2>/dev/null || rm -f "$tmp" 2>/dev/null || true
  chmod 644 "$METRIC_FILE" 2>/dev/null || true
  return 0
}

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
    foreign=0
  else
    git rebase --abort 2>/dev/null || true
    fails=$((fails + 1))
    foreign=$(find . -name .git -prune -o -name node_modules -prune -o ! -uid "$SELF_UID" -print 2>/dev/null | wc -l | tr -d ' ') || foreign=0
    [ -n "${foreign:-}" ] || foreign=0
    echo "fetch/rebase FAILED at $(date -u +%FT%TZ)（連續第 ${fails} 次）"
    if [ "$foreign" -gt 0 ]; then
      # 2026-07-23：sidecar 從 root 改成 uid 1000（為了 .git 物件擁有權）之後，
      # 早年 root 寫下的檔案就再也改寫不了，rebase 每輪 Permission denied。
      # 這種失敗清工作區也沒用，得在 host 上 chown —— 所以病因要自己講出來，
      # 不能只留一行 rebase FAILED 讓人事後翻 log 猜。
      echo "  ^ 工作副本有 ${foreign} 個檔案不屬於 uid ${SELF_UID}，rebase 改寫不了它們；需在 host 上 chown -R"
    fi
    emit
    sleep "$INTERVAL"
    continue
  fi

  # 「推上去了」不等於「上線了」。push 成功之後還隔著一整條 GitHub Actions +
  # Pages CDN，那一段壞掉時上面所有指標都是綠的：2026-07-25 deploy-pages 空等
  # 10 分鐘後 abort，站台停在上一版，三條 alert 一條都沒響。這裡直接去問線上
  # 那份站台自己是哪個 commit（由共用 workflow 的 Stamp build version 寫出）。
  #
  # 比對 origin/main 而不是本地 HEAD 是刻意的：push 失敗時本地會領先，但那時
  # 線上與 origin 其實是一致的，該響的是 site-publish-stale 那條。比對 origin
  # 讓這條只在「origin 有了、站台沒有」時開口，兩條規則不會為同一個故障響兩次。
  origin_sha=$(git rev-parse FETCH_HEAD 2>/dev/null || echo "")
  # origin_sha 空掉時整個檢查沒有意義（比對基準都沒有了），要靜靜跳過而不是
  # 拿空字串去比 —— 那會每輪都判成「線上落後」，最後誤報成一次假停更。
  if [ -n "${SITE_VERSION_URL:-}" ] && [ -n "$origin_sha" ]; then
    # Pages 的 CDN 會 cache 十分鐘，帶個每輪都不同的 query 才問得到剛上線的那份。
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
      last_push=$(date +%s)
      last_ok="$last_push"
      fails=0
    else
      # 失敗原因要進 log，但 token 不能
      echo "push FAILED at $(date -u +%FT%TZ): $(echo "$out" | sed "s|${SITE_GIT_TOKEN}|***|g" | tail -2)"
      fails=$((fails + 1))
    fi
  else
    # 沒有新內容也是健康的一輪 —— 寧缺勿濫的日子沒有 essay 可推，
    # 那不該讓 alert 誤判成停更。last_ok 記的是「這條路走得通」，不是「有東西送出去」。
    last_ok=$(date +%s)
    fails=0
  fi
  emit
  sleep "$INTERVAL"
done

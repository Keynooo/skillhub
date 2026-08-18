#!/usr/bin/env bash
# 服务器上一键升级（或降级）skillhub release 栈到指定镜像版本。
#
# 用法:
#   bash scripts/upgrade-release.sh <tag>
#   例: bash scripts/upgrade-release.sh v0.1.2
#
# 前提: GitHub 上已打 tag <tag> + 发 Release, CI 已把镜像推到 GHCR。
#
# 脚本做的事（一条命令完成）:
#   1. 迁移安全预检: git diff 出当前↔目标版本之间的 Flyway 迁移，
#      发现删字段/改类型/改名等破坏性变更 → 高亮警告；降级遇破坏性变更 → 强制确认。
#   2. 降级检测: 目标版本低于当前 → 需确认。
#   3. git pull（更新 compose；.env.release 不受影响）
#   4. pg_dump 备份 PostgreSQL（失败即中止）
#   5. 改 SKILLHUB_VERSION（留 .env.release.bak）
#   6. docker compose pull（server/web/scanner，另单独预热 sandbox 镜像；失败自动恢复 .env.release）
#   7. docker compose up -d --wait（失败同样恢复 .env.release）
#
# 可选环境变量:
#   FORCE=1        跳过所有交互确认（用于自动化/无人值守）
#   SKIP_PULL=1    跳过 git pull
#   SKIP_BACKUP=1  跳过 PostgreSQL 备份（不建议）
#   SKIP_MIGCHECK=1 跳过迁移安全预检
#   SKIP_IMAGE_PULL=1 跳过 docker compose pull（离线/已用 load-images.sh 加载镜像后使用）
#
# 数据安全: 只重建容器, 命名卷 postgres_data/redis_data/skillhub_storage 不动, 数据保留。
#           本脚本绝不使用 `down -v`。

set -euo pipefail
set +H  # 关 histexpand, 避免 !2026 之类触发历史扩展

# ===========================================================================
# 0. 参数与前置检查
# ===========================================================================
if [[ $# -ne 1 ]]; then
  echo "用法: bash $0 <tag>    例: bash $0 v0.1.2" >&2
  exit 1
fi
IMAGE_TAG="$1"

if [[ ! "$IMAGE_TAG" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "❌ 非法 tag: '$IMAGE_TAG'（仅允许字母数字 . _ -）" >&2
  exit 1
fi

if [[ ! -f .env.release ]]; then
  echo "❌ 当前目录没有 .env.release（还没首次部署过？请先跑 deploy-release.sh）" >&2
  exit 1
fi
if [[ ! -f compose.release.yml || ! -f compose.verify.yml ]]; then
  echo "❌ 当前目录没有 compose.release.yml / compose.verify.yml，请 cd 到仓库根目录" >&2
  exit 1
fi

COMPOSE="docker compose --env-file .env.release -f compose.release.yml -f compose.verify.yml"
# 只拉 skillhub 自家的服务（镜像 tag 跟 SKILLHUB_VERSION 走）。postgres/redis 是固定大版本
# (postgres:16-alpine / redis:7-alpine) 的外部依赖，升级时本地已有、无需重拉；且避开
# 受限网络下 Docker Hub 拉不动(443 / 镜像源需登录)导致整条升级失败的问题。
APP_SERVICES="server web skill-scanner"
MIGRATION_PATH="server/skillhub-app/src/main/resources/db/migration"

CURRENT_TAG="$(grep -E '^SKILLHUB_VERSION=' .env.release | tail -1 | cut -d= -f2- | tr -d '[:space:]')"

echo "当前版本: ${CURRENT_TAG:-（未设置）}"
echo "目标版本: ${IMAGE_TAG}"
echo ""

# ===========================================================================
# 1. 版本方向判定（升级 / 降级 / 无法判断）
# ===========================================================================
# normalize_semver <tag> → 输出 "major minor patch"；非 semver 返回非 0
normalize_semver() {
  local t="${1#v}"; t="${t%%-*}"
  [[ "$t" =~ ^[0-9]+(\.[0-9]+){0,2}$ ]] || return 1
  local major minor patch
  IFS=. read -r major minor patch <<<"$t"
  printf '%s %s %s\n' "${major:-0}" "${minor:-0}" "${patch:-0}"
}

# is_downgrade <target> <current>
#   0=降级 | 1=升级或持平 | 99=非 semver，无法判断
is_downgrade() {
  local a b
  a="$(normalize_semver "$1")" || return 99
  b="$(normalize_semver "$2")" || return 99
  local a1 a2 a3 b1 b2 b3
  read -r a1 a2 a3 <<<"$a"; read -r b1 b2 b3 <<<"$b"
  if (( 10#$a1 != 10#$b1 )); then (( 10#$a1 < 10#$b1 )); return; fi
  if (( 10#$a2 != 10#$b2 )); then (( 10#$a2 < 10#$b2 )); return; fi
  (( 10#$a3 < 10#$b3 ))
}

CMP_RC=0
is_downgrade "$IMAGE_TAG" "$CURRENT_TAG" || CMP_RC=$?

# ===========================================================================
# 2. 迁移安全预检
# ===========================================================================
HIGH_RISK_LINES=""
MED_RISK_LINES=""
CHECK_POSSIBLE=0

# 把版本号解析成 git ref（能 resolve 到 commit 才算）；否则返回空
resolve_ref() {
  local v="$1" cand
  for cand in "$v" "v${v#v}" "${v#v}"; do
    if git rev-parse --verify --quiet "${cand}^{commit}" >/dev/null 2>&1; then
      echo "$cand"; return 0
    fi
  done
  return 1
}

echo "==> 迁移安全预检"
if [[ -n "${SKIP_MIGCHECK:-}" ]]; then
  echo "    已跳过（SKIP_MIGCHECK=1）"
else
  git fetch --tags --quiet 2>/dev/null || true
  CUR_REF="$(resolve_ref "$CURRENT_TAG" 2>/dev/null || true)"
  TGT_REF="$(resolve_ref "$IMAGE_TAG" 2>/dev/null || true)"

  if [[ -z "$CUR_REF" || -z "$TGT_REF" ]]; then
    echo "    ℹ️  当前(${CURRENT_TAG})或目标(${IMAGE_TAG})无法识别为 git tag（常见于 latest/edge/sha-xxx），跳过自动检查。"
    echo "       手动确认: git diff <旧>..<新> -- ${MIGRATION_PATH}/"
  else
    CHECK_POSSIBLE=1
    # diff 方向统一为「低版本..高版本」：破坏性迁移总是高版本相对低版本新增的 SQL
    if [[ "$CMP_RC" -eq 0 ]]; then
      LOW_REF="$TGT_REF"; HIGH_REF="$CUR_REF"      # 降级：target 是低
    else
      LOW_REF="$CUR_REF"; HIGH_REF="$TGT_REF"      # 升级或未知：target 是高
    fi

    HIGH_RE='DROP[[:space:]]+(COLUMN|TABLE)|RENAME[[:space:]]+(COLUMN|TO[[:space:]])|USING[[:space:]]+::|COLUMN[[:space:]]+[^[:space:].]+[[:space:]]+TYPE[[:space:]]'
    MED_RE='DROP[[:space:]]+(CONSTRAINT|INDEX)|NOT[[:space:]]+NULL'

    # 该范围内新增/修改的迁移文件
    CHANGED="$(git diff --name-only --diff-filter=AM "${LOW_REF}..${HIGH_REF}" -- "${MIGRATION_PATH}/" 2>/dev/null || true)"

    if [[ -n "$CHANGED" ]]; then
      while IFS= read -r f; do
        [[ -z "$f" ]] && continue
        # 读该文件在高版本的内容（跳过注释行），逐行匹配
        while IFS= read -r line; do
          [[ -z "$line" ]] && continue
          if echo "$line" | grep -qiE "$HIGH_RE"; then
            HIGH_RISK_LINES+="$(basename "$f"): ${line#"${line%%[![:space:]]*}"}"$'\n'
          elif echo "$line" | grep -qiE "$MED_RE"; then
            MED_RISK_LINES+="$(basename "$f"): ${line#"${line%%[![:space:]]*}"}"$'\n'
          fi
        done < <(git show "${HIGH_REF}:${f}" 2>/dev/null | grep -viE '^[[:space:]]*--')
      done <<< "$CHANGED"
    fi

    if [[ -n "$HIGH_RISK_LINES" ]]; then
      echo "    🚨 高危破坏性迁移（删字段/删表/改类型/改名）:"
      while IFS= read -r l; do [[ -n "$l" ]] && echo "       • $l"; done <<< "$HIGH_RISK_LINES"
    fi
    if [[ -n "$MED_RISK_LINES" ]]; then
      echo "    ⚠️  中危变更（删约束/删索引/加非空约束）:"
      while IFS= read -r l; do [[ -n "$l" ]] && echo "       • $l"; done <<< "$MED_RISK_LINES"
    fi
    if [[ -z "$HIGH_RISK_LINES" && -z "$MED_RISK_LINES" ]]; then
      echo "    ✅ 未发现破坏性迁移，升降级 schema 风险低。"
    fi
  fi
fi
echo ""

# ===========================================================================
# 3. 综合判定 → 警告 / 确认
# ===========================================================================
confirm_or_abort() {
  local prompt="$1"
  if [[ -z "${FORCE:-}" ]]; then
    read -r -p "${prompt}（输入 yes 继续，其他键中止）: " c
    [[ "$c" == "yes" ]] || { echo "已中止，未做任何改动。"; exit 1; }
  else
    echo "${prompt}（FORCE=1，自动继续）"
  fi
}

NEED_CONFIRM=0
CONFIRM_MSG=""

case "$CMP_RC" in
  0) # 降级
     if [[ "$CHECK_POSSIBLE" -eq 1 && -n "$HIGH_RISK_LINES" ]]; then
       echo "🚨 降级 + 破坏性迁移：旧版代码需要这些被删/被改的字段，几乎必然启动失败。"
       NEED_CONFIRM=1; CONFIRM_MSG="🚨 强烈建议中止！确认继续降级？"
     else
       echo "⚠️  检测到降级: ${CURRENT_TAG} → ${IMAGE_TAG}（schema 可能不兼容）"
       NEED_CONFIRM=1; CONFIRM_MSG="⚠️ 确认继续降级？"
     fi
     ;;
  1) # 升级或持平
     if [[ -n "$HIGH_RISK_LINES" ]]; then
       echo "⚠️  本次升级含不可逆破坏性迁移（删字段/改类型），数据将被永久变更。"
       NEED_CONFIRM=1; CONFIRM_MSG="⚠️ 已自动备份。确认继续升级？"
     else
       echo "✅ 正常升级，迁移安全，继续。"
     fi
     ;;
  99) # 无法判断方向
      echo "ℹ️  当前/目标含非语义化 tag，无法自动判断升降级；请结合上面的迁移预检结果自行确认。"
      ;;
esac
echo ""

[[ "$NEED_CONFIRM" -eq 1 ]] && confirm_or_abort "$CONFIRM_MSG"

# ===========================================================================
# 4. git pull（更新仓库里的 compose 等文件; .env.release 已被 gitignore）
# ===========================================================================
echo "==> 1/4 git pull"
if [[ -n "${SKIP_PULL:-}" ]]; then
  echo "    已跳过（SKIP_PULL=1）"
else
  git pull --ff-only
fi

# ===========================================================================
# 5. 备份 PostgreSQL（容器内用容器自己的 PG 账号）
# ===========================================================================
echo ""
echo "==> 2/4 备份 PostgreSQL"
BACKUP_FILE="backup_${IMAGE_TAG}_$(date +%Y%m%d-%H%M%S).sql"
if [[ -n "${SKIP_BACKUP:-}" ]]; then
  echo "    已跳过（SKIP_BACKUP=1，不建议）"
else
  if [[ -z "$($COMPOSE ps -q postgres)" ]]; then
    echo "❌ postgres 容器未运行，无法备份，中止。" >&2
    exit 1
  fi
  $COMPOSE exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > "$BACKUP_FILE"
  if [[ ! -s "$BACKUP_FILE" ]]; then
    echo "❌ 备份失败（文件为空），中止。" >&2
    exit 1
  fi
  echo "    ✅ 已备份: $BACKUP_FILE ($(du -h "$BACKUP_FILE" | cut -f1))"
fi

# ===========================================================================
# 6. 改版本号（留 .env.release.bak 以便回滚）
# ===========================================================================
echo ""
echo "==> 3/4 修改 .env.release: SKILLHUB_VERSION=${IMAGE_TAG}"
sed -i.bak "s/^SKILLHUB_VERSION=.*/SKILLHUB_VERSION=${IMAGE_TAG}/" .env.release

# ===========================================================================
# 7. 拉镜像 + 重建容器（pull 失败则回滚 .env.release）
# ===========================================================================
echo ""
echo "==> 4/4 拉取镜像（仅 skillhub 服务：$APP_SERVICES）"
if [[ -n "${SKIP_IMAGE_PULL:-}" ]]; then
  echo "    已跳过（SKIP_IMAGE_PULL=1，使用本地已加载镜像）"
else
  if ! $COMPOSE pull $APP_SERVICES; then
    echo "" >&2
    echo "❌ 拉取镜像失败（tag ${IMAGE_TAG} 可能还没推到 GHCR），已把 .env.release 恢复到升级前。" >&2
    mv -f .env.release.bak .env.release
    exit 1
  fi
  # 预热 sandbox 镜像（不再是 compose service，forkprobe docker 模式按需 `docker run` 时用）。
  # 拉失败不致命：默认执行模式是 direct-api，且真正用到时 `docker run` 会现场自动 pull。
  SANDBOX_IMAGE="$(grep -E '^SKILLHUB_SANDBOX_IMAGE=' .env.release 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '[:space:]')"
  SANDBOX_IMAGE="${SANDBOX_IMAGE:-ghcr.io/iflytek/skillhub-sandbox}"
  SANDBOX_REF="${SANDBOX_IMAGE}:${IMAGE_TAG}"
  if docker pull "$SANDBOX_REF"; then
    echo "    ✅ 已预热 sandbox 镜像: $SANDBOX_REF"
  else
    echo "    ⚠️  sandbox 镜像预热失败（$SANDBOX_REF），跳过（非致命）" >&2
  fi
fi

echo ""
if ! $COMPOSE up -d --wait; then
  echo "" >&2
  echo "❌ docker compose up 失败，已把 .env.release 恢复到升级前。" >&2
  mv -f .env.release.bak .env.release
  exit 1
fi

echo ""
echo "✅ 完成: ${CURRENT_TAG} → ${IMAGE_TAG}"
echo "   访问: http://<服务器IP>:9001"
echo ""
echo "回滚/降级方法:"
echo "  应用层: 把 .env.release 的 SKILLHUB_VERSION 改回旧版本（或用 .env.release.bak），重跑本脚本"
echo "  数据库: 如需连同数据回退，用「升级前那一刻」的备份恢复:"
echo "    $COMPOSE exec -T postgres sh -c 'psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\"' < <升级前的备份.sql>"
echo "    （注意：用升级前的备份回退会丢失升级后产生的新数据）"

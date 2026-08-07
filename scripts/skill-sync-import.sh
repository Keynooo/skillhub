#!/usr/bin/env bash
# 从七牛拉取副实例导出的 skill 全量包，把「主没有」的 PUBLIC 版本增量 publish 进本机（主）。
# 主 ≥ 副：只加不覆盖；别人 owner 占用的同名 skill（含审核中）整条跳过、绝不碰。
#
# 用法:
#   bash scripts/skill-sync-import.sh
#
# 环境变量:
#   ADMIN_PASSWORD=...                     主上 admin「能登录的」密码（必填）。坑: bootstrap 只在首次
#                                          启动建号，之后改 .env.release 的密码不会同步进库；用你实际能
#                                          登录的那个，而不是 .env.release 里的值。
#   MASTER_BASE_URL=http://localhost:9001   本机 API 地址（走对外 9001，nginx 代理 /api 到 server）
#   ADMIN_USER=admin                       admin 用户名
#   QINIU_AK / QINIU_SK / QINIU_BUCKET     七牛凭证（除非用 SYNC_PKG 本地包）
#   QINIU_KEY=skillhub-sync/latest.tar.gz  七牛对象 key（与导出端一致）
#   SYNC_PKG=<path>                        直接用本地 tar 包，不走七牛（联调/离线测试用）
#
# 跑在「主」实例上，当前目录为 skillhub 仓库根（有 .env.release + compose.release.yml）。
# 原理: 走 publish HTTP API 重传（POST /api/cli/v1/skills/{ns}/publish，multipart file=bundle.zip，
#       visibility=PUBLIC），不灌库。用 admin 登录态（SUPER_ADMIN）→ 跳过 membership、PUBLIC 直接 PUBLISHED、
#       owner 天然落 admin（docker-admin）。缺的 namespace 自动建（session 登录态可建，token 不行）。
# 认证: session cookie + CSRF（X-XSRF-TOKEN），流程照搬 scripts/smoke-test.sh 的 admin 登录段。

set -euo pipefail
set +H

# ===========================================================================
# 0. 前置检查 + 配置
# ===========================================================================
[[ -f .env.release ]] || { echo "❌ 当前目录没有 .env.release，请 cd 到 skillhub 仓库根" >&2; exit 1; }
[[ -f compose.release.yml ]] || { echo "❌ 当前目录没有 compose.release.yml" >&2; exit 1; }

COMPOSE="docker compose --env-file .env.release -f compose.release.yml -f compose.verify.yml"
BASE="${MASTER_BASE_URL:-http://localhost:9001}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-ChangeMe!2026}"
QINIU_KEY="${QINIU_KEY:-skillhub-sync/latest.tar.gz}"

# admin 的 userId（owner 判定用）。.env.release 里 BOOTSTRAP_ADMIN_USER_ID 或默认 docker-admin
ADMIN_UID="$(grep -E '^BOOTSTRAP_ADMIN_USER_ID=' .env.release | tail -1 | cut -d= -f2- | tr -d '[:space:]')"
ADMIN_UID="${ADMIN_UID:-docker-admin}"

PG_USER="$(grep -E '^POSTGRES_USER=' .env.release | tail -1 | cut -d= -f2- | tr -d '[:space:]')"
PG_DB="$(grep -E '^POSTGRES_DB=' .env.release | tail -1 | cut -d= -f2- | tr -d '[:space:]')"
PG_USER="${PG_USER:-skillhub}"; PG_DB="${PG_DB:-skillhub}"

WORK="$(mktemp -d)"; PKG="$WORK/skillhub-sync.tar.gz"; PKGDIR="$WORK/pkg"
COOKIE_JAR="$WORK/cookies.txt"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$PKGDIR"

if [[ "$ADMIN_PASSWORD" == "ChangeMe!2026" ]]; then
  echo "⚠️  ADMIN_PASSWORD 是默认占位值，若主上改过密码请用 ADMIN_PASSWORD=... 重跑" >&2
fi

# ===========================================================================
# 1. 拿到同步包
# ===========================================================================
echo "==> 1/5 获取同步包"
if [[ -n "${SYNC_PKG:-}" ]]; then
  cp "$SYNC_PKG" "$PKG"; echo "    用本地包: $SYNC_PKG"
else
  command -v qshell >/dev/null 2>&1 || { echo "❌ 未装 qshell，或用 SYNC_PKG=<本地包> 跳过七牛" >&2; exit 1; }
  : "${QINIU_BUCKET:?需要 QINIU_BUCKET}"
  # 若已配过 qshell account 则可只传 BUCKET，不用重复 AK/SK
  if [[ -n "${QINIU_AK:-}" && -n "${QINIU_SK:-}" ]]; then
    qshell account "$QINIU_AK" "$QINIU_SK" skillhub-sync >/dev/null
  fi
  qshell get "$QINIU_BUCKET" "$QINIU_KEY" "$PKG"
  echo "    已下载: ${QINIU_BUCKET}/${QINIU_KEY}"
fi
tar xzf "$PKG" -C "$PKGDIR"
[[ -f "$PKGDIR/manifest.tsv" ]] || { echo "❌ 包里没有 manifest.tsv" >&2; exit 1; }
echo "    解包完成: $(wc -l < "$PKGDIR/manifest.tsv") 行 manifest"
echo ""

# ===========================================================================
# 2. 登录 admin（CSRF 流程照搬 smoke-test.sh:127-147）
# ===========================================================================
echo "==> 2/5 登录 admin"
curl -s -c "$COOKIE_JAR" "$BASE/api/v1/auth/me" >/dev/null
CSRF="$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIE_JAR" | tail -n 1)"
LOGIN_CODE="$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "$BASE/api/v1/auth/local/login" -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
  -H "X-XSRF-TOKEN: $CSRF" -H "Content-Type: application/json" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASSWORD\"}" || true)"
[[ "$LOGIN_CODE" == "200" ]] || { echo "❌ admin 登录失败 (HTTP $LOGIN_CODE)，检查 ADMIN_USER/ADMIN_PASSWORD" >&2; exit 1; }
CSRF="$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIE_JAR" | tail -n 1)"  # 登录后重读（changeSessionId 轮换）
echo "    ✅ 已登录 ($ADMIN_USER)"
echo ""

# ===========================================================================
# 3. 查主现有集合（admin_have / other_occupied / namespaces）
# ===========================================================================
echo "==> 3/5 查主现有 skill / namespace"
declare -A have_ver=() other_occ=() nsset=() shown_skip=()
# admin 已有 PUBLISHED 版本（版本级差集）
while IFS=$'\t' read -r ns slug ver; do
  [[ -z "${ns:-}" ]] && continue
  have_ver["${ns}"$'\x1f'"${slug}"$'\x1f'"${ver}"]=1
done < <($COMPOSE exec -T postgres psql -U "$PG_USER" -d "$PG_DB" -A -t -F$'\t' -c \
  "SELECT n.slug, s.slug, sv.version FROM skill s JOIN namespace n ON n.id=s.namespace_id
    JOIN skill_version sv ON sv.skill_id=s.id WHERE s.owner_id='${ADMIN_UID}' AND sv.status='PUBLISHED';")
# 别人 owner 占用的同名 skill（任意状态，含审核中）→ 整条跳过
while IFS=$'\t' read -r ns slug; do
  [[ -z "${ns:-}" ]] && continue
  other_occ["${ns}"$'\x1f'"${slug}"]=1
done < <($COMPOSE exec -T postgres psql -U "$PG_USER" -d "$PG_DB" -A -t -F$'\t' -c \
  "SELECT n.slug, s.slug FROM skill s JOIN namespace n ON n.id=s.namespace_id WHERE s.owner_id<>'${ADMIN_UID}';")
# 主已有 namespace
while IFS=$'\t' read -r ns; do
  [[ -z "${ns:-}" ]] && continue
  nsset["$ns"]=1
done < <($COMPOSE exec -T postgres psql -U "$PG_USER" -d "$PG_DB" -A -t -F$'\t' -c "SELECT slug FROM namespace;")
echo "    admin 已有版本: ${#have_ver[@]}；别人占用同名: ${#other_occ[@]}；已有 namespace: ${#nsset[@]}"
echo ""

# ===========================================================================
# 4. 逐版本判定 + publish
# ===========================================================================
echo "==> 4/5 同步"
ADDED=0; SKIP_OTHER=0; SKIP_HAVE=0; SKIP_CORRUPT=0; FAILED=0

ensure_namespace() {  # $1=slug $2=displayName
  local ns="$1" disp="$2"
  disp="${disp//\"/\'}"; disp="${disp//\\/}"  # 防 JSON 断裂
  [[ -n "${nsset[$ns]+x}" ]] && return 0
  local code
  code="$(curl -s -o "$WORK/nsresp" -w "%{http_code}" -X POST "$BASE/api/v1/namespaces" \
    -b "$COOKIE_JAR" -H "X-XSRF-TOKEN: $CSRF" -H "Content-Type: application/json" \
    -d "{\"slug\":\"$ns\",\"displayName\":\"${disp:-$ns}\",\"description\":\"synced from replica\"}" || true)"
  nsset["$ns"]=1  # 无论成败都记，避免本轮重复尝试
  if [[ "$code" == "200" ]] && grep -q '"code":0' "$WORK/nsresp"; then
    echo "    ＋ 建 namespace: $ns"
  else
    echo "    ⚠️  建 namespace $ns 失败 (HTTP $code)，后续 publish 可能失败: $(head -c 200 "$WORK/nsresp")"
  fi
}

while IFS=$'\t' read -r ns ns_disp ns_type slug disp ver rel sha; do
  [[ -z "${ns:-}" ]] && continue

  # (a) 别人 owner 占用同名 → 整条跳过（绝不碰审核中的）
  if [[ -n "${other_occ["${ns}"$'\x1f'"${slug}"]+x}" ]]; then
    if [[ -z "${shown_skip["${ns}"$'\x1f'"${slug}"]+x}" ]]; then
      echo "    ⊘ 跳过（主上已有同名 owner≠admin）：$ns/$slug"
      shown_skip["${ns}"$'\x1f'"${slug}"]=1
    fi
    SKIP_OTHER=$((SKIP_OTHER+1)); continue
  fi
  # (b) admin 已有该版本 → 跳过
  if [[ -n "${have_ver["${ns}"$'\x1f'"${slug}"$'\x1f'"${ver}"]+x}" ]]; then
    SKIP_HAVE=$((SKIP_HAVE+1)); continue
  fi
  # (c) 校验 bundle 完整性
  bundle="$PKGDIR/$rel"
  if [[ ! -f "$bundle" ]]; then echo "    ⚠️  包里缺文件: $rel"; SKIP_CORRUPT=$((SKIP_CORRUPT+1)); continue; fi
  if [[ "$(sha256sum "$bundle" | cut -d' ' -f1)" != "$sha" ]]; then
    echo "    ⚠️  sha256 不符，跳过: $ns/$slug@$ver"; SKIP_CORRUPT=$((SKIP_CORRUPT+1)); continue
  fi
  # (d) 确保 namespace 存在
  ensure_namespace "$ns" "$ns_disp"
  # (e) publish
  code="$(curl -s -o "$WORK/presp" -w "%{http_code}" -X POST "$BASE/api/cli/v1/skills/$ns/publish" \
    -b "$COOKIE_JAR" -H "X-XSRF-TOKEN: $CSRF" -F "visibility=PUBLIC" -F "file=@$bundle" || true)"
  if [[ "$code" == "200" ]] && grep -q '"code":0' "$WORK/presp"; then
    ADDED=$((ADDED+1)); have_ver["${ns}"$'\x1f'"${slug}"$'\x1f'"${ver}"]=1
    echo "    ✓ $ns/$slug@$ver"
  else
    FAILED=$((FAILED+1))
    echo "    ✗ $ns/$slug@$ver (HTTP $code): $(head -c 300 "$WORK/presp")"
  fi
done < "$PKGDIR/manifest.tsv"
echo ""

# ===========================================================================
# 5. 汇总
# ===========================================================================
echo "==> 5/5 完成"
echo "    新增(added):        $ADDED"
echo "    跳过-已有版本:       $SKIP_HAVE"
echo "    跳过-别人占用同名:   $SKIP_OTHER"
echo "    跳过-包损坏:         $SKIP_CORRUPT"
echo "    失败(failed):        $FAILED"
[[ "$FAILED" -eq 0 ]] || echo "    ⚠️  有失败项，看上面 ✗ 行的响应排查（命名空间/校验/扫描器/版本号冲突等）"

#!/usr/bin/env bash
# 导出本机(副)所有 PUBLIC skill 的全量快照，打包上传七牛，供主实例 skill-sync-import.sh 拉取。
#
# 用法:
#   bash scripts/skill-sync-export.sh
#
# 环境变量:
#   QINIU_AK / QINIU_SK / QINIU_BUCKET   七牛凭证与 bucket（不传则跳过上传，只产本地包）
#   QINIU_KEY=skillhub-sync/latest.tar.gz 七牛对象 key（默认值；导入端按同一 key 拉）
#   OUT_DIR=./sync-out                    本地产物目录
#   SKIP_UPLOAD=1                         不上传（无七牛凭证时测前半段）
#
# 跑在「副」实例上（如 10.16.13.106），当前目录为 skillhub 仓库根（有 .env.release + compose.release.yml）。
# 产物: $OUT_DIR/manifest.tsv + $OUT_DIR/bundles/*.zip + $OUT_DIR/skillhub-sync.tar.gz。
# 原理: 直读副的 Postgres 枚举 PUBLIC PUBLISHED 版本，再从 server 容器把每个 bundle.zip 拷出来；
#       不走 HTTP、免认证、能拿到全部 PUBLIC 版本。bundle.zip 即当初发布时上传的原始 zip（含根级 SKILL.md），
#       可被主端 publish 接口原样回传。

set -euo pipefail
set +H  # 关 histexpand

# ===========================================================================
# 0. 前置检查
# ===========================================================================
[[ -f .env.release ]] || { echo "❌ 当前目录没有 .env.release，请 cd 到 skillhub 仓库根" >&2; exit 1; }
[[ -f compose.release.yml ]] || { echo "❌ 当前目录没有 compose.release.yml" >&2; exit 1; }

COMPOSE="docker compose --env-file .env.release -f compose.release.yml -f compose.verify.yml"
OUT_DIR="${OUT_DIR:-./sync-out}"
QINIU_KEY="${QINIU_KEY:-skillhub-sync/latest.tar.gz}"
STORAGE_ROOT="/var/lib/skillhub/storage"  # compose.release.yml 的 STORAGE_BASE_PATH

# 从 .env.release 取 PG 账号（deploy-release.sh 写进去的）
PG_USER="$(grep -E '^POSTGRES_USER=' .env.release | tail -1 | cut -d= -f2- | tr -d '[:space:]')"
PG_DB="$(grep -E '^POSTGRES_DB=' .env.release | tail -1 | cut -d= -f2- | tr -d '[:space:]')"
PG_USER="${PG_USER:-skillhub}"
PG_DB="${PG_DB:-skillhub}"

mkdir -p "$OUT_DIR/bundles"
rm -f "$OUT_DIR"/bundles/*.zip
: > "$OUT_DIR/manifest.tsv"  # 清空（保证即使无版本也存在空文件供 tar）

ENUM_SQL="SELECT n.slug, COALESCE(n.display_name,''), n.type,
                 s.slug, COALESCE(s.display_name,''), sv.version, s.id, sv.id
          FROM skill s
          JOIN namespace n ON n.id = s.namespace_id
          JOIN skill_version sv ON sv.skill_id = s.id
          WHERE s.visibility='PUBLIC' AND s.status='ACTIVE'
            AND sv.status='PUBLISHED' AND sv.download_ready=true AND sv.yanked_at IS NULL
          ORDER BY n.slug, s.slug, sv.version;"

# ===========================================================================
# 1. 命名空间分布（诊断：确认有没有自定义 namespace）
# ===========================================================================
echo "==> 1/4 命名空间分布（PUBLIC PUBLISHED）"
$COMPOSE exec -T postgres psql -U "$PG_USER" -d "$PG_DB" -A -t -F$'\t' -c \
  "SELECT n.type, n.slug, count(*)
   FROM skill s JOIN namespace n ON n.id=s.namespace_id JOIN skill_version sv ON sv.skill_id=s.id
   WHERE s.visibility='PUBLIC' AND s.status='ACTIVE'
     AND sv.status='PUBLISHED' AND sv.download_ready=true AND sv.yanked_at IS NULL
   GROUP BY n.type,n.slug ORDER BY 3 DESC;" | sed 's/^/    /'
echo ""

# ===========================================================================
# 2. 枚举 + 读 bundle + 写 manifest
# ===========================================================================
echo "==> 2/4 读 bundle 并写 manifest"
ROWS="$($COMPOSE exec -T postgres psql -U "$PG_USER" -d "$PG_DB" -A -t -F$'\t' -c "$ENUM_SQL")"
TOTAL=0
SKIP=0
if [[ -n "$ROWS" ]]; then
  while IFS=$'\t' read -r ns ns_disp ns_type slug disp ver sid vid; do
    [[ -z "${ns:-}" ]] && continue
    safe="$(printf '%s__%s__%s' "$ns" "$slug" "$ver" | tr -dc 'A-Za-z0-9._-')"
    rel="bundles/${safe}.zip"
    src="${STORAGE_ROOT}/packages/${sid}/${vid}/bundle.zip"
    if ! $COMPOSE cp "server:${src}" "$OUT_DIR/$rel" >/dev/null 2>&1; then
      echo "    ⚠️  读不到 bundle，跳过: ${ns}/${slug}@${ver}（${src}）"
      SKIP=$((SKIP+1))
      continue
    fi
    sha="$(sha256sum "$OUT_DIR/$rel" | cut -d' ' -f1)"
    # 列: ns | ns_display | ns_type | slug | display_name | version | bundle_relp | sha256
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "$ns" "$ns_disp" "$ns_type" "$slug" "$disp" "$ver" "$rel" "$sha" >> "$OUT_DIR/manifest.tsv"
    TOTAL=$((TOTAL+1))
    echo "    ✓ ${ns}/${slug}@${ver}"
  done <<< "$ROWS"
fi
echo "    共 ${TOTAL} 个版本可同步（${SKIP} 个读 bundle 失败跳过）"
echo ""

# ===========================================================================
# 3. 打包
# ===========================================================================
echo "==> 3/4 打包"
PKG="$OUT_DIR/skillhub-sync.tar.gz"
tar -C "$OUT_DIR" -czf "$PKG" manifest.tsv bundles
SIZE="$(du -h "$PKG" | cut -f1)"
echo "    ✅ $PKG ($SIZE)"
echo ""

# ===========================================================================
# 4. 上传七牛
# ===========================================================================
echo "==> 4/4 上传七牛"
if [[ -n "${SKIP_UPLOAD:-}" ]]; then
  echo "    已跳过（SKIP_UPLOAD=1）"
elif [[ -z "${QINIU_BUCKET:-}" ]]; then
  echo "    ⚠️  未设 QINIU_BUCKET，跳过上传。产物已生成在 $OUT_DIR。"
  echo "       配好凭证后重跑，或本次仅本地校验（导入端可直连本机文件测试）。"
else
  command -v qshell >/dev/null 2>&1 || { echo "❌ 未安装 qshell（七牛 CLI），无法上传" >&2; exit 1; }
  # 若已配过 qshell account 则可只传 BUCKET，不用重复 AK/SK
  if [[ -n "${QINIU_AK:-}" && -n "${QINIU_SK:-}" ]]; then
    qshell account "$QINIU_AK" "$QINIU_SK" skillhub-sync >/dev/null
  fi
  qshell fput "$QINIU_BUCKET" "$QINIU_KEY" "$PKG" --overwrite
  echo "    ✅ 已上传: ${QINIU_BUCKET}/${QINIU_KEY}"
fi

echo ""
echo "完成: ${TOTAL} 个版本 → ${PKG}"

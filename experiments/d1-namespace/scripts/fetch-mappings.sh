#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# T0.3 — 拉取 D1 实验用的真实映射数据
#
# 目标：得到一份 src=intermediary / dst=named 的真实映射，用来验证
#       "Fabric mod 在加载时从 intermediary 重映射到 mojmap" 这条链路。
#
# 数据来源（都不是本仓库的产物，也不得被提交）：
#   1. intermediary        maven.fabricmc.net  net/fabricmc/intermediary/<mc>/intermediary-<mc>-v2.jar
#                          jar 内的 mappings/mappings.tiny：official -> intermediary
#   2. Mojang 官方映射     piston-meta.mojang.com 版本 json 里的 client_mappings
#                          （proguard 格式）：official -> named(mojmap)
#
# 两者按 official（哈希名）对齐后合并，得到 intermediary -> named。
#
# 用法：
#   ./scripts/fetch-mappings.sh [mc版本] [输出目录]
#   ./scripts/fetch-mappings.sh 1.21.11 build/d1/mappings
#
# 注意：本机若走 HTTP 代理，请先 export https_proxy=http://127.0.0.1:7897
# -----------------------------------------------------------------------------
set -euo pipefail

MC_VERSION="${1:-1.21.11}"
OUT_DIR="${2:-build/d1/mappings}"

mkdir -p "$OUT_DIR"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "[fetch-mappings] MC 版本: $MC_VERSION"
echo "[fetch-mappings] 输出目录: $OUT_DIR"

# ---- 1. intermediary ---------------------------------------------------------
INTERMEDIARY_URL="https://maven.fabricmc.net/net/fabricmc/intermediary/${MC_VERSION}/intermediary-${MC_VERSION}-v2.jar"

if [ ! -f "$OUT_DIR/intermediary.jar" ]; then
	echo "[fetch-mappings] 下载 intermediary: $INTERMEDIARY_URL"
	curl -fsSL --retry 3 -o "$OUT_DIR/intermediary.jar" "$INTERMEDIARY_URL"
else
	echo "[fetch-mappings] intermediary 已存在，跳过下载"
fi

if [ ! -f "$OUT_DIR/intermediary.tiny" ]; then
	echo "[fetch-mappings] 解出 mappings/mappings.tiny"
	python - "$OUT_DIR/intermediary.jar" "$OUT_DIR/intermediary.tiny" <<'PY'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as zf:
    names = [n for n in zf.namelist() if n.endswith("mappings.tiny") or n.endswith("mappings.tiny.gz")]
    if not names:
        raise SystemExit("intermediary jar 里没有 mappings.tiny: " + str(zf.namelist()))
    name = names[0]
    data = zf.read(name)
    if name.endswith(".gz"):
        import gzip
        data = gzip.decompress(data)
open(dst, "wb").write(data)
print("wrote", dst, len(data), "bytes from", name)
PY
fi

# ---- 2. Mojang 官方映射（mojmap）---------------------------------------------
if [ ! -f "$OUT_DIR/mojmap.txt" ]; then
	echo "[fetch-mappings] 取版本清单，定位 ${MC_VERSION} 的 client_mappings"
	curl -fsSL -o "$OUT_DIR/version-manifest.json" \
		"https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

	VERSION_JSON="$(python - "$OUT_DIR/version-manifest.json" "$MC_VERSION" <<'PY'
import json, sys
manifest = json.load(open(sys.argv[1], encoding="utf-8"))
want = sys.argv[2]
for v in manifest["versions"]:
    if v["id"] == want:
        print(v["url"])
        break
else:
    raise SystemExit("版本清单里没有 " + want)
PY
)"

	curl -fsSL -o "$OUT_DIR/version.json" "$VERSION_JSON"

	MOJMAP_URL="$(python - "$OUT_DIR/version.json" <<'PY'
import json, sys
v = json.load(open(sys.argv[1], encoding="utf-8"))
url = v["downloads"].get("client_mappings", {}).get("url")
if not url:
    raise SystemExit("该版本没有 client_mappings")
print(url)
PY
)"

	echo "[fetch-mappings] 下载 mojmap: $MOJMAP_URL"
	curl -fsSL --retry 3 -o "$OUT_DIR/mojmap.txt" "$MOJMAP_URL"
else
	echo "[fetch-mappings] mojmap 已存在，跳过下载"
fi

echo "[fetch-mappings] 完成："
ls -la "$OUT_DIR"

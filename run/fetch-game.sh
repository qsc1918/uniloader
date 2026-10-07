#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# 拉取原版 Minecraft（服务端 + 库）到 run/game/
#
#   ./run/fetch-game.sh [mc版本]        默认读 gradle.properties 的 minecraft_version
#
# 为什么要有这个脚本：本仓库**不分发** MC 字节码（见 NOTICE 第四节），
# 但冒烟测试必须有一份原版文件。所以这里只做「从官方源拉取到本地」，
# 产物全部落在 run/game/（已 gitignore）。
#
# 关于 26.x：MC 26.1 起不再混淆，官方版本 json 里没有 client_mappings，
# 因此**不需要任何映射文件**，也不需要 intermediary（见 docs/stage0/T0.2-version-matrix.md）。
#
# 产物：
#   run/game/server.jar           原版服务端（新版是 bundler，内含 libraries/）
#   run/game/version.json         该版本的官方元数据
#   run/game/classpath.txt        服务端库的 classpath（供 KnotServer 使用）
#
# 本机若走 HTTP 代理：export https_proxy=http://127.0.0.1:7897
# -----------------------------------------------------------------------------
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UL_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GAME_DIR="${UL_GAME_DIR:-$SCRIPT_DIR/game}"

MC_VERSION="${1:-$(grep -E '^minecraft_version' "$UL_ROOT/gradle.properties" | head -1 | sed 's/.*= *//')}"

mkdir -p "$GAME_DIR/libs"

echo "[fetch-game] 目标版本: $MC_VERSION"
echo "[fetch-game] 输出目录: $GAME_DIR"

curl -fsSL --retry 3 -o "$GAME_DIR/version-manifest.json" \
	"https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

VERSION_URL="$(python - "$GAME_DIR/version-manifest.json" "$MC_VERSION" <<'PY'
import json, sys
man = json.load(open(sys.argv[1], encoding="utf-8"))
for v in man["versions"]:
    if v["id"] == sys.argv[2]:
        print(v["url"]); break
else:
    raise SystemExit("版本清单里没有 " + sys.argv[2])
PY
)"

curl -fsSL --retry 3 -o "$GAME_DIR/version.json" "$VERSION_URL"

# ---- 服务端 jar -------------------------------------------------------------
python - "$GAME_DIR/version.json" "$GAME_DIR" <<'PY'
import json, os, sys, urllib.request

version = json.load(open(sys.argv[1], encoding="utf-8"))
game_dir = sys.argv[2]
dl = version["downloads"]

# 有没有映射，直接说明这个版本是否混淆（26.1 起就没有了）
has_mappings = "server_mappings" in dl or "client_mappings" in dl
print(f"[fetch-game] 版本 {version['id']}：downloads={sorted(dl)}")
print(f"[fetch-game] 该版本{'仍有混淆（需要映射）' if has_mappings else '未混淆（零重映射，不需要任何映射）'}")

target = os.path.join(game_dir, "server.jar")
if os.path.exists(target):
    print(f"[fetch-game] server.jar 已存在，跳过下载")
else:
    print(f"[fetch-game] 下载服务端 {dl['server']['size'] / 1048576:.1f} MB")
    urllib.request.urlretrieve(dl["server"]["url"], target)

# ---- 库 ---------------------------------------------------------------------
libs = []
for lib in version.get("libraries", []):
    art = (lib.get("downloads") or {}).get("artifact")
    if not art:
        continue
    name = art["path"].split("/")[-1]
    out = os.path.join(game_dir, "libs", name)
    libs.append(out)
    if os.path.exists(out):
        continue
    urllib.request.urlretrieve(art["url"], out)

with open(os.path.join(game_dir, "classpath.txt"), "w", encoding="utf-8") as fh:
    fh.write(os.pathsep.join([target] + libs))

print(f"[fetch-game] 库 {len(libs)} 个，classpath 写入 classpath.txt")
PY

ls -la "$GAME_DIR" | head -12
echo "[fetch-game] 完成"

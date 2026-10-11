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
import json, os, subprocess, sys, zipfile

# 网络一律走 curl，不用 urllib：
#   Windows 上 Python 的 ssl 不认系统证书库，走代理时 urllib 要么 CERTIFICATE_VERIFY_FAILED、
#   要么直接连不上（WinError 10061）。curl 用系统证书库，没这个问题。
#   这条规则在仓库里已经写过（见 tooling/mod-matrix/fetch-mods.sh），这个脚本当时漏了。
def download(url, target):
    if os.path.exists(target):
        return False
    r = subprocess.run(["curl", "-fsSL", "--retry", "3", "--max-time", "900", "-o", target, url],
                       capture_output=True)
    if r.returncode != 0:
        raise SystemExit("[fetch-game] 下载失败(%d): %s\n%s" % (
            r.returncode, url, r.stderr.decode("utf-8", "replace").strip()))
    return True

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
    download(dl["server"]["url"], target)

# ---- 服务端 jar 的两种形态：bundler 还是 fat jar ------------------------------
#
# 这件事不搞清楚，1.14 会以一种非常迷惑的方式坏掉：
#   1.18 起服务端是 **bundler**：server.jar 里只有 165 个条目（一个壳），
#     真正的游戏与库在 META-INF/libraries/ 里，必须把 version json 的 libraries 也挂上。
#   1.14 的 server.jar 是 **fat jar**：24464 个条目，自带 netty / guava / log4j-core，
#     启动方式就是 `java -jar server.jar`。
# 对 fat jar 再挂一遍 libraries，等于把 log4j 塞进 classpath 两份，
# 症状是 log4j2 的插件表读不出来 → 每条日志格式都被判成 "Unrecognized format specifier"，
# 然后回退成「只往控制台打 error」，于是**游戏正常跑起来了但一行日志都看不到**，
# 看起来像「卡住了」。这个坑实测踩过一次，别删这段。
with zipfile.ZipFile(target) as zf:
    names = set(zf.namelist())

is_bundler = "META-INF/versions.list" in names or any(n.startswith("META-INF/libraries/") for n in names)

if is_bundler:
    print("[fetch-game] 服务端形态：bundler（1.18+），需要 version json 里的 libraries")
else:
    print("[fetch-game] 服务端形态：自包含 fat jar（1.14–1.17），**不挂** libraries（挂了两份 log4j）")

# ---- 库 ---------------------------------------------------------------------
libs = []

if is_bundler:
    for lib in version.get("libraries", []):
        art = (lib.get("downloads") or {}).get("artifact")
        if not art:
            continue
        name = art["path"].split("/")[-1]
        out = os.path.join(game_dir, "libs", name)
        libs.append(out)
        download(art["url"], out)

with open(os.path.join(game_dir, "classpath.txt"), "w", encoding="utf-8") as fh:
    fh.write(os.pathsep.join([target] + libs))

print(f"[fetch-game] 库 {len(libs)} 个，classpath 写入 classpath.txt")
PY

ls -la "$GAME_DIR" | head -12
echo "[fetch-game] 完成"

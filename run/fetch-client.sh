#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# 拉取原版 Minecraft **客户端**（客户端 jar + 库 + natives + assets）
#
#   ./run/fetch-client.sh 1.14            # 含 assets（约 200 MB，慢）
#   ./run/fetch-client.sh 1.14 --no-assets
#   UL_GAME_DIR=run/game-1.14 ./run/fetch-client.sh 1.14
#
# 与服务端的区别（这三条都得单独处理）：
#   1. 客户端 jar 是 `downloads.client`，不是 `downloads.server`；
#   2. 库要**按 rules 过滤**（os / arch / features），且 natives 要解压到单独目录；
#   3. 需要 assets：assetIndex 索引 + 索引里每个 object 从 resources.download.minecraft.net 拉。
#
# 已经有官方启动器的人可以跳过 assets：直接指 UL_ASSETS_DIR=<.minecraft>/assets
# 到客户端脚本上，省掉最大的一块下载。
#
# 产物（都在 run/game-<版本>/，已 gitignore）：
#   client.jar  version.json  classpath.txt  natives/  assets/
#
# 本机若走 HTTP 代理：export https_proxy=http://127.0.0.1:7897
# -----------------------------------------------------------------------------

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UL_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

MC_VERSION="${1:-}"
WITH_ASSETS=1
[ "${2:-}" = "--no-assets" ] && WITH_ASSETS=0

if [ -z "$MC_VERSION" ]; then
	echo "用法：./run/fetch-client.sh <mc版本> [--no-assets]" >&2
	exit 2
fi

GAME_DIR="${UL_GAME_DIR:-$SCRIPT_DIR/game-$MC_VERSION}"

mkdir -p "$GAME_DIR/libs" "$GAME_DIR/natives"

echo "[fetch-client] 目标版本: $MC_VERSION"
echo "[fetch-client] 输出目录: $GAME_DIR"
echo "[fetch-client] assets  : $([ "$WITH_ASSETS" -eq 1 ] && echo 下载 || echo 跳过)"

curl -fsSL --retry 3 -o "$GAME_DIR/version-manifest.json" \
	"https://piston-meta.mojang.com/mc/game/version_manifest_v2.json" || {
	echo "UL 错误：拉版本清单失败（网络/代理？先 export https_proxy=http://127.0.0.1:7897）" >&2
	exit 2
}

VERSION_URL="$(python - "$GAME_DIR/version-manifest.json" "$MC_VERSION" <<'PY'
import json, sys

manifest = json.load(open(sys.argv[1], encoding="utf-8"))

for v in manifest["versions"]:
    if v["id"] == sys.argv[2]:
        print(v["url"])
        break
else:
    raise SystemExit("版本清单里没有 " + sys.argv[2])
PY
)"

curl -fsSL --retry 3 -o "$GAME_DIR/version.json" "$VERSION_URL"

python - "$GAME_DIR/version.json" "$GAME_DIR" "$WITH_ASSETS" <<'PY'
import json, os, subprocess, sys, zipfile

version = json.load(open(sys.argv[1], encoding="utf-8"))
game_dir = sys.argv[2]
with_assets = sys.argv[3] == "1"
dl = version["downloads"]


def download(url, target):
    if os.path.exists(target) and os.path.getsize(target) > 0:
        return False
    tmp = target + ".tmp"
    r = subprocess.run(["curl", "-fsSL", "--retry", "3", "--max-time", "900", "-o", tmp, url],
                       capture_output=True)
    if r.returncode != 0:
        raise SystemExit("[fetch-client] 下载失败(%d): %s\n%s" % (
            r.returncode, url, r.stderr.decode("utf-8", "replace").strip()))
    os.replace(tmp, target)
    return True


# ---- 1. 客户端 jar -----------------------------------------------------------
client = dl.get("client")
if not client:
    raise SystemExit("[fetch-client] 这个版本没有 client 下载项")

target = os.path.join(game_dir, "client.jar")
if os.path.exists(target):
    print("[fetch-client] client.jar 已存在，跳过下载")
else:
    print("[fetch-client] 下载客户端 %.1f MB" % (client["size"] / 1048576))
    download(client["url"], target)

# ---- 2. 库（按 rules 过滤）+ natives -----------------------------------------
def rules_allow(lib):
    """官方 rules 语义：没有 rules 就是允许；有 rules 时按顺序取最后一条命中的动作。"""
    rules = lib.get("rules")
    if not rules:
        return True

    import platform

    system = platform.system().lower()
    os_name = {"windows": "windows", "linux": "linux", "darwin": "osx"}.get(system, system)
    arch = platform.machine().lower()
    arch_name = "x86" if arch in ("x86", "i386", "i686") else "x86_64" if arch in ("amd64", "x86_64") else arch

    allowed = False

    for rule in rules:
        action = rule.get("action") == "allow"
        cond = rule.get("os") or {}

        match = True

        if "name" in cond and cond["name"] != os_name:
            match = False
        if "arch" in cond and cond["arch"] != arch_name:
            match = False
        if rule.get("features"):
            # 我们没有 demo/quickplay 之类的 features，全部按「不启用」处理
            match = False

        if match:
            allowed = action

    return allowed


libs = []
natives = []

for lib in version.get("libraries", []):
    if not rules_allow(lib):
        continue

    art = (lib.get("downloads") or {}).get("artifact")
    if art:
        out = os.path.join(game_dir, "libs", art["path"].split("/")[-1])
        libs.append(out)
        download(art["url"], out)

    classifiers = (lib.get("downloads") or {}).get("classifiers") or {}
    native_key = (lib.get("natives") or {}).get(
        {"windows": "windows", "linux": "linux", "darwin": "osx"}.get(
            __import__("platform").system().lower(), "linux"))

    if native_key and native_key in classifiers:
        nart = classifiers[native_key]
        out = os.path.join(game_dir, "natives", nart["path"].split("/")[-1])
        download(nart["url"], out)
        natives.append(out)

# 解压 natives（只取本地库，跳过 META-INF）
natives_dir = os.path.join(game_dir, "natives")

for jar in natives:
    try:
        with zipfile.ZipFile(jar) as zf:
            for name in zf.namelist():
                if name.startswith("META-INF/") or name.endswith("/"):
                    continue
                zf.extract(name, natives_dir)
    except Exception as e:
        print("[fetch-client] natives 解压失败（可忽略，多半是平台不匹配）: %s -> %s" % (jar, e))

with open(os.path.join(game_dir, "classpath.txt"), "w", encoding="utf-8") as fh:
    fh.write(os.pathsep.join([target] + libs))

print("[fetch-client] 库 %d 个，natives %d 个，classpath 写入 classpath.txt" % (len(libs), len(natives)))

# ---- 3. assets ---------------------------------------------------------------
if not with_assets:
    print("[fetch-client] 跳过 assets（用 UL_ASSETS_DIR 指向已有的 assets 目录即可）")
    sys.exit(0)

# assetIndex 在 version json 的**顶层**，不在 downloads 里。
# 之前写成 dl.get("assetIndex") → 永远取不到 → 静默跳过 assets →
# 客户端能起来但停在资源加载阶段，而日志里只轻描淡写一句「没有 assetIndex」。
index = version.get("assetIndex")
if not index:
    print("[fetch-client] 这个版本没有 assetIndex，跳过")
    sys.exit(0)

assets_root = os.path.join(game_dir, "assets")
indexes_dir = os.path.join(assets_root, "indexes")
objects_dir = os.path.join(assets_root, "objects")
os.makedirs(indexes_dir, exist_ok=True)

index_file = os.path.join(indexes_dir, index["id"] + ".json")
download(index["url"], index_file)

data = json.load(open(index_file, encoding="utf-8"))
objects = data.get("objects", {})
print("[fetch-client] assets 共 %d 个对象（首次下载会比较久，之后会跳过）" % len(objects))

base = "https://resources.download.minecraft.net"
done = 0
skipped = 0

for name, obj in objects.items():
    h = obj["hash"]
    sub = h[:2]
    target = os.path.join(objects_dir, sub, h)

    if os.path.exists(target) and os.path.getsize(target) == obj.get("size", -1):
        skipped += 1
        continue

    os.makedirs(os.path.join(objects_dir, sub), exist_ok=True)
    download("%s/%s/%s" % (base, sub, h), target)
    done += 1

    if done % 200 == 0:
        print("[fetch-client]   ... 已下载 %d 个" % done)

print("[fetch-client] assets 完成：新下载 %d，跳过 %d" % (done, skipped))
PY

echo "[fetch-client] 完成：$GAME_DIR"

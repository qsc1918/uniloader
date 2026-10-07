#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# T0.6 — 按矩阵清单拉取模组（不提交二进制，只提交清单与这个脚本）
#
#   ./fetch-mods.sh baseline            # 拉取某个矩阵
#   ./fetch-mods.sh --all               # 拉取全部矩阵
#   ./fetch-mods.sh baseline --dry-run  # 只查可用性，不下载
#
# 数据源：Modrinth 公开 API v2（无需 token）。
# 输出：
#   run/mods/*.jar            下载到的模组
#   matrix-resolved.json      每个 slug 的实测可用性（这才是数据，清单里的 available 只是占位）
#
# 重要：**「查不到」本身就是结论**。某个 mod 对目标 MC 版本没有发布时，
# 脚本会明确记下 unavailable 并给出原因，而不是静默跳过——静默跳过会让
# 后续的冒烟测试变成「跑了但什么都没验证」。
#
# 本机若走 HTTP 代理：export https_proxy=http://127.0.0.1:7897
# -----------------------------------------------------------------------------

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UL_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MATRIX_FILE="$SCRIPT_DIR/matrix.yml"
OUT_DIR="${UL_MODS_DIR:-$UL_ROOT/run/mods}"
RESOLVED="$SCRIPT_DIR/matrix-resolved.json"

MATRIX="${1:-baseline}"
DRY_RUN=0
[ "${2:-}" = "--dry-run" ] && DRY_RUN=1
[ "$MATRIX" = "--dry-run" ] && { DRY_RUN=1; MATRIX="baseline"; }

if [ ! -f "$MATRIX_FILE" ]; then
	echo "找不到矩阵清单: $MATRIX_FILE" >&2
	exit 2
fi

MC_VERSION="$(grep -E '^minecraft_version:' "$MATRIX_FILE" | head -1 | sed 's/.*: *"\{0,1\}\([^"]*\)"\{0,1\}/\1/')"
echo "[mod-matrix] MC 版本: $MC_VERSION  矩阵: $MATRIX  输出: $OUT_DIR"

mkdir -p "$OUT_DIR"
echo "{" > "$RESOLVED"

# 用 python 解析 yml（只做缩进级解析，不引入依赖）并逐条拉取
python - "$MATRIX_FILE" "$MATRIX" "$MC_VERSION" "$OUT_DIR" "$RESOLVED" "$DRY_RUN" <<'PY'
import json, os, sys, urllib.request, urllib.parse, urllib.error

matrix_file, want_matrix, mc_version, out_dir, resolved_path, dry_run = sys.argv[1:7]
dry_run = dry_run == "1"

# ---- 极简 YAML 解析：只认本文件用到的两层结构 --------------------------------
matrices = {}
current_matrix = None
current_mod = None

for raw in open(matrix_file, encoding="utf-8"):
    if not raw.strip() or raw.lstrip().startswith("#"):
        continue
    indent = len(raw) - len(raw.lstrip(" "))
    line = raw.strip()

    if indent == 2 and line.endswith(":"):
        current_matrix = line[:-1]
        matrices[current_matrix] = []
    elif indent == 4 and line.startswith("- slug:"):
        current_mod = {"slug": line.split(":", 1)[1].strip()}
        matrices[current_matrix].append(current_mod)
    elif indent == 6 and current_mod is not None and ":" in line:
        k, v = line.split(":", 1)
        current_mod[k.strip()] = v.strip()

if want_matrix == "--all":
    targets = matrices
else:
    targets = {want_matrix: matrices.get(want_matrix, [])}
    if not targets[want_matrix]:
        print("矩阵不存在或为空: " + want_matrix, file=sys.stderr)
        sys.exit(2)

def api(path):
    req = urllib.request.Request("https://api.modrinth.com/v2" + path,
                                 headers={"User-Agent": "UniLoader-mod-matrix/0.1 (T0.6)"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)

results = {}
ok = fail = 0

for name, mods in targets.items():
    print(f"\n=== 矩阵 {name}（{len(mods)} 个 mod）===")
    for mod in mods:
        slug = mod["slug"]
        loaders = [mod.get("ecosystem", "fabric")]
        query = urllib.parse.urlencode({
            "loaders": json.dumps(loaders),
            "game_versions": json.dumps([mc_version]),
        })
        try:
            versions = api(f"/project/{slug}/version?{query}")
        except urllib.error.HTTPError as e:
            results[slug] = {"available": False, "reason": f"HTTP {e.code}"}
            print(f"  [UNAVAILABLE] {slug}: HTTP {e.code}")
            fail += 1
            continue
        except Exception as e:  # 网络问题也要说出来
            results[slug] = {"available": False, "reason": f"{type(e).__name__}: {e}"}
            print(f"  [UNAVAILABLE] {slug}: {e}")
            fail += 1
            continue

        if not versions:
            results[slug] = {"available": False, "reason": f"该 mod 没有 {mc_version} / {loaders[0]} 的发布"}
            print(f"  [UNAVAILABLE] {slug}: 没有 {mc_version} 的 {loaders[0]} 发布")
            fail += 1
            continue

        v = versions[0]
        files = [f for f in v["files"] if f.get("primary")] or v["files"]
        f = files[0]
        results[slug] = {
            "available": True,
            "version": v["version_number"],
            "file": f["filename"],
            "url": f["url"],
            "verifies": mod.get("verifies", ""),
        }
        print(f"  [OK] {slug} {v['version_number']} -> {f['filename']}")

        if not dry_run:
            target = os.path.join(out_dir, f["filename"])
            if os.path.exists(target):
                print(f"       已存在，跳过下载")
            else:
                urllib.request.urlretrieve(f["url"], target)
                print(f"       已下载到 {target}")
        ok += 1

with open(resolved_path, "w", encoding="utf-8") as fh:
    json.dump({"minecraft_version": mc_version, "matrix": want_matrix, "results": results},
              fh, ensure_ascii=False, indent=2)
    fh.write("\n")

print(f"\n[mod-matrix] 可用 {ok}，不可用 {fail}；明细见 {resolved_path}")
sys.exit(0 if ok > 0 else 1)
PY

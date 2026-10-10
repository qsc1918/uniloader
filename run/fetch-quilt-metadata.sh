#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# T2.1 验收夹具 —— 把真实 jar 里的 quilt.mod.json 全都抠出来
#
#   ./run/fetch-quilt-metadata.sh            # 拉 QSL（一个 jar 里 50+ 个模组）
#   ./run/fetch-quilt-metadata.sh --extra    # 顺带拉几个 26.3 上的真实 Quilt 模组
#
# 产出：build/quilt-metadata/*.json（每份文件名带来源，便于失败时指认）
#
# 用法（配合单测做批量解析验证）：
#   ./run/fetch-quilt-metadata.sh
#   UL_QUILT_METADATA_DIR=$PWD/build/quilt-metadata \
#       ./gradlew :loader:test --tests '*QuiltModMetadataReaderTest*'
#
# 为什么要这么一层：单测里提交的夹具只有 4 份，覆盖得到字段、覆盖不到「规模」。
# QSL 一个 jar 里塞了 50 多个嵌套模组，每份都是独立的 quilt.mod.json，
# 正好是「20 个真实 quilt.mod.json 解析成功率 100%」这条验收要的样本量。
# 二进制不进版本库（.gitignore 已覆盖 build/），这里只留脚本。
# -----------------------------------------------------------------------------

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UL_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="$UL_ROOT/build/quilt-metadata"
JAR_DIR="$UL_ROOT/build/quilt-metadata-jars"

WITH_EXTRA=0
[ "${1:-}" = "--extra" ] && WITH_EXTRA=1

export https_proxy="${https_proxy:-http://127.0.0.1:7897}"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR" "$JAR_DIR"

# QSL 是唯一一个「provides 语义成体系」的真实样本（qsl 的 slug 就叫 qsl，
# quilted-fabric-api 那个 slug 在 Modrinth 上是 404）。
echo "[quilt-metadata] 拉取 QSL…"
curl -fsSL --retry 3 --max-time 300 -o "$JAR_DIR/qsl.jar" \
	"https://cdn.modrinth.com/data/qvIfYCYJ/versions/WDWNp8qJ/quilted-fabric-api-11.0.0-alpha.3%2B0.102.0-1.21.jar" || {
	echo "UL 错误：QSL 下载失败（网络/代理？）" >&2
	exit 2
}

if [ "$WITH_EXTRA" -eq 1 ]; then
	echo "[quilt-metadata] 拉取 26.3 上的真实 Quilt 模组…"
	curl -fsSL --retry 3 --max-time 300 -o "$JAR_DIR/voicechat-quilt.jar" \
		"https://cdn.modrinth.com/data/9eGKb6K1/versions/hL0QwVEh/voicechat-quilt-2.6.24%2B26.3.jar" || true
fi

# 用 python 解压：run/ 下已经依赖 python（fetch-mods.sh），不算新增前提。
python - "$JAR_DIR" "$OUT_DIR" <<'PY'
import io, os, sys, zipfile

jar_dir, out_dir = sys.argv[1], sys.argv[2]
count = 0
failures = []

def dump(name, data, source):
    global count
    path = os.path.join(out_dir, name)
    with open(path, 'wb') as fh:
        fh.write(data)
    count += 1

for jar_name in sorted(os.listdir(jar_dir)):
    if not jar_name.endswith('.jar'):
        continue

    jar_path = os.path.join(jar_dir, jar_name)
    base = os.path.splitext(jar_name)[0]

    try:
        outer = zipfile.ZipFile(jar_path)
    except Exception as e:
        failures.append('%s: %s' % (jar_name, e))
        continue

    if 'quilt.mod.json' in outer.namelist():
        dump('%s.json' % base, outer.read('quilt.mod.json'), jar_name)

    # 嵌套 jar：Quilt 模组把依赖塞在 META-INF/jars/ 下，每个都是一份独立的 quilt.mod.json
    for entry in outer.namelist():
        if not entry.endswith('.jar'):
            continue
        try:
            inner = zipfile.ZipFile(io.BytesIO(outer.read(entry)))
        except Exception:
            continue
        if 'quilt.mod.json' not in inner.namelist():
            continue
        nested = os.path.basename(entry)[:-4]
        dump('%s--%s.json' % (base, nested), inner.read('quilt.mod.json'), entry)

print('[quilt-metadata] 抽出 %d 份 quilt.mod.json -> %s' % (count, out_dir))
if failures:
    print('[quilt-metadata] 打不开的 jar: %s' % failures)
if count == 0:
    sys.exit(3)
PY

STATUS=$?

if [ "$STATUS" -ne 0 ]; then
	echo "UL 错误：一份 quilt.mod.json 都没抽出来（退出码 $STATUS）" >&2
	exit "$STATUS"
fi

echo
echo "下一步（批量解析验证）："
echo "  UL_QUILT_METADATA_DIR=$OUT_DIR ./gradlew :loader:test --tests '*QuiltModMetadataReaderTest*'"

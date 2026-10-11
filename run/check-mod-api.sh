#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# 检查模组引用的 fabric-loader API 在**当前内核**里到底有没有
#
#   ./run/check-mod-api.sh <模组jar> [更多jar...]
#   ./run/check-mod-api.sh run/mods-1.14/*.jar      # 整个目录一次查完
#
# 为什么需要它：
#   上游 fabric-loader 会**删掉**旧 API，而老模组是对着老 loader 编译的。
#   删掉之后编译期没问题，运行期才抛 NoSuchMethodError —— 而且只在**该模组真的
#   被调用到时**才出现，所以「崩一次修一次」会非常慢。
#
#   实测样本：ModMenu 1.5.3（2019）调用 `ModMetadata.getCustomElement(String)`，
#   这个方法在 loader 0.19.5 里已经不存在 → 客户端在入口点阶段崩，
#   报错只说 NoSuchMethodError，不会告诉你「这是 loader 删掉的 API」。
#
# 一次把所有这类模组都列出来，比一个一个跑快得多。
# -----------------------------------------------------------------------------

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UL_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

KERNEL="${UL_LOADER_JAR:-}"

if [ -z "$KERNEL" ]; then
	KERNEL="$(ls -t "$UL_ROOT/loader/build/libs"/*.jar 2>/dev/null | grep -v -E 'sources|javadoc|-fat' | head -1 || true)"
fi

if [ -z "$KERNEL" ] || [ ! -f "$KERNEL" ]; then
	echo "UL 错误：找不到内核 jar。先跑 ./gradlew :loader:build" >&2
	exit 2
fi

if [ "$#" -eq 0 ]; then
	echo "用法：./run/check-mod-api.sh <模组jar> [更多jar...]" >&2
	exit 2
fi

exec python "$UL_ROOT/tooling/mod-matrix/check-mod-api.py" "$KERNEL" "$@"

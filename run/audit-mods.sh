#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# 批量审计一批模组 jar：能不能在目标 MC 版本上用
#
#   ./run/audit-mods.sh <目录或jar>...
#   ./run/audit-mods.sh /d/work2/1.14mods /d/work2/1.14.4mods
#   UL_AUDIT_JAVA=8 ./run/audit-mods.sh <目录>
#
# 三个维度互相独立：字节码版本（决定需要哪个 Java）、MC 版本（intermediary 名字
# 命中率）、元数据与生态标记。详细理由写在 tooling/mod-matrix/audit-mods.py 文件头。
#
# 本机若走 HTTP 代理：export https_proxy=http://127.0.0.1:7897
# -----------------------------------------------------------------------------

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UL_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ "$#" -eq 0 ]; then
	echo "用法：./run/audit-mods.sh <目录或jar>..." >&2
	exit 2
fi

to_native() {
	if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi
}

ARGS=(--java "${UL_AUDIT_JAVA:-8}" --cache "$(to_native "$UL_ROOT/build/intermediary-cache")")

for p in "$@"; do
	ARGS+=("$(to_native "$p")")
done

exec python "$UL_ROOT/tooling/mod-matrix/audit-mods.py" "${ARGS[@]}"

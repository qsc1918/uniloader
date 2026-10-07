#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# T1.6 — 把内核落下的诊断证据（.uniloader/kernel-diagnostics.txt）渲染成
#        玩家能找到的 .uniloader/mods-report.txt
#
#   ./run/report.sh [游戏目录]
#
# 内核只负责「记事实并落盘」，渲染在这里做：内核是独立构建，不能反向依赖诊断模块，
# 而且把渲染放在启动之外，上一次崩溃的那次启动也一样能被渲染出来。
#
# 全绿的一次启动不会留下 mods-report.txt——文件的存在本身就是信号。
# -----------------------------------------------------------------------------

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# env.sh 的 [UL] 提示行走 stdout，这里不需要，重定向掉；它的错误走 stderr，照常可见。
# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh" > /dev/null

GAME_DIR_ARG="${1:-${UL_GAME_DIR:-$GAME_DIR}}"

CP=""
add_jar() {
	[ -f "$1" ] || return 0
	case "$1" in
		*sources* | *javadoc*) return 0 ;;
	esac
	CP="${CP:+$CP:}$1"
}

for jar in "$UL_ROOT"/tooling/load-report/build/libs/*.jar "$UL_ROOT"/compat/compat-api/build/libs/*.jar; do
	add_jar "$jar"
done

if [ -z "$CP" ]; then
	echo "UL 错误：还没有诊断模块的 jar。先构建：" >&2
	echo "        ./gradlew build" >&2
	exit 2
fi

exec "$JAVA" -cp "$CP" org.uniloader.diagnostics.report.ReportMain "$GAME_DIR_ARG"

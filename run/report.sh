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

# 诊断工具用**能跑它的** JVM，而不是游戏那个 JVM。
#
# 为什么：工具是构建产物（tooling/* 用 toolchain 21 编译，class file 65），
# 而 1.14 这类老版本的游戏 JVM 是 Java 8，加载不了 class file 65 —— 直接
# UnsupportedClassVersionError，报告生成失败，而真正的问题（游戏）其实已经跑完了。
# 工具只读一个文本文件，跟游戏的运行时无关，所以这里独立挑 JVM。
tool_java() {
	if [ -n "${UL_TOOL_JAVA:-}" ] && "$UL_TOOL_JAVA" -version >/dev/null 2>&1; then
		echo "$UL_TOOL_JAVA"
		return
	fi

	local candidate

	for candidate in "${JAVA_HOME:+$JAVA_HOME/bin/java}" \
			"/c/Program Files/Zulu"/zulu-21*/bin/java "/c/Program Files/Zulu"/zulu-25*/bin/java \
			"/c/Program Files/Java"/jdk-21*/bin/java "/c/Program Files/Java"/jdk-25*/bin/java \
			"$JAVA"; do
		[ -n "$candidate" ] && [ -x "$candidate" ] && { echo "$candidate"; return; }
	done

	echo "$JAVA"
}

TOOL_JAVA="$(tool_java)"

exec "$TOOL_JAVA" -cp "$CP" org.uniloader.diagnostics.report.ReportMain "$GAME_DIR_ARG"

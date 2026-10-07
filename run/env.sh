#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# T0.4 — 实验台公共环境
#
# 被 client.sh / server.sh / smoke.sh 引用。只做三件事：
#   1. 定位仓库根、Java、内核 jar；
#   2. 约定运行目录（run/game 放游戏与日志，run/mods 放模组）；
#   3. 把「跑不起来的原因」说清楚，而不是让调用方看到一个空指针。
#
# 本仓库不分发 Minecraft 字节码与模组二进制（见 NOTICE 第四节），
# 所以这里只检查它们在不在，不会去下载。
# -----------------------------------------------------------------------------

set -uo pipefail

RUN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UL_ROOT="$(cd "$RUN_DIR/.." && pwd)"

GAME_DIR="${UL_GAME_DIR:-$RUN_DIR/game}"
MODS_DIR="${UL_MODS_DIR:-$RUN_DIR/mods}"
LOG_DIR="$GAME_DIR/logs"

mkdir -p "$GAME_DIR" "$MODS_DIR"

# ---- Java ------------------------------------------------------------------
# 运行目标 25（与 NeoForge/Forge 对齐）；允许用 UL_JAVA 覆盖。
if [ -n "${UL_JAVA:-}" ]; then
	JAVA="$UL_JAVA"
elif [ -n "${JAVA_HOME:-}" ]; then
	JAVA="$JAVA_HOME/bin/java"
else
	JAVA="java"
fi

if ! "$JAVA" -version >/dev/null 2>&1; then
	echo "UL 错误：找不到可用的 Java（试过 UL_JAVA / JAVA_HOME / PATH）。" >&2
	echo "        本项目的运行目标是 Java 25，见 gradle.properties 的 java_version。" >&2
	exit 2
fi

# ---- 内核 jar ---------------------------------------------------------------
# 优先用 UL_LOADER_JAR；否则取 loader/build/libs 下最新的非 sources/javadoc jar。
find_loader_jar() {
	if [ -n "${UL_LOADER_JAR:-}" ]; then
		echo "$UL_LOADER_JAR"
		return
	fi

	local libs="$UL_ROOT/loader/build/libs"
	[ -d "$libs" ] || return 1

	# 排除 sources / javadoc / fat：fat 是 proguard 之前的中间产物，
	# 拿它当运行内核会把 shade 过的依赖再套一层。
	local newest
	newest="$(ls -t "$libs"/*.jar 2>/dev/null | grep -v -E 'sources|javadoc|-fat' | head -1 || true)"
	[ -n "$newest" ] && echo "$newest"
}

LOADER_JAR="$(find_loader_jar || true)"

require_loader_jar() {
	if [ -z "$LOADER_JAR" ] || [ ! -f "$LOADER_JAR" ]; then
		echo "UL 错误：还没有内核 jar。先构建：" >&2
		echo "        ./gradlew :loader:build    （或在仓库根跑 ./gradlew build）" >&2
		exit 2
	fi
}

# ---- 模组 -------------------------------------------------------------------
# 真实模组由 tooling/mod-matrix 的脚本按清单拉取到 run/mods；这里只做存在性检查。
count_mods() {
	ls "$MODS_DIR"/*.jar 2>/dev/null | wc -l | tr -d ' '
}

echo "[UL] 根目录   : $UL_ROOT"
echo "[UL] Java     : $("$JAVA" -version 2>&1 | head -1)"
echo "[UL] 内核 jar : ${LOADER_JAR:-<未构建>}"
echo "[UL] 游戏目录 : $GAME_DIR"
echo "[UL] 模组目录 : $MODS_DIR（$(count_mods) 个 jar）"

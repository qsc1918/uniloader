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

# 目录一律绝对化。原因：server.sh 会 `cd "$GAME_DIR"` 之后再 exec java，于是
# `-Dfabric.modsFolder=<相对路径>` 会被解析到**游戏目录下面**——曾出现过
# 「Could not create directory run/game/run/mods-mixed」这种错。调用方给相对路径
# （例如 `UL_MODS_DIR=run/mods-broken`）时，在这里补全成绝对路径，后面就不用再小心。
# 万一 cd 失败（权限、路径异常），保留原值而不是把它清空——空目录会让后面的
# `-Dfabric.modsFolder=` 静默变成当前目录，那比直接报错更难查。
GAME_DIR="$(cd "$GAME_DIR" 2>/dev/null && pwd)" || GAME_DIR="${UL_GAME_DIR:-$RUN_DIR/game}"
MODS_DIR="$(cd "$MODS_DIR" 2>/dev/null && pwd)" || MODS_DIR="${UL_MODS_DIR:-$RUN_DIR/mods}"

# ---- Java ------------------------------------------------------------------
# **构建**用 21（内核下限），但**运行**要跟目标 MC 版本走：
#   MC 26.x        → Java 25（class file 69.0）
#   MC 1.18–1.20.4 → Java 17
#   MC 1.17        → Java 16
#   MC 1.14–1.16.5 → **Java 8**（jre-legacy）
# 用错版本的下场不是「跑不起来」这么简单：1.14 在 Java 17+ 上会因为
# `--illegal-access` 被移除、`Unsafe` 受限而抛 InaccessibleObjectException，
# 报错发生在游戏内部，很容易被误判成「内核把 1.14 搞坏了」。
#
# **不硬编码版本表**（工程纪律第 4 条）：要什么 Java 由官方元数据说了算——
# fetch-game.sh 会把版本 json 落在 $GAME_DIR/version.json，里面就有
# javaVersion.majorVersion。读不到才退回 gradle.properties 的 java_version。
java_major() {
	# 注意 Java 8 的版本串是 "1.8.0_503"：第一位是 1，真正的主版本是第二位。
	"$1" -version 2>&1 | head -1 \
		| sed -E 's/.*"([0-9]+)(\.([0-9]+))?.*/\1 \3/' \
		| awk '{ if ($1 == "1" && $2 != "") print $2; else print $1 }'
}

REQUIRED_JAVA_MAJOR=""
JAVA_INFO_FILE="$GAME_DIR/version.json"

if [ -f "$JAVA_INFO_FILE" ]; then
	REQUIRED_JAVA_MAJOR="$("$UL_ROOT/run/java-major-from-json.sh" "$JAVA_INFO_FILE" 2>/dev/null || true)"
fi

if [ -z "$REQUIRED_JAVA_MAJOR" ]; then
	REQUIRED_JAVA_MAJOR="$(grep -E '^java_version' "$UL_ROOT/gradle.properties" | head -1 | sed 's/.*= *//')"
	JAVA_INFO_FILE="gradle.properties 的 java_version（没有版本 json）"
fi

# 显式覆盖：UL_JAVA_MAJOR=8 ./run/smoke.sh ...
REQUIRED_JAVA_MAJOR="${UL_JAVA_MAJOR:-$REQUIRED_JAVA_MAJOR}"

JAVA=""
FALLBACK_JAVA=""
FALLBACK_MAJOR=""
EXACT_JAVA=""

try_java() {
	local candidate="$1"
	[ -n "$candidate" ] || return 0
	"$candidate" -version >/dev/null 2>&1 || return 0

	local major
	major="$(java_major "$candidate")"

	# 主版本完全对上就直接定下来：1.14 要的是 Java 8，不是「>= 8」——
	# 更高版本的 Java 跑老 MC 会以别的方式坏掉。
	# 先到先得：UL_JAVA / JAVA_HOME 排在前面，所以它们优先于扫描到的安装位置；
	# 扫描顺序固定，结果就是确定的（不依赖 glob 的最后一项碰巧是谁）。
	if [ -n "$major" ] && [ "$major" = "$REQUIRED_JAVA_MAJOR" ] && [ -z "$EXACT_JAVA" ]; then
		EXACT_JAVA="$candidate"
	fi

	if [ -n "$major" ] && [ "$major" -ge "$REQUIRED_JAVA_MAJOR" ] 2>/dev/null && [ -z "$JAVA" ]; then
		JAVA="$candidate"
	fi

	if [ -z "$FALLBACK_JAVA" ]; then
		FALLBACK_JAVA="$candidate"
		FALLBACK_MAJOR="$major"
	fi

	return 0
}

try_java "${UL_JAVA:-}"
try_java "${JAVA_HOME:+$JAVA_HOME/bin/java}"

# 常见安装位置：覆盖 8 / 11 / 17 / 21 / 25，缺哪个都不影响（-x 兜住）。
for dir in "/c/Program Files/Zulu"/zulu-* "/c/Program Files/Java"/jdk-* \
		"/c/Program Files/Java"/jre1.* "/c/Program Files/Eclipse Adoptium"/*; do
	[ -x "$dir/bin/java" ] && try_java "$dir/bin/java"
done

try_java "java"

if [ -n "$EXACT_JAVA" ]; then
	JAVA="$EXACT_JAVA"
elif [ -z "$JAVA" ]; then
	if [ -n "$FALLBACK_JAVA" ]; then
		echo "UL 警告：只找到 Java $FALLBACK_MAJOR（$FALLBACK_JAVA），但目标需要 Java $REQUIRED_JAVA_MAJOR。" >&2
		echo "         目标版本要求的 Java 来自 $JAVA_INFO_FILE。" >&2
		echo "         用错版本会在游戏内部以奇怪的方式炸（1.14 在 Java 17+ 上会 InaccessibleObjectException）。" >&2
		echo "         请设 UL_JAVA 指向 Java $REQUIRED_JAVA_MAJOR，或用 UL_JAVA_MAJOR 覆盖期望值。" >&2
		JAVA="$FALLBACK_JAVA"
	else
		echo "UL 错误：找不到任何可用的 Java（试过 UL_JAVA / JAVA_HOME / 常见安装位置 / PATH）。" >&2
		exit 2
	fi
fi

echo "[UL] 目标 Java  : $REQUIRED_JAVA_MAJOR（来自 $JAVA_INFO_FILE）"

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

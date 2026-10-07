#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# 组装「跑一次内核」所需的完整 classpath，并打印出来（一行）。
#
#   ./run/loader-classpath.sh            # 内核 + 依赖 + 原版游戏文件
#   ./run/loader-classpath.sh --no-game  # 只要内核 + 依赖
#
# 组成：
#   1. 内核 jar（loader/build/libs 下最新的非 sources/javadoc/-fat 的那个）
#   2. 内核的 installer 依赖：asm 全家 + sponge-mixin（在 Gradle 缓存里）
#   3. 原版游戏：run/game/classpath.txt（由 run/fetch-game.sh 生成；服务端 jar + 114 个库）
#
# 为什么要单独一个脚本：内核的 installer 依赖不在内核 jar 里（它们是启动器注入的），
# 而 Knot 启动时必须有 asm 与 mixin。把这件事收在一处，server/client/smoke 共用。
# -----------------------------------------------------------------------------
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UL_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GAME_DIR="${UL_GAME_DIR:-$SCRIPT_DIR/game}"

WITH_GAME=1
[ "${1:-}" = "--no-game" ] && WITH_GAME=0

# Windows 上 java.exe 需要 Windows 形式路径；其余平台用原样路径。
to_native() {
	if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi
}

# ---- 1. 内核 jar -------------------------------------------------------------
LOADER_JAR=""
if [ -n "${UL_LOADER_JAR:-}" ]; then
	LOADER_JAR="$UL_LOADER_JAR"
else
	LOADER_JAR="$(ls -t "$UL_ROOT/loader/build/libs"/*.jar 2>/dev/null | grep -v -E 'sources|javadoc|-fat' | head -1 || true)"
fi

if [ -z "$LOADER_JAR" ] || [ ! -f "$LOADER_JAR" ]; then
	echo "UL 错误：还没有内核 jar。先跑 ./gradlew :loader:build" >&2
	exit 2
fi

# ---- 2. installer 依赖（asm + mixin）----------------------------------------
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
ASM_VERSION="$(grep -E '^asm_version' "$UL_ROOT/gradle.properties" | head -1 | sed 's/.*= *//')"

DEPS=()

while IFS= read -r jar; do
	[ -n "$jar" ] && DEPS+=("$jar")
done < <(ls "$CACHE"/org.ow2.asm/*/"$ASM_VERSION"/*/*.jar 2>/dev/null | sort)

while IFS= read -r jar; do
	[ -n "$jar" ] && DEPS+=("$jar")
done < <(ls "$CACHE"/net.fabricmc/sponge-mixin/*/*/*.jar 2>/dev/null | sort -V | tail -1)

if [ "${#DEPS[@]}" -lt 2 ]; then
	echo "UL 错误：找不到内核的 installer 依赖（asm / sponge-mixin）。" >&2
	echo "        它们会在 ./gradlew :loader:build 时被下载到 Gradle 缓存：" >&2
	echo "        $CACHE" >&2
	exit 2
fi

PARTS=("$(to_native "$LOADER_JAR")")
for d in "${DEPS[@]}"; do PARTS+=("$(to_native "$d")"); done

# ---- 3. 原版游戏文件 ---------------------------------------------------------
if [ "$WITH_GAME" -eq 1 ]; then
	if [ ! -f "$GAME_DIR/classpath.txt" ]; then
		echo "UL 错误：$GAME_DIR/classpath.txt 不存在。先跑 ./run/fetch-game.sh" >&2
		exit 2
	fi

	PARTS+=("$(cat "$GAME_DIR/classpath.txt")")
fi

# 用平台的分隔符拼接
SEP=":"
case "$(uname -s)" in
	MINGW*|MSYS*|CYGWIN*) SEP=";" ;;
esac

(IFS="$SEP"; echo "${PARTS[*]}")

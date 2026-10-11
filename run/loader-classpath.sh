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

# ---- 1b. Quilt 兼容层（独立模块，T2.3）--------------------------------------
# 它是独立 jar（AGENTS.md 5.1 的 compat/compat-quilt），所以必须显式挂到 classpath 上；
# 挂不上也不会让游戏起不来（内核那处 pre_launch 走反射、找不到类就跳过），
# 但 Quilt 模组的 pre_launch 会静默不触发——所以这里缺 jar 直接报错退出，别让它悄悄发生。
COMPAT_QUILT_JAR="$(ls -t "$UL_ROOT/compat/compat-quilt/build/libs"/*.jar 2>/dev/null | grep -v -E 'sources|javadoc' | head -1 || true)"

if [ -z "$COMPAT_QUILT_JAR" ] || [ ! -f "$COMPAT_QUILT_JAR" ]; then
	echo "UL 错误：还没有 Quilt 兼容层 jar。先跑 ./gradlew :compat:compat-quilt:build" >&2
	exit 2
fi

# ---- 2b. Mixin 老包名的 ASM shim ---------------------------------------------
# 让 2019-2020 年的老模组（按 org.spongepowered.asm.lib.** 编译的）能注册自定义注入点。
# 完整理由写在 tooling/mixin-asm-shim/relocate_asm.py 的文件头里，这里只说两条硬约束：
#   1. 它**不是**给用户模组的，是给 Mixin 自己用的（Mixin 老版本把 ASM shade 在这个包下，
#      Fabric 现在只留了 7 个空壳类，老模组一用就 NoClassDefFoundError）；
#   2. 它必须排在 sponge-mixin **前面**，否则空壳类先被加载、shim 等于没做。
# **默认不开**，要显式设 UL_MIXIN_ASM_SHIM=1。
#
# 为什么默认不开（实测结论，不是保守）：
#   shim 只修「类找不到」这一层。appleskin 1.0.6 上了 shim 之后，
#   它的自定义注入点确实注册成功了（APPLESKIN_IINC 不再报 invalid specifier），
#   但那个注入点的**实现**对着老 Mixin 的 InjectionPoint 抽象写的，
#   真正 apply 时抛 AbstractMethodError —— 照样崩。
#   也就是说：不开 shim 崩在「注入点没注册」，开了崩在「注入点不兼容」，两个都是崩。
#
#   既然我手上**没有一个已证明的正样本**，默认打开的风险是不对称的：
#   有些模组现在只是「功能悄悄缺失」（Mixin 自己 catch 了，游戏照常跑），
#   打开后可能变成硬崩。所以默认关；遇到老模组报
#   NoClassDefFoundError: org/spongepowered/asm/lib/** 时再开，看看能不能救回来。
SHIM_JAR="$UL_ROOT/build/mixin-asm-shim.jar"
USE_SHIM="${UL_MIXIN_ASM_SHIM:-0}"

if [ "$USE_SHIM" = "1" ] && [ ! -f "$SHIM_JAR" ]; then
	if [ -x "$UL_ROOT/tooling/mixin-asm-shim/build.sh" ]; then
		"$UL_ROOT/tooling/mixin-asm-shim/build.sh" "$SHIM_JAR" >&2 || true
	fi
fi

PARTS=("$(to_native "$LOADER_JAR")" "$(to_native "$COMPAT_QUILT_JAR")")

# shim 先于 DEPS（DEPS 里最后是 sponge-mixin）
if [ "$USE_SHIM" = "1" ]; then
	if [ -f "$SHIM_JAR" ]; then
		PARTS+=("$(to_native "$SHIM_JAR")")
	else
		echo "UL 警告：开了 UL_MIXIN_ASM_SHIM=1 但 shim jar 没生成（$SHIM_JAR）。" >&2
		echo "        手动补：./tooling/mixin-asm-shim/build.sh" >&2
	fi
fi

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

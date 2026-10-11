#!/usr/bin/env bash
# 生成「Mixin 老包名」的 ASM shim jar。
#
# 背景与理由写在同目录的 relocate_asm.py 文件头里，这里只说用法：
#   ./tooling/mixin-asm-shim/build.sh [输出jar]
#
# 不联网：ASM 直接从 Gradle 缓存里取（run/loader-classpath.sh 已经依赖同一份），
# 版本从 gradle.properties 的 asm_version 读（工程纪律第 4 条：不写版本字面量）。
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UL_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

OUT_JAR="${1:-$UL_ROOT/build/mixin-asm-shim.jar}"

ASM_VERSION="$(grep -E '^asm_version' "$UL_ROOT/gradle.properties" | head -1 | sed 's/.*= *//')"

if [ -z "$ASM_VERSION" ]; then
	echo "[mixin-asm-shim] 错误：gradle.properties 里读不到 asm_version" >&2
	exit 2
fi

CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.ow2.asm"

JARS=()
for module in asm asm-tree asm-analysis asm-commons asm-util; do
	candidate="$(ls "$CACHE"/"$module"/"$ASM_VERSION"/*/"$module-$ASM_VERSION.jar" 2>/dev/null | head -1)"
	if [ -z "$candidate" ]; then
		echo "[mixin-asm-shim] 错误：找不到 $module-$ASM_VERSION.jar（在 $CACHE 下）" >&2
		echo "                 先跑一次 ./gradlew build 把它拉进 Gradle 缓存。" >&2
		exit 2
	fi
	JARS+=("$candidate")
done

mkdir -p "$(dirname "$OUT_JAR")"

if ! python3 "$SCRIPT_DIR/relocate_asm.py" "$ASM_VERSION" "$OUT_JAR" "${JARS[@]}"; then
	if ! python "$SCRIPT_DIR/relocate_asm.py" "$ASM_VERSION" "$OUT_JAR" "${JARS[@]}"; then
		echo "[mixin-asm-shim] 错误：重定位失败" >&2
		exit 2
	fi
fi

echo "[mixin-asm-shim] 完成：$OUT_JAR"
echo "[mixin-asm-shim] 注意：这个 jar 必须排在 sponge-mixin **前面**，"
echo "                 否则 sponge-mixin 里那 7 个空壳类会先被加载，shim 等于没做。"

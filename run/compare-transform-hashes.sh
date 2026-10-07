#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# T1.5 验收 —— 两次启动的变换产物哈希对拍
#
#   ./run/compare-transform-hashes.sh before.txt after.txt
#
# 用法（T1.5 就是这么跑的）：
#   UL_EXTRA_JVM_ARGS="-Dul.transform.hashFile=D:/.../before.txt" ./run/smoke.sh --matrix=baseline
#   # 改代码
#   UL_EXTRA_JVM_ARGS="-Dul.transform.hashFile=D:/.../after.txt"  ./run/smoke.sh --matrix=baseline
#   ./run/compare-transform-hashes.sh before.txt after.txt
#
# 判据（这是硬判据，不是「看起来差不多」）：
#   1. 两次都出现过的类，哈希必须逐字节相同——有一处不同就是 FAIL；
#   2. 只出现在其中一次的类，只做记录不做判定：哪些类被加载取决于启动时序，
#      两次运行本来就可能不同（例如某个后台线程晚了一步）。把它算成失败，
#      只会让人去调一个不存在的问题。
#
# 输出格式：类名 <TAB> 哈希（每次变换追加一行，同一个类可能出现多次，先按类名去重）。
# -----------------------------------------------------------------------------

set -uo pipefail

BEFORE="${1:-}"
AFTER="${2:-}"

if [ -z "$BEFORE" ] || [ -z "$AFTER" ]; then
	echo "用法: $0 <before.txt> <after.txt>" >&2
	exit 2
fi

for f in "$BEFORE" "$AFTER"; do
	if [ ! -f "$f" ]; then
		echo "找不到文件: $f" >&2
		exit 2
	fi
done

# 临时目录放在仓库的 build/ 下（已 gitignore）：系统临时目录在 Windows 上会被
# 沙箱的 safe-delete 拦下（"embedded drive prefix is not allowed"），
# 而一个清不掉临时文件的脚本会在 CI 上慢慢把磁盘填满。
TMP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/build/t1.5-compare"
rm -rf "$TMP"
mkdir -p "$TMP"

# 按类名排序并去重（同一个类可能被变换多次，哈希必然相同，留一条就够）
LC_ALL=C sort -t $'\t' -k1,1 -u "$BEFORE" > "$TMP/before"
LC_ALL=C sort -t $'\t' -k1,1 -u "$AFTER" > "$TMP/after"

TOTAL_BEFORE="$(wc -l < "$TMP/before" | tr -d ' ')"
TOTAL_AFTER="$(wc -l < "$TMP/after" | tr -d ' ')"

LC_ALL=C join -t $'\t' "$TMP/before" "$TMP/after" > "$TMP/common"
awk -F'\t' '$2 != $3' "$TMP/common" > "$TMP/diff"
LC_ALL=C join -t $'\t' -v1 "$TMP/before" "$TMP/after" | cut -f1 > "$TMP/only-before"
LC_ALL=C join -t $'\t' -v2 "$TMP/before" "$TMP/after" | cut -f1 > "$TMP/only-after"

COMMON="$(wc -l < "$TMP/common" | tr -d ' ')"
DIFF="$(wc -l < "$TMP/diff" | tr -d ' ')"
ONLY_BEFORE="$(wc -l < "$TMP/only-before" | tr -d ' ')"
ONLY_AFTER="$(wc -l < "$TMP/only-after" | tr -d ' ')"

echo "==================================================================="
echo " 变换产物哈希对拍"
echo "   before: $TOTAL_BEFORE 个类   after: $TOTAL_AFTER 个类"
echo "   两次都有: $COMMON   仅 before: $ONLY_BEFORE   仅 after: $ONLY_AFTER"
echo "==================================================================="

if [ "$ONLY_BEFORE" -gt 0 ]; then
	echo
	echo "只出现在 before（不计入判定，通常是加载时序差异）："
	head -10 "$TMP/only-before" | sed 's/^/  - /'
	[ "$ONLY_BEFORE" -gt 10 ] && echo "  … 其余 $((ONLY_BEFORE - 10)) 个"
fi

if [ "$ONLY_AFTER" -gt 0 ]; then
	echo
	echo "只出现在 after（不计入判定）："
	head -10 "$TMP/only-after" | sed 's/^/  - /'
	[ "$ONLY_AFTER" -gt 10 ] && echo "  … 其余 $((ONLY_AFTER - 10)) 个"
fi

echo

if [ "$COMMON" -eq 0 ]; then
	echo "FAIL — 两次运行没有一个共同的类，这份对拍没有意义（先确认两次都真的跑到了加载完成）"
	exit 1
fi

if [ "$DIFF" -eq 0 ]; then
	echo "PASS — 两次都出现过的 $COMMON 个类，变换产物逐字节相同"
	exit 0
fi

echo "FAIL — $DIFF 个类的变换产物不一致（这是行为变化，不是「看起来没坏」）："
head -20 "$TMP/diff" | awk -F'\t' '{ printf "  %s\n    before %s\n    after  %s\n", $1, $2, $3 }'
[ "$DIFF" -gt 20 ] && echo "  … 其余 $((DIFF - 20)) 个"
exit 1

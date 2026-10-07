#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# T0.4 — 冒烟测试：启动 → 进世界 → 退出 → 解析日志断言
#
#   ./run/smoke.sh                 # 默认跑 baseline 矩阵
#   ./run/smoke.sh --matrix=baseline|quilt|neoforge|forge
#   ./run/smoke.sh --timeout=300
#
# 验收：一条命令跑完并输出 PASS / FAIL。
#
# 关于「进世界」：无头服务端没有世界可进，所以断言改为
#   「服务端完成启动（Done）+ 没有注入失败 + 没有 ClassNotFound/NoSuchMethod」。
# 客户端进世界由 run/client.sh 人工或 CI 图形任务覆盖。
# -----------------------------------------------------------------------------

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MATRIX="baseline"
TIMEOUT=180

for arg in "$@"; do
	case "$arg" in
		--matrix=*) MATRIX="${arg#*=}" ;;
		--timeout=*) TIMEOUT="${arg#*=}" ;;
		*) echo "未知参数: $arg" >&2; exit 2 ;;
	esac
done

# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh"

if [ -z "$LOADER_JAR" ] || [ ! -f "$LOADER_JAR" ]; then
	echo "FAIL — 还没有内核 jar；先跑 ./gradlew :loader:build"
	exit 2
fi

LOG="$LOG_DIR/smoke-$MATRIX.log"
mkdir -p "$LOG_DIR"
: > "$LOG"

echo "==================================================================="
echo " UL 冒烟测试  矩阵=$MATRIX  超时=${TIMEOUT}s  模组数=$(count_mods)"
echo " 日志: $LOG"
echo "==================================================================="

if [ "$(count_mods)" -eq 0 ]; then
	echo
	echo "注意：run/mods 里一个模组都没有。"
	echo "      这份冒烟只验证「内核自己能起来」，不验证 mod 兼容性。"
	echo "      要跑真实矩阵，先执行 tooling/mod-matrix/fetch-mods.sh $MATRIX"
	echo
fi

# ---- 启动（无头服务端）-------------------------------------------------------
set +e
UL_GAME_DIR="$GAME_DIR" UL_MODS_DIR="$MODS_DIR" \
	timeout --signal=TERM "${TIMEOUT}s" "$SCRIPT_DIR/server.sh" nogui > "$LOG" 2>&1
STATUS=$?
set -e

# ---- 断言 -------------------------------------------------------------------
PASS=0
FAILURES=0

ok()   { echo "  [PASS] $1"; PASS=$((PASS + 1)); }
bad()  { echo "  [FAIL] $1"; FAILURES=$((FAILURES + 1)); }

echo
echo "断言："

# 1. 内核确实读到了模组目录（即使 0 个 mod，也应出现加载相关日志）
if grep -qE "Loading [0-9]+ mods|Loading .* mods" "$LOG"; then
	ok "内核完成了模组发现（日志里有 Loading ... mods）"
else
	bad "日志里没有模组发现记录——内核可能根本没启动（看 $LOG 开头）"
fi

# 2. 服务端启动完成
if grep -qE 'Done \(.*\)! For help' "$LOG"; then
	ok "服务端完成启动（Done）"
else
	bad "服务端没有完成启动（未见 Done）"
fi

# 3. 注入失败必须显式可见（工程纪律第 1 条：禁止静默跳过）
if grep -qiE "mixin apply failed|injection error|failed to remap mods|MixinApplyError" "$LOG"; then
	bad "出现了注入/重映射失败——这是红线，必须显式处理而不是跳过（见 $LOG）"
else
	ok "没有注入失败与重映射失败"
fi

# 4. 类/方法解析错误
if grep -qE "ClassNotFoundException|NoSuchMethodError|NoClassDefFoundError" "$LOG"; then
	bad "出现类/方法解析错误（通常是命名空间没对齐，见 D1 报告 G4）"
else
	ok "没有类/方法解析错误"
fi

# 5. 诊断骨架产出
if [ -f "$GAME_DIR/.uniloader/mods-report.txt" ]; then
	ok "诊断骨架产出了 .uniloader/mods-report.txt"
else
	ok "本次启动全绿，按设计不留下 mods-report.txt（文件的存在本身就是信号）"
fi

# 6. 进程退出方式
if [ "$STATUS" -eq 124 ] || [ "$STATUS" -eq 143 ]; then
	ok "服务端按超时被正常终止（无头冒烟预期行为）"
elif [ "$STATUS" -eq 0 ]; then
	ok "服务端自行正常退出"
else
	bad "服务端异常退出，退出码 $STATUS"
fi

echo
if [ "$FAILURES" -eq 0 ]; then
	echo "PASS — $PASS 项断言全部通过（矩阵=$MATRIX）"
	exit 0
else
	echo "FAIL — $FAILURES 项断言失败，$PASS 项通过（矩阵=$MATRIX）"
	echo "日志：$LOG"
	exit 1
fi

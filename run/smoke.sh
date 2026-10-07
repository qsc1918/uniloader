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
# boot  = 服务端应当正常起来（默认）
# crash = 服务端应当起不来，验收的是「报告能不能说清是谁炸的」（T1.6 的对照组）
EXPECT="boot"

for arg in "$@"; do
	case "$arg" in
		--matrix=*) MATRIX="${arg#*=}" ;;
		--timeout=*) TIMEOUT="${arg#*=}" ;;
		--expect=*) EXPECT="${arg#*=}" ;;
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
echo " UL 冒烟测试  矩阵=$MATRIX  预期=$EXPECT  超时=${TIMEOUT}s  模组数=$(count_mods)"
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

# ---- 渲染报告（T1.6）-------------------------------------------------------
# 内核只把证据落在 .uniloader/kernel-diagnostics.txt，渲染在这里做。
REPORT="$GAME_DIR/.uniloader/mods-report.txt"
REPORT_LOG="$LOG_DIR/report-$MATRIX.log"

set +e
"$SCRIPT_DIR/report.sh" "$GAME_DIR" > "$REPORT_LOG" 2>&1
REPORT_STATUS=$?
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

if [ "$EXPECT" = "crash" ]; then
	# ---------------------------------------------------------------------
	# 对照组（T1.6）：这次启动是故意让它炸的。
	# 验收的不是「游戏能跑」，而是「出事之后报告能不能说清是谁、在哪个阶段、因为什么」。
	# ---------------------------------------------------------------------
	if grep -qE 'Done \(.*\)! For help' "$LOG"; then
		bad "服务端居然起来了——这个矩阵本该炸掉，检查 $MODS_DIR 里放的是不是对照组模组"
	else
		ok "服务端按预期起不来（未见 Done）"
	fi

	if [ "$REPORT_STATUS" -eq 0 ]; then
		ok "报告步骤读到了内核落下的诊断证据"
	else
		bad "报告步骤失败（退出码 $REPORT_STATUS，见 $REPORT_LOG）"
	fi

	if [ -f "$REPORT" ]; then
		ok "出事后留下了 .uniloader/mods-report.txt（文件的存在本身就是信号）"
	else
		bad "出事了却没有留下 mods-report.txt——诊断没有接上（见 $REPORT_LOG）"
	fi

	if [ -f "$REPORT" ] && grep -q "\[FAILED\]" "$REPORT"; then
		ok "报告里出现了 [FAILED]"
	elif [ -f "$REPORT" ]; then
		bad "报告里没有 [FAILED]，见 $REPORT"
	fi

	if [ -f "$REPORT" ] && grep -qE "^      (阶段|stage)：.+[^[:space:]]" "$REPORT"; then
		ok "报告写明了出事的阶段"
	elif [ -f "$REPORT" ]; then
		bad "报告没有写明阶段——失败必须落在某个阶段上，见 $REPORT"
	fi

	if [ -f "$REPORT" ] && grep -qE "^      (原因|reason)：.+[^[:space:]]" "$REPORT"; then
		ok "报告写明了原因（不是空白）"
	elif [ -f "$REPORT" ]; then
		bad "报告的原因一栏是空的——纪律第 3 条不允许「失败但无原因」，见 $REPORT"
	fi

	if [ -f "$REPORT" ] && grep -q "各阶段耗时\|Stage timings" "$REPORT"; then
		ok "报告列出了各阶段耗时（T1.6）"
	elif [ -f "$REPORT" ]; then
		bad "报告没有列出各阶段耗时，见 $REPORT"
	fi

	# 注意：不要拿退出码判断「有没有崩」。专服的 Main 会自己 catch 掉入口点异常、
	# 打一句 "Failed to start the minecraft server" 然后正常返回，退出码是 0。
	# 真正该断言的是「它没跑到超时」和「崩溃被指认到了具体模组」。
	if [ "$STATUS" -eq 124 ] || [ "$STATUS" -eq 143 ]; then
		bad "服务端一直跑到超时——这个矩阵本该在入口点上就炸掉"
	else
		ok "服务端在入口点阶段就结束了（退出码 $STATUS），没有跑到超时"
	fi

	if grep -qE "Failed to start the minecraft server|Could not execute entrypoint" "$LOG"; then
		ok "崩溃被指认到了具体模组（日志里有 provided by / Failed to start）"
	else
		bad "崩溃日志里没有指认到模组，见 $LOG"
	fi
else
	# ---------------------------------------------------------------------
	# 正常路径：服务端应当起来，且不留报告文件。
	# ---------------------------------------------------------------------
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

	# 5. 报告步骤本身要跑通，但全绿时不该留下文件
	if [ "$REPORT_STATUS" -eq 0 ]; then
		ok "报告步骤读到了内核落下的诊断证据"
	else
		bad "报告步骤失败（退出码 $REPORT_STATUS，见 $REPORT_LOG）"
	fi

	if [ -f "$REPORT" ]; then
		bad "本次启动全绿却留下了 mods-report.txt——有模组没被如实上报，见 $REPORT"
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

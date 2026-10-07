#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# 交接自检 —— 接手的人（或 AI）开工前先跑这一条
#
#   ./run/preflight.sh              # 全量：环境 + 依赖 + git + 构建 + 三条冒烟路径
#   ./run/preflight.sh --quick      # 只跑环境 + 依赖 + git + 构建（约 1 分钟）
#   ./run/preflight.sh --fetch      # 缺游戏/模组时顺便拉取（需要代理，约几分钟）
#
# 它做三件事：
#   1. 确认「上一阶段交过来的状态」是健康的（而不是「他那边能跑」）；
#   2. 把上一阶段**已验收**与**未验收**的清单打印出来——避免把「没测」当成「没问题」；
#   3. 给出下一步该读哪份文档。
#
# 退出码：0 = 全部通过；1 = 有硬失败（先修，别急着开工）；2 = 用法/前置错误。
# -----------------------------------------------------------------------------

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UL_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

QUICK=0
FETCH=0

for arg in "$@"; do
	case "$arg" in
		--quick) QUICK=1 ;;
		--fetch) FETCH=1 ;;
		*) echo "未知参数: $arg" >&2; exit 2 ;;
	esac
done

PASS=0
WARN=0
FAIL=0

ok()   { echo "  [OK]   $1"; PASS=$((PASS + 1)); }
warn() { echo "  [WARN] $1"; WARN=$((WARN + 1)); }
bad()  { echo "  [FAIL] $1"; FAIL=$((FAIL + 1)); }
info() { echo "         $1"; }

echo "==================================================================="
echo " UniLoader 交接自检"
echo "==================================================================="

# ---- 1. 环境 -----------------------------------------------------------------
echo
echo "1. 环境"

# env.sh 的 try_java 靠「返回 1」表示找到了 Java，所以在 -e 下必须先关掉再 source。
# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh" > /dev/null
set +e

JAVA_MAJOR="$("$JAVA" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)(\.[0-9]+)?.*/\1/')"

if [ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -ge 25 ] 2>/dev/null; then
	ok "运行 Java = $JAVA_MAJOR（需要 ≥ 25）"
else
	bad "运行 Java = ${JAVA_MAJOR:-未知}，需要 ≥ 25（MC 26.3 是 class file 69.0）"
fi

if [ -n "${https_proxy:-}" ]; then
	ok "已设 https_proxy=$https_proxy"
else
	warn "没设 https_proxy；拉取游戏/模组会失败。先 export https_proxy=http://127.0.0.1:7897"
fi

GRADLE_VER="$(cd "$UL_ROOT" && ./gradlew --version 2>/dev/null | sed -n 's/^Gradle \([0-9.]*\).*/\1/p' | head -1)"

if [ "$GRADLE_VER" = "9.1.0" ]; then
	ok "Gradle = $GRADLE_VER（fabric-loader 上游要求；9.7 会构建失败）"
elif [ -n "$GRADLE_VER" ]; then
	bad "Gradle = $GRADLE_VER，必须是 9.1.0（wrapper 已锁定，别手动升级）"
else
	warn "读不出 Gradle 版本（wrapper 没下好？）"
fi

# ---- 2. 依赖（游戏与模组不在版本库里，必须本机拉取）-----------------------------
echo
echo "2. 本机依赖（游戏与模组二进制不进版本库）"

if [ -f "$GAME_DIR/server.jar" ] || [ -f "$GAME_DIR/bundler.jar" ] || [ -d "$GAME_DIR/libraries" ]; then
	ok "原版游戏已就位（$GAME_DIR）"
else
	if [ "$FETCH" -eq 1 ]; then
		info "正在拉取游戏…"
		(cd "$UL_ROOT" && ./run/fetch-game.sh) || bad "拉取游戏失败"
	else
		bad "原版游戏不在 $GAME_DIR —— 先跑 ./run/fetch-game.sh（或本脚本加 --fetch）"
	fi
fi

MODS_COUNT="$(ls "$MODS_DIR"/*.jar 2>/dev/null | wc -l | tr -d ' ')"
if [ "$MODS_COUNT" -ge 10 ]; then
	ok "Fabric 侧模组 $MODS_COUNT 个（期望 11）"
elif [ "$MODS_COUNT" -gt 0 ]; then
	warn "Fabric 侧模组只有 $MODS_COUNT 个（期望 11）；冒烟仍能跑，但覆盖变弱"
else
	if [ "$FETCH" -eq 1 ]; then
		info "正在拉取 baseline 矩阵…"
		(cd "$UL_ROOT" && ./tooling/mod-matrix/fetch-mods.sh baseline) || bad "拉取 baseline 失败"
	else
		warn "run/mods 是空的 —— 跑 ./tooling/mod-matrix/fetch-mods.sh baseline（或本脚本加 --fetch）"
	fi
fi

FOREIGN_DIR="$SCRIPT_DIR/mods-foreign"
FOREIGN_COUNT="$(ls "$FOREIGN_DIR"/*.jar 2>/dev/null | wc -l | tr -d ' ')"
if [ "$FOREIGN_COUNT" -ge 1 ]; then
	ok "别家生态模组 $FOREIGN_COUNT 个（NeoForge 矩阵，实测 MC 26.3 上可用 7 个）"
else
	if [ "$FETCH" -eq 1 ]; then
		info "正在拉取 neoforge 矩阵…"
		(cd "$UL_ROOT" && UL_MODS_DIR="$FOREIGN_DIR" ./tooling/mod-matrix/fetch-mods.sh neoforge) || warn "拉取 neoforge 失败"
	else
		warn "run/mods-foreign 是空的 —— 跑 ./run/smoke-foreign.sh --fetch（或本脚本加 --fetch）"
	fi
fi

# ---- 3. git ------------------------------------------------------------------
echo
echo "3. 版本库状态"

cd "$UL_ROOT" || exit 2

DIRTY="$(git status --porcelain | wc -l | tr -d ' ')"
HEAD_SUBJECT="$(git log -1 --format='%h %s')"

if [ "$DIRTY" -eq 0 ]; then
	ok "工作区干净"
else
	warn "工作区有 $DIRTY 处未提交改动（上一个阶段可能留了半成品，先看清楚再开工）"
fi

info "HEAD = $HEAD_SUBJECT"

# ---- 4. 构建 -----------------------------------------------------------------
echo
echo "4. 构建（编译 + 全部单测 + checkstyle/spotless + 许可证边界）"

BUILD_LOG="$UL_ROOT/build/preflight-build.log"
mkdir -p "$UL_ROOT/build"

if (cd "$UL_ROOT" && ./gradlew build --console=plain > "$BUILD_LOG" 2>&1); then
	ok "./gradlew build 通过"
	info "日志：$BUILD_LOG"
else
	bad "./gradlew build 失败 —— 先修构建，别急着开工"
	info "看 $BUILD_LOG 的末尾："
	tail -25 "$BUILD_LOG" | sed 's/^/         /'
fi

# 数全部测试套件（不按时间过滤：全 up-to-date 时报告文件不会刷新，按时间数会数出 0 份）。
TESTS="$(find "$UL_ROOT" -path '*/build/test-results/test/*.xml' 2>/dev/null | wc -l | tr -d ' ')"
CASES="$(find "$UL_ROOT" -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + 2>/dev/null \
	| sed 's/[^0-9]//g' | paste -sd+ - | bc 2>/dev/null || echo '?')"

if [ "$TESTS" -ge 14 ]; then
	ok "单测套件 $TESTS 份 / $CASES 个用例（阶段 1 结束时的基线：14 份 / 105 个）"
else
	warn "只找到 $TESTS 份测试报告（基线 14 份）——构建可能没跑完测试"
fi

# ---- 5. 冒烟 -----------------------------------------------------------------
if [ "$QUICK" -eq 1 ]; then
	echo
	echo "5. 冒烟 —— 已按 --quick 跳过（开工前至少完整跑一次）"
	warn "跳过了三条冒烟路径"
else
	echo
	echo "5. 冒烟（三条路径，全绿才算健康）"

	if [ "$FAIL" -gt 0 ]; then
		warn "构建有硬失败，跳过冒烟（跑了也是白跑）"
	else
		if (cd "$UL_ROOT" && ./run/smoke.sh --matrix=baseline > /dev/null 2>&1); then
			ok "纯 Fabric 冒烟 PASS（7/7）"
		else
			bad "纯 Fabric 冒烟失败 —— 看 run/game/logs/smoke-baseline.log"
		fi

		if [ "$FOREIGN_COUNT" -ge 1 ]; then
			if (cd "$UL_ROOT" && ./run/smoke-foreign.sh > /dev/null 2>&1); then
				ok "别家生态混装冒烟 PASS（10/10）"
			else
				bad "别家生态混装冒烟失败 —— 看 run/game/logs/smoke-foreign.log"
			fi
		else
			warn "跳过别家生态混装冒烟（run/mods-foreign 为空）"
		fi

		if (cd "$UL_ROOT" && ./run/make-broken-mod.sh > /dev/null 2>&1); then
			if (cd "$UL_ROOT" && UL_MODS_DIR="$UL_ROOT/run/mods-broken" ./run/smoke.sh --matrix=broken --expect=crash --timeout=120 > /dev/null 2>&1); then
				ok "崩溃对照组冒烟 PASS（10/10，报告能指认到具体模组）"
			else
				bad "崩溃对照组冒烟失败 —— 看 run/game/logs/smoke-broken.log"
			fi
		else
			bad "造不出对照组模组（run/make-broken-mod.sh 失败）"
		fi
	fi
fi

# ---- 6. 交接清单 --------------------------------------------------------------
echo
echo "6. 上一阶段（阶段 1）的验收边界 —— 别把「没测」当成「没问题」"
cat <<'CHECKLIST'
  已验收（有可执行的复现命令）：
    - T1.1 三个命名空间          → ./gradlew :loader:test --tests '*NamespacePolicyTest*'
                                    （运行时 / 模组分发 / 游戏 jar 的 G4 判定，含与 MappingConfiguration 对拍）
    - T1.3 语言适配器注册表      → ./gradlew :loader:test --tests '*LanguageAdapterRegistryTest*'
    - T1.4 别家生态识别          → ./run/smoke-foreign.sh（真实 NeoForge mod 混装）
    - T1.5 变换产物逐字节不变    → ./run/compare-transform-hashes.sh（对拍 10756 个类）
    - T1.6 诊断与三态报告        → ./run/smoke.sh --expect=crash（报告里出现 [FAILED]+阶段+原因）

  未验收（不是遗漏，是留给后续阶段；别当成已完成）：
    - 真实客户端「进世界」       需要显卡与 assets，只测了无头服务端
    - 注入成功率统计             需要 mixin audit（阶段 7 的 T7.1）；现在只能证明「没有注入失败」
    - T1.2 重映射缓存            已按许可降级 P2，见 D:/work2/AGENTS.md 1.2 第 3 条
    - TransformPhase.HOOK_INJECTION 的位置是暂定的，阶段 7 必须靠实测确定
CHECKLIST

echo
echo "7. 下一步该读什么"
echo "  1) D:/work2/uniloader/docs/stage2/HANDOFF.md   ← 当前阶段的交接文档，先读它"
echo "  2) D:/work2/AGENTS.md 末尾「阶段 1 完成情况与结论回写（v1.2）」"
echo "     （含与方案原文的 5 处差异及理由、坑 G5–G11、风险登记册 R1–R12）"
echo "  3) D:/work2/uniloader/docs/stage1/STAGE1-REPORT.md"
echo "     （写新阶段报告时照它的结构）"

# ---- 汇总 --------------------------------------------------------------------
echo
echo "==================================================================="
echo " 汇总：OK $PASS   WARN $WARN   FAIL $FAIL"
echo "==================================================================="

if [ "$FAIL" -gt 0 ]; then
	echo "FAIL —— 有 $FAIL 项硬失败。先修好再开工，否则会在错误的地基上堆东西。"
	exit 1
fi

if [ "$WARN" -gt 0 ]; then
	echo "PASS（有 $WARN 项警告）—— 警告项多半是本机依赖没拉全，看清楚再决定要不要补。"
	exit 0
fi

echo "PASS —— 交接状态健康，可以开工。"
exit 0

#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# T1.4 验收 —— 四家共存的第一块拼图：别家生态的模组要「被看见」
#
#   ./run/smoke-foreign.sh              # 用已下载的别家模组跑
#   ./run/smoke-foreign.sh --fetch      # 先按 neoforge 矩阵拉一遍再跑
#
# 验收两条，缺一不可：
#   1. 真实 NeoForge mod 出现在 mods 目录里时，日志必须说
#      「识别为 NeoForge mod: xxx（依据 META-INF/neoforge.mods.toml；…尚未支持加载）」，
#      而不是把它当「未知文件」丢掉；
#   2. 11 个真实 Fabric mod 照常跑起来——「多放一个 jar 就把原来能跑的搞坏」
#      正是本项目最不能犯的错。
#
# 做法：把 run/mods-foreign 里的 jar 临时复制进 run/mods，跑完无论成败都撤掉。
# 真实模组二进制不进版本库（.gitignore 已忽略 run/mods-foreign）。
# -----------------------------------------------------------------------------

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UL_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

FOREIGN_DIR="$SCRIPT_DIR/mods-foreign"
MODS_DIR="$SCRIPT_DIR/mods"

if [ "${1:-}" = "--fetch" ]; then
	export https_proxy="${https_proxy:-http://127.0.0.1:7897}"
	echo "[UL] 先按 neoforge 矩阵拉取真实模组…"
	UL_MODS_DIR="$FOREIGN_DIR" "$UL_ROOT/tooling/mod-matrix/fetch-mods.sh" neoforge || true
fi

shopt -s nullglob
FOREIGN_JARS=("$FOREIGN_DIR"/*.jar)
shopt -u nullglob

if [ "${#FOREIGN_JARS[@]}" -eq 0 ]; then
	echo "UL 错误：$FOREIGN_DIR 里没有别家生态的模组。" >&2
	echo "        先拉取：./tooling/mod-matrix/fetch-mods.sh neoforge（或用 --fetch）" >&2
	exit 2
fi

COPIED=()
cleanup() {
	for jar in "${COPIED[@]:-}"; do
		[ -n "$jar" ] && rm -f "$jar"
	done
}
trap cleanup EXIT INT TERM

for jar in "${FOREIGN_JARS[@]}"; do
	dest="$MODS_DIR/$(basename "$jar")"
	cp -f "$jar" "$dest"
	COPIED+=("$dest")
done

echo "[UL] 临时放入 ${#COPIED[@]} 个别家生态模组，跑完自动撤掉"
echo

"$SCRIPT_DIR/smoke.sh" --matrix=foreign --timeout=180
STATUS=$?

echo
echo "[UL] 已撤掉临时放入的 ${#COPIED[@]} 个 jar"
exit $STATUS

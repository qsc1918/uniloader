#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# T2.6 验收 —— Quilt 模组与 Fabric 模组混装
#
#   ./run/smoke-quilt.sh            # 用已下载的模组跑
#   ./run/smoke-quilt.sh --fetch    # 先按 quilt 矩阵拉一遍再跑
#
# 为什么是「混装」而不是「单跑 Quilt」：单跑 Quilt 只能证明「Quilt 模组能起来」，
# 证明不了「多一个生态不会把原来能跑的搞坏」——后者才是这个项目存在的理由。
#
# 目录策略（照 T1.4 的教训）：不往 run/mods 里临时塞东西再撤，
# 而是拼一个独立目录 run/mods-quilt，两边各自干净。
#   右边：run/mods（真实 Fabric 模组，baseline 矩阵）
#   左边：run/mods-quilt-only（真·Quilt 模组，来自 quilt 矩阵）
#
# 实测前提（2026-10-10）：26.3 上只有 simple-voice-chat 一个真·Quilt 构建。
# 所以「左边」现在只有它——这不是偷懒，是生态现状（见 matrix.yml 的实测结论）。
# -----------------------------------------------------------------------------

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UL_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

QUILT_DIR="$SCRIPT_DIR/mods-quilt"
FABRIC_DIR="$SCRIPT_DIR/mods"
MIXED_DIR="$SCRIPT_DIR/mods-quilt-mixed"

if [ "${1:-}" = "--fetch" ]; then
	export https_proxy="${https_proxy:-http://127.0.0.1:7897}"
	echo "[UL] 先按 quilt 矩阵拉取真实模组…"
	UL_MODS_DIR="$QUILT_DIR" "$UL_ROOT/tooling/mod-matrix/fetch-mods.sh" quilt || true
fi

shopt -s nullglob
QUILT_JARS=("$QUILT_DIR"/*.jar)
FABRIC_JARS=("$FABRIC_DIR"/*.jar)
shopt -u nullglob

if [ "${#QUILT_JARS[@]}" -eq 0 ]; then
	echo "UL 错误：$QUILT_DIR 里没有 Quilt 模组。" >&2
	echo "        先拉取：./tooling/mod-matrix/fetch-mods.sh quilt（或用 --fetch）" >&2
	exit 2
fi

if [ "${#FABRIC_JARS[@]}" -eq 0 ]; then
	echo "UL 错误：$FABRIC_DIR 里没有 Fabric 模组（混装需要两边都有）。" >&2
	echo "        先拉取：./tooling/mod-matrix/fetch-mods.sh baseline" >&2
	exit 2
fi

# 真·Quilt 模组必须真的带 quilt.mod.json，否则这个冒烟什么都没验到——
# 「目录里放了 jar」和「这个 jar 会走 Quilt 那条路」是两件事。
JAR_TOOL="jar"
QUILT_MANIFEST=0
for jar in "${QUILT_JARS[@]}"; do
	if "$JAR_TOOL" tf "$jar" 2>/dev/null | grep -qx "quilt.mod.json"; then
		QUILT_MANIFEST=$((QUILT_MANIFEST + 1))
	fi
done

if [ "$QUILT_MANIFEST" -eq 0 ]; then
	echo "UL 错误：$QUILT_DIR 里没有任何带 quilt.mod.json 的 jar。" >&2
	echo "        这样跑出来的「Quilt 冒烟」走的其实是 Fabric 路径，等于没验。" >&2
	exit 2
fi

rm -rf "$MIXED_DIR"
mkdir -p "$MIXED_DIR"

for jar in "${FABRIC_JARS[@]}"; do cp -f "$jar" "$MIXED_DIR/"; done
for jar in "${QUILT_JARS[@]}"; do cp -f "$jar" "$MIXED_DIR/"; done

echo "[UL] 混装目录 $MIXED_DIR：Fabric ${#FABRIC_JARS[@]} 个 + Quilt ${#QUILT_JARS[@]} 个（其中 $QUILT_MANIFEST 个真带 quilt.mod.json）"
echo

UL_MODS_DIR="$MIXED_DIR" "$SCRIPT_DIR/smoke.sh" --matrix=quilt --timeout=180

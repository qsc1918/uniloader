#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# T1.2 验收：在**仍有混淆**的 MC（≤1.21.11）上跑一次 named 运行时冒烟
#
#   ./run/prepare-named.sh          # 先准备夹具（原版游戏 + 映射 + intermediary jar + 模组）
#   ./run/smoke-named.sh            # 冷启动（会先清空重映射缓存）
#   ./run/smoke-named.sh            # 再跑一次 = 热启动（命中缓存）
#
# 与 run/smoke.sh 的区别只有三处环境变量：游戏目录、模组目录、运行时命名空间。
# 之所以单独一个脚本，是因为「跑混淆版本」这件事必须一条命令可复现——手打三个环境变量
# 迟早会漏一个，然后就会得到一个看起来正常、其实没走重映射通道的结果。
#
# 判据（T1.2）：
#   - 冷启动：remap 阶段耗时 > 0，日志里出现「重映射完成并写入缓存」；
#   - 热启动：remap 阶段耗时 ≈ 0，日志里出现「重映射缓存命中」；
#   - 两次都 PASS，且两次的产物一致（缓存不能改变产物）。
# -----------------------------------------------------------------------------
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MC_VERSION="${UL_MC_VERSION:-1.21.11}"

export UL_GAME_DIR="${UL_NAMED_GAME_DIR:-$SCRIPT_DIR/game-$MC_VERSION}"
export UL_MODS_DIR="${UL_NAMED_MODS_DIR:-$SCRIPT_DIR/mods-$MC_VERSION}"
export UL_NAMESPACE=named

if [ ! -f "$UL_GAME_DIR/mappings.tiny" ] || [ ! -f "$UL_GAME_DIR/remap-classpath.txt" ]; then
	echo "UL 错误：$UL_GAME_DIR 里缺少 mappings.tiny / remap-classpath.txt。" >&2
	echo "        先跑 ./run/prepare-named.sh" >&2
	exit 2
fi

echo "[named] 游戏目录 : $UL_GAME_DIR"
echo "[named] 模组目录 : $UL_MODS_DIR"
echo "[named] 命名空间 : $UL_NAMESPACE"

exec "$SCRIPT_DIR/smoke.sh" --matrix=named "$@"

#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# 老版本（1.x）冒烟 —— 一条命令跑完，命名空间与 Java 都自动挑
#
#   ./run/smoke-legacy.sh 1.14
#   ./run/smoke-legacy.sh 1.14.4
#
# 前提：夹具已准备好（./run/prepare-legacy.sh <版本> [模组目录]）
#
# 这个脚本自己不做判断以外的事：命名空间来自夹具准备时写下的
# run/game-<版本>/.ul-namespace（选项 A 拍板后**默认一律 intermediary**，
# 只有显式 prepare --namespace=named 才是 named），Java 版本由 run/env.sh
# 从官方版本 json 的 javaVersion 读。
# 判断只在一处发生，避免「prepare 时按 A 准备、smoke 时按 B 跑」这种不一致。
# -----------------------------------------------------------------------------

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

MC_VERSION="${1:-}"

if [ -z "$MC_VERSION" ]; then
	echo "用法：./run/smoke-legacy.sh <mc版本>（例如 1.14 / 1.14.4）" >&2
	exit 2
fi

GAME_DIR="$SCRIPT_DIR/game-$MC_VERSION"
MODS_DIR="$SCRIPT_DIR/mods-$MC_VERSION"
NS_FILE="$GAME_DIR/.ul-namespace"

if [ ! -d "$GAME_DIR" ]; then
	echo "UL 错误：$GAME_DIR 不存在。先跑：" >&2
	echo "        ./run/prepare-legacy.sh $MC_VERSION <模组目录>" >&2
	exit 2
fi

if [ ! -f "$NS_FILE" ]; then
	echo "UL 错误：$NS_FILE 不存在——夹具是旧版脚本准备的，重跑一次：" >&2
	echo "        ./run/prepare-legacy.sh $MC_VERSION" >&2
	exit 2
fi

FIXTURE_NAMESPACE="$(cat "$NS_FILE")"
# 允许显式覆盖（UL_NAMESPACE=intermediary ./run/smoke-legacy.sh 1.14.4），
# 与 client-legacy.sh 保持同一套口子，方便对同一批模组做两种运行时的对照。
UL_NAMESPACE="${UL_NAMESPACE:-$FIXTURE_NAMESPACE}"

# 选项 A 护栏：named 运行时需要三命名空间 mappings.tiny（含 named），
# 而默认夹具只写 official → intermediary 两命名空间。只靠 UL_NAMESPACE=named
# 覆盖启动，内核会拿不到 named 映射，报一堆莫名其妙的类找不到——在这里直接挡住。
if [ "$UL_NAMESPACE" = "named" ] && [ "$FIXTURE_NAMESPACE" != "named" ]; then
	echo "UL 错误：夹具按 $FIXTURE_NAMESPACE 准备（mappings.tiny 只有两命名空间），" >&2
	echo "        不能只用 UL_NAMESPACE=named 覆盖启动。先重新准备夹具：" >&2
	echo "        ./run/prepare-legacy.sh $MC_VERSION [<模组目录>] --namespace=named" >&2
	exit 2
fi

if [ "$UL_NAMESPACE" != "$FIXTURE_NAMESPACE" ]; then
	echo "[UL] 注意：夹具按 $FIXTURE_NAMESPACE 准备，本次显式覆盖为 $UL_NAMESPACE" >&2
fi

echo "[UL] 老版本冒烟：MC $MC_VERSION   命名空间=$UL_NAMESPACE"

UL_GAME_DIR="$GAME_DIR" UL_MODS_DIR="$MODS_DIR" UL_NAMESPACE="$UL_NAMESPACE" \
	"$SCRIPT_DIR/smoke.sh" --matrix="legacy-$MC_VERSION" --timeout="${UL_SMOKE_TIMEOUT:-240}"

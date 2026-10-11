#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# 老版本（1.x）**客户端**启动 —— 给你在本机手动验证用
#
#   ./run/client-legacy.sh 1.14
#   ./run/client-legacy.sh 1.14.4
#
# 前提：夹具已准备好
#   ./run/prepare-legacy.sh 1.14 --client /d/work2/1.14fabric
#
# 与 smoke-legacy.sh 的关系：同一套判定（命名空间从夹具里的 .ul-namespace 读，
# Java 版本从官方版本 json 的 javaVersion 读），只是把 KnotServer 换成 KnotClient。
#
# 这个脚本**不判断成功失败**——客户端要看画面，机器判不了。
# 判定标准见 docs/CLIENT-TEST.md。
# -----------------------------------------------------------------------------

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

MC_VERSION="${1:-}"

if [ -z "$MC_VERSION" ]; then
	echo "用法：./run/client-legacy.sh <mc版本>（例如 1.14 / 1.14.4）" >&2
	exit 2
fi

# 支持第二个参数覆盖客户端夹具目录（默认 run/client-<版本>）
GAME_DIR="${2:-$SCRIPT_DIR/client-$MC_VERSION}"
MODS_DIR="${UL_MODS_DIR:-$SCRIPT_DIR/mods-$MC_VERSION}"
NS_FILE="$GAME_DIR/.ul-namespace"

if [ ! -d "$GAME_DIR" ]; then
	echo "UL 错误：$GAME_DIR 不存在。先跑：" >&2
	echo "        ./run/prepare-legacy.sh $MC_VERSION --client <模组目录>" >&2
	exit 2
fi

if [ ! -f "$NS_FILE" ]; then
	echo "UL 错误：$NS_FILE 不存在——夹具不完整，重跑 prepare-legacy.sh --client" >&2
	exit 2
fi

UL_NAMESPACE="$(cat "$NS_FILE")"

echo "[UL] 客户端启动：MC $MC_VERSION   命名空间=$UL_NAMESPACE"
echo "[UL] 客户端目录: $GAME_DIR"
echo "[UL] 模组目录  : $MODS_DIR"

export UL_GAME_DIR="$GAME_DIR"
export UL_MODS_DIR="$MODS_DIR"
export UL_NAMESPACE

# assets：夹具里下载过就直接指过去；否则用 UL_ASSETS_DIR（指向已有 .minecraft/assets）。
if [ -d "$GAME_DIR/assets/indexes" ]; then
	export UL_ASSETS_DIR="$GAME_DIR/assets"
elif [ -n "${UL_ASSETS_DIR:-}" ]; then
	echo "[UL] assets    : $UL_ASSETS_DIR（外部指定）"
else
	echo "UL 警告：既没有 $GAME_DIR/assets，也没有 UL_ASSETS_DIR。" >&2
	echo "         客户端会因为缺 assets 停在资源加载阶段。两个办法：" >&2
	echo "           1) ./run/prepare-legacy.sh $MC_VERSION --client   （会下 assets，约 200MB）" >&2
	echo "           2) UL_ASSETS_DIR=<你的 .minecraft/assets> ./run/client-legacy.sh $MC_VERSION" >&2
fi

# 客户端要在有显示器的会话里跑；这里只提醒，不拦。
#
# 注意只把**额外的**参数转给 client.sh：$1 是 MC 版本、$2 是夹具目录，
# 它们不是 KnotClient 的参数。之前直接把 "$@" 转过去，游戏日志里就多了一句
# "Completely ignored arguments: [1.14]"——不致命，但说明参数传递是错的。
shift "$#"
exec "$SCRIPT_DIR/client.sh" "$@"

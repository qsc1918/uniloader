#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# T1.2 验收夹具：把「跑一次仍有混淆的 MC（≤1.21.11）」所需的东西全部准备好
#
#   ./run/prepare-named.sh                 # 默认 1.21.11
#   UL_MC_VERSION=1.21.11 ./run/prepare-named.sh
#
# 为什么需要它：内核在 26.1+ 上零重映射（两侧命名空间同名），所以「重映射」这条通道在
# 26.3 上永远不会启动，T1.2（重映射缓存与性能）也就无从验收。要真正验收，必须能跑一次
# **仍有混淆**的 MC——本脚本就是把那条路铺出来。
#
# 产物（全部落在 run/ 下，已 gitignore；本仓库不分发 MC 字节码与模组二进制）：
#   run/game-<版本>/            原版服务端 + 库（official 名）
#   run/game-<版本>/mappings.tiny          official → intermediary → named（三命名空间）
#   run/game-<版本>/intermediary-game.jar  原版游戏 jar 重映射到 intermediary（门闸 G2）
#   run/game-<版本>/remap-classpath.txt    fabric.remapClasspathFile 的内容
#   run/mods-<版本>/             该版本可用的 Fabric 模组
#
# 本机若走 HTTP 代理：export https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897
# -----------------------------------------------------------------------------
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UL_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

MC_VERSION="${UL_MC_VERSION:-1.21.11}"
GAME_DIR="${UL_NAMED_GAME_DIR:-$SCRIPT_DIR/game-$MC_VERSION}"
MODS_DIR="${UL_NAMED_MODS_DIR:-$SCRIPT_DIR/mods-$MC_VERSION}"
MAPPINGS_DIR="${UL_NAMED_MAPPINGS_DIR:-$UL_ROOT/build/d1/mappings}"

echo "==================================================================="
echo " T1.2 夹具准备：MC $MC_VERSION"
echo "   游戏目录: $GAME_DIR"
echo "   模组目录: $MODS_DIR"
echo "   映射目录: $MAPPINGS_DIR"
echo "==================================================================="

# ---- 1. 原版游戏 -------------------------------------------------------------
# 注意：fetch-game.sh 内部用 python 的 urllib 下载。本机走代理时 python 的 ssl 不认系统
# 证书库，会 CERTIFICATE_VERIFY_FAILED；所以这里先把代理导给 curl 能认的形式，
# 并在失败时明确提示「是网络问题，不是夹具坏了」。
echo
echo "[1/4] 拉取原版服务端 + 库 → $GAME_DIR"
if ! UL_GAME_DIR="$GAME_DIR" "$SCRIPT_DIR/fetch-game.sh" "$MC_VERSION"; then
	echo "UL 错误：拉取原版游戏失败。本机走代理时请先：" >&2
	echo "  export https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897" >&2
	exit 2
fi

# ---- 2. 真实映射（intermediary + mojmap）-------------------------------------
echo
echo "[2/4] 拉取真实映射（intermediary + Mojang 官方映射）→ $MAPPINGS_DIR"
if ! "$UL_ROOT/experiments/d1-namespace/scripts/fetch-mappings.sh" "$MC_VERSION" "$MAPPINGS_DIR"; then
	echo "UL 错误：拉取映射失败。" >&2
	exit 2
fi

# ---- 3. 三命名空间映射 + intermediary 版游戏 jar ------------------------------
echo
echo "[3/4] 生成三命名空间映射与 intermediary 版游戏 jar"
export GRADLE_OPTS="${GRADLE_OPTS:--Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897}"
if ! "$UL_ROOT/gradlew" -p "$UL_ROOT" :experiments:d1-namespace:prepareLoaderNamespaces --console=plain \
		--args="--intermediary $MAPPINGS_DIR/intermediary.tiny --mojmap $MAPPINGS_DIR/mojmap.txt --game-jar $GAME_DIR/server.jar --libs $GAME_DIR/classpath.txt --out $GAME_DIR --base $UL_ROOT"; then
	echo "UL 错误：生成命名空间产物失败。" >&2
	exit 2
fi

# ---- 4. 该版本可用的 Fabric 模组 ---------------------------------------------
echo
echo "[4/4] 拉取 MC $MC_VERSION 的 Fabric 模组 → $MODS_DIR"
if ! UL_MC_VERSION="$MC_VERSION" UL_MODS_DIR="$MODS_DIR" \
		"$UL_ROOT/tooling/mod-matrix/fetch-mods.sh" baseline; then
	echo "UL 警告：模组拉取未全部成功（「查不到」本身就是结论，见 matrix-resolved.json）" >&2
fi

echo
echo "==================================================================="
echo " 准备完成。跑一次 named 运行时冒烟："
echo
echo "   UL_GAME_DIR=$GAME_DIR UL_MODS_DIR=$MODS_DIR UL_NAMESPACE=named \\"
echo "     ./run/smoke.sh --matrix=named"
echo "==================================================================="

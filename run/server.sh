#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# T0.4 — 启动一个（无头）服务端
#
#   ./run/server.sh [额外的 KnotServer 参数...]
#
# 为什么先做服务端：D1（命名空间切换）与混装冒烟都可以在没有显卡的机器上跑完，
# 客户端只是把同一套结论再看一遍。客户端见 run/client.sh。
#
# 需要：内核 jar（./gradlew :loader:build）+ 一份 Minecraft 26.3 的原版服务端
#       （放在 run/game/，由安装器/你自己准备；本仓库不分发 MC 字节码）。
# -----------------------------------------------------------------------------

set -uo pipefail

# shellcheck source=env.sh
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
require_loader_jar

if [ ! -d "$GAME_DIR" ]; then
	echo "UL 错误：游戏目录不存在：$GAME_DIR" >&2
	exit 2
fi

# ---- D1 相关的命名空间开关 ---------------------------------------------------
# 见 docs/stage0/T0.3-D1-namespace-experiment.md：
#   ul.namespace=named 表示「内核与 mod 都在 Mojang 官方名下运行」。
#   此时必须保证喂给内核的 MC 本体已经是 mojmap（由安装器预先 remap），
#   否则会跳过反混淆、全线对不上名（G4）。
export UL_NAMESPACE="${UL_NAMESPACE:-intermediary}"

JVM_ARGS=(
	"-Dfabric.gameDir=$GAME_DIR"
	"-Dfabric.modsFolder=$MODS_DIR"
	"-Dfabric.log.level=${UL_LOG_LEVEL:-info}"
	# 诊断骨架（T0.5）的输出位置
	"-Dul.crashAnalysis=${UL_CRASH_ANALYSIS:-on}"
)

if [ "$UL_NAMESPACE" = "named" ]; then
	JVM_ARGS+=(
		"-Dfabric.runtimeMappingNamespace=named"
		"-Dfabric.gameMappingNamespace=named"
		"-Dfabric.defaultModDistributionNamespace=intermediary"
		# 生产环境也要重映射 mod（G1）
		"-Dul.remapFabricMods=true"
		# 重映射 classpath 必须是**源命名空间**（intermediary）的 MC + 库（G2，见 D1 报告）
		"-Dfabric.remapClasspathFile=$GAME_DIR/remap-classpath.txt"
	)
fi

echo "[UL] 命名空间模式: $UL_NAMESPACE"
echo "[UL] 启动 KnotServer"

exec "$JAVA" "${JVM_ARGS[@]}" \
	-cp "$LOADER_JAR" \
	net.fabricmc.loader.impl.launch.knot.KnotServer \
	--gameDir "$GAME_DIR" \
	"$@"

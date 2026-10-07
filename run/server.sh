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

# ---- 命名空间开关 ------------------------------------------------------------
# 取决于**目标 MC 版本的混淆状态**（实测方法：看官方版本 json 里有没有 client_mappings）：
#
#   MC >= 26.1（新版本号体系）：Mojang **不再混淆**，也不再发布 *_mappings。
#     实测 26.3 的版本 json：downloads = [client, server]；1.21.11 则是
#     [client, client_mappings, server, server_mappings]。
#     此时 official 名 == 运行时名 == mod 分发名，**什么都不用设**。
#     loom 也是这么判的：isUnobfuscated() → productionNamespace=official、
#     useIntermediateMappings=false、defaultMixinRemapType=static。
#   MC <= 1.21.11：仍有混淆，才需要下面 named/intermediary 那套开关；
#     四处门闸 G1-G4 也只在这条路上适用（见 docs/stage0/T0.3-D1-namespace-experiment.md）。
export UL_NAMESPACE="${UL_NAMESPACE:-official}"

JVM_ARGS=(
	"-Dfabric.gameDir=$GAME_DIR"
	"-Dfabric.modsFolder=$MODS_DIR"
	"-Dfabric.log.level=${UL_LOG_LEVEL:-info}"
	# 诊断骨架（T0.5）的输出位置
	"-Dul.crashAnalysis=${UL_CRASH_ANALYSIS:-on}"
)

case "$UL_NAMESPACE" in
	official)
		# 未混淆版本的正确状态就是「不设任何命名空间属性」：
		# 内核默认走 official，mod 分发命名空间也是 official，两边天然同名。
		;;
	named|intermediary)
		# 仅用于「目标 MC 仍有混淆」的场景（<= 1.21.11）。
		JVM_ARGS+=(
			"-Dfabric.runtimeMappingNamespace=$UL_NAMESPACE"
			"-Dfabric.gameMappingNamespace=${UL_GAME_NAMESPACE:-official}"
			"-Dfabric.defaultModDistributionNamespace=intermediary"
			# 生产环境也要重映射 mod（G1）
			"-Dul.remapFabricMods=true"
			# 重映射 classpath 必须是**源命名空间**（intermediary）的 MC + 库（G2，见 D1 报告）
			"-Dfabric.remapClasspathFile=$GAME_DIR/remap-classpath.txt"
		)
		;;
	*)
		echo "UL 错误：UL_NAMESPACE 只能是 official / intermediary / named，收到 '$UL_NAMESPACE'" >&2
		exit 2
		;;
esac

echo "[UL] 命名空间模式: $UL_NAMESPACE"
echo "[UL] 启动 KnotServer${*:+ （参数: $*）}"

# classpath = 内核 + installer 依赖（asm/mixin）+ 原版游戏文件（run/fetch-game.sh 拉的）
CLASS_PATH="$(UL_GAME_DIR="$GAME_DIR" UL_LOADER_JAR="$LOADER_JAR" bash "$(dirname "${BASH_SOURCE[0]}")/loader-classpath.sh")"

# 工作目录必须是游戏目录：MC 用相对路径读写 eula.txt / server.properties / world /
# logs / config，启动器就是这么做的。不切目录的话它会报
# "Failed to load eula.txt" 然后拒绝启动（而 -Dfabric.gameDir 只影响 loader 自己的判断）。
cd "$GAME_DIR" || { echo "UL 错误：无法进入游戏目录 $GAME_DIR" >&2; exit 2; }

exec "$JAVA" "${JVM_ARGS[@]}" \
	-cp "$CLASS_PATH" \
	net.fabricmc.loader.impl.launch.knot.KnotServer \
	--gameDir "$GAME_DIR" \
	"$@"

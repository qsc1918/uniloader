#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# T0.4 — 启动客户端
#
#   ./run/client.sh [额外的 KnotClient 参数...]
#
# 与服务端共用同一套环境与命名空间开关（见 run/env.sh 与 run/server.sh）。
# 客户端需要显卡与 assets；CI 上请用 run/smoke.sh 跑无头冒烟。
# -----------------------------------------------------------------------------

set -uo pipefail

# shellcheck source=env.sh
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
require_loader_jar

# 命名空间开关与 server.sh 完全一致，理由见那边的注释：MC >= 26.1 不再混淆，
# 因此 official 名 == 运行时名 == mod 分发名，默认模式什么都不用设。
export UL_NAMESPACE="${UL_NAMESPACE:-official}"

JVM_ARGS=(
	"-Dfabric.gameDir=$GAME_DIR"
	"-Dfabric.modsFolder=$MODS_DIR"
	"-Dfabric.log.level=${UL_LOG_LEVEL:-info}"
	"-Dul.crashAnalysis=${UL_CRASH_ANALYSIS:-on}"
)

case "$UL_NAMESPACE" in
	official)
		;;
	named|intermediary)
		# 仅用于「目标 MC 仍有混淆」的场景（<= 1.21.11）。
		JVM_ARGS+=(
			"-Dfabric.runtimeMappingNamespace=$UL_NAMESPACE"
			"-Dfabric.gameMappingNamespace=${UL_GAME_NAMESPACE:-official}"
			"-Dfabric.defaultModDistributionNamespace=intermediary"
			"-Dul.remapFabricMods=true"
			"-Dfabric.remapClasspathFile=$GAME_DIR/remap-classpath.txt"
		)
		;;
	*)
		echo "UL 错误：UL_NAMESPACE 只能是 official / intermediary / named，收到 '$UL_NAMESPACE'" >&2
		exit 2
		;;
esac

if [ -n "${UL_ASSETS_DIR:-}" ]; then
	JVM_ARGS+=("-Dfabric.assetsDir=$UL_ASSETS_DIR")
fi

echo "[UL] 命名空间模式: $UL_NAMESPACE"
echo "[UL] 启动 KnotClient"

CLASS_PATH="$(UL_GAME_DIR="$GAME_DIR" UL_LOADER_JAR="$LOADER_JAR" bash "$(dirname "${BASH_SOURCE[0]}")/loader-classpath.sh")"

# 工作目录必须是游戏目录：MC 用相对路径读写 eula.txt / server.properties / world /
# logs / config，启动器就是这么做的。不切目录的话它会报
# "Failed to load eula.txt" 然后拒绝启动（而 -Dfabric.gameDir 只影响 loader 自己的判断）。
cd "$GAME_DIR" || { echo "UL 错误：无法进入游戏目录 $GAME_DIR" >&2; exit 2; }

exec "$JAVA" "${JVM_ARGS[@]}" \
	-cp "$CLASS_PATH" \
	net.fabricmc.loader.impl.launch.knot.KnotClient \
	--gameDir "$GAME_DIR" \
	"$@"

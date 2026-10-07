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

export UL_NAMESPACE="${UL_NAMESPACE:-intermediary}"

JVM_ARGS=(
	"-Dfabric.gameDir=$GAME_DIR"
	"-Dfabric.modsFolder=$MODS_DIR"
	"-Dfabric.log.level=${UL_LOG_LEVEL:-info}"
	"-Dul.crashAnalysis=${UL_CRASH_ANALYSIS:-on}"
)

if [ "$UL_NAMESPACE" = "named" ]; then
	JVM_ARGS+=(
		"-Dfabric.runtimeMappingNamespace=named"
		"-Dfabric.gameMappingNamespace=named"
		"-Dfabric.defaultModDistributionNamespace=intermediary"
		"-Dul.remapFabricMods=true"
		"-Dfabric.remapClasspathFile=$GAME_DIR/remap-classpath.txt"
	)
fi

if [ -n "${UL_ASSETS_DIR:-}" ]; then
	JVM_ARGS+=("-Dfabric.assetsDir=$UL_ASSETS_DIR")
fi

echo "[UL] 命名空间模式: $UL_NAMESPACE"
echo "[UL] 启动 KnotClient"

exec "$JAVA" "${JVM_ARGS[@]}" \
	-cp "$LOADER_JAR" \
	net.fabricmc.loader.impl.launch.knot.KnotClient \
	--gameDir "$GAME_DIR" \
	"$@"

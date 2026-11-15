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
	# 日志编码固定成 UTF-8。
	#
	# 为什么必须显式设：老版本 MC 跑在 Java 8 上，Windows 的**平台默认编码是 GBK**，
	# 而日志最终由游戏的 log4j2 控制台 appender 输出（loader 的消息也走它），
	# 它用的是默认编码 → 中文全成乱码，按 UTF-8 找关键字（冒烟断言、用户排障）一条都匹配不到。
	# 实测：只设 sun.stdout.encoding 不够（log4j2 自己包了一层流），必须同时固定 file.encoding。
	#
	# 风险有多大：只改「默认编码」。MC 自己显式指定编码的地方不受影响
	# （server.properties 走 Properties.load，规范就是 ISO-8859-1；存档是二进制；
	# json 走 StandardCharsets.UTF_8）。Java 18+ 默认本来就是 UTF-8，设了是空操作。
	"-Dfile.encoding=UTF-8"
	"-Dsun.stdout.encoding=UTF-8"
	"-Dsun.stderr.encoding=UTF-8"
	"-Dfabric.gameDir=$GAME_DIR"
	"-Dfabric.modsFolder=$MODS_DIR"
	"-Dfabric.log.level=${UL_LOG_LEVEL:-info}"
	"-Dul.crashAnalysis=${UL_CRASH_ANALYSIS:-on}"
)

case "$UL_NAMESPACE" in
	official)
		;;
	intermediary)
		# 目标 MC 仍有混淆（1.14 – 1.21.11，**选项 A 默认路径**）：运行时 = intermediary
		# = mod 分发命名空间，两侧同名 → **零重映射**，只把游戏 jar 换到 intermediary。
		# 有 mojmap 也一样，理由见 server.sh 的分支注释。
		JVM_ARGS+=(
			"-Dfabric.runtimeMappingNamespace=intermediary"
			"-Dfabric.gameMappingNamespace=${UL_GAME_NAMESPACE:-official}"
			"-Dfabric.defaultModDistributionNamespace=intermediary"
		)

		if [ -f "$GAME_DIR/mappings.tiny" ]; then
			JVM_ARGS+=("-Dfabric.mappingPath=$GAME_DIR/mappings.tiny")
		else
			echo "UL 警告：$GAME_DIR/mappings.tiny 不存在——先跑 ./run/prepare-legacy.sh <版本> --client" >&2
		fi
		;;
	named)
		# **显式路径**（D1 原方案，选项 A 后不再默认）：目标 MC 仍有混淆且有 mojmap
		# （1.14.4 – 1.21.11），仅在需要 NeoForge/Forge 混装时启用。运行时 named，
		# mod 从 intermediary 重映射过来；夹具必须是三命名空间。
		# 理由与所需夹具见 server.sh 的分支注释。
		JVM_ARGS+=(
			"-Dfabric.runtimeMappingNamespace=named"
			"-Dfabric.gameMappingNamespace=${UL_GAME_NAMESPACE:-official}"
			"-Dfabric.defaultModDistributionNamespace=intermediary"
			"-Dul.remapFabricMods=true"
			"-Dfabric.remapClasspathFile=$GAME_DIR/remap-classpath.txt"
		)

		if [ -f "$GAME_DIR/mappings.tiny" ]; then
			JVM_ARGS+=("-Dfabric.mappingPath=$GAME_DIR/mappings.tiny")
		else
			echo "UL 警告：$GAME_DIR/mappings.tiny 不存在——先跑 ./run/prepare-named.sh" >&2
		fi
		;;
	*)
		echo "UL 错误：UL_NAMESPACE 只能是 official / intermediary / named，收到 '$UL_NAMESPACE'" >&2
		exit 2
		;;
esac

if [ -n "${UL_ASSETS_DIR:-}" ]; then
	JVM_ARGS+=("-Dfabric.assetsDir=$UL_ASSETS_DIR")
fi

# natives 目录必须挂到 java.library.path 上。
#
# 为什么：LWJGL 的原生库（glfw.dll / openal.dll / lwjgl.dll…）在老版本 MC 上是
# 从**磁盘上的 natives 目录**加载的（新版 LWJGL 才支持直接从 jar 里取）。
# 官方启动器就是靠 `-Djava.library.path=<natives>` 告诉 JVM 去哪找。
# 少了这一句的症状是：[LWJGL] Failed to load a library，然后在入口点阶段
# 连锁炸开（模组初始化时碰到 null 的 GL/窗口对象），
# 报错看起来像「某个模组有问题」，其实一个 native 都没加载上。
if [ -d "$GAME_DIR/natives" ]; then
	JVM_ARGS+=("-Djava.library.path=$GAME_DIR/natives")
	echo "[UL] natives  : $GAME_DIR/natives（已挂到 java.library.path）"
else
	echo "UL 警告：$GAME_DIR/natives 不存在 —— LWJGL 的原生库找不到，客户端会在入口点阶段炸。" >&2
	echo "         先跑 ./run/prepare-legacy.sh <版本> --client（或 run/fetch-client.sh）。" >&2
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

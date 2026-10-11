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
#       · 有 mojmap（1.14.4–1.21.11）→ UL_NAMESPACE=named（重映射 mod）
#       · 没有 mojmap（1.14–1.14.3 等）→ UL_NAMESPACE=intermediary（零重映射）
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
	# 诊断骨架（T0.5）的输出位置
	"-Dul.crashAnalysis=${UL_CRASH_ANALYSIS:-on}"
)

case "$UL_NAMESPACE" in
	official)
		# 未混淆版本的正确状态就是「不设任何命名空间属性」：
		# 内核默认走 official，mod 分发命名空间也是 official，两边天然同名。
		;;
	intermediary)
		# 目标 MC 仍有混淆、但**没有 mojmap** 的老版本（1.14 就是：Mojang 从 1.14.4
		# 才开始发 client_mappings）。运行时 = intermediary = mod 分发命名空间，
		# 两侧同名 → **模组零重映射**，只需要把游戏 jar 从 official 换到 intermediary。
		# 这也正是原生 Fabric 在生产环境的行为，是最稳的一条路。
		JVM_ARGS+=(
			"-Dfabric.runtimeMappingNamespace=intermediary"
			"-Dfabric.gameMappingNamespace=${UL_GAME_NAMESPACE:-official}"
			"-Dfabric.defaultModDistributionNamespace=intermediary"
		)

		if [ -f "$GAME_DIR/mappings.tiny" ]; then
			JVM_ARGS+=("-Dfabric.mappingPath=$GAME_DIR/mappings.tiny")
		else
			echo "UL 警告：$GAME_DIR/mappings.tiny 不存在——先跑 ./run/prepare-legacy.sh <版本>" >&2
		fi
		;;
	named)
		# 目标 MC 仍有混淆、且有 mojmap（1.14.4 – 1.21.11）：运行时统一到 named，
		# mod 从 intermediary 重映射过来，四处门闸 G1–G4 全开。
		JVM_ARGS+=(
			"-Dfabric.runtimeMappingNamespace=named"
			"-Dfabric.gameMappingNamespace=${UL_GAME_NAMESPACE:-official}"
			"-Dfabric.defaultModDistributionNamespace=intermediary"
			# 生产环境也要重映射 mod（G1）
			"-Dul.remapFabricMods=true"
			# 重映射 classpath 必须是**源命名空间**（intermediary）的 MC + 库（G2，见 D1 报告）
			"-Dfabric.remapClasspathFile=$GAME_DIR/remap-classpath.txt"
		)

		# 映射文件（三命名空间 official → intermediary → named）：反混淆游戏本体用
		# official → named，重映射模组用 intermediary → named，两条边都要有。
		# 由 run/prepare-named.sh 生成。没设的时候不硬塞一个不存在的路径，让内核去 classpath 找。
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

# 额外的 JVM 参数（空格分隔）。留给实验与验收用，例如 T1.5 的产物哈希对拍：
#   UL_EXTRA_JVM_ARGS="-Dul.transform.hashFile=/tmp/new.txt" ./run/smoke.sh
if [ -n "${UL_EXTRA_JVM_ARGS:-}" ]; then
	# 故意不加引号：这里就是要按空格拆成多个参数。
	# shellcheck disable=SC2206
	JVM_ARGS+=($UL_EXTRA_JVM_ARGS)
fi

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

#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# 老版本（1.x）的夹具准备 —— 通用脚本
#
#   ./run/prepare-legacy.sh 1.14                  # 夹具自带模组时可不给第二参
#   ./run/prepare-legacy.sh 1.14 /d/work2/1.14fabric
#   ./run/prepare-legacy.sh 1.14.4 /d/work2/1.14.4fabric
#
# 产出（都在 run/ 下，已 gitignore）：
#   run/game-<版本>/mappings.tiny          命名空间映射
#   run/game-<版本>/intermediary-game.jar  游戏 jar 的 intermediary 版
#   run/game-<版本>/remap-classpath.txt    重映射用的源命名空间 classpath（named 模式才需要）
#   run/mods-<版本>/*.jar                  该版本的真实模组
#
# **默认一条路（2026-11-15 选项 A 拍板）**：运行时 = intermediary = mod 分发命名空间，
# 两命名空间 official → intermediary，**零重映射**。这正是原生 Fabric 生产环境的行为。
# 判据仍然是「有没有 mappings」，**不比较版本号**（工程纪律第 4 条、T1.1 约定）。
#
#   没有 mojmap（1.14 – 1.14.3 等）：本来就只能走这条路。
#   有 mojmap（1.14.4 – 1.21.11）：**默认也走这条路**。D1 原本一律用 named，
#     但真实模组在 named 下有三个独立不兼容（R16 refmap / CustomSkinLoader 动态
#     target 的 class_3298 / replaymod 注入描述符），intermediary 全量跑通。
#
# **显式例外**：--namespace=named → 三命名空间 official → intermediary → named，
# 运行时 named、mod 从 intermediary 重映射过去、门闸 G1–G4 全开。
# 只在「混淆版本上要跑 NeoForge/Forge 混装」时用；需要三命名空间 mappings.tiny，
# 所以必须**重新 prepare**，不能只靠 UL_NAMESPACE 覆盖启动（护栏见 smoke-legacy.sh）。
#
# 本机若走 HTTP 代理：export https_proxy=http://127.0.0.1:7897
# -----------------------------------------------------------------------------

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UL_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

MC_VERSION=""
MODS_SRC=""

# 设了 UL_ASSETS_DIR 就默认不下 assets（复用你已有的 .minecraft/assets）
[ -n "${UL_ASSETS_DIR:-}" ] && NO_ASSETS=1

# Gradle 的 --args 是**一个字符串**，MSYS 不会替我们转换里面的路径：
# 传进去的 `/d/work2/...` 到 Java 手里会变成 `\d\work2\...`（相对当前盘），
# 于是报 NoSuchFileException，而路径看起来「明明是对的」。
# 给 Gradle 的路径必须转成 Windows 形式（run/loader-classpath.sh 里同样的做法）。
to_native() {
	if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi
}

# ---- 参数解析（**顺序无关**）--------------------------------------------------
#
# 位置参数只有两个：MC 版本、模组来源目录；其余一律是 --flag。
# 为什么强调顺序无关：文档里写的是 `prepare-legacy.sh 1.14 --client <目录>`，
# 而脚本原来按「$1 版本、$2 目录」硬取，于是 `--client` 被当成目录名、
# 真正的目录被忽略 → **模组一个都没更新，排除开关也静默失效**，
# 表现是「改了参数但结果没变」。这种不一致比报错更难查。
CLIENT=0
NO_ASSETS=0
EXCLUDES=""
NS_OVERRIDE=""

for arg in "$@"; do
	case "$arg" in
		--client) CLIENT=1 ;;
		--no-assets) NO_ASSETS=1 ;;
		--exclude=*) EXCLUDES="${EXCLUDES}${EXCLUDES:+,}${arg#--exclude=}" ;;
		--namespace=*) NS_OVERRIDE="${arg#--namespace=}" ;;
		-*) echo "UL 错误：不认识的参数 '$arg'" >&2; exit 2 ;;
		*)
			if [ -z "$MC_VERSION" ]; then
				MC_VERSION="$arg"
			elif [ -z "$MODS_SRC" ]; then
				MODS_SRC="$arg"
			else
				echo "UL 错误：多余的参数 '$arg'" >&2
				exit 2
			fi
			;;
	esac
done

if [ -z "$MC_VERSION" ]; then
	echo "用法：./run/prepare-legacy.sh <mc版本> [模组来源目录] [--client] [--no-assets] [--exclude=a.jar,b.jar] [--namespace=intermediary|named]" >&2
	echo "例如：./run/prepare-legacy.sh 1.14 /d/work2/1.14fabric" >&2
	echo "      ./run/prepare-legacy.sh 1.14 --client /d/work2/1.14fabric --exclude=dynamic-fps-1.0.2.jar" >&2
	exit 2
fi

if [ "$CLIENT" -eq 1 ]; then
	GAME_DIR="$SCRIPT_DIR/client-$MC_VERSION"
else
	GAME_DIR="$SCRIPT_DIR/game-$MC_VERSION"
fi
MODS_DIR="$SCRIPT_DIR/mods-$MC_VERSION"
MAPPINGS_DIR="$UL_ROOT/build/d1/mappings-$MC_VERSION"

echo "==================================================================="
echo " 老版本夹具准备：MC $MC_VERSION（$([ "$CLIENT" -eq 1 ] && echo 客户端 || echo 服务端)）"
echo "   游戏目录: $GAME_DIR"
echo "   模组目录: $MODS_DIR"
echo "   映射目录: $MAPPINGS_DIR"
echo "==================================================================="

# ---- 1. 原版游戏 -------------------------------------------------------------
if [ "$CLIENT" -eq 1 ]; then
	echo
	echo "[1/4] 拉取原版客户端（含 assets）→ $GAME_DIR"
	echo "      想省掉 assets 的下载：先把 UL_ASSETS_DIR 指向已有的 .minecraft/assets"
	CLIENT_ASSET_ARG=""
	[ "$NO_ASSETS" -eq 1 ] && CLIENT_ASSET_ARG="--no-assets"

	# shellcheck disable=SC2086
	if ! UL_GAME_DIR="$GAME_DIR" "$SCRIPT_DIR/fetch-client.sh" "$MC_VERSION" $CLIENT_ASSET_ARG; then
		echo "UL 错误：拉取原版客户端失败。" >&2
		exit 2
	fi

	GAME_JAR="$GAME_DIR/client.jar"
else
	echo
	echo "[1/4] 拉取原版服务端 → $GAME_DIR"
	if ! UL_GAME_DIR="$GAME_DIR" "$SCRIPT_DIR/fetch-game.sh" "$MC_VERSION"; then
		echo "UL 错误：拉取原版游戏失败。本机走代理时请先：" >&2
		echo "  export https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897" >&2
		exit 2
	fi

	GAME_JAR="$GAME_DIR/server.jar"
fi

# ---- 2. 映射（intermediary，以及有的话 mojmap）-------------------------------
echo
echo "[2/4] 拉取映射 → $MAPPINGS_DIR"
if ! "$UL_ROOT/experiments/d1-namespace/scripts/fetch-mappings.sh" "$MC_VERSION" "$MAPPINGS_DIR"; then
	echo "UL 错误：拉取映射失败。" >&2
	exit 2
fi

# ---- 3. 命名空间产物 ---------------------------------------------------------
echo
echo "[3/4] 生成命名空间产物"
export GRADLE_OPTS="${GRADLE_OPTS:--Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897}"

ARGS=(--intermediary "$(to_native "$MAPPINGS_DIR/intermediary.tiny")"
	--game-jar "$(to_native "$GAME_JAR")"
	--libs "$(to_native "$GAME_DIR/classpath.txt")"
	--out "$(to_native "$GAME_DIR")"
	--base "$(to_native "$UL_ROOT")")

# 选项 A（2026-11-15 用户拍板）：混淆版本**默认 intermediary 零重映射**，
# 有 mojmap 也不自动切 named。判据仍是「有没有 mappings」而不是版本号表。
#
# 为什么改：真实模组实测（2026-11-08，1.14.4 的 35 个）named 路径有三个独立问题——
#   1. RebornCore/TechReborn 的 refmap 在重映射时方法名冲突（R16）；
#   2. CustomSkinLoader 15.1 的 MixinConfigPlugin 动态加 target，硬编码
#      net/minecraft/class_3298 —— named 运行时下这个类不存在，ClassInfo 解析失败；
#   3. replaymod 的 @Inject 描述符在重映射后对不上（CallbackInfo vs Returnable）。
# 三个都源于「模组是按 intermediary 编译/设计的」，而零重映射正是原版 Fabric 生产行为，
# 且用户已确认该路径能进主菜单和世界。D1 的 named 路径**一行没删**，改为显式开启：
# 只有混淆版本上要跑 NeoForge/Forge 混装时才需要 --namespace=named。
NAMESPACE="intermediary"

case "$NS_OVERRIDE" in
	"") ;;
	intermediary) NAMESPACE="intermediary" ;;
	named)
		if [ ! -f "$MAPPINGS_DIR/mojmap.txt" ]; then
			echo "UL 错误：这个版本没有 mojmap，不能用 --namespace=named" >&2
			exit 2
		fi
		NAMESPACE="named" ;;
	*) echo "UL 错误：--namespace 只能是 intermediary 或 named，收到 '$NS_OVERRIDE'" >&2; exit 2 ;;
esac

if [ "$NAMESPACE" = "named" ]; then
	ARGS+=(--mojmap "$(to_native "$MAPPINGS_DIR/mojmap.txt")")
	echo "[3/4] 三命名空间，运行时 named（mod 需要重映射）"
else
	echo "[3/4] 两命名空间，运行时 intermediary（**零重映射**，与原版 Fabric 生产行为一致）"
fi

if [ -n "$NS_OVERRIDE" ]; then
	echo "[3/4] 运行时命名空间由 --namespace=$NS_OVERRIDE 显式指定"
fi

GRADLE_TASK=(:experiments:d1-namespace:prepareLoaderNamespaces --console=plain --args="${ARGS[*]}")

if ! "$UL_ROOT/gradlew" -p "$UL_ROOT" "${GRADLE_TASK[@]}"; then
	# 在线解析失败时自动回退到 --offline 重试一次。
	#
	# 为什么需要：loader 的组合构建用 fabric-loom:1.11-SNAPSHOT（上游写死在
	# loader/build.gradle 里），SNAPSHOT 的元数据会随时间变；一个月后再跑，
	# Gradle 在线重新解析可能失败（"Plugin ... was not found"），而 jar 其实
	# 就在本地 Gradle 缓存里。实测 2026-11-08 踩到过一次。
	# 这不是「忽略失败」：offline 也要真的把任务跑成功才继续，两次都失败照样退出。
	echo "[3/4] 在线解析失败，用本地缓存（--offline）重试一次……" >&2

	if ! "$UL_ROOT/gradlew" -p "$UL_ROOT" --offline "${GRADLE_TASK[@]}"; then
		echo "UL 错误：生成命名空间产物失败（在线与 offline 都试过了）。" >&2
		exit 2
	fi
fi

# 把判定结果落盘：冒烟脚本读它，而不是自己再判一次。
# 两处各判一次的下场是「prepare 按 named 准备、smoke 按 intermediary 跑」，
# 而那种不一致只会表现为一堆莫名其妙的类找不到。
printf '%s\n' "$NAMESPACE" > "$GAME_DIR/.ul-namespace"
echo "[3/4] 命名空间判定写入 $GAME_DIR/.ul-namespace：$NAMESPACE"

# ---- 3b. 服务端跑起来所需的最小文件 ------------------------------------------
# 无头服务端需要 eula.txt 才肯启动。这是**本地测试夹具**：只影响 run/game-<版本>/，
# 不代表对 EULA 有任何额外解释——你在本机跑原版服务端本来也要做这一步。
# 注意判据是「内容里有没有 eula=true」，不是「文件在不在」：
# 上一次启动失败时 MC 会自己写一份 eula=false 的模板出来（带注释头，180 多字节），
# 只判存在的话就会一直跳过，然后每次都在同一处停下——看起来像加载器的问题。
if [ "$CLIENT" -eq 0 ] && ! grep -q '^eula=true' "$GAME_DIR/eula.txt" 2>/dev/null; then
	printf 'eula=true\n' > "$GAME_DIR/eula.txt"
	echo "[3/4] 写入夹具用的 eula.txt（无头服务端必需）"
fi

# 关掉在线验证、把地形生成压到最小：冒烟测的是加载器，不是服务端性能。
if [ "$CLIENT" -eq 0 ] && [ ! -f "$GAME_DIR/server.properties" ]; then
	{
		printf 'online-mode=false\n'
		printf 'level-type=flat\n'
		printf 'max-tick-time=-1\n'
		printf 'spawn-protection=0\n'
	} > "$GAME_DIR/server.properties"
	echo "[3/4] 写入夹具用的 server.properties"
fi

# ---- 4. 模组 ----------------------------------------------------------------
echo
echo "[4/4] 准备模组 → $MODS_DIR"
mkdir -p "$MODS_DIR"

if [ -n "$MODS_SRC" ]; then
	if [ ! -d "$MODS_SRC" ]; then
		echo "UL 错误：模组来源目录不存在：$MODS_SRC" >&2
		exit 2
	fi

	# 目标 Java 能加载的最高 class file 版本（从官方版本 json 读，不写版本表）
	REQ_JAVA="$(bash "$SCRIPT_DIR/java-major-from-json.sh" "$GAME_DIR/version.json" 2>/dev/null || echo 8)"
	MAX_CF=$((44 + REQ_JAVA))

	# 先清空目标目录再拷。
	#
	# 为什么必须清：不清的话，上一次留下的 jar 会**一直待在目录里**——
	# 这次被排除/跳过的模组看起来「排除没生效」，而实际是旧文件还在。
	# 实测踩到过：加了 --exclude 之后客户端仍然崩在同一个模组上，
	# 因为那个 jar 是上一次拷进去的。这个目录是脚本自己生成的夹具（已 gitignore），
	# 清空是安全的；但要说出来，不能静默删。
	if compgen -G "$MODS_DIR/*.jar" > /dev/null; then
		removed=0

		for old in "$MODS_DIR"/*.jar; do
			[ -f "$old" ] || continue

			if rm -f "$old"; then
				removed=$((removed + 1))
			else
				echo "UL 错误：删不掉 $old" >&2
				echo "        Windows 上多半是还有 Java 进程占着这个 jar（上次冒烟没退干净）。" >&2
				exit 2
			fi
		done

		# **删完必须复核**：rm -f 返回 0 不等于文件真的没了。
		# 实测踩到：清空日志打了「移除 37 个」，实际有两个 jar 没删掉（文件被占），
		# 于是 --exclude 排掉 TechReborn/RebornCore 之后它们还在目录里，
		# 冒烟仍然崩在同一个 refmap 冲突上 —— 看起来像「排除没生效」，
		# 又像「重映射器有 bug」，两个方向都是错的。
		leftover="$(ls "$MODS_DIR"/*.jar 2>/dev/null || true)"

		if [ -n "$leftover" ]; then
			echo "UL 错误：清空 $MODS_DIR 后仍有残留（继续跑会让 --exclude 静默失效）：" >&2
			printf '   %s\n' $leftover >&2
			echo "        关掉占用这些 jar 的 Java 进程再重试。" >&2
			exit 2
		fi

		echo "[4/4] 清空 $MODS_DIR（已确认移除 $removed 个 jar）"
	fi

	count=0
	skipped=0

	for jar in "$MODS_SRC"/*.jar; do
		[ -f "$jar" ] || continue

		# 检查这个模组的字节码版本。
		#
		# 为什么必须查：模组名里写着 1.14 不代表它真是 1.14 时代的构建。
		# 实测踩到过：rightclickharvest-2.0.0.jar 是**用 Java 17 编的**，
		# 它 mixin 1.14 的 Block 时，Mixin 会把目标类提升到与 mixin 类相同的 class file 版本，
		# 于是 Block 变成 class file 61，而 Java 8 只认到 52 → 整批类加载失败，
		# 报错却说「net/minecraft/class_2248 版本太新」，看起来像游戏 jar 坏了。
		# 与其让它在运行期以那种方式炸，不如在这里就把它拦下来并说清楚。
		# 一次问三件事：字节码版本、元数据能不能读、是不是别家生态的 jar。
		#
		# 为什么是这三件：模组名里写着 1.14 不代表它真是 1.14 时代的构建，
		# 而**元数据本身经常给不出结论**——真实样本里有一半的模组根本不声明
		# minecraft 依赖（dynamic-fps 1.0.2 就是这样，它的 mixin 指向 1.14 里
		# 不存在的 method_20813，一 apply 就崩，而错误信息只说「方法找不到」）。
		# 能静态判出来的先判掉，判不出来的留给运行期并让错误信息说人话。
		# 给 Windows 版 Python 的路径必须转成原生形式（to_native = cygpath -w）。
		# MSYS 只对「看起来像普通路径」的参数自动转换；**含中文或方括号**的文件名
		# 不会转，Python 就收到 /d/work2/... 这种它打不开的路径 → FileNotFoundError，
		# probe 变成空字符串 → 三列全是空 → 静态检查静默失效，
		# 日志还会打出「带  —— 别家生态模组」这种自相矛盾的提示。
		# 实测样本：CameraOverhaul-v2.1.2-fabric+mc[1.14-1.14.4].jar、
		# [万用皮肤补丁] CustomSkinLoader_Universal-15.1.jar。
		probe="$(python - "$(to_native "$jar")" <<'PY'
import json, struct, sys, zipfile

path = sys.argv[1]

with zipfile.ZipFile(path) as zf:
    names = zf.namelist()

    worst = 0

    for name in names:
        if not name.endswith('.class'):
            continue

        # **多版本 jar 的高版本类不算数**：META-INF/versions/9|10|16/... 是给
        # 对应的高版本 JVM 用的，Java 8 根本不会去加载它们。
        # 实测踩到：replaymod-1.14.4-2.6.20.jar 的 class file 53 全在
        # META-INF/versions/9/ 下，randomtp 的 54/60 全在 versions/10|16/ 下 ——
        # 一刀切按「最高版本」判会把这两个完全能用的模组误杀。
        # module-info.class 同理（Java 9+ 的模块描述符，Java 8 忽略）。
        if name.startswith('META-INF/versions/') or name.endswith('module-info.class'):
            continue

        try:
            data = zf.read(name)
        except Exception:
            continue
        if len(data) >= 8 and data[:4] == b'\xca\xfe\xba\xbe':
            worst = max(worst, struct.unpack('>H', data[6:8])[0])

    # 元数据能不能读（有些 jar 的 fabric.mod.json 里有裸控制字符，标准 JSON 读不了）
    meta = 'ok'

    if 'fabric.mod.json' not in names:
        meta = 'missing'
    else:
        try:
            json.loads(zf.read('fabric.mod.json').decode('utf-8'))
        except Exception:
            meta = 'broken'

    foreign = 'no'
    for marker in ('META-INF/neoforge.mods.toml', 'META-INF/mods.toml'):
        if marker in names:
            foreign = marker
            break

print('%d\t%s\t%s' % (worst, meta, foreign))
PY
)"

		worst="$(printf '%s' "$probe" | cut -f1)"
		meta="$(printf '%s' "$probe" | cut -f2)"
		foreign="$(printf '%s' "$probe" | cut -f3)"

		# 没有 fabric.mod.json 的**不跳过**：那正是「别家生态模组」，
		# 是 T1.4「识别为 X mod」这条验收的对象，删了就没人验它了。只提醒。
		if [ "$meta" = "missing" ]; then
			echo "[4/4] 注意 $(basename "$jar")：没有 fabric.mod.json —— 会被当成别家生态模组识别（不加载）"
		fi

		if [ "$meta" = "broken" ]; then
			echo "[4/4] 跳过 $(basename "$jar")：fabric.mod.json 不是合法 JSON（多半含裸控制字符）"
			echo "        Fabric 自己也读不了这种清单，放进去只会在解析阶段炸。"
			skipped=$((skipped + 1))
			continue
		fi

		if [ "$foreign" != "no" ]; then
			echo "[4/4] 注意 $(basename "$jar")：带 $foreign —— 别家生态模组，只会被识别、不会被加载"
		fi

		if [ -n "$worst" ] && [ "$worst" -gt "$MAX_CF" ] 2>/dev/null; then
			echo "[4/4] 跳过 $(basename "$jar")：最高 class file $worst，而 MC $MC_VERSION 的 Java $REQ_JAVA 只支持到 $MAX_CF"
			echo "        这个 jar 不是该 MC 版本的构建（多半是更新版本的）。拿它跑会在 mixin 阶段"
			echo "        把目标类的版本抬上去，报错却指向游戏 jar。"
			skipped=$((skipped + 1))
			continue
		fi

		# 显式排除（--exclude=名字或名字的一部分，逗号分隔）。
		# 为什么需要它：有些问题是静态判不出来的——最典型的是「模组不是这个 MC 版本的构建」，
		# 它字节码版本正常、元数据也读得出来，只有在 mixin 真的 apply 时才炸。
		# 这种只能靠人确认后显式排除，不能靠脚本猜（猜错就是静默少装一个模组）。
		if [ -n "$EXCLUDES" ]; then
			excluded=0

			for pat in ${EXCLUDES//,/ }; do
				case "$(basename "$jar")" in
					*"$pat"*) excluded=1 ;;
				esac
			done

			if [ "$excluded" -eq 1 ]; then
				echo "[4/4] 按 --exclude 跳过 $(basename "$jar")"
				skipped=$((skipped + 1))
				continue
			fi
		fi

		cp -f "$jar" "$MODS_DIR/"
		count=$((count + 1))
	done

	echo "[4/4] 从 $MODS_SRC 拷入 $count 个模组（跳过 $skipped 个版本不匹配的）"
else
	echo "[4/4] 没给模组来源目录，$MODS_DIR 保持原样（$(ls "$MODS_DIR"/*.jar 2>/dev/null | wc -l | tr -d ' ') 个 jar）"
fi

echo
echo "==================================================================="
echo " 准备完成。跑一次冒烟："
echo
if [ "$CLIENT" -eq 1 ]; then
	echo "   ./run/client-legacy.sh $MC_VERSION"
else
	echo "   ./run/smoke-legacy.sh $MC_VERSION"
fi

echo
echo "   命名空间：$NAMESPACE（选项 A 默认零重映射，无需带参数）"
if [ "$NAMESPACE" = "intermediary" ]; then
	echo "   若将来要在混淆版本上跑 NeoForge/Forge 混装，需重新准备 named 夹具："
	echo "        ./run/prepare-legacy.sh $MC_VERSION ... --namespace=named"
	echo "   然后 ./run/smoke-legacy.sh $MC_VERSION 会按夹具自动走 named。"
else
	echo "   注意：named 是显式实验/混装路径；老模组可能报 ClassInfo / refmap / 注入描述符错误"
	echo "        （2026-11-08 实测：1.14.4 的 35 个模组在 intermediary 下才全量跑通）。"
fi
echo
echo " （等价于： UL_GAME_DIR=$GAME_DIR UL_MODS_DIR=$MODS_DIR \\"
echo "     UL_NAMESPACE=$NAMESPACE ./run/smoke.sh --matrix=legacy-$MC_VERSION）"
echo "==================================================================="

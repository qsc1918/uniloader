#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# 判断一个模组 jar **到底是给哪个 MC 版本**编译的
#
#   ./run/check-mod-version.sh <jar>                    # 用默认候选版本
#   ./run/check-mod-version.sh <jar> 1.14 1.14.4 1.16.5 # 指定候选
#
# 为什么需要这个工具：
#   模组文件名里的版本号**不可信**，CurseForge/Modrinth 的标签也可能不准
#   （实测：dynamic-fps-1.0.2.jar 放在 1.14 的下载里，但它是 1.14.4 的构建——
#   它的 mixin 指向 method_20813，而这个方法只存在于 1.14.4 的 class_1255 里）。
#   1.14 与 1.14.4 的混淆表完全不同，拿错版本的模组跑，报错会是
#   「某个方法在目标类里找不到」，看起来像加载器的问题。
#
# 原理：Fabric 模组是对着 intermediary 名字编译的（class_1255 / method_20813…），
#   而每个版本的 intermediary 都不一样。把 jar 里的 MC 名字抠出来，拿去各版本的
#   映射里查，**命中率最高的那个就是它真正对应的版本**。这是可验证的判定，不是猜。
#
# 本机若走 HTTP 代理：export https_proxy=http://127.0.0.1:7897
# -----------------------------------------------------------------------------

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UL_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

JAR="${1:-}"

if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
	echo "用法：./run/check-mod-version.sh <模组jar> [候选MC版本...]" >&2
	echo "例如：./run/check-mod-version.sh /d/work2/1.14fabric/dynamic-fps-1.0.2.jar 1.14 1.14.4" >&2
	exit 2
fi

shift

# 默认候选：覆盖 1.x 的主要时代 + 26.3
if [ "$#" -eq 0 ]; then
	set -- 1.14 1.14.1 1.14.2 1.14.3 1.14.4 1.15.2 1.16.5 1.18.2 1.21.11
fi

exec python "$UL_ROOT/tooling/mod-matrix/check-mod-version.py" "$JAR" "$@" \
	--cache "$UL_ROOT/build/intermediary-cache"

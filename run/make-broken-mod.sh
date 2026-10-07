#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# T1.6 — 造一个「初始化必炸」的模组，用来验证诊断归因与三态报告
#
#   ./run/make-broken-mod.sh        # 产出 mods-broken/ul-broken.jar
#   UL_MODS_DIR=run/mods-broken ./run/smoke.sh --matrix=broken --expect=crash
#
# 为什么要有这个：mods-report.txt 只有在出事的时候才存在，而「出事」不能靠祈祷。
# 这个 jar 的 onInitialize() 里就一句 throw，是给诊断用的对照组。
#
# 产物不进版本库（.gitignore 已忽略 run/mods-broken）。
# -----------------------------------------------------------------------------

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# env.sh 里的 try_java 靠「返回 1」表示找到了 Java，所以在 -e 下必须先关掉再 source，
# 否则脚本会在刚找到 Java 的那一刻静默退出。
# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh" > /dev/null
set -e

require_loader_jar

OUT="$SCRIPT_DIR/mods-broken"
SRC="$OUT/src"
CLASSES="$OUT/classes"

rm -rf "$OUT"
mkdir -p "$SRC/ulbroken" "$CLASSES"

cat > "$SRC/fabric.mod.json" <<'JSON'
{
  "schemaVersion": 1,
  "id": "ul-broken",
  "version": "1.0.0",
  "name": "UL Broken",
  "description": "故意在初始化时抛异常，用于验证 T1.6 的诊断归因与三态报告",
  "entrypoints": {
    "main": [ "ulbroken.Broken" ]
  }
}
JSON

cat > "$SRC/ulbroken/Broken.java" <<'JAVA'
package ulbroken;

import net.fabricmc.api.ModInitializer;

/**
 * 只做一件事：在初始化时抛异常。
 *
 * 这是给诊断用的对照组——它让「mods-report.txt 里出现了 [FAILED] + 阶段 + 原因」
 * 成为一条可重复执行的断言，而不是一句「应该会写」。
 */
public final class Broken implements ModInitializer {
	@Override
	public void onInitialize() {
		throw new IllegalStateException("故意抛的：用于验证 T1.6 的诊断归因与三态报告");
	}
}
JAVA

JAVAC="$(dirname "$JAVA")/javac"
if [ ! -x "$JAVAC" ]; then
	JAVAC="javac"
fi

# --release 17：落到的 class 文件版本远低于内核 ASM 的上限，免得把「验证诊断」
# 变成「验证 ASM 认不认得新 class 文件」。
"$JAVAC" --release 17 -cp "$LOADER_JAR" -d "$CLASSES" "$SRC/ulbroken/Broken.java"

jar --create --file "$OUT/ul-broken.jar" -C "$CLASSES" . -C "$SRC" fabric.mod.json

echo "[UL] 已生成 $OUT/ul-broken.jar"
echo "[UL] 验收：UL_MODS_DIR=$OUT ./run/smoke.sh --matrix=broken --expect=crash"

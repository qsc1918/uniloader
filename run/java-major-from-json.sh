#!/usr/bin/env bash
# 从官方版本 json 里读出这个 MC 版本要求的 Java 主版本。
#
#   ./run/java-major-from-json.sh run/game-1.14/version.json   → 8
#
# 单独一个脚本而不是内嵌在 env.sh 里：env.sh 是每个 run/*.sh 都要 source 的，
# 里面套一层 heredoc 会让「shell 里嵌 python」变成维护噩梦（真踩过：
# 内层 heredoc 的结束符把外层提前结束了，整个补丁静默没生效）。
#
# 读不到就退出码 1、不输出任何东西——由调用方决定回退策略。
set -uo pipefail

JSON="${1:-}"

[ -n "$JSON" ] && [ -f "$JSON" ] || exit 1

python - "$JSON" <<'EOF'
import json, sys

try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    sys.exit(1)

major = (data.get("javaVersion") or {}).get("majorVersion")

if not major:
    sys.exit(1)

print(major)
EOF

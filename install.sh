#!/usr/bin/env bash
# Install pycharm-vcs-patch.jar into the PyCharm config and wire it into
# pycharm64.vmoptions. Idempotent — safe to re-run.
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
JAR_SRC="$HERE/dist/pycharm-vcs-patch.jar"

CFG="${CFG:-$HOME/.config/JetBrains/PyCharm2026.1}"
AGENT_DIR="$CFG/agents"
VMO="$CFG/pycharm64.vmoptions"
JAR_DEST="$AGENT_DIR/pycharm-vcs-patch.jar"

if [[ ! -f "$JAR_SRC" ]]; then
  echo "error: $JAR_SRC missing — run ./build.sh first" >&2
  exit 1
fi
if [[ ! -d "$CFG" ]]; then
  echo "error: PyCharm config dir $CFG missing" >&2
  echo "Set CFG=/path/to/PyCharm<version> in env if you use a different one." >&2
  exit 1
fi

mkdir -p "$AGENT_DIR"
cp "$JAR_SRC" "$JAR_DEST"
echo "[install] copied $JAR_DEST"

# Remove any pre-existing -javaagent line for this JAR (idempotency)
if [[ -f "$VMO" ]]; then
  cp "$VMO" "$VMO.before-install-$(date +%Y%m%d-%H%M%S).bak"
  grep -v "pycharm-vcs-patch" "$VMO" > "$VMO.tmp" && mv "$VMO.tmp" "$VMO"
fi

# Append the agent line
echo "-javaagent:$JAR_DEST" >> "$VMO"
echo "[install] appended -javaagent: line to $VMO"
echo
echo "Current vmoptions:"
cat "$VMO"
echo
echo "[install] done. Restart PyCharm for the agent to take effect."
echo "Watch for the markers in PyCharm stdout or in:"
echo "  $HOME/.cache/JetBrains/PyCharm2026.1/log/idea.log   (grep VcsPatchAgent)"

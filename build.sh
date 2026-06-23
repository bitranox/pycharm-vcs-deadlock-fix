#!/usr/bin/env bash
# Build pycharm-vcs-patch.jar from src/VcsPatchAgent.java.
# Uses PyCharm's bundled JBR javac and ASM that ships with the Junie plugin.
# Output: dist/pycharm-vcs-patch.jar
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

JAVAC="${JAVAC:-$HOME/.local/share/JetBrains/Toolbox/apps/pycharm-community/jbr/bin/javac}"
ASM_JAR="${ASM_JAR:-$HOME/.local/share/JetBrains/PyCharm2026.1/ej/lib/asm-9.6.jar}"

if [[ ! -x "$JAVAC" ]]; then
  echo "error: javac not found at $JAVAC" >&2
  echo "Set JAVAC=/path/to/javac in the environment if PyCharm lives elsewhere." >&2
  exit 1
fi

if [[ ! -f "$ASM_JAR" ]]; then
  echo "error: ASM not found at $ASM_JAR" >&2
  echo "Looking for an alternate copy..." >&2
  alt="$(find "$HOME/.local/share/JetBrains" -name 'asm-9*.jar' 2>/dev/null | head -1)"
  if [[ -n "$alt" ]]; then
    echo "found alternate: $alt" >&2
    ASM_JAR="$alt"
  else
    echo "Set ASM_JAR=/path/to/asm-9.x.jar in the environment." >&2
    exit 1
  fi
fi

echo "[build] javac:  $JAVAC"
echo "[build] asm:    $ASM_JAR"
"$JAVAC" -version

rm -rf build
mkdir -p build/META-INF dist

# Unpack ASM into build/ so the compiler doesn't treat it as a named module
# (which would block access to java.lang.instrument from the unnamed module).
unzip -oq "$ASM_JAR" -d build
rm -f build/module-info.class build/META-INF/MANIFEST.MF
find build/META-INF -maxdepth 1 -type f \( -name "*.SF" -o -name "*.DSA" -o -name "*.RSA" \) -delete 2>/dev/null || true

"$JAVAC" -d build -cp build src/VcsPatchAgent.java

cat > build/META-INF/MANIFEST.MF <<'EOF'
Manifest-Version: 1.0
Premain-Class: dev.bx.pycharm.VcsPatchAgent
Can-Retransform-Classes: true
Can-Redefine-Classes: true
EOF

cd build
rm -f "$HERE/dist/pycharm-vcs-patch.jar"
zip -rq "$HERE/dist/pycharm-vcs-patch.jar" META-INF/ dev/ org/
cd "$HERE"

echo
ls -la dist/pycharm-vcs-patch.jar
echo
echo "Sanity check — manifest:"
unzip -p dist/pycharm-vcs-patch.jar META-INF/MANIFEST.MF

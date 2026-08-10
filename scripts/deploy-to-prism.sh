#!/usr/bin/env bash
# Rebuilds the mod and drops it into the Prism Launcher instance, replacing any older copy.
#
#   ./scripts/deploy-to-prism.sh                 # default instance below
#   ./scripts/deploy-to-prism.sh "My Instance"   # some other instance folder
#
# Prism reads mods from disk at launch, so there is no need to restart the launcher itself —
# just close the game, run this, and start it again.

set -euo pipefail

INSTANCE="${1:-XetiusMap-26.2}"
PRISM="/mnt/c/Users/$(ls /mnt/c/Users | grep -vwE 'All Users|Default|Default User|Public|desktop.ini' | head -1)/AppData/Roaming/PrismLauncher"
MODS="$PRISM/instances/$INSTANCE/minecraft/mods"

if [ ! -d "$MODS" ]; then
    echo "No mods folder at: $MODS" >&2
    echo "Available instances:" >&2
    ls "$PRISM/instances" 2>/dev/null | sed 's/^/  /' >&2
    exit 1
fi

cd "$(dirname "$0")/.."
./gradlew :fabric:build --console=plain -q

JAR=$(ls -t fabric/build/libs/*.jar | grep -v sources | head -1)
rm -f "$MODS"/xetiusmap-fabric-*.jar
cp "$JAR" "$MODS/"

echo "Deployed $(basename "$JAR") -> $INSTANCE"
ls -la "$MODS"

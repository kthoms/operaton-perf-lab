#!/usr/bin/env bash
# Installs (or re-installs) the perflab launchd agent. Idempotent: an already loaded agent is
# booted out first. Requires a built jar: mvn -DskipTests package
#
# Environment overrides: JAVA_HOME (default: java 25 from /usr/libexec/java_home),
# PERFLAB_CONFIG (default: ~/.config/perflab/perflab.yml), DOCKER_HOST (default: Colima socket).
set -euo pipefail

LABEL="io.github.kthoms.perflab"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE="$REPO/ops/launchd/$LABEL.plist"
JAR="$REPO/runner/target/perflab.jar"
CONFIG="${PERFLAB_CONFIG:-$HOME/.config/perflab/perflab.yml}"
LOGDIR="$HOME/Library/Logs/perflab"
AGENTS="$HOME/Library/LaunchAgents"
TARGET="$AGENTS/$LABEL.plist"
DOMAIN="gui/$(id -u)"

JAVA_HOME_DIR="${JAVA_HOME:-$(/usr/libexec/java_home -v 25)}"
JAVA="$JAVA_HOME_DIR/bin/java"
DOCKER_HOST_VALUE="${DOCKER_HOST:-unix://$HOME/.colima/default/docker.sock}"

[ -x "$JAVA" ] || { echo "java not found at $JAVA" >&2; exit 1; }
[ -f "$JAR" ] || { echo "jar missing: $JAR (run: mvn -DskipTests package)" >&2; exit 1; }
[ -f "$CONFIG" ] || { echo "config missing: $CONFIG" >&2; exit 1; }

# XML-escape the value, then escape what is special in a sed replacement (delimiter is |)
esc() {
  printf '%s' "$1" \
    | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' \
    | sed -e 's/[\\&|]/\\&/g'
}

mkdir -p "$LOGDIR" "$AGENTS"
sed -e "s|@JAVA@|$(esc "$JAVA")|g" \
    -e "s|@JAR@|$(esc "$JAR")|g" \
    -e "s|@CONFIG@|$(esc "$CONFIG")|g" \
    -e "s|@LOGDIR@|$(esc "$LOGDIR")|g" \
    -e "s|@DOCKER_HOST@|$(esc "$DOCKER_HOST_VALUE")|g" \
    "$TEMPLATE" > "$TARGET"
plutil -lint "$TARGET"

if launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1; then
  launchctl bootout "$DOMAIN/$LABEL"
fi
launchctl bootstrap "$DOMAIN" "$TARGET"
echo "Installed and started $LABEL (logs: $LOGDIR)"

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
AGENTS="${PERFLAB_AGENTS_DIR:-$HOME/Library/LaunchAgents}"
TARGET="$AGENTS/$LABEL.plist"
DOMAIN="gui/$(id -u)"

JAVA_HOME_DIR="${JAVA_HOME:-$(/usr/libexec/java_home -v 25)}"
JAVA="$JAVA_HOME_DIR/bin/java"
# grafana_ro password: exported variable wins, else infra/.env (same precedence as compose).
# The runner applies it on start. One pair of surrounding quotes, CR and whitespace are stripped.
ENV_FILE="${PERFLAB_ENV_FILE:-$REPO/infra/.env}"
GRAFANA_RO_PASSWORD="${PERFLAB_GRAFANA_RO_PASSWORD:-}"
if [ -z "$GRAFANA_RO_PASSWORD" ] && [ -f "$ENV_FILE" ]; then
  line="$(grep -E '^PERFLAB_GRAFANA_RO_PASSWORD=' "$ENV_FILE" | tail -n1 || true)"
  value="${line#*=}"
  value="${value%$'\r'}"
  value="$(printf '%s' "$value" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  case "$value" in
    \"*\") value="${value#\"}"; value="${value%\"}" ;;
    \'*\') value="${value#\'}"; value="${value%\'}" ;;
  esac
  GRAFANA_RO_PASSWORD="$value"
  if [ -n "$line" ] && [ -z "$value" ]; then
    echo "PERFLAB_GRAFANA_RO_PASSWORD is empty in $ENV_FILE" >&2; exit 1
  fi
fi
GRAFANA_RO_PASSWORD="${GRAFANA_RO_PASSWORD:-grafana-ro}"
case "$GRAFANA_RO_PASSWORD" in
  *\'*|*[[:space:]]*) echo "PERFLAB_GRAFANA_RO_PASSWORD must not contain single quotes or whitespace" >&2; exit 1 ;;
esac

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
umask 077   # the plist contains the grafana_ro password
sed -e "s|@JAVA@|$(esc "$JAVA")|g" \
    -e "s|@JAR@|$(esc "$JAR")|g" \
    -e "s|@CONFIG@|$(esc "$CONFIG")|g" \
    -e "s|@LOGDIR@|$(esc "$LOGDIR")|g" \
    -e "s|@GRAFANA_RO_PASSWORD@|$(esc "$GRAFANA_RO_PASSWORD")|g" \
    -e "s|@DOCKER_HOST@|$(esc "$DOCKER_HOST_VALUE")|g" \
    "$TEMPLATE" > "$TARGET"
plutil -lint "$TARGET"
chmod 600 "$TARGET"

# PERFLAB_DRY_RUN=1: only render the plist (for checking), do not touch launchd
[ -z "${PERFLAB_DRY_RUN:-}" ] || { echo "Rendered $TARGET (dry run)"; exit 0; }

if launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1; then
  launchctl bootout "$DOMAIN/$LABEL"
  # bootout is asynchronous: wait (max ~5 s) until the service is gone before bootstrapping
  for _ in $(seq 1 25); do
    launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1 || break
    sleep 0.2
  done
fi
launchctl bootstrap "$DOMAIN" "$TARGET"
echo "Installed and started $LABEL (logs: $LOGDIR)"

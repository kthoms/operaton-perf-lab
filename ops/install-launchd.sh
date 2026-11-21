#!/usr/bin/env bash
# Installs (or re-installs) the perflab launchd agent. Idempotent: an already loaded agent is
# booted out first. Requires a built jar: mvn -DskipTests package
#
# Environment overrides: JAVA_HOME (default: java 25 from /usr/libexec/java_home),
# PERFLAB_CONFIG (default: ~/.config/perflab/perflab.yml), DOCKER_HOST (default: the Colima socket
# under $COLIMA_HOME, else ~/.colima). A non-empty COLIMA_HOME is also put into the plist, so docker
# and colima commands of the daemon find the moved Colima home (prefer a symlink of ~/.colima).
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
# grafana_ro password for the runner: exported variable wins, else the key in the env file
# (infra/.env, same precedence as compose). One pair of surrounding quotes, CR and whitespace are
# stripped. If neither source has it, the plist gets no entry and the runner leaves the DB
# password unchanged.
GRAFANA_RO_PASSWORD=""
HAVE_RO_PASSWORD=""
if [ "${PERFLAB_GRAFANA_RO_PASSWORD+x}" = x ]; then
  GRAFANA_RO_PASSWORD="$PERFLAB_GRAFANA_RO_PASSWORD"
  HAVE_RO_PASSWORD=1
  RO_SOURCE="exported PERFLAB_GRAFANA_RO_PASSWORD"
else
  if [ -n "${PERFLAB_ENV_FILE:-}" ]; then
    ENV_FILE="$PERFLAB_ENV_FILE"
    [ -f "$ENV_FILE" ] || { echo "PERFLAB_ENV_FILE not found: $ENV_FILE" >&2; exit 1; }
  else
    ENV_FILE="$REPO/infra/.env"
  fi
  RO_SOURCE="$ENV_FILE"
  if [ -f "$ENV_FILE" ] && grep -qE '^PERFLAB_GRAFANA_RO_PASSWORD=' "$ENV_FILE"; then
    line="$(grep -E '^PERFLAB_GRAFANA_RO_PASSWORD=' "$ENV_FILE" | tail -n1)"
    value="${line#*=}"
    value="${value%$'\r'}"
    value="$(printf '%s' "$value" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    case "$value" in
      \"*\") value="${value#\"}"; value="${value%\"}" ;;
      \'*\') value="${value#\'}"; value="${value%\'}" ;;
    esac
    GRAFANA_RO_PASSWORD="$value"
    HAVE_RO_PASSWORD=1
  fi
fi
if [ -n "$HAVE_RO_PASSWORD" ]; then
  [ -n "$GRAFANA_RO_PASSWORD" ] || { echo "PERFLAB_GRAFANA_RO_PASSWORD is empty ($RO_SOURCE)" >&2; exit 1; }
  case "$GRAFANA_RO_PASSWORD" in
    *\'*|*[[:space:]]*) echo "PERFLAB_GRAFANA_RO_PASSWORD must not contain single quotes or whitespace" >&2; exit 1 ;;
    \"*|*\") echo "warning: PERFLAB_GRAFANA_RO_PASSWORD has an unmatched double quote; it is used literally" >&2 ;;
  esac
else
  echo "note: PERFLAB_GRAFANA_RO_PASSWORD not found (exported or in $ENV_FILE): not set in the plist, the runner leaves the grafana_ro password unchanged" >&2
fi

COLIMA_HOME_VALUE="${COLIMA_HOME:-}"
case "$COLIMA_HOME_VALUE" in
  ""|/*) ;;
  *) echo "COLIMA_HOME must be an absolute path: $COLIMA_HOME_VALUE" >&2; exit 1 ;;
esac
DOCKER_HOST_VALUE="${DOCKER_HOST:-unix://${COLIMA_HOME_VALUE:-$HOME/.colima}/default/docker.sock}"

[ -x "$JAVA" ] || { echo "java not found at $JAVA" >&2; exit 1; }
[ -f "$JAR" ] || { echo "jar missing: $JAR (run: mvn -DskipTests package)" >&2; exit 1; }
[ -f "$CONFIG" ] || { echo "config missing: $CONFIG" >&2; exit 1; }

# XML-escape the value, then escape what is special in a sed replacement (delimiter is |)
esc() {
  printf '%s' "$1" \
    | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' \
    | sed -e 's/[\\&|]/\\&/g'
}


DRY_RUN=""
[ "${PERFLAB_DRY_RUN:-}" = 1 ] && DRY_RUN=1   # render to a temp file only, never touch launchd

umask 077   # the plist may contain the grafana_ro password
mkdir -p "$LOGDIR"
if [ -n "$DRY_RUN" ]; then
  OUT="$(mktemp "${TMPDIR:-/tmp}/$LABEL.XXXXXX")"
else
  mkdir -p "$AGENTS"
  OUT="$(mktemp "$AGENTS/.$LABEL.XXXXXX")"
fi
trap '[ ! -e "$OUT" ] || rm -f "$OUT"' EXIT
sed -e "s|@JAVA@|$(esc "$JAVA")|g" \
    -e "s|@JAR@|$(esc "$JAR")|g" \
    -e "s|@CONFIG@|$(esc "$CONFIG")|g" \
    -e "s|@LOGDIR@|$(esc "$LOGDIR")|g" \
    -e "s|@DOCKER_HOST@|$(esc "$DOCKER_HOST_VALUE")|g" \
    "$TEMPLATE" > "$OUT"
if [ -n "$COLIMA_HOME_VALUE" ]; then
  sed -i.bak -e "s|@COLIMA_HOME@|$(esc "$COLIMA_HOME_VALUE")|g" "$OUT"
else
  sed -i.bak -e '/<key>COLIMA_HOME<\/key>/d' -e '/@COLIMA_HOME@/d' "$OUT"
fi
if [ -n "$HAVE_RO_PASSWORD" ]; then
  sed -i.bak -e "s|@GRAFANA_RO_PASSWORD@|$(esc "$GRAFANA_RO_PASSWORD")|g" "$OUT"
else
  sed -i.bak -e '/PERFLAB_GRAFANA_RO_PASSWORD<\/key>/d' -e '/@GRAFANA_RO_PASSWORD@/d' "$OUT"
fi
rm -f "$OUT.bak"
chmod 600 "$OUT"
plutil -lint "$OUT"

if [ -n "$DRY_RUN" ]; then
  trap - EXIT
  echo "Dry run: rendered to $OUT (mode 600, delete it when done); launchd and $TARGET untouched"
  exit 0
fi
mv -f "$OUT" "$TARGET"
trap - EXIT

if launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1; then
  launchctl bootout "$DOMAIN/$LABEL"
  # bootout is asynchronous: wait (max ~5 s) until the service is gone before bootstrapping
  for _ in $(seq 1 25); do
    launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1 || break
    sleep 0.2
  done
fi
echo "Installing $TARGET"
launchctl bootstrap "$DOMAIN" "$TARGET"
echo "Installed and started $LABEL (logs: $LOGDIR)"

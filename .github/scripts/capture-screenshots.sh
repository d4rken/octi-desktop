#!/usr/bin/env bash
# Orchestrate the screenshot capture sequence against a running desktop instance.
#
# Preconditions (set up by the calling workflow):
#   - Xvfb is running on $DISPLAY
#   - sync-server is reachable at $SYNC_SERVER_URL
#   - The desktop app has been launched with --enable-debug-rpc --debug-rpc-port $RPC_PORT,
#     and its stderr has been tee'd to $APP_LOG so we can extract the token banner.
#   - PeerSeeder has written a LinkingData blob to $LINK_FILE.
#   - Screenshots will land in $OUT_DIR.
#
# All steps are written so a single failure aborts the workflow with a useful message.

set -euo pipefail

: "${SYNC_SERVER_URL:?SYNC_SERVER_URL must be set}"
: "${RPC_PORT:?RPC_PORT must be set}"
: "${APP_LOG:?APP_LOG must be set}"
: "${LINK_FILE:?LINK_FILE must be set}"
: "${OUT_DIR:?OUT_DIR must be set}"

mkdir -p "$OUT_DIR"

RPC_URL="http://127.0.0.1:${RPC_PORT}"

# --- Wait for debug RPC to come up ---
echo "Waiting for debug RPC at ${RPC_URL}..."
for attempt in $(seq 1 60); do
  if curl -sf "${RPC_URL}/dev/health" >/dev/null 2>&1; then
    echo "  debug RPC up after ${attempt}s"
    break
  fi
  sleep 1
  if [[ $attempt -eq 60 ]]; then
    echo "::error::debug RPC did not respond within 60s" >&2
    tail -200 "$APP_LOG" >&2 || true
    exit 1
  fi
done

# --- Extract the debug RPC token from the app's banner ---
TOKEN=$(grep -E "DEBUG_RPC url=.* token=" "$APP_LOG" | tail -1 | sed -E 's/.*token=([A-Za-z0-9_-]+).*/\1/')
if [[ -z "${TOKEN:-}" ]]; then
  echo "::error::could not find debug RPC token in ${APP_LOG}" >&2
  tail -200 "$APP_LOG" >&2 || true
  exit 1
fi

auth() {
  curl -sf -H "X-Debug-Token: ${TOKEN}" "$@"
}

action() {
  local name="$1"; local body="$2"
  auth -X POST -H "Content-Type: application/json" --data "$body" "${RPC_URL}/dev/action/${name}"
}

snap() {
  local name="$1"
  local path="${OUT_DIR}/${name}.png"
  # Give Compose a moment to settle after navigation/state changes.
  sleep 2
  auth "${RPC_URL}/dev/screen.png" --output "$path"
  if [[ ! -s "$path" ]]; then
    echo "::error::screenshot ${name} was empty" >&2
    exit 1
  fi
  echo "  captured ${name} ($(stat -c%s "$path") bytes)"
}

# --- 1. Linking screen (pre-link). ---
echo "Step 1: capture linking screen"
action "navigation.go" '{"route":"linking"}' >/dev/null
snap "01_linking"

# --- 2. Link the desktop to the phantom peer. ---
echo "Step 2: submit share code"
LINK_CODE=$(cat "$LINK_FILE")
LINK_PAYLOAD=$(python3 -c 'import json,sys; print(json.dumps({"code": sys.argv[1]}))' "$LINK_CODE")
RESULT=$(action "linking.submit" "$LINK_PAYLOAD")
echo "  linking result: $RESULT"
if ! grep -q '"result":"Success"' <<<"$RESULT"; then
  echo "::error::linking did not succeed" >&2
  exit 1
fi

# --- 3. Wait for dashboard load (meta + device list ready). ---
echo "Step 3: wait for dashboard"
for attempt in $(seq 1 30); do
  STATE=$(auth "${RPC_URL}/dev/state")
  if grep -q '"screen":"dashboard"' <<<"$STATE" && grep -q '"deviceListLoadState":"Ok"' <<<"$STATE"; then
    echo "  dashboard ready after ${attempt}s"
    break
  fi
  sleep 1
done
# Extra settle time for tiles to fill in (meta module fetch + per-tile rendering).
sleep 4

# --- 4. Dashboard — light + dark. ---
echo "Step 4: dashboard screenshots"
action "settings.themeMode" '{"mode":"LIGHT"}' >/dev/null
snap "02_dashboard_light"

action "settings.themeMode" '{"mode":"DARK"}' >/dev/null
snap "03_dashboard_dark"

# Reset for the remaining screens.
action "settings.themeMode" '{"mode":"LIGHT"}' >/dev/null

# --- 5. Files screen (phantom peer). ---
echo "Step 5: files screen"
STATE=$(auth "${RPC_URL}/dev/state")
PEER_ID=$(python3 -c '
import json, sys
state = json.loads(sys.stdin.read())
for d in state.get("knownDevices", []):
    if d.get("platform","").startswith("android"):
        print(d["deviceId"]); sys.exit(0)
sys.exit(1)
' <<<"$STATE")
action "navigation.go" "$(python3 -c 'import json,sys; print(json.dumps({"route":"files","deviceId":sys.argv[1]}))' "$PEER_ID")" >/dev/null
snap "04_files"

# --- 6. Settings. ---
echo "Step 6: settings screen"
action "navigation.go" '{"route":"settings"}' >/dev/null
snap "05_settings"

echo "All screenshots captured into ${OUT_DIR}:"
ls -la "$OUT_DIR"

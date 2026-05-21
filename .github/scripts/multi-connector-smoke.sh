#!/usr/bin/env bash
# Runtime multi-connector smoke driver for CI.
#
# Preconditions:
#   - The desktop app is already running with --enable-debug-rpc on $RPC_PORT.
#   - $APP_LOG contains the desktop stdout/stderr stream so we can extract the debug token.
#   - In link mode, $LINK_FILE_A and $LINK_FILE_B contain LinkingData blobs for two servers.
#
# The script intentionally asserts debug JSON state instead of screenshots. It verifies the
# runtime graph, persistence, device-list merge, and meta fan-out without binding the test to UI
# pixels.

set -euo pipefail

: "${RPC_PORT:?RPC_PORT must be set}"
: "${APP_LOG:?APP_LOG must be set}"

RPC_URL="http://127.0.0.1:${RPC_PORT}"
STATE_DIR="${STATE_DIR:-${OUT_DIR:-build/multi-connector-smoke/states}}"
SKIP_LINK="${MULTI_CONNECTOR_SKIP_LINK:-0}"
PEER_LABEL_A="${PEER_LABEL_A:-Pixel-A}"
PEER_LABEL_B="${PEER_LABEL_B:-Pixel-B}"
DESKTOP_LABEL="${DESKTOP_LABEL:-Multi Smoke Desktop}"

if [[ "$SKIP_LINK" != "1" ]]; then
  : "${LINK_FILE_A:?LINK_FILE_A must be set unless MULTI_CONNECTOR_SKIP_LINK=1}"
  : "${LINK_FILE_B:?LINK_FILE_B must be set unless MULTI_CONNECTOR_SKIP_LINK=1}"
fi

mkdir -p "$STATE_DIR"

TOKEN=""

wait_for_debug_rpc() {
  echo "Waiting for debug RPC at ${RPC_URL}..."
  for attempt in $(seq 1 60); do
    if curl -sf "${RPC_URL}/dev/health" >/dev/null 2>&1; then
      echo "  debug RPC up after ${attempt}s"
      return 0
    fi
    sleep 1
  done

  echo "::error::debug RPC did not respond within 60s" >&2
  tail -200 "$APP_LOG" >&2 || true
  exit 1
}

read_token() {
  echo "Reading debug RPC token from ${APP_LOG}..."
  for _ in $(seq 1 30); do
    local candidates
    candidates=$(
      grep -E "DEBUG_RPC url=.* token=" "$APP_LOG" 2>/dev/null \
        | sed -E 's/.*token=([A-Za-z0-9_-]+).*/\1/' \
        | tac \
        || true
    )
    while IFS= read -r candidate; do
      [[ -n "$candidate" ]] || continue
      if curl -sf -H "X-Debug-Token: ${candidate}" "${RPC_URL}/dev/state" >/dev/null 2>&1; then
        TOKEN="$candidate"
        echo "  token accepted"
        return 0
      fi
    done <<<"$candidates"
    sleep 1
  done

  echo "::error::could not find a valid debug RPC token in ${APP_LOG}" >&2
  tail -200 "$APP_LOG" >&2 || true
  exit 1
}

auth() {
  curl -sf -H "X-Debug-Token: ${TOKEN}" "$@"
}

action() {
  local name="$1"
  local body="$2"
  auth -X POST -H "Content-Type: application/json" --data "$body" "${RPC_URL}/dev/action/${name}"
}

json_link_payload() {
  local code="$1"
  python3 -c 'import json, sys; print(json.dumps({"code": sys.argv[1]}))' "$code"
}

submit_link() {
  local label="$1"
  local link_file="$2"
  local code payload result
  code=$(cat "$link_file")
  payload=$(json_link_payload "$code")
  result=$(action "linking.submit" "$payload")
  echo "  ${label} linking result: ${result}"
  python3 - "$result" <<'PY'
import json
import sys

payload = json.loads(sys.argv[1])
result = payload.get("result", {}).get("result")
if result != "Success":
    print(f"linking did not succeed: {result}", file=sys.stderr)
    sys.exit(1)
PY
}

validate_state() {
  local state_file="$1"
  python3 - "$state_file" "$PEER_LABEL_A" "$PEER_LABEL_B" <<'PY'
import json
import sys

state_path, label_a, label_b = sys.argv[1:4]
with open(state_path, "r", encoding="utf-8") as fh:
    state = json.load(fh)

errors = []
connectors = state.get("connectors") or []
connector_ids = {c.get("id") for c in connectors if c.get("id")}

if state.get("screen") != "dashboard":
    errors.append(f"screen is {state.get('screen')!r}, expected 'dashboard'")
if len(connectors) != 2:
    errors.append(f"connector count is {len(connectors)}, expected 2")

for connector in connectors:
    cid = connector.get("id", "<missing-id>")
    if connector.get("deviceListLoadState") != "Ok":
        errors.append(f"{cid} deviceListLoadState is {connector.get('deviceListLoadState')!r}")
    if connector.get("lastMetaWriteSuccessAt") in (None, ""):
        errors.append(f"{cid} has no lastMetaWriteSuccessAt")
    if connector.get("paused") is True:
        errors.append(f"{cid} is paused")

known = state.get("knownDevices") or []
desktop_id = state.get("deviceId")
desktop_rows = [d for d in known if d.get("deviceId") == desktop_id]
if len(desktop_rows) != 1:
    errors.append(f"desktop rows for {desktop_id!r}: {len(desktop_rows)}, expected 1")
else:
    desktop_sources = set(desktop_rows[0].get("sources") or [])
    if desktop_sources != connector_ids:
        errors.append(
            "desktop sources are "
            f"{sorted(desktop_sources)}, expected {sorted(connector_ids)}"
        )

peer_sources = []
for label in (label_a, label_b):
    rows = [
        d for d in known
        if d.get("label") == label and (d.get("platform") or "").startswith("android")
    ]
    if len(rows) != 1:
        errors.append(f"peer rows for label {label!r}: {len(rows)}, expected 1")
        continue
    sources = set(rows[0].get("sources") or [])
    peer_sources.append(sources)
    if len(sources) != 1:
        errors.append(f"peer {label!r} sources are {sorted(sources)}, expected exactly one")
    if not sources.issubset(connector_ids):
        errors.append(f"peer {label!r} sources are not a subset of connector ids")

if len(peer_sources) == 2 and len(set().union(*peer_sources)) != 2:
    errors.append("the two seeded peers did not resolve to two distinct connector sources")

if len(known) < 3:
    errors.append(f"knownDevices count is {len(known)}, expected at least 3")

if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)

print(
    "state ok: "
    f"{len(connectors)} connectors, {len(known)} known devices, desktop sources={sorted(connector_ids)}"
)
PY
}

wait_for_state() {
  local name="$1"
  local state_file="${STATE_DIR}/${name}.json"
  local errors_file="${STATE_DIR}/${name}.errors"

  echo "Waiting for multi-connector state (${name})..."
  for attempt in $(seq 1 90); do
    if auth "${RPC_URL}/dev/state" >"$state_file" 2>"$errors_file"; then
      if validate_state "$state_file" >"${STATE_DIR}/${name}.ok" 2>"$errors_file"; then
        cat "${STATE_DIR}/${name}.ok"
        echo "  state ready after ${attempt}s"
        return 0
      fi
    fi
    sleep 1
  done

  echo "::error::multi-connector state did not become ready (${name})" >&2
  echo "--- validation errors ---" >&2
  cat "$errors_file" >&2 || true
  echo "--- last state ---" >&2
  cat "$state_file" >&2 || true
  echo >&2
  echo "--- app log tail ---" >&2
  tail -200 "$APP_LOG" >&2 || true
  exit 1
}

wait_for_debug_rpc
read_token

if [[ "$SKIP_LINK" == "1" ]]; then
  echo "Verifying existing linked connectors after restart..."
else
  echo "Linking desktop to both sync-server accounts..."
  action "settings.deviceLabel" "$(python3 -c 'import json, sys; print(json.dumps({"label": sys.argv[1]}))' "$DESKTOP_LABEL")" >/dev/null
  submit_link "server A" "$LINK_FILE_A"
  submit_link "server B" "$LINK_FILE_B"
fi

action "dashboard.refresh" '{}' >/dev/null
wait_for_state "$([[ "$SKIP_LINK" == "1" ]] && echo "after-restart" || echo "after-link")"

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

# --- Extended runtime phases (link mode only) ------------------------------------------------
#
# Each phase drives a debug-RPC action and asserts the runtime effect via /dev/state or the
# action's own JSON response. Unlike `action`/`auth` (curl -sf, which hides error bodies), the
# helpers below capture every response under STATE_DIR with `curl -sS` so a 500 from a new
# action surfaces its `{error,message}` payload in the logs.

post_action_capture() {
  # name body outfile -> writes body to outfile, prints HTTP status code on stdout
  local name="$1" body="$2" outfile="$3"
  curl -sS -X POST \
    -H "X-Debug-Token: ${TOKEN}" \
    -H "Content-Type: application/json" \
    --data "$body" \
    -o "$outfile" -w '%{http_code}' \
    "${RPC_URL}/dev/action/${name}"
}

get_state_capture() {
  # outfile -> writes /dev/state to outfile, prints HTTP status code on stdout
  curl -sS -H "X-Debug-Token: ${TOKEN}" -o "$1" -w '%{http_code}' "${RPC_URL}/dev/state"
}

require_ok() {
  # label status_code response_file
  local label="$1" code="$2" file="$3"
  if [[ "$code" != "200" ]]; then
    echo "::error::${label} returned HTTP ${code}" >&2
    cat "$file" >&2 || true
    echo >&2
    exit 1
  fi
}

connector_ids() {
  # state_file -> prints the two connector idStrings, sorted, one per line
  python3 - "$1" <<'PY'
import json, sys
state = json.load(open(sys.argv[1]))
ids = sorted(c["id"] for c in (state.get("connectors") or []))
if len(ids) != 2:
    print(f"expected 2 connectors, got {len(ids)}", file=sys.stderr)
    sys.exit(1)
for cid in ids:
    print(cid)
PY
}

peer_device_id() {
  # state_file label -> prints the android peer's deviceId
  python3 - "$1" "$2" <<'PY'
import json, sys
state = json.load(open(sys.argv[1]))
label = sys.argv[2]
rows = [
    d for d in (state.get("knownDevices") or [])
    if d.get("label") == label and (d.get("platform") or "").startswith("android")
]
if len(rows) != 1:
    print(f"expected exactly one android peer for label {label!r}, got {len(rows)}", file=sys.stderr)
    sys.exit(1)
print(rows[0]["deviceId"])
PY
}

run_pause_phase() {
  echo "== Pause phase: pause one connector, assert the other keeps running and cached devices survive =="
  local state_file="${STATE_DIR}/pause-initial.json"
  local code
  code=$(get_state_capture "$state_file")
  require_ok "GET /dev/state (pause init)" "$code" "$state_file"

  local ids paused_id running_id
  mapfile -t ids < <(connector_ids "$state_file")
  if [[ "${#ids[@]}" -ne 2 ]]; then
    echo "::error::expected 2 connectors before pause phase, got ${#ids[@]}" >&2
    cat "$state_file" >&2 || true
    exit 1
  fi
  paused_id="${ids[0]}"
  running_id="${ids[1]}"
  echo "  pausing ${paused_id}; keeping ${running_id} running"

  local resp="${STATE_DIR}/pause-set.json"
  code=$(post_action_capture "settings.pause" \
    "$(python3 -c 'import json,sys; print(json.dumps({"connectorId": sys.argv[1], "paused": True}))' "$paused_id")" \
    "$resp")
  require_ok "settings.pause(true)" "$code" "$resp"

  local ok=0 attempt
  for attempt in $(seq 1 60); do
    code=$(get_state_capture "${STATE_DIR}/pause-wait.json")
    if [[ "$code" == "200" ]] && validate_paused_state "${STATE_DIR}/pause-wait.json" "$paused_id" "$running_id" \
        >"${STATE_DIR}/pause-wait.ok" 2>"${STATE_DIR}/pause-wait.errors"; then
      cat "${STATE_DIR}/pause-wait.ok"
      ok=1
      break
    fi
    sleep 1
  done
  if [[ "$ok" -ne 1 ]]; then
    echo "::error::paused-connector state never settled" >&2
    cat "${STATE_DIR}/pause-wait.errors" >&2 || true
    cat "${STATE_DIR}/pause-wait.json" >&2 || true
    exit 1
  fi

  echo "  resuming ${paused_id}"
  code=$(post_action_capture "settings.pause" \
    "$(python3 -c 'import json,sys; print(json.dumps({"connectorId": sys.argv[1], "paused": False}))' "$paused_id")" \
    "${STATE_DIR}/pause-resume.json")
  require_ok "settings.pause(false)" "$code" "${STATE_DIR}/pause-resume.json"

  ok=0
  for attempt in $(seq 1 60); do
    code=$(get_state_capture "${STATE_DIR}/pause-resumed.json")
    if [[ "$code" == "200" ]] && validate_resumed_state "${STATE_DIR}/pause-resumed.json" "$paused_id" \
        >/dev/null 2>"${STATE_DIR}/pause-resumed.errors"; then
      echo "  ${paused_id} reconnected after resume"
      ok=1
      break
    fi
    sleep 1
  done
  if [[ "$ok" -ne 1 ]]; then
    echo "::error::resumed connector never returned to Connected" >&2
    cat "${STATE_DIR}/pause-resumed.errors" >&2 || true
    cat "${STATE_DIR}/pause-resumed.json" >&2 || true
    exit 1
  fi
}

validate_paused_state() {
  # state_file paused_id running_id
  python3 - "$1" "$2" "$3" <<'PY'
import json, sys
state_path, paused_id, running_id = sys.argv[1:4]
state = json.load(open(state_path))
errors = []
by_id = {c.get("id"): c for c in (state.get("connectors") or [])}

paused = by_id.get(paused_id)
running = by_id.get(running_id)
if paused is None:
    errors.append(f"paused connector {paused_id} missing from state")
else:
    if paused.get("paused") is not True:
        errors.append(f"{paused_id} paused flag is {paused.get('paused')!r}, expected True")
    # A paused connector's WS loop is cancelled and its statesByConnector entry removed, so the
    # debug snapshot reports Idle for it.
    if paused.get("webSocketState") != "Idle":
        errors.append(f"{paused_id} webSocketState is {paused.get('webSocketState')!r}, expected 'Idle'")
if running is None:
    errors.append(f"running connector {running_id} missing from state")
else:
    if running.get("paused") is True:
        errors.append(f"{running_id} should not be paused")
    if running.get("webSocketState") != "Connected":
        errors.append(f"{running_id} webSocketState is {running.get('webSocketState')!r}, expected 'Connected'")

# Offline-fallback behaviour: pausing silences sync but the last-known device list stays. The
# desktop self-row must still list BOTH connectors as sources, and the seeded peers must remain.
known = state.get("knownDevices") or []
desktop_id = state.get("deviceId")
desktop_rows = [d for d in known if d.get("deviceId") == desktop_id]
if len(desktop_rows) == 1:
    sources = set(desktop_rows[0].get("sources") or [])
    if sources != {paused_id, running_id}:
        errors.append(f"desktop sources are {sorted(sources)}, expected both connectors while paused")
else:
    errors.append(f"desktop rows: {len(desktop_rows)}, expected 1")
if len(known) < 3:
    errors.append(f"knownDevices dropped to {len(known)} while paused, expected >=3 (cached devices must survive)")

if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print(f"paused {paused_id} (Idle), {running_id} still Connected, {len(known)} devices retained")
PY
}

validate_resumed_state() {
  # state_file resumed_id
  python3 - "$1" "$2" <<'PY'
import json, sys
state = json.load(open(sys.argv[1]))
resumed_id = sys.argv[2]
c = {x.get("id"): x for x in (state.get("connectors") or [])}.get(resumed_id)
if c is None:
    print(f"{resumed_id} missing", file=sys.stderr); sys.exit(1)
if c.get("paused") is True:
    print(f"{resumed_id} still paused", file=sys.stderr); sys.exit(1)
if c.get("webSocketState") != "Connected":
    print(f"{resumed_id} webSocketState is {c.get('webSocketState')!r}", file=sys.stderr); sys.exit(1)
PY
}

run_module_read_phase() {
  echo "== Module read phase: read + decrypt each seeded peer's meta + clipboard =="
  local state_file="${STATE_DIR}/modread-state.json"
  local code
  code=$(get_state_capture "$state_file")
  require_ok "GET /dev/state (module read)" "$code" "$state_file"

  local label
  for label in "$PEER_LABEL_A" "$PEER_LABEL_B"; do
    local device_id
    device_id=$(peer_device_id "$state_file" "$label")
    echo "  reading modules for peer ${label} (${device_id})"

    local meta_resp="${STATE_DIR}/modread-${label}-meta.json"
    code=$(post_action_capture "module.read" \
      "$(python3 -c 'import json,sys; print(json.dumps({"deviceId": sys.argv[1], "moduleId": "meta"}))' "$device_id")" \
      "$meta_resp")
    require_ok "module.read meta (${label})" "$code" "$meta_resp"
    python3 - "$meta_resp" "$label" <<'PY'
import json, sys
# Debug-RPC wraps every action's JSON under a top-level "result" key (see DebugRpcServer).
resp = (json.load(open(sys.argv[1])) or {}).get("result") or {}
label = sys.argv[2]
if resp.get("state") != "Ok":
    print(f"meta read for {label} not Ok: {resp}", file=sys.stderr); sys.exit(1)
m = resp["module"]
expected = {"deviceManufacturer": "Google", "deviceName": "Phantom Phone", "deviceType": "PHONE"}
bad = {k: m.get(k) for k, v in expected.items() if m.get(k) != v}
if bad:
    print(f"meta fields for {label} mismatched: {bad}", file=sys.stderr); sys.exit(1)
print(f"  meta decrypted for {label}: {m.get('deviceManufacturer')} {m.get('deviceName')} ({m.get('deviceType')})")
PY

    local clip_resp="${STATE_DIR}/modread-${label}-clipboard.json"
    code=$(post_action_capture "module.read" \
      "$(python3 -c 'import json,sys; print(json.dumps({"deviceId": sys.argv[1], "moduleId": "clipboard"}))' "$device_id")" \
      "$clip_resp")
    require_ok "module.read clipboard (${label})" "$code" "$clip_resp"
    python3 - "$clip_resp" "$label" <<'PY'
import base64, json, sys
resp = (json.load(open(sys.argv[1])) or {}).get("result") or {}
label = sys.argv[2]
if resp.get("state") != "Ok":
    print(f"clipboard read for {label} not Ok: {resp}", file=sys.stderr); sys.exit(1)
data = base64.b64decode(resp["module"]["data"]).decode("utf-8")
if data != "Hello from Octi!":
    print(f"clipboard text for {label} is {data!r}, expected 'Hello from Octi!'", file=sys.stderr); sys.exit(1)
print(f"  clipboard decrypted for {label}: {data!r}")
PY
  done
}

run_blob_phase() {
  echo "== Blob phase: own-file multi-source download routing (running connector preferred over paused) =="
  local state_file="${STATE_DIR}/blob-state.json"
  local code
  code=$(get_state_capture "$state_file")
  require_ok "GET /dev/state (blob)" "$code" "$state_file"

  local own_device_id
  own_device_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["deviceId"])' "$state_file")

  local source_file="${STATE_DIR}/blob-source.bin"
  printf 'octi multi-connector blob smoke payload\n' > "$source_file"

  echo "  sharing ${source_file} across both connectors"
  local share_resp="${STATE_DIR}/blob-share.json"
  code=$(post_action_capture "files.share" \
    "$(python3 -c 'import json,sys; print(json.dumps({"path": sys.argv[1]}))' "$source_file")" \
    "$share_resp")
  require_ok "files.share" "$code" "$share_resp"
  local blob_key
  blob_key=$(python3 - "$share_resp" <<'PY'
import json, sys
resp = (json.load(open(sys.argv[1])) or {}).get("result") or {}
if resp.get("result") != "Ok":
    print(f"files.share not Ok: {resp}", file=sys.stderr); sys.exit(1)
if resp.get("connectorRefCount") != 2:
    print(f"blob fanned out to {resp.get('connectorRefCount')} connectors, expected 2", file=sys.stderr); sys.exit(1)
if resp.get("committedConnectorCount") != 2:
    print(f"document committed to {resp.get('committedConnectorCount')} connectors, expected 2", file=sys.stderr); sys.exit(1)
print(resp["blobKey"])
PY
  )
  echo "  shared blobKey=${blob_key} (blob + document on both connectors)"

  # First download: both connectors running -> running-preference picks the lex-smallest id.
  local first_served
  first_served=$(download_and_report "${blob_key}" "${own_device_id}" "${STATE_DIR}/blob-dl-1.bin" "${STATE_DIR}/blob-dl-1.json")
  echo "  first download served by ${first_served}"

  echo "  pausing ${first_served} to force routing to the other connector"
  code=$(post_action_capture "settings.pause" \
    "$(python3 -c 'import json,sys; print(json.dumps({"connectorId": sys.argv[1], "paused": True}))' "$first_served")" \
    "${STATE_DIR}/blob-pause.json")
  require_ok "settings.pause(true) for ${first_served}" "$code" "${STATE_DIR}/blob-pause.json"
  # Wait until the pause is reflected so runningConnectors no longer includes it.
  local attempt
  for attempt in $(seq 1 30); do
    code=$(get_state_capture "${STATE_DIR}/blob-pause-wait.json")
    if [[ "$code" == "200" ]] && python3 - "${STATE_DIR}/blob-pause-wait.json" "$first_served" <<'PY' >/dev/null 2>&1
import json, sys
state = json.load(open(sys.argv[1]))
c = {x.get("id"): x for x in (state.get("connectors") or [])}.get(sys.argv[2])
sys.exit(0 if c and c.get("paused") is True else 1)
PY
    then
      break
    fi
    sleep 1
  done

  local second_served
  second_served=$(download_and_report "${blob_key}" "${own_device_id}" "${STATE_DIR}/blob-dl-2.bin" "${STATE_DIR}/blob-dl-2.json")
  echo "  second download served by ${second_served}"
  if [[ "$second_served" == "$first_served" ]]; then
    echo "::error::after pausing ${first_served}, download was still served by it — routing did not reorder" >&2
    exit 1
  fi

  echo "  resuming ${first_served}"
  code=$(post_action_capture "settings.pause" \
    "$(python3 -c 'import json,sys; print(json.dumps({"connectorId": sys.argv[1], "paused": False}))' "$first_served")" \
    "${STATE_DIR}/blob-resume.json")
  require_ok "settings.pause(false) for ${first_served}" "$code" "${STATE_DIR}/blob-resume.json"
}

download_and_report() {
  # blob_key owner_device_id dest_file resp_file -> prints servedBy connector id
  local blob_key="$1" owner="$2" dest="$3" resp="$4"
  local code
  code=$(post_action_capture "files.download" \
    "$(python3 -c 'import json,sys; print(json.dumps({"deviceId": sys.argv[1], "blobKey": sys.argv[2], "destination": sys.argv[3]}))' "$owner" "$blob_key" "$dest")" \
    "$resp")
  require_ok "files.download" "$code" "$resp"
  python3 - "$resp" <<'PY'
import json, sys
resp = (json.load(open(sys.argv[1])) or {}).get("result") or {}
if resp.get("result") != "Ok":
    print(f"files.download not Ok: {resp}", file=sys.stderr); sys.exit(1)
served = resp.get("servedBy")
if not served:
    print(f"files.download Ok but servedBy missing: {resp}", file=sys.stderr); sys.exit(1)
print(served)
PY
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

# Extended runtime scenarios only run on the initial link pass. The restart pass keeps to the
# persistence check above — and these phases leave every connector unpaused so the restart
# pass's validate_state (which rejects paused connectors) still sees a clean two-connector graph.
if [[ "$SKIP_LINK" != "1" ]]; then
  run_pause_phase
  run_module_read_phase
  run_blob_phase
fi

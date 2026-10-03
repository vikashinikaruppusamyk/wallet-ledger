#!/usr/bin/env bash
# Five-minute demo against `docker compose up` (or a local run on :8080).
# Each step pauses so it can be narrated on video. Needs bash, curl and jq.
#
#   ./scripts/demo.sh            # http://localhost:8080
#   API=http://host:8080 ./scripts/demo.sh
set -euo pipefail

API=${API:-http://localhost:8080}
ADMIN=1
n=$(date +%s)

step() { printf '\n\033[1;34m== %s ==\033[0m\n' "$1"; read -r -p "(enter) " _; }
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())'; }
post() { # post <path> <user|-> <key> <json>
  local user=()
  [ "$2" != "-" ] && user=(-H "X-User-Id: $2")
  curl -s -i -X POST "$API$1" "${user[@]}" -H "Idempotency-Key: $3" -H 'Content-Type: application/json' -d "$4" \
    | sed -n '1p;/^Idempotent-Replayed/p;/^\r$/,$p'
}
body() { # same as post but only the JSON body
  local user=()
  [ "$2" != "-" ] && user=(-H "X-User-Id: $2")
  curl -s -X POST "$API$1" "${user[@]}" -H "Idempotency-Key: $3" -H 'Content-Type: application/json' -d "$4"
}

step "Setup: two users with wallets, Asha tops up ₹1,000"
ASHA=$(body /users - "$(uuid)" "{\"name\":\"Asha Verma\",\"email\":\"asha$n@demo.in\",\"phone\":\"+9198${n: -8}\"}" | jq .user_id)
RAHUL=$(body /users - "$(uuid)" "{\"name\":\"Rahul Mehta\",\"email\":\"rahul$n@demo.in\",\"phone\":\"+9197${n: -8}\"}" | jq .user_id)
for u in $ASHA $RAHUL; do body /admin/users/$u/kyc $ADMIN "$(uuid)" '{"kyc_status":"VERIFIED"}' >/dev/null; done
A=$(body /users/$ASHA/wallets $ASHA "$(uuid)" '{"name":"main"}' | jq .wallet_id)
R=$(body /users/$RAHUL/wallets $RAHUL "$(uuid)" '{"name":"main"}' | jq .wallet_id)
body /transfers/topup $ASHA "$(uuid)" "{\"wallet_id\":$A,\"amount\":100000}" | jq '{transfer_id, type, status, amount, entries}'
echo "Asha=$ASHA wallet=$A   Rahul=$RAHUL wallet=$R"

step "1. One transfer end to end: Asha sends Rahul ₹250"
T=$(body /transfers $ASHA "$(uuid)" "{\"from_wallet_id\":$A,\"to_wallet_id\":$R,\"amount\":25000,\"note\":\"Dinner\"}")
echo "$T" | jq '{transfer_id, status, amount, entries, status_history}'
curl -s "$API/wallets/$A/balance" -H "X-User-Id: $ASHA" | jq
curl -s "$API/wallets/$R/statement?limit=5" -H "X-User-Id: $RAHUL" | jq '.entries'

step "2. A retry: the same request three times with one Idempotency-Key"
KEY=$(uuid)
for i in 1 2 3; do post /transfers $ASHA "$KEY" "{\"from_wallet_id\":$A,\"to_wallet_id\":$R,\"amount\":5000}" | head -3; echo; done
echo "--- same key, different amount:"
post /transfers $ASHA "$KEY" "{\"from_wallet_id\":$A,\"to_wallet_id\":$R,\"amount\":9999}"
echo; curl -s "$API/wallets/$A/balance" -H "X-User-Id: $ASHA" | jq .balance

step "3. A held transfer: ₹60,000 trips LARGE_AMOUNT"
body /transfers/topup $ASHA "$(uuid)" "{\"wallet_id\":$A,\"amount\":7000000}" >/dev/null
H=$(body /transfers $ASHA "$(uuid)" "{\"from_wallet_id\":$A,\"to_wallet_id\":$R,\"amount\":6000000,\"note\":\"Car\"}")
echo "$H" | jq '{transfer_id, status, entries, risk_flags}'
HID=$(echo "$H" | jq .transfer_id)
curl -s "$API/admin/risk/queue?limit=3" -H "X-User-Id: $ADMIN" | jq '.[0]'
body /admin/risk/explain $ADMIN "$(uuid)" '{}' | jq
body /admin/risk/$HID/decision $ADMIN "$(uuid)" '{"decision":"RELEASE","reason":"Confirmed by phone"}' \
  | jq '{transfer_id, status, entries, status_history, risk_flags}'

step "4. A reconciliation run"
RUN=$(body /admin/reconciliation/run $ADMIN "$(uuid)" '{}')
echo "$RUN" | jq '{run_id, status}'
sleep 2
curl -s "$API/admin/reconciliation/runs/$(echo "$RUN" | jq .run_id)" -H "X-User-Id: $ADMIN" | jq

step "5. A statement question"
body /users/$ASHA/statement/ask $ASHA "$(uuid)" '{"question":"How much did I send to Rahul last month?"}' | jq
echo "(With LLM_PROVIDER=anthropic the question is parsed by Claude; with the default mock only the sample questions in docs/qa-samples.md are recognised.)"

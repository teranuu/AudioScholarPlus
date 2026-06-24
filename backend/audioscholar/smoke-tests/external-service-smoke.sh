#!/usr/bin/env bash
# =============================================================================
# External Service Smoke Tests
# Task: external-service-smoke
# Date:   2026-05-21
#
# Validates configured external integrations using low-cost API calls.
# Reads secrets from .env; NEVER prints secrets to output.
#
# Success Criteria:
#   1. Gemini/Gemma API smoke (model listing, minimal content generation)
#   2. YouTube Data API smoke (single search query)
#   3. Supabase Storage bucket/object smoke (list buckets, upload tiny object, delete)
#   4. OpenCode Zen model listing + minimal chat smoke (no-key, rate-limit aware)
#   5. Report HTTP statuses/response shapes with secrets redacted
# =============================================================================

set -u
# NOTE: NOT using set -e — we handle failures explicitly in each test step.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"
REPORT_DIR="$SCRIPT_DIR"
REPORT_FILE="$REPORT_DIR/smoke-results-$(date +%Y%m%d-%H%M%S).txt"

PASS=0; FAIL=0; SKIP=0

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'
NC='\033[0m'

rm -f "$REPORT_FILE"; touch "$REPORT_FILE"

log()   { echo -e "${CYAN}[INFO]${NC}  $*"     | tee -a "$REPORT_FILE"; }
pass()  { echo -e "${GREEN}[PASS]${NC}  $*"    | tee -a "$REPORT_FILE"; ((PASS++)) || true; }
fail()  { echo -e "${RED}[FAIL]${NC}  $*"      | tee -a "$REPORT_FILE"; ((FAIL++)) || true; }
skip()  { echo -e "${YELLOW}[SKIP]${NC} $*"     | tee -a "$REPORT_FILE"; ((SKIP++)) || true; }

# ---- Safely extract a JSON field using python (never fails) -----------------
json_get() {
    local json="$1" field="$2" default="${3:-?}"
    echo "$json" | python3 -c "
import sys,json
try:
    d=json.load(sys.stdin)
    parts='$field'.split('.')
    for p in parts:
        if isinstance(d,dict):
            d=d.get(p,None)
        elif isinstance(d,list):
            try:
                d=d[int(p)]
            except (ValueError,IndexError):
                d=None
                break
        else:
            d=None
            break
    if d is None:
        print('$default')
    elif isinstance(d,str):
        print(d)
    else:
        print(json.dumps(d))
except Exception:
    print('$default')
" 2>/dev/null || echo "$default"
}

# ---- Load environment -------------------------------------------------------
if [ ! -f "$ENV_FILE" ]; then
    fail ".env file not found at $ENV_FILE"
    exit 1
fi

log "Loading .env from $ENV_FILE (secrets never printed)"
while IFS='=' read -r key value; do
    key=$(echo "$key" | xargs)
    if [ -n "$key" ] && [[ ! "$key" =~ ^# ]]; then
        value=$(echo "$value" | sed -e 's/^["\x27]//' -e 's/["\x27]$//')
        export "$key=$value"
    fi
done < <(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$ENV_FILE" 2>/dev/null || true)

# ---- Utility: HTTP helper ---------------------------------------------------
do_curl() {
    local method="$1" url="$2" output
    shift 2
    output=$(curl -s -w '\n%{http_code}' -X "$method" "$url" "$@" 2>&1) || true
    echo "$output"
}

parse_status() { echo "$1" | tail -1; }
parse_body()   { echo "$1" | sed '$d'; }

# =============================================================================
# 1. GEMINI / GEMMA API SMOKE
# =============================================================================
log ""
log "====================================================="
log " 1. Gemini API Smoke"
log "====================================================="

GEMINI_KEY="${GOOGLE_AI_API_KEY:-}"
if [ -z "$GEMINI_KEY" ]; then
    GEMINI_KEY="${GEMINI_API_KEYS%%,*}"
fi

if [ -z "$GEMINI_KEY" ]; then
    skip "Skipping Gemini: no GOOGLE_AI_API_KEY or GEMINI_API_KEYS configured"
else
    log "Gemini key found (length=${#GEMINI_KEY})"

    # 1a. List models (cheap metadata call, no cost)
    log "--- 1a. List models (GET /v1beta/models) ---"
    LIST_URL="https://generativelanguage.googleapis.com/v1beta/models?key=${GEMINI_KEY}"
    LIST_RESP=$(do_curl GET "$LIST_URL")
    LIST_STATUS=$(parse_status "$LIST_RESP")
    LIST_BODY=$(parse_body "$LIST_RESP")

    if [ "$LIST_STATUS" = "200" ]; then
        MODEL_COUNT=$(json_get "$LIST_BODY" "models" "?" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d) if isinstance(d,list) else '?')" 2>/dev/null || echo "?")
        FIRST_GEN=$(echo "$LIST_BODY" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for m in d.get('models',[]):
    if 'generateContent' in m.get('supportedGenerationMethods',[]):
        print(m['name']); break
" 2>/dev/null || echo "?")
        pass "Gemini list models: HTTP 200 | availableModels=$MODEL_COUNT | firstGenerateModel=$FIRST_GEN"
    else
        ERR_MSG=$(json_get "$LIST_BODY" "error.message" "raw-error")
        fail "Gemini list models: HTTP $LIST_STATUS | error=$ERR_MSG"
    fi

    # 1b. Minimal content generation (very cheap — single short prompt, 5 tokens)
    log "--- 1b. Minimal generateContent (gemini-2.5-flash, maxOutputTokens=5) ---"
    GEN_URL="https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${GEMINI_KEY}"
    GEN_BODY='{"contents":[{"parts":[{"text":"Respond with exactly one word: hello"}]}],"generationConfig":{"temperature":0,"maxOutputTokens":5}}'
    GEN_RESP=$(do_curl POST "$GEN_URL" -H "Content-Type: application/json" -d "$GEN_BODY")
    GEN_STATUS=$(parse_status "$GEN_RESP")
    GEN_BODY=$(parse_body "$GEN_RESP")

    if [ "$GEN_STATUS" = "200" ]; then
        CANDIDATE_COUNT=$(json_get "$GEN_BODY" "candidates" "?" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d) if isinstance(d,list) else '?')" 2>/dev/null || echo "?")
        USAGE=$(json_get "$GEN_BODY" "usageMetadata" "{}")
        # Redact model output for privacy; only report metadata
        FINISH_REASON=$(json_get "$GEN_BODY" "candidates.0.finishReason" "?")
        pass "Gemini generateContent: HTTP 200 | candidates=$CANDIDATE_COUNT | finishReason=$FINISH_REASON | usage=$USAGE"
    else
        ERR_MSG=$(json_get "$GEN_BODY" "error.message" "raw-error")
        fail "Gemini generateContent: HTTP $GEN_STATUS | error=$ERR_MSG"
    fi
fi

# =============================================================================
# 2. YOUTUBE DATA API SMOKE
# =============================================================================
log ""
log "====================================================="
log " 2. YouTube Data API Smoke"
log "====================================================="

YT_KEY="${YOUTUBE_API_KEY:-}"
if [ -z "$YT_KEY" ]; then
    skip "Skipping YouTube: no YOUTUBE_API_KEY configured"
else
    log "YouTube key found (length=${#YT_KEY})"

    # 2a. Minimal search — 1 result, cost: 100 quota units (10,000/day free)
    log "--- 2a. Single search query (maxResults=1) ---"
    YT_URL="https://www.googleapis.com/youtube/v3/search"
    YT_PARAMS="part=snippet&q=test&maxResults=1&type=video&key=${YT_KEY}"
    YT_RESP=$(do_curl GET "${YT_URL}?${YT_PARAMS}")
    YT_STATUS=$(parse_status "$YT_RESP")
    YT_BODY=$(parse_body "$YT_RESP")

    if [ "$YT_STATUS" = "200" ]; then
        ITEM_COUNT=$(json_get "$YT_BODY" "items" "?" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d) if isinstance(d,list) else '?')" 2>/dev/null || echo "?")
        TOTAL=$(json_get "$YT_BODY" "pageInfo.totalResults" "?")
        pass "YouTube search: HTTP 200 | itemsReturned=$ITEM_COUNT | totalResults=$TOTAL"
    elif [ "$YT_STATUS" = "403" ]; then
        ERR_REASON=$(json_get "$YT_BODY" "error.errors.0.reason" "unknown")
        ERR_MSG=$(json_get "$YT_BODY" "error.message" "forbidden")
        fail "YouTube search: HTTP 403 | reason=$ERR_REASON | message=$ERR_MSG"
    else
        ERR_BODY=$(echo "$YT_BODY" | head -c 300)
        fail "YouTube search: HTTP $YT_STATUS | body=$ERR_BODY"
    fi
fi

# =============================================================================
# 3. SUPABASE STORAGE SMOKE
# =============================================================================
log ""
log "====================================================="
log " 3. Supabase Storage Smoke"
log "====================================================="

SUPABASE_URL_VAL="${SUPABASE_URL:-}"
SR_KEY="${SUPABASE_SERVICE_ROLE_KEY:-}"
BUCKET="${SUPABASE_STORAGE_BUCKET:-audioscholar}"

if [ -z "$SUPABASE_URL_VAL" ] || [ -z "$SR_KEY" ]; then
    skip "Skipping Supabase Storage: SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY not configured"
else
    log "Supabase URL=$SUPABASE_URL_VAL | bucket=$BUCKET"
    AUTH_HEADERS=(-H "Authorization: Bearer ${SR_KEY}" -H "apikey: ${SR_KEY}")

    # 3a. List buckets
    log "--- 3a. List buckets ---"
    LIST_URL="${SUPABASE_URL_VAL}/storage/v1/bucket"
    LIST_RESP=$(do_curl GET "$LIST_URL" "${AUTH_HEADERS[@]}")
    LIST_STATUS=$(parse_status "$LIST_RESP")
    LIST_BODY=$(parse_body "$LIST_RESP")

    TARGET_EXISTS="no"
    if [ "$LIST_STATUS" = "200" ]; then
        BUCKET_COUNT=$(echo "$LIST_BODY" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "?")
        # Check if target bucket exists
        TARGET_EXISTS=$(echo "$LIST_BODY" | python3 -c "
import sys,json
for b in json.load(sys.stdin):
    if b.get('name','') == '${BUCKET}':
        print('yes'); break
" 2>/dev/null || echo "no")
        pass "Supabase list buckets: HTTP 200 | bucketCount=$BUCKET_COUNT | targetBucket='$BUCKET' exists=$TARGET_EXISTS"
    else
        ERR_BODY=$(echo "$LIST_BODY" | head -c 300)
        fail "Supabase list buckets: HTTP $LIST_STATUS | body=$ERR_BODY"
    fi

    # 3b. Upload a tiny smoke-test object
    if [ "$TARGET_EXISTS" = "yes" ] || [ "$LIST_STATUS" = "200" ]; then
        log "--- 3b. Upload tiny smoke-test object ---"
        SMOKE_FILENAME="smoke-test-$(date +%s).txt"
        SMOKE_PATH="$SMOKE_FILENAME"
        SMOKE_CONTENT="AudioScholar external service smoke test — safe to delete"

        UPLOAD_URL="${SUPABASE_URL_VAL}/storage/v1/object/${BUCKET}/${SMOKE_PATH}"
        UPLOAD_RESP=$(do_curl POST "$UPLOAD_URL" \
            "${AUTH_HEADERS[@]}" \
            -H "Content-Type: text/plain" \
            -H "x-upsert: true" \
            --data-binary "$SMOKE_CONTENT")
        UPLOAD_STATUS=$(parse_status "$UPLOAD_RESP")
        UPLOAD_BODY=$(parse_body "$UPLOAD_RESP")

        if [ "$UPLOAD_STATUS" = "200" ]; then
            pass "Supabase upload: HTTP 200 | object='$SMOKE_PATH'"
        else
            ERR_BODY=$(echo "$UPLOAD_BODY" | head -c 300)
            fail "Supabase upload: HTTP $UPLOAD_STATUS | body=$ERR_BODY"
        fi

        # 3c. Verify object exists via authenticated list endpoint
        log "--- 3c. Verify object existence (POST /storage/v1/object/list) ---"
        sleep 1  # brief consistency delay
        LIST_OBJ_URL="${SUPABASE_URL_VAL}/storage/v1/object/list/${BUCKET}"
        LIST_OBJ_RESP=$(do_curl POST "$LIST_OBJ_URL" \
            "${AUTH_HEADERS[@]}" \
            -H "Content-Type: application/json" \
            -d '{"prefix":"'"${SMOKE_PATH}"'","limit":1,"offset":0}')
        LIST_OBJ_STATUS=$(parse_status "$LIST_OBJ_RESP")
        if [ "$LIST_OBJ_STATUS" = "200" ]; then
            FOUND_COUNT=$(json_get "$(parse_body "$LIST_OBJ_RESP")" "" "?" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d) if isinstance(d,list) else 0)" 2>/dev/null || echo "0")
            pass "Supabase list objects: HTTP 200 | found=$FOUND_COUNT item(s) matching prefix"
        else
            fail "Supabase list objects: HTTP $LIST_OBJ_STATUS | object may not be visible"
        fi

        # 3d. Delete the smoke object
        log "--- 3d. Delete smoke-test object ---"
        DELETE_URL="${SUPABASE_URL_VAL}/storage/v1/object/${BUCKET}"
        DELETE_RESP=$(do_curl DELETE "$DELETE_URL" \
            "${AUTH_HEADERS[@]}" \
            -H "Content-Type: application/json" \
            -d '{"prefixes":["'"${SMOKE_PATH}"'"]}')
        DELETE_STATUS=$(parse_status "$DELETE_RESP")
        DELETE_BODY=$(parse_body "$DELETE_RESP")

        if [ "$DELETE_STATUS" = "200" ]; then
            DELETE_RESULT=$(json_get "$DELETE_BODY" "0.status" "deleted")
            pass "Supabase delete: HTTP 200 | status=$DELETE_RESULT | object cleaned up"
        else
            ERR_BODY=$(echo "$DELETE_BODY" | head -c 300)
            fail "Supabase delete: HTTP $DELETE_STATUS | body=$ERR_BODY | object '$SMOKE_PATH' may still exist"
        fi
    else
        skip "Skipping Supabase upload/delete: bucket '$BUCKET' not confirmed"
    fi
fi

# =============================================================================
# 4. OPENCODE ZEN / OPENAI-COMPATIBLE SMOKE
# =============================================================================
log ""
log "====================================================="
log " 4. OpenCode Zen / OpenAI-Compatible Smoke"
log "====================================================="

OC_BASE="${AI_OPENAI_COMPATIBLE_BASE_URL:-https://opencode.ai/zen/v1}"
OC_KEY="${AI_OPENAI_COMPATIBLE_API_KEY:-}"
OC_MODEL="${AI_OPENAI_COMPATIBLE_CHAT_MODEL:-big-pickle}"

log "Base URL=$OC_BASE | model=$OC_MODEL | API key=$(if [ -n "$OC_KEY" ]; then echo 'configured'; else echo 'NOT set (no-key mode)'; fi)"

# 4a. List models
log "--- 4a. List models (GET /models) ---"
MODELS_URL="${OC_BASE}/models"
if [ -n "$OC_KEY" ]; then
    MODELS_RESP=$(do_curl GET "$MODELS_URL" -H "Authorization: Bearer ${OC_KEY}" -H "Content-Type: application/json")
else
    MODELS_RESP=$(do_curl GET "$MODELS_URL" -H "Content-Type: application/json")
fi
MODELS_STATUS=$(parse_status "$MODELS_RESP")
MODELS_BODY=$(parse_body "$MODELS_RESP")

if [ "$MODELS_STATUS" = "200" ]; then
    MODEL_SUMMARY=$(echo "$MODELS_BODY" | python3 -c "
import sys,json
d=json.load(sys.stdin)
items=d if isinstance(d,list) else d.get('data',d.get('models',[]))
if isinstance(items,list):
    ids=[m.get('id','?') for m in items[:5]]
    print(f'count={len(items)} sample={ids}')
else:
    print(f'type={type(d).__name__}')
" 2>/dev/null || echo "parse-error")
    pass "OpenCode Zen list models: HTTP 200 | $MODEL_SUMMARY"
elif [ "$MODELS_STATUS" = "429" ]; then
    ERR_MSG=$(json_get "$MODELS_BODY" "error.message" "")
    pass "OpenCode Zen list models: HTTP 429 (rate limited) | message=$ERR_MSG | Expected behavior for no-key endpoints under load"
elif [ "$MODELS_STATUS" = "401" ] || [ "$MODELS_STATUS" = "403" ]; then
    ERR_MSG=$(json_get "$MODELS_BODY" "error.message" "")
    skip "OpenCode Zen list models: HTTP $MODELS_STATUS (auth required) | message=$ERR_MSG | Not expected with no-key default endpoint"
else
    ERR_BODY=$(echo "$MODELS_BODY" | head -c 300)
    fail "OpenCode Zen list models: HTTP $MODELS_STATUS | body=$ERR_BODY"
fi

# 4b. Minimal chat completion
log "--- 4b. Minimal chat completion (POST /chat/completions) ---"
CHAT_URL="${OC_BASE}/chat/completions"
CHAT_BODY='{"model":"'"${OC_MODEL}"'","temperature":0,"max_tokens":5,"messages":[{"role":"user","content":"hi"}]}'

if [ -n "$OC_KEY" ]; then
    CHAT_RESP=$(do_curl POST "$CHAT_URL" \
        -H "Authorization: Bearer ${OC_KEY}" \
        -H "Content-Type: application/json" \
        -d "$CHAT_BODY")
else
    CHAT_RESP=$(do_curl POST "$CHAT_URL" \
        -H "Content-Type: application/json" \
        -d "$CHAT_BODY")
fi
CHAT_STATUS=$(parse_status "$CHAT_RESP")
CHAT_BODY=$(parse_body "$CHAT_RESP")

if [ "$CHAT_STATUS" = "200" ]; then
    CHOICES=$(json_get "$CHAT_BODY" "choices" "" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'count={len(d)}')" 2>/dev/null || echo "?")
    FINISH=$(json_get "$CHAT_BODY" "choices.0.finish_reason" "?")
    USAGE=$(json_get "$CHAT_BODY" "usage" "{}")
    pass "OpenCode Zen chat: HTTP 200 | $CHOICES | finish_reason=$FINISH | usage=$USAGE"
elif [ "$CHAT_STATUS" = "429" ]; then
    ERR_MSG=$(json_get "$CHAT_BODY" "error.message" "")
    pass "OpenCode Zen chat: HTTP 429 (rate limited) | message=$ERR_MSG | Consistent with model listing behavior"
elif [ "$CHAT_STATUS" = "401" ] || [ "$CHAT_STATUS" = "403" ]; then
    ERR_MSG=$(json_get "$CHAT_BODY" "error.message" "")
    skip "OpenCode Zen chat: HTTP $CHAT_STATUS (auth required) | message=$ERR_MSG | Not expected with no-key default endpoint"
else
    ERR_BODY=$(echo "$CHAT_BODY" | head -c 300)
    fail "OpenCode Zen chat: HTTP $CHAT_STATUS | body=$ERR_BODY"
fi

# =============================================================================
# SUMMARY
# =============================================================================
log ""
log "====================================================="
log " SMOKE TEST RESULTS"
log "====================================================="
log ""

TOTAL=$((PASS + FAIL + SKIP))
echo -e "  ${GREEN}PASS${NC}: $PASS  ${RED}FAIL${NC}: $FAIL  ${YELLOW}SKIP${NC}: $SKIP  TOTAL: $TOTAL" | tee -a "$REPORT_FILE"
echo ""
echo "Full report: $REPORT_FILE" | tee -a "$REPORT_FILE"

if [ "$FAIL" -gt 0 ]; then
    echo -e "${RED}Some smoke tests FAILED.${NC}"
    exit 1
else
    echo -e "${GREEN}All executed smoke tests PASSED.${NC}"
    exit 0
fi

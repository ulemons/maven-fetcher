#!/usr/bin/env zsh
# maven-fetcher manual runner
#
# Usage: ./run_nightly.sh [--env local|prod]
#
# Writes a dated log to logs/run-YYYY-MM-DD.log and a change report to
# logs/change-report-YYYY-MM-DD.txt. Logs older than 30 days are pruned.

# ── Environment selection ──────────────────────────────────────────────────────
ENV="local"
for arg in "$@"; do
    if [[ "$arg" == "--env" ]]; then
        shift; ENV="$1"; shift; break
    fi
done

ENV_FILE="$(cd "$(dirname "$0")" && pwd)/.env.$ENV"
if [ ! -f "$ENV_FILE" ]; then
    echo "ERROR: env file not found: $ENV_FILE"
    exit 1
fi
source "$ENV_FILE"

DB_URL="$MAVEN_FETCHER_DB_URL"
DB_USER="$MAVEN_FETCHER_DB_USER"
DB_PASSWORD="$MAVEN_FETCHER_DB_PASSWORD"

# ── Paths ──────────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/target/maven-fetcher-1.0.0.jar"
LOG_DIR="$SCRIPT_DIR/logs"
JAVA="/opt/homebrew/opt/openjdk@17/bin/java"

# ── Logging ────────────────────────────────────────────────────────────────────
mkdir -p "$LOG_DIR"
DATE_TAG="$(date +%Y-%m-%d)"
LOG="$LOG_DIR/run-$DATE_TAG.log"
REPORT="$LOG_DIR/change-report-$DATE_TAG.txt"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOG"; }

# ── Sanity checks ─────────────────────────────────────────────────────────────
if [ ! -f "$JAR" ]; then
    log "ERROR: JAR not found at $JAR"
    log "       Run 'mvn clean package -q' inside $SCRIPT_DIR first."
    exit 1
fi

if [ ! -x "$JAVA" ]; then
    log "ERROR: java not found at $JAVA"
    exit 1
fi

# ── Run ───────────────────────────────────────────────────────────────────────
log "===== START maven-fetcher -n all ====="
log "JAR    : $JAR"
log "Java   : $($JAVA -version 2>&1 | head -1)"
log "DB URL : $DB_URL"
log "Report : $REPORT"

"$JAVA" -jar "$JAR" \
    -n all \
    --db-url      "$DB_URL" \
    --db-user     "$DB_USER" \
    --db-password "$DB_PASSWORD" \
    --report-file "$REPORT" \
    2>&1 | tee -a "$LOG"
EXIT_CODE=${PIPESTATUS[0]}

if [ "$EXIT_CODE" -eq 0 ]; then
    log "===== END: success ====="
    log "Change report: $REPORT"
else
    log "===== END: FAILED (exit $EXIT_CODE) ====="
fi

# ── Prune logs and reports older than 30 days ─────────────────────────────────
find "$LOG_DIR" -name "run-*.log"           -mtime +30 -delete 2>/dev/null || true
find "$LOG_DIR" -name "change-report-*.txt" -mtime +30 -delete 2>/dev/null || true

exit $EXIT_CODE

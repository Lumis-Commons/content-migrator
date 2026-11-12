#!/bin/bash
# One command owns the fixture, browser, and restoration, including failure paths.
set -euo pipefail
MODULE_DIR=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$MODULE_DIR"
# Same ai_tools default as run-tests.sh: ai_lumis_dev/ai_tools in the environment version folder.
LUMIS_AI_TOOLS=${LUMIS_AI_TOOLS:-$(cd -- "$MODULE_DIR/../../../../../../../.." && pwd)/ai_lumis_dev/ai_tools}
export LUMIS_AI_TOOLS
read_env() { sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$LUMIS_AI_TOOLS/.env" | tail -n 1; }
TOMCAT_HOME=$(read_env TOMCAT_HOME)
LUMIS_PORTAL_URL=$(read_env LUMIS_PORTAL_URL)
export TOMCAT_HOME LUMIS_PORTAL_URL
for key in LUMIS_ADMIN_USER; do
    [ -n "$(read_env "$key")" ] || { echo "ERROR $key is empty in ai_tools/.env" >&2; exit 2; }
done
[ -n "$TOMCAT_HOME" ] && [ -n "$LUMIS_PORTAL_URL" ] || { echo 'ERROR missing Tomcat/portal configuration' >&2; exit 2; }
# playwright-core comes from NODE_PATH or, when unset, from an npx cache of the current user.
if [ -z "${NODE_PATH:-}" ]; then
    for candidate in "$HOME"/.npm/_npx/*/node_modules; do
        if [ -d "$candidate/playwright-core" ]; then NODE_PATH=$candidate; break; fi
    done
fi
[ -n "${NODE_PATH:-}" ] || { echo 'ERROR playwright-core not found; set NODE_PATH' >&2; exit 2; }
export NODE_PATH
mkdir -p target
# Hold the portal test lock throughout browser execution, too. Inner run-tests uses a private lock.
exec 8>"${RUN_TESTS_LOCK_FILE:-${TMPDIR:-/tmp}/lumis-portal-run-tests.lock}"
flock -n 8 || { echo 'ERROR another portal test is running' >&2; exit 2; }
export RUN_TESTS_LOCK_FILE="$MODULE_DIR/target/ui-inner.lock"
guard_ports() {
    python3 - <<'PY'
import glob, os, pathlib
ports = {8080, 8009}
listeners = set()
for table in ('/proc/net/tcp', '/proc/net/tcp6'):
    for line in pathlib.Path(table).read_text().splitlines()[1:]:
        fields = line.split()
        if fields[3] == '0A' and int(fields[1].split(':')[1], 16) in ports:
            listeners.add(fields[9])
found = set()
expected = os.path.realpath(os.environ['TOMCAT_HOME'])
for proc in glob.glob('/proc/[0-9]*'):
    try:
        sockets = {os.readlink(fd)[8:-1] for fd in glob.glob(proc + '/fd/*')
                   if os.readlink(fd).startswith('socket:[')}
        owned = listeners & sockets
        if not owned:
            continue
        args = pathlib.Path(proc + '/cmdline').read_bytes().decode().split('\0')
        bases = [arg.split('=', 1)[1] for arg in args if arg.startswith('-Dcatalina.base=')]
        if len(bases) != 1 or os.path.realpath(bases[0]) != expected:
            raise SystemExit('ERROR 8080/8009 occupied by another catalina.base/process: ' + proc)
        found |= owned
    except (FileNotFoundError, PermissionError, ProcessLookupError):
        continue
if found != listeners:
    raise SystemExit('ERROR cannot identify owner of 8080/8009; refusing to change portal state')
PY
}
guard_ports
bash "$MODULE_DIR/test/ensure-es.sh"
[ ! -e target/ui-ids.json ] || { echo 'ERROR previous scenario exists; remove it before starting another smoke run' >&2; exit 2; }
source "$MODULE_DIR/test/tomcat-cycle.sh"
tomcat_cycle_init
scenario_attempted=false
scenario() {
    RUN_TESTS_JAVA_OPTS="${RUN_TESTS_JAVA_OPTS:-} -Dcontentmigrator.uiScenario=$1" \
        bash test/run-hier-tests.sh UiScenarioTest
}
cleanup() {
    local result=$?
    trap - EXIT INT TERM
    echo '[ui-smoke] PROGRESS stopping Tomcat for cleanup'
    if guard_ports && tomcat_cycle_stop; then
        if [ "$scenario_attempted" = true ]; then
            scenario remove || { echo 'ERROR UI scenario cleanup failed; retaining ids for retry' >&2; result=1; }
        fi
    else
        echo 'ERROR cannot safely stop Tomcat; scenario cleanup skipped' >&2
        result=1
    fi
    tomcat_cycle_restore || result=1
    echo "[ui-smoke] PROGRESS finished, status=$result, previous Tomcat running=$TOMCAT_CYCLE_WAS_RUNNING"
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
tomcat_cycle_stop
scenario_attempted=true
echo '[ui-smoke] PROGRESS creating scenario'
scenario create
echo '[ui-smoke] PROGRESS starting Tomcat'
tomcat_cycle_start
ready=false
previous_code=
for ((attempt=0; attempt<90; attempt++)); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$LUMIS_PORTAL_URL/login.jsp" || true)
    if [ "$code" != "$previous_code" ]; then
        echo "[ui-smoke] PROGRESS portal readiness HTTP $code"
        previous_code=$code
    fi
    # The portal deliberately returns 403 with the unauthenticated login form.
    if [[ "$code" == 200 || "$code" == 403 ]] &&
        curl -s --max-time 3 "$LUMIS_PORTAL_URL/login.jsp" | grep -q 'name="login"'; then
        ready=true
        break
    fi
    sleep 2
done
[ "$ready" = true ] || { echo 'ERROR portal login did not become ready' >&2; exit 1; }
echo '[ui-smoke] PROGRESS running HTTP and popup cases'
node test/ui/smoke.js

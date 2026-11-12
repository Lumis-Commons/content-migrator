#!/bin/bash
# Shared lifecycle for portal tests. Source after setting LUMIS_AI_TOOLS.

tomcat_cycle_log() { echo "[tomcat-cycle] $*" >&2; }
tomcat_cycle_env() { sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$LUMIS_AI_TOOLS/.env" | tail -n 1; }

tomcat_cycle_init()
{
    TOMCAT_CYCLE_BASE=${CATALINA_BASE:-$(tomcat_cycle_env CATALINA_BASE)}
    TOMCAT_CYCLE_BASE=${TOMCAT_CYCLE_BASE:-${TOMCAT_HOME:-$(tomcat_cycle_env TOMCAT_HOME)}}
    TOMCAT_CYCLE_URL=${LUMIS_PORTAL_URL:-$(tomcat_cycle_env LUMIS_PORTAL_URL)}
    [ -d "$TOMCAT_CYCLE_BASE" ] && [ -n "$TOMCAT_CYCLE_URL" ] || {
        tomcat_cycle_log 'ERROR missing Tomcat base or portal URL'; return 1;
    }
    TOMCAT_CYCLE_PID_FILE=${CATALINA_PID:-$(tomcat_cycle_env CATALINA_PID)}
    TOMCAT_CYCLE_PID_FILE=${TOMCAT_CYCLE_PID_FILE:-$TOMCAT_CYCLE_BASE/temp/tomcat.pid}
    TOMCAT_CYCLE_WAS_RUNNING=false
    local pid
    pid=$(tomcat_cycle_process find) || return 1
    if [ -n "$pid" ]; then TOMCAT_CYCLE_WAS_RUNNING=true; fi
    tomcat_cycle_log "PROGRESS initial state: running=$TOMCAT_CYCLE_WAS_RUNNING base=$TOMCAT_CYCLE_BASE PID=${pid:-none}"
}

# Never trust a pid file for process identity. Open a pidfd before checking the
# exact Java/Bootstrap/base tuple, so a reused PID cannot receive our signal.
tomcat_cycle_process()
{
    python3 - "$TOMCAT_CYCLE_BASE" "$@" <<'PY'
import os
import pathlib
import signal
import sys

base, action, *rest = sys.argv[1:]
base = os.path.realpath(base)

def matches(pid):
    try:
        proc = pathlib.Path('/proc') / str(pid)
        args = proc.joinpath('cmdline').read_bytes().split(b'\0')
        bases = [os.fsdecode(a.split(b'=', 1)[1]) for a in args if a.startswith(b'-Dcatalina.base=')]
        return (pathlib.Path(os.readlink(proc / 'exe')).name == 'java'
                and b'org.apache.catalina.startup.Bootstrap' in args
                and len(bases) == 1 and os.path.realpath(bases[0]) == base)
    except (FileNotFoundError, ProcessLookupError, PermissionError):
        return False

if action == 'find':
    pids = [p.name for p in pathlib.Path('/proc').iterdir() if p.name.isdigit() and matches(p.name)]
    if len(pids) > 1:
        sys.exit('ERROR multiple Tomcat JVMs for this base; refusing lifecycle changes')
    print(pids[0] if pids else '')
else:
    pid = int(rest[0])
    if action == 'alive':
        sys.exit(0 if matches(pid) else 1)
    try:
        fd = os.pidfd_open(pid)
    except ProcessLookupError:
        sys.exit(0)
    try:
        if matches(pid):
            signal.pidfd_send_signal(fd, getattr(signal, 'SIG' + action))
    except ProcessLookupError:
        pass
    finally:
        os.close(fd)
PY
}

tomcat_cycle_wait_ready()
{
    local deadline=$((SECONDS + 180)) code previous= remaining
    tomcat_cycle_log 'PROGRESS waiting for portal HTTP 200/302 (limit 180s)'
    while (( SECONDS < deadline )); do
        remaining=$((deadline - SECONDS))
        (( remaining > 3 )) && remaining=3
        code=$(curl --silent --output /dev/null --write-out '%{http_code}' \
            --max-time "$remaining" "${TOMCAT_CYCLE_URL%/}/" || true)
        if [ "$code" != "$previous" ]; then
            tomcat_cycle_log "PROGRESS portal readiness HTTP $code"
            previous=$code
        fi
        case "$code" in 200|302) return 0 ;; esac
        sleep 2
    done
    tomcat_cycle_log "ERROR portal readiness timed out after 180s (HTTP $previous)"
    return 1
}

tomcat_cycle_wait_stopped()
{
    local pid=$1 deadline=$((SECONDS + $2))
    while tomcat_cycle_process alive "$pid"; do
        (( SECONDS < deadline )) || return 1
        sleep 1
    done
}

tomcat_cycle_stop()
{
    local pid reason= status=0 remaining_pid
    pid=$(tomcat_cycle_process find) || return 1
    [ -n "$pid" ] || return 0
    # Even an unready/stranded JVM must be stopped before another portal JVM runs.
    tomcat_cycle_wait_ready || tomcat_cycle_log "PROGRESS readiness failed; proceeding with bounded stop of PID=$pid"
    tomcat_cycle_log "PROGRESS stopping PID=$pid (limit 60s)"
    timeout --kill-after=5s 60s "$LUMIS_AI_TOOLS/tomcat.sh" stop || status=$?
    if [ "$status" -ne 0 ]; then reason="tomcat.sh stop failed/timed out, status=$status"; fi
    if tomcat_cycle_process alive "$pid"; then
        reason=${reason:-tomcat.sh stop returned with the JVM still alive}
        tomcat_cycle_log "PROGRESS TERM PID=$pid: $reason"
        tomcat_cycle_process TERM "$pid" || return 1
        if ! tomcat_cycle_wait_stopped "$pid" 10; then
            tomcat_cycle_log "PROGRESS KILL PID=$pid: still alive 10s after TERM"
            tomcat_cycle_process KILL "$pid" || return 1
            tomcat_cycle_wait_stopped "$pid" 10 || {
                tomcat_cycle_log "ERROR PID=$pid survived KILL"; return 1;
            }
        fi
    fi
    remaining_pid=$(tomcat_cycle_process find) || return 1
    [ -z "$remaining_pid" ] || { tomcat_cycle_log "ERROR another JVM appeared: PID=$remaining_pid"; return 1; }
    if [ -f "$TOMCAT_CYCLE_PID_FILE" ] && [ "$(cat "$TOMCAT_CYCLE_PID_FILE")" = "$pid" ]; then
        rm -- "$TOMCAT_CYCLE_PID_FILE" || return 1
        tomcat_cycle_log "PROGRESS removed stale pid file for PID=$pid"
    fi
    tomcat_cycle_log "PROGRESS stopped PID=$pid"
}

tomcat_cycle_start()
{
    tomcat_cycle_log 'PROGRESS starting Tomcat (limit 240s)'
    # Tomcat must outlive the test runner's process group.
    if ! setsid -w timeout --kill-after=5s 240s "$LUMIS_AI_TOOLS/tomcat.sh" start 8>&- 9>&-; then
        tomcat_cycle_log 'ERROR Tomcat start failed/timed out'; return 1
    fi
    tomcat_cycle_wait_ready || return 1
    tomcat_cycle_log 'PROGRESS checking portal health (limit 60s)'
    if ! timeout --kill-after=5s 60s "$LUMIS_AI_TOOLS/tomcat.sh" health; then
        tomcat_cycle_log 'ERROR restored portal is not healthy'; return 1
    fi
    tomcat_cycle_log 'PROGRESS Tomcat restored and portal healthy'
}

tomcat_cycle_restore()
{
    if [ "$TOMCAT_CYCLE_WAS_RUNNING" = true ]; then
        tomcat_cycle_start
    else
        tomcat_cycle_stop
    fi
}

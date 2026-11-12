#!/bin/bash
# Start only the Elasticsearch belonging to 19.0.0.
set -euo pipefail
# Cluster name of this version's Elasticsearch; the environment names it lumisxp-19-0-0-<user>.
EXPECTED_CLUSTER=${LUMIS_ES_CLUSTER:-lumisxp-19-0-0-$(id -un)}
MODULE_DIR=$(cd -- "$(dirname -- "$0")/.." && pwd)
VERSION_DIR=$(cd -- "$MODULE_DIR/../../../../../../../.." && pwd)
ES_DIR="$VERSION_DIR/servers/elasticsearch-8.17"
health() { curl --silent --show-error --fail --max-time 3 http://127.0.0.1:9200/_cluster/health 2>/dev/null; }
check_cluster()
{
    local cluster
    cluster=$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("cluster_name", ""))' <<< "$1") || return 3
    if [ "$cluster" != "$EXPECTED_CLUSTER" ]; then
        echo "[ensure-es] ERROR: outro cluster na 9200 ($cluster); esperado $EXPECTED_CLUSTER" >&2
        return 3
    fi
}
# Any HTTP response means the port is occupied; never start over an unknown server.
if response=$(curl --silent --show-error --max-time 3 http://127.0.0.1:9200/_cluster/health 2>/dev/null); then
    check_cluster "$response" || exit 3
else
    [ -x "$ES_DIR/bin/elasticsearch" ] || { echo "[ensure-es] ERROR: Elasticsearch ausente em $ES_DIR" >&2; exit 1; }
    echo "[ensure-es] PROGRESS iniciando $ES_DIR"
    (cd "$ES_DIR" && exec setsid nohup bin/elasticsearch -p elasticsearch.pid) </dev/null > /tmp/contentmigrator-es-19-0-0.log 2>&1 &
    es_pid=$!
fi
last_status=
for ((attempt=0; attempt<120; attempt++)); do
    if response=$(health); then
        check_cluster "$response" || exit 3
        status=$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("status", ""))' <<< "$response")
        if [ "$status" != "$last_status" ]; then
            echo "[ensure-es] PROGRESS cluster $EXPECTED_CLUSTER: $status"
            last_status=$status
        fi
        case "$status" in yellow|green) exit 0 ;; esac
    fi
    if [ -n "${es_pid:-}" ] && ! kill -0 "$es_pid" 2>/dev/null; then
        echo "[ensure-es] ERROR: Elasticsearch encerrou durante a inicializacao" >&2
        tail -n 20 /tmp/contentmigrator-es-19-0-0.log >&2
        exit 1
    fi
    sleep 2
done
echo "[ensure-es] ERROR: cluster nao atingiu yellow em 240 s; veja /tmp/contentmigrator-es-19-0-0.log" >&2
exit 1

#!/bin/sh
# Runs every package's tests, compiled and in the JIT with -Memsafe, against
# a PostgreSQL container of its own (ktm-migrate-pg on port 55498, host network).
# KTM_SQLITE=<a ktm checkout built with ./build.sh --sqlite> adds the SQLite
# driver's tests; without it they are left out. Exit code 1 when anything failed.
# scripts/test.sh down removes the container.
set -u
dir=$(cd "$(dirname "$0")/.." && pwd)
name=ktm-migrate-pg
port=55498
pgver=${PGVER:-17}
if [ "${1:-}" = down ]; then docker rm -f $name >/dev/null 2>&1; exit 0; fi
if ! docker ps --format '{{.Names}}' | grep -qx $name; then
    if docker ps -a --format '{{.Names}}' | grep -qx $name; then
        docker start $name >/dev/null
    else
        docker run -d --name $name --network host -e PGPORT=$port -e POSTGRES_PASSWORD=ktm \
            --shm-size=64m postgres:$pgver >/dev/null || exit 1
    fi
fi
i=0
until docker exec $name pg_isready -h 127.0.0.1 -p $port -U postgres -q 2>/dev/null; do
    i=$((i + 1)); [ $i -gt 120 ] && { echo "$name did not start" >&2; exit 1; }
    sleep 0.5
done
export KTM_MIGRATE_PG="postgres://postgres:ktm@127.0.0.1:$port/postgres"
ulimit -v 4000000
cd "$dir" || exit 1
if [ -n "${KTM_SQLITE:-}" ]; then
    set -- "$KTM_SQLITE/ktm" test "--install-dir=$KTM_SQLITE/build/prefix"
    pkgs=./...
else
    set -- ktm test
    pkgs=". ./postgres"
fi
fail=0
"$@" $pkgs || fail=1
"$@" -jit -Memsafe $pkgs > /tmp/ktm-migrate-jit.$$ 2>&1 || fail=1
cat /tmp/ktm-migrate-jit.$$
grep -q "never freed\|double free\|wild free" /tmp/ktm-migrate-jit.$$ && fail=1
rm -f /tmp/ktm-migrate-jit.$$
[ $fail -eq 0 ] && echo PASS || echo FAIL
exit $fail

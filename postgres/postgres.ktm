module postgres;
import "github.com/ozzenerol/ktm-migrate";
import "github.com/ozzenerol/ktm-pg";
import vec;

/* migrate.Driver over a ktm-pg connection:
 *
 *   pg.Conn *c = pg.connect(url);
 *   migrate.Driver d = postgres.driver(c);
 *   ms.up(&d);
 *
 * The runner holds a session advisory lock (pg_advisory_lock) while it
 * works, so instances of a program that start together migrate one after
 * the other; the later ones find nothing left to do. Use one connection
 * for the whole run (from a pool: pool.acquire()), since the lock
 * belongs to it. */

/* The advisory lock's key: "ktm-migrate" */
pub const i64 LOCK_KEY = 0x6B746D6D6967;

static void pgExec(void *ctx, u8[] script) {
    pg.Conn *c = ctx;
    c.execScript(script);
}

static void pgQueryInts(void *ctx, u8[] query, vec.Vec<i64> *out) {
    pg.Conn *c = ctx;
    vec.Vec<i64> rows = pg.queryAll<i64>(c, query);
    for (auto v : rows) { out.push(v); }
    rows.free();
}

static void pgLock(void *ctx) {
    pg.Conn *c = ctx;
    c.exec("SELECT pg_advisory_lock($1)", LOCK_KEY);
}

static void pgUnlock(void *ctx) {
    pg.Conn *c = ctx;
    if (c.isDead()) { return; }    /* the lock went with the session */
    c.exec("SELECT pg_advisory_unlock($1)", LOCK_KEY);
}

/* A Driver for c (which stays yours to close) */
pub migrate.Driver driver(pg.Conn *c) {
    migrate.Driver d;
    d.ctx = c;
    d.exec = &pgExec;
    d.queryInts = &pgQueryInts;
    d.lock = &pgLock;
    d.unlock = &pgUnlock;
    return d;
}

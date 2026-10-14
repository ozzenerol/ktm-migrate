module postgres;
import "github.com/ozzenerol/ktm-migrate";
import "github.com/ozzenerol/ktm-pg";
import vec;

/* migrate.Driver over ktm-pg, a connection or a pool:
 *
 *   migrate.Driver d = postgres.driver(conn);      // a pg.Conn *
 *   migrate.Driver d = postgres.driverPool(pool);  // a pg.Pool *: one connection per run
 *   ms.up(&d);
 *
 * The runner holds a session advisory lock (pg_advisory_lock) while it
 * works, so instances of a program that start together migrate one after
 * the other; the later ones find nothing left to do. The lock belongs to
 * a connection, so every call of a run uses the same one: driverPool
 * acquires it when the run starts and gives it back when it ends. */

/* The advisory lock's key: "ktm-migrate" */
pub const i64 LOCK_KEY = 0x6B746D6D6967;

static void pgExec(migrate.Driver *d, u8[] script) {
    pg.Conn *c = d.conn;
    c.execScript(script);
}

static void pgQueryInts(migrate.Driver *d, u8[] query, vec.Vec<i64> *out) {
    vec.Vec<i64> rows = pg.queryAll<i64>((pg.Conn *)d.conn, query);
    for (auto v : rows) { out.push(v); }
    rows.free();
}

static void pgLock(migrate.Driver *d) {
    pg.Conn *c = d.conn;
    c.exec("SELECT pg_advisory_lock($1)", LOCK_KEY);
}

static void pgUnlock(migrate.Driver *d) {
    pg.Conn *c = d.conn;
    if (c.isDead()) { return; }    /* the lock went with the session */
    c.exec("SELECT pg_advisory_unlock($1)", LOCK_KEY);
}

/* A Driver for c (which stays yours to close) */
pub migrate.Driver driver(pg.Conn *c) {
    migrate.Driver d;
    d.ctx = c;
    d.conn = c;
    d.exec = &pgExec;
    d.queryInts = &pgQueryInts;
    d.lock = &pgLock;
    d.unlock = &pgUnlock;
    return d;
}

static void poolLock(migrate.Driver *d) {
    pg.Pool *p = d.ctx;
    pg.Conn *c = p.acquire();
    d.conn = c;
    try {
        pgLock(d);
    } catch (Error e) {
        d.conn = null;
        c.release();
        throw(e);
    }
}

static void poolUnlock(migrate.Driver *d) {
    pg.Conn *c = d.conn;
    if (!c) { return; }
    d.conn = null;
    try {
        if (!c.isDead()) { c.exec("SELECT pg_advisory_unlock($1)", LOCK_KEY); }
    } catch (Error e) {
        c.release();
        throw(e);
    }
    c.release();
}

/* A Driver for the pool p (which stays yours to close): each run (up,
 * down, to, status, version) acquires a connection and gives it back */
pub migrate.Driver driverPool(pg.Pool *p) {
    migrate.Driver d;
    d.ctx = p;
    d.exec = &pgExec;
    d.queryInts = &pgQueryInts;
    d.lock = &poolLock;
    d.unlock = &poolUnlock;
    return d;
}

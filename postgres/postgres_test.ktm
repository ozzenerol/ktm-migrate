module postgres;
import "github.com/ozzenerol/ktm-migrate";
import "github.com/ozzenerol/ktm-pg";
import coro, cstr, libc, str, testing, vec;

/* Against the Postgres in KTM_MIGRATE_PG (a URL); skipped without it.
 * Each test works in a schema of its own, dropped at the end. */

static u8[] gUrl;
static String gSchema;

static pg.Conn *open(testing.T *t) {
    u8 *u = libc.getenv("KTM_MIGRATE_PG");
    if (!u || !u[0]) { t.skip("set KTM_MIGRATE_PG=postgres://... to test against Postgres"); }
    gUrl = cstr.view(u);
    gSchema.free();
    gSchema = str.format("migrate_test_%d", libc.getpid());
    pg.Conn *c = connectIn(gSchema[0:]);
    c.execScript("SET client_min_messages = warning");
    scoped String sql = str.format("DROP SCHEMA IF EXISTS %s CASCADE; CREATE SCHEMA %s", gSchema.cstr(), gSchema.cstr());
    c.execScript(sql[0:]);
    return c;
}

static pg.Conn *connectIn(u8[] schema) {
    pg.Conn *c = pg.connect(gUrl);
    scoped String sql = str.format("SET search_path TO %.*s", schema.len, schema.ptr);
    c.execScript(sql[0:]);
    return c;
}

static void close(pg.Conn *c) {
    scoped String sql = str.format("DROP SCHEMA IF EXISTS %s CASCADE", gSchema.cstr());
    c.execScript(sql[0:]);
    c.close();
    gSchema.free();
}

static migrate.Migrations users() {
    migrate.Migrations ms;
    ms.add(1, "create_users", "CREATE TABLE users (id bigserial PRIMARY KEY, name text NOT NULL)", "DROP TABLE users");
    ms.add(2, "add_email", "ALTER TABLE users ADD email text UNIQUE", "ALTER TABLE users DROP email");
    ms.add(3, "seed", "INSERT INTO users (name, email) VALUES ('ann', 'ann@example.com')", "DELETE FROM users");
    return ms;
}

void testUpDownStatus(testing.T *t) {
    pg.Conn *c = open(t);
    migrate.Driver d = driver(c);
    migrate.Migrations ms = users();
    t.equal(ms.up(&d), 3);
    t.equal(migrate.version(&d), 3);
    t.equal(pg.queryOne<i64>(c, "SELECT count(*) FROM users WHERE email = 'ann@example.com'"), 1);
    t.equal(ms.up(&d), 0);
    t.equal(ms.down(&d, 2), 2);
    t.equal(pg.queryOne<i64>(c, "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'users' AND column_name = 'email'"), 0);
    vec.Vec<migrate.Status> st = ms.status(&d);
    t.check(st.len == 3 && st.data[0].applied && !st.data[1].applied, "status");
    st.free();
    t.equal(ms.to(&d, 0), 1);
    t.equal(pg.queryOne<i64>(c, "SELECT count(*) FROM pg_tables WHERE schemaname = current_schema() AND tablename = 'users'"), 0);
    t.equal(pg.queryOne<i64>(c, "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'"), 0);
    ms.free();
    close(c);
}

void testFailureRollsBack(testing.T *t) {
    pg.Conn *c = open(t);
    migrate.Driver d = driver(c);
    migrate.Migrations ms = users();
    ms.add(4, "broken", "CREATE TABLE tags (id int); INSERT INTO nowhere VALUES (1)", "");
    bool threw = false;
    try {
        ms.up(&d);
    } catch (Error e) {
        threw = e.code == migrate.ERR_MIGRATE &&
                str.startsWith(cstr.view(e.message), "migrate: 4_broken.up.sql: ") &&
                str.contains(cstr.view(e.message), "nowhere");
        if (!threw) { t.log("%s", e.message); }
    }
    t.check(threw, "the error names the file and the server's message");
    t.equal(migrate.version(&d), 3);
    t.equal(pg.queryOne<i64>(c, "SELECT count(*) FROM pg_tables WHERE schemaname = current_schema() AND tablename = 'tags'"), 0);
    t.equal(pg.queryOne<i64>(c, "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'"), 0);
    t.check(c.txState() == 'I', "not left in a transaction");
    ms.free();
    close(c);
}

void testNoTransaction(testing.T *t) {
    pg.Conn *c = open(t);
    migrate.Driver d = driver(c);
    migrate.Migrations ms = users();
    ms.add(4, "index_name", "-- migrate: no-transaction\nCREATE INDEX CONCURRENTLY users_name ON users (name)",
           "-- migrate: no-transaction\nDROP INDEX CONCURRENTLY users_name");
    t.equal(ms.up(&d), 4);
    t.equal(pg.queryOne<i64>(c, "SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema() AND indexname = 'users_name'"), 1);
    t.equal(ms.down(&d), 1);
    t.equal(migrate.version(&d), 3);
    ms.free();
    close(c);
}

/* two instances that start together: the lock makes them take turns */
static migrate.Migrations gSlow;
static i64 gRan[2];

static void *instance(void *arg) {
    i64 i = (i64)arg;
    pg.Conn *c = connectIn(gSchema[0:]);
    migrate.Driver d = driver(c);
    gRan[i] = gSlow.up(&d);
    c.close();
    return null;
}

void testTwoInstances(testing.T *t) {
    pg.Conn *c = open(t);
    gSlow = users();
    gSlow.add(4, "slow", "SELECT pg_sleep(0.3); CREATE TABLE slow (id int)", "DROP TABLE slow");
    gRan[0] = gRan[1] = -1;
    coro.go(&instance, (void *)0);
    coro.go(&instance, (void *)1);
    coro.run();
    t.equal(gRan[0] + gRan[1], 4);
    t.check(gRan[0] == 0 || gRan[1] == 0, "one did everything: %d and %d", gRan[0], gRan[1]);
    t.equal(pg.queryOne<i64>(c, "SELECT count(*) FROM schema_migrations"), 4);
    gSlow.free();
    close(c);
}

void testPool(testing.T *t) {
    pg.Conn *c = open(t);
    scoped String url = str.format("%.*s?search_path=%s", gUrl.len, gUrl.ptr, gSchema.cstr());
    pg.Pool *p = pg.open(url[0:]);
    migrate.Driver d = driverPool(p);
    migrate.Migrations ms = users();
    t.equal(ms.up(&d), 3);
    t.equal(migrate.version(&d), 3);
    t.check(d.conn == null, "no connection held between runs");
    t.equal(p.idleCount(), p.openCount());
    t.equal(pg.queryOne<i64>(c, "SELECT count(*) FROM users"), 1);
    ms.add(4, "broken", "SELECT * FROM nowhere", "");
    bool threw = false;
    try { ms.up(&d); } catch (Error e) { threw = e.code == migrate.ERR_MIGRATE; }
    t.check(threw, "a failure through the pool");
    t.equal(p.idleCount(), p.openCount());
    t.equal(pg.queryOne<i64>(c, "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'"), 0);
    t.equal(ms.to(&d, 0), 3);
    ms.free();
    p.close();
    close(c);
}

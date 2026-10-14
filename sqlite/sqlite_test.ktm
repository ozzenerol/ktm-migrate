module sqlite;
import "github.com/ozzenerol/ktm-migrate";
import cstr, fs, libc, sql, str, testing, vec;

static String gDir;
static String gDb;

static void write(u8[] name, u8[] text) {
    ad String p = fs.join(gDir[0:], name);
    fs.writeAll(p[0:], text);
}

static void setUp() {
    gDir = str.format("/tmp/ktm-migrate-sqlite-%d", libc.getpid());
    fs.removeDir(gDir[0:], fs.REMOVE_RECURSE);
    fs.makeDir(gDir[0:]);
    gDb = str.format("%s.db", gDir.cstr());
    fs.remove(gDb[0:]);
    write("001_create_users.up.sql", "CREATE TABLE users (id integer PRIMARY KEY, name text NOT NULL);\n");
    write("001_create_users.down.sql", "DROP TABLE users;\n");
    write("002_add_email.up.sql", "ALTER TABLE users ADD COLUMN email text;\nCREATE UNIQUE INDEX users_email ON users (email);\n");
    write("002_add_email.down.sql", "DROP INDEX users_email;\nALTER TABLE users DROP COLUMN email;\n");
    write("003_seed.up.sql", "INSERT INTO users (name, email) VALUES ('ann', 'ann@example.com');\n");
    write("003_seed.down.sql", "DELETE FROM users;\n");
}

static void tearDown() {
    fs.removeDir(gDir[0:], fs.REMOVE_RECURSE);
    fs.remove(gDb[0:]);
    gDir.free();
    gDb.free();
}

static i64 count(sql.Db *db, u8[] query) {
    sql.Row row;
    i64 n = -1;
    if (sql.select(db, &row, query)) {
        while (sql.next(&row)) { n = row.col[0].intVal; }
    }
    return n;
}

void testUpDownStatus(testing.T *t) {
    setUp();
    sql.Db *db = sql.open(gDb[0:]);
    migrate.Driver d = driver(db);
    migrate.Migrations ms = migrate.load(gDir[0:]);
    t.equal(ms.up(&d), 3);
    t.equal(migrate.version(&d), 3);
    t.equal(count(db, "SELECT count(*) FROM users WHERE email = 'ann@example.com'"), 1);
    t.equal(count(db, "SELECT count(*) FROM schema_migrations WHERE applied_at IS NOT NULL"), 3);

    /* another connection: nothing left to do */
    sql.Db *db2 = sql.open(gDb[0:]);
    migrate.Driver d2 = driver(db2);
    t.equal(ms.up(&d2), 0);
    sql.close(db2);

    t.equal(ms.down(&d, 2), 2);
    t.equal(migrate.version(&d), 1);
    t.equal(count(db, "SELECT count(*) FROM pragma_table_info('users') WHERE name = 'email'"), 0);
    vec.Vec<migrate.Status> st = ms.status(&d);
    t.equal(st.len, 3);
    t.check(st.data[0].applied && !st.data[1].applied && !st.data[2].applied, "1 applied, 2 and 3 pending");
    st.free();
    t.equal(ms.to(&d, 3), 2);
    t.equal(ms.to(&d, 0), 3);
    t.equal(count(db, "SELECT count(*) FROM sqlite_master WHERE name = 'users'"), 0);
    ms.free();
    sql.close(db);
    tearDown();
}

void testFailureRollsBack(testing.T *t) {
    setUp();
    write("004_broken.up.sql", "CREATE TABLE tags (id integer);\nINSERT INTO nowhere VALUES (1);\n");
    sql.Db *db = sql.open(gDb[0:]);
    migrate.Driver d = driver(db);
    migrate.Migrations ms = migrate.load(gDir[0:]);
    bool threw = false;
    try {
        ms.up(&d);
    } catch (Error e) {
        threw = e.code == migrate.ERR_MIGRATE &&
                str.startsWith(cstr.view(e.message), "migrate: 4_broken.up.sql: no such table: nowhere");
        if (!threw) { t.log("%s", e.message); }
    }
    t.check(threw, "the error names the file and says what");
    t.equal(migrate.version(&d), 3);
    t.equal(count(db, "SELECT count(*) FROM sqlite_master WHERE name = 'tags'"), 0);
    ms.free();
    sql.close(db);
    tearDown();
}

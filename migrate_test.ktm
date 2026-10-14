module migrate;
import cstr, fs, libc, str, testing, vec;

/* The runner against a fake database: it keeps the applied versions,
 * takes them back on ROLLBACK, and fails a script containing FAIL. It
 * records every statement, so a test can see the transactions. */
struct Fake {
    vec.Vec<i64> versions;
    vec.Vec<i64> saved;
    bool inTx;
    String log;
    i64 locks;
    i64 unlocks;
};

static i64 numberAfter(u8[] s, u8[] marker) {
    i64 at = str.find(s, marker);
    if (at < 0) { return -1; }
    i64 v = 0;
    for (i64 i = at + marker.len; i < s.len && s[i] >= '0' && s[i] <= '9'; i++) { v = v * 10 + (s[i] - '0'); }
    return v;
}

static void fakeExec(Driver *d, u8[] sql) {
    Fake *f = d.ctx;
    f.log.append(sql);
    f.log.append(";\n");
    if (str.contains(sql, "FAIL")) { throw(1, "syntax error at FAIL"); }
    if (str.startsWith(sql, "BEGIN")) {
        f.inTx = true;
        f.saved.clear();
        for (auto v : f.versions) { f.saved.push(v); }
    } else if (str.equals(sql, "ROLLBACK")) {
        f.inTx = false;
        f.versions.clear();
        for (auto v : f.saved) { f.versions.push(v); }
    } else if (str.equals(sql, "COMMIT")) {
        f.inTx = false;
    } else if (str.startsWith(sql, "INSERT INTO schema_migrations")) {
        f.versions.push(numberAfter(sql, "VALUES ("));
    } else if (str.startsWith(sql, "DELETE FROM schema_migrations")) {
        i64 v = numberAfter(sql, "version = ");
        for (i64 i = 0; i < f.versions.len; i++) {
            if (f.versions.data[i] == v) { f.versions.removeAt(i); break; }
        }
    }
}

static void fakeQueryInts(Driver *d, u8[] sql, vec.Vec<i64> *out) {
    Fake *f = d.ctx;
    f.log.append(sql);
    f.log.append(";\n");
    i64 only = numberAfter(sql, "version = ");
    vec.Vec<i64> sorted;
    for (auto v : f.versions) { if (only < 0 || v == only) { sorted.push(v); } }
    sorted.sortAsc();
    for (auto v : sorted) { out.push(v); }
    sorted.free();
}

static void fakeLock(Driver *d) { Fake *f = d.ctx; f.locks++; }
static void fakeUnlock(Driver *d) { Fake *f = d.ctx; f.unlocks++; }

static Driver fakeDriver(Fake *f) {
    Driver d;
    d.ctx = f;
    d.exec = &fakeExec;
    d.queryInts = &fakeQueryInts;
    d.lock = &fakeLock;
    d.unlock = &fakeUnlock;
    return d;
}

static void freeFake(Fake *f) {
    f.versions.free();
    f.saved.free();
    f.log.free();
}

static Migrations three() {
    Migrations ms;
    ms.add(2, "add_email", "ALTER TABLE users ADD email text", "ALTER TABLE users DROP email");
    ms.add(1, "create_users", "CREATE TABLE users (id int)", "DROP TABLE users");
    ms.add(3, "seed", "INSERT INTO users VALUES (1)", "");
    return ms;
}

void testUpDownTo(testing.T *t) {
    Fake f;
    Driver d = fakeDriver(&f);
    Migrations ms = three();
    t.equal(ms.items.data[0].version, 1);
    t.equal(version(&d), 0);
    t.equal(ms.up(&d), 3);
    t.equal(version(&d), 3);
    t.equal(ms.up(&d), 0);
    t.check(str.contains(f.log[0:], "BEGIN;\nSELECT"), "the re-check is inside the transaction");
    t.check(str.contains(f.log[0:], "CREATE TABLE users (id int);\nINSERT INTO schema_migrations (version, name) VALUES (1, 'create_users');\nCOMMIT;"),
            "the script, its row and the commit");
    /* 3 has no down */
    bool threw = false;
    try { ms.down(&d); } catch (Error e) {
        threw = e.code == ERR_MIGRATE && str.contains(cstr.view(e.message), "3_seed has no down migration");
    }
    t.check(threw, "no down migration");
    t.equal(version(&d), 3);
    t.equal(ms.to(&d, 3), 0);
    ms.items.data[2].down = str.from("DELETE FROM users");
    ms.items.data[2].hasDown = true;
    t.equal(ms.down(&d, 2), 2);
    t.equal(version(&d), 1);
    t.equal(ms.to(&d, 2), 1);
    t.equal(version(&d), 2);
    t.equal(ms.to(&d, 0), 2);
    t.equal(version(&d), 0);
    t.equal(f.locks, f.unlocks);
    t.check(f.locks > 0, "locked");
    threw = false;
    try { ms.to(&d, 9); } catch (Error e) { threw = str.contains(cstr.view(e.message), "there is no migration 9"); }
    t.check(threw, "to an unknown version");
    ms.free();
    freeFake(&f);
}

void testFailureRollsBack(testing.T *t) {
    Fake f;
    Driver d = fakeDriver(&f);
    Migrations ms = three();
    ms.add(4, "broken", "CREATE TABLE x (FAIL)", "");
    ms.add(5, "after", "SELECT 1", "");
    bool threw = false;
    try { ms.up(&d); } catch (Error e) {
        threw = e.code == ERR_MIGRATE &&
                str.equals(cstr.view(e.message), "migrate: 4_broken.up.sql: syntax error at FAIL");
        if (!threw) { t.log("%s", e.message); }
    }
    t.check(threw, "the error names the file");
    t.equal(version(&d), 3);
    t.check(str.endsWith(f.log[0:], "CREATE TABLE x (FAIL);\nROLLBACK;\nCREATE TABLE IF NOT EXISTS schema_migrations (version bigint PRIMARY KEY, name text NOT NULL, applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP);\nSELECT version FROM schema_migrations ORDER BY version;\n"),
            "rolled back, 5 not tried");
    t.equal(f.locks, f.unlocks);
    ms.free();
    freeFake(&f);
}

void testNoTransaction(testing.T *t) {
    Fake f;
    Driver d = fakeDriver(&f);
    d.begin = "BEGIN IMMEDIATE";
    Migrations ms;
    ms.add(1, "a", "CREATE TABLE a (id int)", "");
    ms.add(2, "index", "-- migrate: no-transaction\nCREATE INDEX CONCURRENTLY i ON a (id)", "");
    t.equal(ms.up(&d), 2);
    t.check(str.contains(f.log[0:], "BEGIN IMMEDIATE;"), "the driver's BEGIN");
    t.check(str.contains(f.log[0:], "COMMIT;\n-- migrate: no-transaction\nCREATE INDEX"), "no BEGIN before 2");
    ms.add(3, "bad", "-- migrate: no-transaction\nFAIL", "");
    bool threw = false;
    try { ms.up(&d); } catch (Error e) { threw = str.contains(cstr.view(e.message), "it ran without a transaction"); }
    t.check(threw, "says it ran without one");
    ms.free();
    freeFake(&f);
}

/* another instance applied it between our read and our transaction */
static i64 gRaceAt = 0;
static Fake *gRacing;
static void racingExec(Driver *d, u8[] sql) {
    if (gRaceAt && str.startsWith(sql, "BEGIN")) {
        gRacing.versions.push(gRaceAt);
        fakeExec(d, sql);
        gRaceAt = 0;
        return;
    }
    fakeExec(d, sql);
}

void testRecheckInsideTransaction(testing.T *t) {
    Fake f;
    gRacing = &f;
    Driver d = fakeDriver(&f);
    d.exec = &racingExec;
    Migrations ms = three();
    gRaceAt = 1;
    t.equal(ms.up(&d), 2);
    t.equal(f.versions.len, 3);
    ms.free();
    freeFake(&f);
}

void testStatus(testing.T *t) {
    Fake f;
    Driver d = fakeDriver(&f);
    Migrations ms = three();
    ms.to(&d, 2);
    f.versions.push(7);             /* applied by a newer program */
    vec.Vec<Status> st = ms.status(&d);
    t.equal(st.len, 4);
    t.check(st.data[0].applied && str.equals(st.data[0].name, "create_users"), "1 applied");
    t.check(st.data[1].applied && !st.data[2].applied, "2 applied, 3 pending");
    t.check(st.data[3].version == 7 && st.data[3].applied && st.data[3].name.len == 0, "7 without a file");
    st.free();
    bool threw = false;
    try { ms.down(&d); } catch (Error e) { threw = str.contains(cstr.view(e.message), "version 7 is applied, but there is no migration 7 here"); }
    t.check(threw, "down past an unknown version");
    ms.free();
    freeFake(&f);
}

void testAdd(testing.T *t) {
    Migrations ms;
    ms.add(1, "ok", "SELECT 1");
    i64 threw = 0;
    try { ms.add(0, "x", ""); } catch (Error e) { threw++; }
    try { ms.add(2, "a b", ""); } catch (Error e) { threw++; }
    try { ms.add(3, "", ""); } catch (Error e) { threw++; }
    try { ms.add(1, "again", ""); } catch (Error e) { threw += str.contains(cstr.view(e.message), "version 1 is there twice"); }
    t.equal(threw, 4);
    t.equal(ms.items.len, 1);
    t.check(!ms.items.data[0].hasDown, "no down given");
    ms.free();
}

static String gDir;

static void write(u8[] name, u8[] text) {
    ad String p = fs.join(gDir[0:], name);
    fs.writeAll(p[0:], text);
}

static String loadError() {
    try {
        Migrations ms = load(gDir[0:]);
        ms.free();
    } catch (Error e) {
        return str.from(cstr.view(e.message));
    }
    return str.from("");
}

static void fresh() {
    gDir.free();
    gDir = str.format("/tmp/ktm-migrate-test-%d", libc.getpid());
    fs.removeDir(gDir[0:], fs.REMOVE_RECURSE);
    fs.makeDir(gDir[0:]);
}

void testLoad(testing.T *t) {
    fresh();
    write("001_create_users.up.sql", "CREATE TABLE users (id int);\n");
    write("001_create_users.down.sql", "DROP TABLE users;\n");
    write("10_seed.up.sql", "INSERT INTO users VALUES (1);\n");
    write("002_add-email.up.sql", "");
    write("README.md", "not a migration");
    Migrations ms = load(gDir[0:]);
    t.equal(ms.items.len, 3);
    t.equal(ms.items.data[0].version, 1);
    t.equal(ms.items.data[1].name, "add-email");
    t.equal(ms.items.data[1].up, "");
    t.equal(ms.items.data[2].version, 10);
    t.check(ms.items.data[0].hasDown && !ms.items.data[2].hasDown, "downs");
    t.equal(ms.items.data[0].down, "DROP TABLE users;\n");
    ms.free();

    write("003_x.sql", "");
    ad String e1 = loadError();
    t.check(str.contains(e1[0:], "003_x.sql: name it NNN_name.up.sql or NNN_name.down.sql"), "%s", e1.cstr());
    fresh();
    write("004_a.up.sql", "");
    write("004_b.up.sql", "");
    ad String e2 = loadError();
    t.check(str.contains(e2[0:], "version 4 is") && str.contains(e2[0:], "one name per version"), "%s", e2.cstr());
    fresh();
    write("005_c.down.sql", "");
    ad String e3 = loadError();
    t.equal(e3, "migrate: 5_c.down.sql has no 5_c.up.sql");
    fresh();
    ad String e4 = loadError();
    t.equal(e4, "");
    fs.removeDir(gDir[0:], fs.REMOVE_RECURSE);
    ad String e5 = loadError();
    t.check(str.contains(e5[0:], "can't read the directory"), "%s", e5.cstr());
    gDir.free();
}

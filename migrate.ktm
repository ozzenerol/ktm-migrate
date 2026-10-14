module migrate;
import fs, str, vec;

/* Versioned SQL migrations for any SQL engine:
 *
 *   migrations/001_create_users.up.sql     CREATE TABLE users (...);
 *   migrations/001_create_users.down.sql   DROP TABLE users;
 *   migrations/002_add_email.up.sql        ...
 *
 *   migrate.Migrations ms = migrate.load("migrations");
 *   defer ms.free();
 *   migrate.Driver d = postgres.driver(conn);     // or sqlite.driver(db)
 *   i64 n = ms.up(&d);                            // every pending one, in order
 *
 * The database keeps what ran in a table, schema_migrations (version,
 * name, applied_at). Each migration runs in a transaction with its row
 * in that table, so it happens whole or not at all; a script whose first
 * line is `-- migrate: no-transaction` runs without one (Postgres'
 * CREATE INDEX CONCURRENTLY). The driver's lock keeps two instances of
 * a program from migrating at once. Every failure throws ERR_MIGRATE
 * naming the file. */

/* What load and the runner throw */
pub const i64 ERR_MIGRATE = 0x6D670001;

/* What the runner needs from a database: the sub-packages postgres and
 * sqlite make one, and any engine can (see the README). Each function
 * gets the Driver and throws on an error. */
pub struct Driver;
pub struct Driver {
    void *ctx;          /* the driver's: its connection, database, pool */
    void *conn;         /* the driver's too: what lock() took for the run, if anything */
    /* runs a script of any number of statements, no parameters */
    fn(Driver *, u8[]) void exec;
    /* runs a query and appends the first column of each row, an integer */
    fn(Driver *, u8[], vec.Vec<i64> *) void queryInts;
    /* called before and after every run (unlock also after a failure):
     * null for nothing */
    fn(Driver *) void lock;
    fn(Driver *) void unlock;
    /* the statement that starts a transaction: "" for BEGIN */
    u8[] begin;
};

/* One migration: its version and name (from 001_create_users), the up
 * script and the down one ("" with hasDown false when there is none) */
pub struct Migration {
    i64 version;
    String name;
    String up;
    String down;
    bool hasDown;
};

/* Migrations in version order */
pub struct Migrations {
    vec.Vec<Migration> items;
};

/* A migration's state, from status(): name is a view of the Migrations
 * ("" for a version the database has but no migration here) */
pub struct Status {
    i64 version;
    u8[] name;
    bool applied;
};

pub void Migrations.free() {
    for (i64 i = 0; i < self.items.len; i++) {
        Migration *m = self.items.at(i);
        m.name.free();
        m.up.free();
        m.down.free();
    }
    self.items.free();
}

static bool nameOk(u8[] name) {
    if (!name.len) { return false; }
    for (auto c : name) {
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
            return false;
        }
    }
    return true;
}

static Migration *find(Migrations *ms, i64 version) {
    for (i64 i = 0; i < ms.items.len; i++) {
        if (ms.items.data[i].version == version) { return ms.items.at(i); }
    }
    return null;
}

static i64 byVersion(Migration *a, Migration *b) {
    return a.version < b.version ? -1 : a.version > b.version ? 1 : 0;
}

/* Adds a migration written in the program (copies of the texts): for
 * migrations built into the binary instead of read from files */
pub void Migrations.add(i64 version, u8[] name, u8[] up, u8[] down = "", bool hasDown = true) {
    if (version <= 0) {
        throw(ERR_MIGRATE, "migrate: version %d: versions start at 1", version);
    }
    if (!nameOk(name)) {
        throw(ERR_MIGRATE, "migrate: %d_%.*s: a name is letters, digits, _ and -", version, name.len, name.ptr);
    }
    if (find(self, version)) {
        throw(ERR_MIGRATE, "migrate: version %d is there twice", version);
    }
    Migration m;
    m.version = version;
    m.name = str.from(name);
    m.up = str.from(up);
    m.down = str.from(down);
    m.hasDown = hasDown && down.len > 0;
    self.items.push(m);
    self.items.sort(&byVersion);
}

/* "001_create_users.up.sql" into its parts; false when it isn't one */
static bool parseName(u8[] file, i64 *version, u8[] *name, bool *up) {
    u8[] rest;
    if (str.endsWith(file, ".up.sql")) {
        *up = true;
        rest = file[0:file.len - 7];
    } else if (str.endsWith(file, ".down.sql")) {
        *up = false;
        rest = file[0:file.len - 9];
    } else {
        return false;
    }
    i64 us = str.find(rest, "_");
    if (us <= 0 || us > 18) { return false; }
    i64 v = 0;
    for (i64 i = 0; i < us; i++) {
        if (rest[i] < '0' || rest[i] > '9') { return false; }
        v = v * 10 + (rest[i] - '0');
    }
    *version = v;
    *name = rest[us + 1:];
    return v > 0 && nameOk(*name);
}

static void loadFile(Migrations *ms, u8[] dir, u8[] file) {
    if (!str.endsWith(file, ".sql")) { return; }
    i64 version;
    u8[] name;
    bool up;
    if (!parseName(file, &version, &name, &up)) {
        throw(ERR_MIGRATE, "migrate: %.*s: name it NNN_name.up.sql or NNN_name.down.sql "
              "(NNN a number, the name letters, digits, _ and -)", file.len, file.ptr);
    }
    Migration *m = find(ms, version);
    if (m && !str.equals(m.name[0:], name)) {
        throw(ERR_MIGRATE, "migrate: version %d is %s and %.*s: one name per version",
              version, m.name.cstr(), name.len, name.ptr);
    }
    ad String path = fs.join(dir, file);
    String text = fs.readAll(path[0:]);
    if (!text.ptr) {
        throw(ERR_MIGRATE, "migrate: can't read %s", path.cstr());
    }
    if (!m) {
        Migration n;
        n.version = version;
        n.name = str.from(name);
        ms.items.push(n);
        m = ms.items.at(ms.items.len - 1);
    }
    if (up) {
        m.up.free();
        m.up = text;
        if (!m.up.ptr) { m.up = str.from(""); }
    } else {
        m.down.free();
        m.down = text;
        m.hasDown = true;
    }
}

/* The migrations in dir: every NNN_name.up.sql and NNN_name.down.sql
 * (any number of digits: 001, 20261014120000). Other files are left
 * alone, but a .sql file named otherwise, a version used by two names,
 * a down without its up or a file twice throw ERR_MIGRATE. */
pub Migrations load(u8[] dir) {
    bool ok;
    vec.Vec<String> names = fs.list(dir, &ok);
    defer fs.freeList(&names);
    if (!ok) {
        throw(ERR_MIGRATE, "migrate: can't read the directory %.*s", dir.len, dir.ptr);
    }
    Migrations ms;
    try {
        for (i64 i = 0; i < names.len; i++) {
            loadFile(&ms, dir, names.data[i][0:]);
        }
        for (i64 i = 0; i < ms.items.len; i++) {
            Migration *m = ms.items.at(i);
            if (!m.up.ptr) {
                throw(ERR_MIGRATE, "migrate: %d_%s.down.sql has no %d_%s.up.sql", m.version, m.name.cstr(),
                      m.version, m.name.cstr());
            }
        }
    } catch (Error e) {
        ms.free();
        throw(e);
    }
    ms.items.sort(&byVersion);
    return ms;
}

/* ---- the runner ---- */

static void run(Driver *d, u8[] sql) {
    d.exec(d, sql);
}

static vec.Vec<i64> ints(Driver *d, u8[] sql) {
    vec.Vec<i64> out;
    d.queryInts(d, sql, &out);
    return out;
}

static void ensureTable(Driver *d) {
    ad String sql = str.format("CREATE TABLE IF NOT EXISTS schema_migrations (version bigint PRIMARY KEY, name text NOT NULL, "
                               "applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP)");
    run(d, sql[0:]);
}

/* The versions the database has, in order */
static vec.Vec<i64> applied(Driver *d) {
    ad String sql = str.from("SELECT version FROM schema_migrations ORDER BY version");
    return ints(d, sql[0:]);
}

static bool isApplied(Driver *d, i64 version) {
    ad String sql = str.format("SELECT version FROM schema_migrations WHERE version = %d", version);
    vec.Vec<i64> v = ints(d, sql[0:]);
    bool yes = v.len > 0;
    v.free();
    return yes;
}

/* Does the script ask to run outside a transaction? */
static bool noTransaction(u8[] sql) {
    u8[] s = str.trimStart(sql);
    i64 nl = str.find(s, "\n");
    u8[] first = str.trim(nl < 0 ? s : s[0:nl]);
    return str.equals(first, "-- migrate: no-transaction");
}

/* Runs one migration's script up or down, with its row in the table;
 * false when another instance already did it */
static bool step(Driver *d, Migration *m, bool up) {
    u8[] script = up ? m.up[0:] : m.down[0:];
    bool tx = !noTransaction(script);
    u8 *file = up ? "up" : "down";
    if (tx) { run(d, d.begin.len ? d.begin : "BEGIN"); }
    try {
        if (tx && isApplied(d, m.version) == up) {
            run(d, "ROLLBACK");
            return false;
        }
        run(d, script);
        ad String row = up
            ? str.format("INSERT INTO schema_migrations (version, name) VALUES (%d, '%s')", m.version, m.name.cstr())
            : str.format("DELETE FROM schema_migrations WHERE version = %d", m.version);
        run(d, row[0:]);
        if (tx) { run(d, "COMMIT"); }
    } catch (Error e) {
        if (tx) {
            try { run(d, "ROLLBACK"); } catch (Error e2) { }
        }
        throw(ERR_MIGRATE, "migrate: %d_%s.%s.sql: %s%s", m.version, m.name.cstr(), file, e.message,
              tx ? "" : " (it ran without a transaction: what ran before the error stays)");
    }
    return true;
}

static void lockIt(Driver *d) {
    if (!d.exec || !d.queryInts) {
        throw(ERR_MIGRATE, "migrate: a Driver needs exec and queryInts");
    }
    if (d.lock) { d.lock(d); }
}

static void unlockIt(Driver *d) {
    if (d.unlock) { d.unlock(d); }
}

/* Applies the pending migrations up to version `target` (every one for
 * 0), in version order; answers how many ran. One that was added after
 * later ones already ran (from another branch) runs too. */
static i64 upTo(Migrations *ms, Driver *d, i64 target) {
    vec.Vec<i64> done = applied(d);
    defer done.free();
    i64 n = 0;
    for (i64 i = 0; i < ms.items.len; i++) {
        Migration *m = ms.items.at(i);
        if (target && m.version > target) { break; }
        if (done.contains(m.version)) { continue; }
        if (step(d, m, true)) { n++; }
    }
    return n;
}

/* Reverts the applied migrations above version `keep`, at most `max`
 * of them (-1: all), newest first; answers how many ran */
static i64 downTo(Migrations *ms, Driver *d, i64 keep, i64 max) {
    vec.Vec<i64> done = applied(d);
    defer done.free();
    i64 n = 0;
    for (i64 i = done.len - 1; i >= 0 && (max < 0 || n < max); i--) {
        i64 v = done.data[i];
        if (v <= keep) { break; }
        Migration *m = find(ms, v);
        if (!m) {
            throw(ERR_MIGRATE, "migrate: version %d is applied, but there is no migration %d here", v, v);
        }
        if (!m.hasDown) {
            throw(ERR_MIGRATE, "migrate: %d_%s has no down migration", m.version, m.name.cstr());
        }
        if (step(d, m, false)) { n++; }
    }
    return n;
}

/* Applies every pending migration, in version order: how many ran */
pub i64 Migrations.up(Driver *d) {
    lockIt(d);
    try {
        ensureTable(d);
        i64 n = upTo(self, d, 0);
        unlockIt(d);
        return n;
    } catch (Error e) {
        unlockIt(d);
        throw(e);
    }
    return 0;
}

/* Reverts the newest n applied migrations (1 by default): how many ran */
pub i64 Migrations.down(Driver *d, i64 n = 1) {
    lockIt(d);
    try {
        ensureTable(d);
        i64 r = downTo(self, d, 0, n);
        unlockIt(d);
        return r;
    } catch (Error e) {
        unlockIt(d);
        throw(e);
    }
    return 0;
}

/* Migrates up or down until version is the newest applied (0: every
 * migration reverted): how many ran */
pub i64 Migrations.to(Driver *d, i64 version) {
    if (version && !find(self, version)) {
        throw(ERR_MIGRATE, "migrate: there is no migration %d", version);
    }
    lockIt(d);
    try {
        ensureTable(d);
        i64 r = downTo(self, d, version, -1) + (version ? upTo(self, d, version) : 0);
        unlockIt(d);
        return r;
    } catch (Error e) {
        unlockIt(d);
        throw(e);
    }
    return 0;
}

static i64 statusOrder(Status *a, Status *b) {
    return a.version < b.version ? -1 : a.version > b.version ? 1 : 0;
}

/* Every migration here and every version the database has, in version
 * order, with whether it is applied */
pub vec.Vec<Status> Migrations.status(Driver *d) {
    lockIt(d);
    vec.Vec<i64> done;
    try {
        ensureTable(d);
        done = applied(d);
        unlockIt(d);
    } catch (Error e) {
        unlockIt(d);
        throw(e);
    }
    defer done.free();
    vec.Vec<Status> out;
    for (i64 i = 0; i < self.items.len; i++) {
        Status s;
        s.version = self.items.data[i].version;
        s.name = self.items.data[i].name[0:];
        s.applied = done.contains(s.version);
        out.push(s);
    }
    for (i64 i = 0; i < done.len; i++) {
        if (find(self, done.data[i])) { continue; }
        Status s;
        s.version = done.data[i];
        s.name = "";
        s.applied = true;
        out.push(s);
    }
    out.sort(&statusOrder);
    return out;
}

/* The newest applied version, 0 for none */
pub i64 version(Driver *d) {
    lockIt(d);
    vec.Vec<i64> done;
    try {
        ensureTable(d);
        done = applied(d);
        unlockIt(d);
    } catch (Error e) {
        unlockIt(d);
        throw(e);
    }
    i64 v = done.len ? done.data[done.len - 1] : 0;
    done.free();
    return v;
}

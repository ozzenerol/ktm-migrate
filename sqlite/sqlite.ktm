module sqlite;
import "github.com/ozzenerol/ktm-migrate";
import sql, vec;

#ifndef __KTM_LINK_SQLITE3__
#error "ktm-migrate/sqlite needs ktm built with SQLite (./build.sh --sqlite)"
#endif

/* migrate.Driver over an SQLite database of ktm's sql module (ktm built
 * with ./build.sh --sqlite):
 *
 *   sql.Db *db = sql.open("app.db");
 *   migrate.Driver d = sqlite.driver(db);
 *   ms.up(&d);
 *
 * Each migration's transaction starts with BEGIN IMMEDIATE, which takes
 * the database's write lock: a second program migrating the same file
 * waits for it (up to the busy timeout), then skips what already ran. */

static void sqliteExec(void *ctx, u8[] script) {
    ad String err = sql.execRaw(ctx, script);
    if (err.len) { throw(migrate.ERR_MIGRATE, "%s", err.cstr()); }
}

static void sqliteQueryInts(void *ctx, u8[] query, vec.Vec<i64> *out) {
    sql.Row row;
    if (!sql.select(ctx, &row, query)) {
        sql.Db *db = ctx;
        throw(migrate.ERR_MIGRATE, "%s", sql.sqlite3_errmsg(db.conn));
    }
    while (sql.next(&row)) { out.push(row.col[0].intVal); }
}

/* A Driver for db (which stays yours to close) */
pub migrate.Driver driver(sql.Db *db) {
    sqliteExec(db, "PRAGMA busy_timeout = 10000");
    migrate.Driver d;
    d.ctx = db;
    d.exec = &sqliteExec;
    d.queryInts = &sqliteQueryInts;
    d.begin = "BEGIN IMMEDIATE";
    return d;
}

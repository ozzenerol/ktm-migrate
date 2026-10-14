# ktm-migrate

Versioned SQL migrations for [ktm](https://github.com/ozzenerol/ktm-lang), for any SQL engine.
The core package has no dependencies. Two drivers come with it: PostgreSQL through
[ktm-pg](https://github.com/ozzenerol/ktm-pg) and SQLite through ktm's `sql` module. Any other
engine needs a driver of four functions.

```c
import "github.com/ozzenerol/ktm-migrate";            // the package is named migrate
import "github.com/ozzenerol/ktm-migrate/postgres";   // postgres.driver(conn)
import "github.com/ozzenerol/ktm-migrate/sqlite";     // sqlite.driver(db)
```

- Migrations are SQL files: `001_create_users.up.sql`, `001_create_users.down.sql`.
- Each one runs in a transaction together with its row in `schema_migrations`, so it happens whole
  or not at all.
- A lock means two instances of a program that start together don't both migrate.
- `up`, `down`, `to` a version, `status`, `version`.
- Errors name the file and say what the database said.

## A first program

```
migrations/
  001_create_users.up.sql      CREATE TABLE users (id bigserial PRIMARY KEY, name text NOT NULL);
  001_create_users.down.sql    DROP TABLE users;
  002_add_email.up.sql         ALTER TABLE users ADD email text UNIQUE;
  002_add_email.down.sql       ALTER TABLE users DROP email;
```

```c
import "github.com/ozzenerol/ktm-pg";
import "github.com/ozzenerol/ktm-migrate";
import "github.com/ozzenerol/ktm-migrate/postgres";

i32 main()
{
    pg.Conn *c = pg.connect("postgres://ann:secret@localhost:5432/app");
    defer c.close();

    migrate.Migrations ms = migrate.load("migrations");
    defer ms.free();
    migrate.Driver d = postgres.driver(c);
    i64 n = ms.up(&d);
    printf("%d migrations ran, the database is at version %d\n", n, migrate.version(&d));
    return 0;
}
```

```
$ ktm get github.com/ozzenerol/ktm-migrate
$ ktm run .
2 migrations ran, the database is at version 2
```

Run it at start-up, before the server listens, or as its own command (`app migrate up`).

## Migration files

- The name is `NNN_name.up.sql` or `NNN_name.down.sql`. `NNN` is the version, a whole number with
  any number of digits (`001`, `42`, `20261014120000`). The name is letters, digits, `_` and `-`.
- Files run in version order. A version has one name, its up file and optionally a down file.
- Other files in the directory (a README) are left alone. A `.sql` file named some other way is an
  error, so a typo doesn't go unnoticed.
- A script holds any number of statements.
- A script whose first line is `-- migrate: no-transaction` runs without a transaction. Use it
  for statements Postgres refuses inside one, such as `CREATE INDEX CONCURRENTLY`. If such a
  script fails partway, the statements before the error stay done, and the error says so.

To build migrations into the binary instead of reading files, add them in code:

```c
migrate.Migrations ms;
ms.add(1, "create_users", "CREATE TABLE users (id bigserial PRIMARY KEY, name text NOT NULL)",
                          "DROP TABLE users");
ms.add(2, "add_email", "ALTER TABLE users ADD email text UNIQUE", "ALTER TABLE users DROP email");
```

## Running them

| Call | What it does |
| --- | --- |
| `migrate.load(dir)` | The migrations in a directory, in version order |
| `ms.add(version, name, up, down)` | Adds one written in the program (`down` may be `""`) |
| `ms.up(&d)` | Applies every pending migration in order and returns how many ran |
| `ms.down(&d, n)` | Reverts the newest `n` applied migrations (1 by default) |
| `ms.to(&d, version)` | Goes up or down until `version` is the newest applied; `0` reverts everything |
| `ms.status(&d)` | A `vec.Vec<migrate.Status>`: each version, its name, and whether it is applied |
| `migrate.version(&d)` | The newest applied version, `0` for none |
| `ms.free()` | Frees the migrations |

- The database records what ran in `schema_migrations (version, name, applied_at)`, which the
  runner creates when it isn't there.
- A failure throws `migrate.ERR_MIGRATE`, with the file and the database's message:
  `migrate: 4_add_tags.up.sql: relation "tag" does not exist`. The failed migration is rolled back,
  and the ones before it stay applied.
- Merged branches: `up` also applies a pending migration whose version is lower than the newest
  applied one.
- `status` also lists a version the database has but no file here (a newer build ran it), with
  an empty name. `down` refuses to go past such a version.

## Drivers

### PostgreSQL

`postgres.driver(conn)` takes a `pg.Conn *`, which stays yours to close. While it works, the runner
holds `pg_advisory_lock(postgres.LOCK_KEY)`, so instances that start together take turns, and the
later ones find nothing left to do. The lock belongs to the connection: use one connection for
the whole run. From a pool, that's `pool.acquire()`.

### SQLite

`sqlite.driver(db)` takes an `sql.Db *` from ktm's `sql` module. That module only exists in a ktm
built with `./build.sh --sqlite`; on any other build, importing this package stops with a message
saying so. Each migration starts with `BEGIN IMMEDIATE`, which takes the database's write lock. A
second program that migrates the same file waits for it (`busy_timeout` 10 s), then skips what
already ran.

### Writing one

A `migrate.Driver` is four functions and a context. Each one throws on an error.

```c
migrate.Driver d;
d.ctx = myConn;
d.exec = &myExec;            // fn(void *ctx, u8[] script) void: runs a script of statements
d.queryInts = &myQueryInts;  // fn(void *ctx, u8[] sql, vec.Vec<i64> *out) void: the first column of each row
d.lock = &myLock;            // fn(void *ctx) void, or null: held while the runner works
d.unlock = &myUnlock;
d.begin = "BEGIN";           // the statement that starts a transaction ("" is BEGIN)
```

The runner only sends `CREATE TABLE IF NOT EXISTS`, `SELECT`, `INSERT`, `DELETE`, the begin
statement, `COMMIT`, `ROLLBACK` and your scripts. An engine that runs those works.

## Dependencies

Everything is in one repository, so `ktm get github.com/ozzenerol/ktm-migrate` also downloads
ktm-pg (the postgres driver needs it) and adds it to your `ktm.mod` as `// indirect`. ktm-pg is
compiled only when a program imports `ktm-migrate/postgres`. The core and the SQLite driver don't
use it.

## Tests

```
$ scripts/test.sh            # every package, compiled and in the JIT with -Memsafe
```

`scripts/test.sh` starts a PostgreSQL container (`ktm-migrate-pg`, port 55498) and runs
`ktm test ./...` with `KTM_MIGRATE_PG` pointing at it. The core's tests use a fake driver and run
anywhere. The SQLite tests need a ktm built with `--sqlite`: set `KTM_SQLITE` to that ktm checkout,
or they are left out. Without `KTM_MIGRATE_PG`, the Postgres tests are
skipped.

## License

BSD-2-Clause, see [LICENSE](LICENSE).

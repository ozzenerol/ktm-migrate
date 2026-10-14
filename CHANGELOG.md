# Changelog

## v0.1.0 (unreleased)

The first release: versioned SQL migrations for ktm, for any SQL engine.

- Migrations from a directory (`NNN_name.up.sql`, `NNN_name.down.sql`) or added in code.
- `up`, `down(n)`, `to(version)`, `status`, `version`, recorded in `schema_migrations`.
- Each migration in a transaction with its row; `-- migrate: no-transaction` to opt out.
- A lock while migrating, and a re-check inside each transaction, so instances that start
  together don't both migrate.
- Errors throw `migrate.ERR_MIGRATE` naming the file, with the database's message.
- Drivers: `ktm-migrate/postgres` (ktm-pg, `pg_advisory_lock`; `driver(conn)` and
  `driverPool(pool)`, which uses one connection of the pool per run) and `ktm-migrate/sqlite` (ktm's
  `sql` module, `BEGIN IMMEDIATE`); the `migrate.Driver` interface for any other engine
  (functions that get the Driver, with `ctx` and `conn` for the driver's own use).
- Tested with `ktm test` against a fake driver, SQLite and PostgreSQL 17, compiled and in the JIT
  with `-Memsafe`.

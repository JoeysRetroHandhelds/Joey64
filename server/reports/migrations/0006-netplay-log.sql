-- 2026-11-14: a netplay room's figures and the log excerpt the app sends with a session that ended badly or played in
-- a room (Joey: "send reports needs to have more information on net play and crashes"). sessions.ended also takes
-- next, stuck, anr, killed, closed, signal and gone now (no schema change: it's TEXT). Run once on a database made
-- from the schema.sql before that day (the live one): ADD COLUMN can't run twice. Run it before deploying the Worker
-- that writes them.
-- wrangler d1 execute joey64-reports --remote --file migrations/0006-netplay-log.sql
ALTER TABLE sessions ADD COLUMN netplay TEXT;
ALTER TABLE sessions ADD COLUMN log TEXT;

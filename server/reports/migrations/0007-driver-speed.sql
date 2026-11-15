-- 2026-11-15: each session's mean work a frame and the driver that ran (approved drvspeed mockup, screen 4), for
-- devtools/driver-order.py's ranking of drivers per GPU. Run once on the live database, before deploying the Worker
-- that writes them: ADD COLUMN can't run twice.
-- wrangler d1 execute joey64-reports --remote --file migrations/0007-driver-speed.sql
ALTER TABLE sessions ADD COLUMN work_ms REAL;
ALTER TABLE sessions ADD COLUMN driver_id TEXT;

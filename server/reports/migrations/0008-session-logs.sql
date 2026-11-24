-- 2026-11-24: whole session logs (Joey: reports should carry everything the log does; the 16,000-character excerpt left
-- out the session's start: device, firmware, display, options). A session event may carry logId (32 hex, made by the
-- app); the app then posts that session's whole cleaned log, gzipped, to POST /v1/log?id=<logId> (src/logs.js), which
-- goes to the R2 bucket joey64-logs under log_key, with its size in log_bytes. Run once on a database made from the
-- schema.sql before that day (the live one): ADD COLUMN can't run twice. Run it before deploying the Worker that writes
-- them.
-- wrangler d1 execute joey64-reports --remote --file migrations/0008-session-logs.sql
ALTER TABLE sessions ADD COLUMN log_id TEXT;
ALTER TABLE sessions ADD COLUMN log_bytes INTEGER;
ALTER TABLE sessions ADD COLUMN log_key TEXT;
CREATE INDEX IF NOT EXISTS sessions_log_id ON sessions (log_id);
-- Logs taken today, by address block's hash (for POST /v1/log's limits; days before are deleted as logs come in).
CREATE TABLE IF NOT EXISTS logs_sent (
  day TEXT NOT NULL,
  who TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS logs_sent_day ON logs_sent (day, who);

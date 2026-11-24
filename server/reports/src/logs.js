// POST /v1/log?id=<logId>: a session's whole log (Joey 2026-11-24: reports should carry everything the log does; the
// excerpt in the session row starts mid-game, so the device, firmware, display and options lines were never there).
// The app sends a session event with logId (validate.js), and once the report is taken posts that session's process
// log, cleaned on the device as the excerpt is (paths, player names, public addresses out) and gzipped. It goes to the
// R2 bucket joey64-logs, private: never served back; we read it with devtools/report-log.py --full.
//
// Body: the gzipped log (application/gzip). Answers: 204 stored; 400 a bad id or not gzip; 404 no session has that id
// (the app drops it); 409 that session's log is here already (dropped); 410 the session is too old (dropped); 413 too
// big (dropped); 429 with Retry-After when a limit is reached (sent again later).
import { blockOf, limiterKeyOf, scramble } from "./address.js";
import { today } from "./store.js";

/** One log, gzipped. An hour's log is about 1.5 MB raw, 150 KB gzipped; the app keeps at most 32 MB raw. */
export const MAX_LOG_GZ = 4 << 20;
/** A log is taken up to this many days after its report (the app sends right after the report; retries in between). */
export const LOG_DAYS = 7;
/** Logs one /24 or /64 can send in a day, and the whole server. */
export const BLOCK_DAY_LOGS = 60;
export const DAY_LOGS = 3000;
/** All logs kept: R2's free 10 GB is shared with the states (state.js keeps those under 8 GB). */
export const MAX_TOTAL_LOG_BYTES = 1536 << 20;

const GZIP = [0x1f, 0x8b, 0x08];

function secondsToMidnight() {
  const now = new Date();
  const next = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1);
  return Math.max(60, Math.ceil((next - now.getTime()) / 1000));
}

function daysBefore(day, n) {
  const d = new Date(day + "T00:00:00Z");
  d.setUTCDate(d.getUTCDate() - n);
  return d.toISOString().slice(0, 10);
}

export async function handleLog(request, env, text) {
  if (request.method !== "POST") return text(405, "POST only");
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  if (env.PER_IP) {
    const { success } = await env.PER_IP.limit({ key: limiterKeyOf(ip) });
    if (!success) return text(429, "slow down", { "retry-after": "60" });
  }
  const id = new URL(request.url).searchParams.get("id") || "";
  if (!/^[0-9a-f]{32}$/.test(id)) return text(400, "bad id");
  const length = Number(request.headers.get("content-length") || "0");
  if (length > MAX_LOG_GZ) return text(413, "too big");

  const session = await env.DB.prepare("SELECT s.rowid AS row, s.log_bytes AS bytes, r.day AS day FROM sessions s JOIN reports r ON r.id = s.report WHERE s.log_id = ? LIMIT 1")
    .bind(id).first();
  if (!session) return text(404, "unknown log");
  if (session.bytes !== null && session.bytes !== undefined) return text(409, "have it");
  const day = new Date().toISOString().slice(0, 10);
  if (session.day < daysBefore(day, LOG_DAYS)) return text(410, "too old");

  const body = await request.arrayBuffer();
  if (body.byteLength > MAX_LOG_GZ) return text(413, "too big");
  const head = new Uint8Array(body, 0, Math.min(3, body.byteLength));
  if (body.byteLength < 20 || GZIP.some((b, i) => head[i] !== b)) return text(400, "not gzip");

  const total = await env.DB.prepare("SELECT coalesce(sum(log_bytes), 0) AS b FROM sessions WHERE log_bytes IS NOT NULL").first("b");
  if (total + body.byteLength > MAX_TOTAL_LOG_BYTES) return text(429, "storage full", { "retry-after": "86400" });
  const t = await today(env.DB, day);
  const dayCount = await env.DB.prepare("SELECT count(*) AS n FROM logs_sent WHERE day = ?").bind(day).first("n");
  if (dayCount >= DAY_LOGS) return text(429, "full for today", { "retry-after": String(secondsToMidnight()) });
  const who = await scramble(t.salt, blockOf(ip));
  const mine = await env.DB.prepare("SELECT count(*) AS n FROM logs_sent WHERE day = ? AND who = ?").bind(day, who).first("n");
  if (mine >= BLOCK_DAY_LOGS) return text(429, "enough from this address today", { "retry-after": String(secondsToMidnight()) });

  const key = `logs/${session.day}/${id}.log.gz`;
  await env.LOGS.put(key, body, { httpMetadata: { contentType: "application/gzip" } });
  try {
    await env.DB.batch([
      env.DB.prepare("UPDATE sessions SET log_bytes = ?, log_key = ? WHERE log_id = ? AND log_bytes IS NULL").bind(body.byteLength, key, id),
      // The day's count of logs, by address block's hash: only for today's limits (the salt goes with the day, and so do
      // these rows: store.js today() deletes days before).
      env.DB.prepare("INSERT INTO logs_sent (day, who) VALUES (?, ?)").bind(day, who),
      env.DB.prepare("DELETE FROM logs_sent WHERE day < ?").bind(day),
      env.DB.prepare("UPDATE days SET written = written + 3 WHERE day = ?").bind(day),
    ]);
  } catch (e) {
    await env.LOGS.delete(key);
    throw e;
  }
  return text(204, null);
}

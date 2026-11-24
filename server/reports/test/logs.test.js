import test from "node:test";
import assert from "node:assert/strict";
import { gzipSync } from "node:zlib";
import worker from "../src/index.js";
import { BLOCK_DAY_LOGS, MAX_LOG_GZ } from "../src/logs.js";
import { rowsOf } from "../src/store.js";
import { validate, Invalid } from "../src/validate.js";
import { good } from "./fixture.js";

const ID = "0123456789abcdef0123456789abcdef";
const OTHER = "fedcba9876543210fedcba9876543210";
const TODAY = new Date().toISOString().slice(0, 10);

/** Enough of D1 for the log route: sessions joined to their report's day, the day's row, logs_sent. */
function fakeDb(sessions) {
  const t = { sessions, days: {}, logs: [] };
  const prep = (sql) => ({
    sql, args: [],
    bind(...a) { this.args = a; return this; },
    async first(col) {
      const a = this.args;
      let v;
      if (sql.startsWith("SELECT s.rowid")) { const s = t.sessions.find((x) => x.log_id === a[0]); v = s ? { row: 1, bytes: s.log_bytes ?? null, day: s.day } : null; }
      else if (sql.startsWith("SELECT coalesce(sum(log_bytes)")) v = { b: t.sessions.reduce((x, s) => x + (s.log_bytes ?? 0), 0) };
      else if (sql.startsWith("SELECT written, bytes, salt FROM days")) v = t.days[a[0]] ? { ...t.days[a[0]] } : null;
      else if (sql.startsWith("SELECT count(*) AS n FROM logs_sent WHERE day = ? AND who")) v = { n: t.logs.filter((l) => l.day === a[0] && l.who === a[1]).length };
      else if (sql.startsWith("SELECT count(*) AS n FROM logs_sent WHERE day")) v = { n: t.logs.filter((l) => l.day === a[0]).length };
      else throw new Error("unexpected " + sql);
      return v && col ? v[col] : v;
    },
    async run() {
      const a = this.args;
      if (sql.startsWith("UPDATE sessions SET log_bytes")) { const s = t.sessions.find((x) => x.log_id === a[2] && x.log_bytes == null); if (s) { s.log_bytes = a[0]; s.log_key = a[1]; } }
      else if (sql.startsWith("INSERT INTO logs_sent")) t.logs.push({ day: a[0], who: a[1] });
      else if (sql.startsWith("DELETE FROM logs_sent")) t.logs = t.logs.filter((l) => l.day >= a[0]);
      else if (sql.startsWith("INSERT INTO days")) { if (!t.days[a[0]]) t.days[a[0]] = { written: 0, bytes: 0, salt: a[1] }; }
      else if (sql.startsWith("UPDATE days SET written")) { if (t.days[a[0]]) t.days[a[0]].written += 3; }
      else if (sql.startsWith("DELETE FROM posters") || sql.startsWith("UPDATE days SET salt")) {}
      else throw new Error("unexpected " + sql);
    },
  });
  return { t, prepare: prep, async batch(list) { for (const s of list) await s.run(); } };
}

function fakeR2() {
  const objects = new Map();
  return { objects, async put(k, v) { objects.set(k, new Uint8Array(v)); }, async delete(k) { objects.delete(k); } };
}

const LOG = gzipSync(Buffer.from("11-24 09:25:29.030 13115 13315 I joey64host: Joey64 0.2.7 on RG 476H\n".repeat(50)));

function post(id, body = LOG, ip = "203.0.113.7") {
  return new Request(`https://x/v1/log?id=${id}`, {
    method: "POST", body, headers: { "content-type": "application/gzip", "content-length": String(body.length), "cf-connecting-ip": ip },
  });
}

function env(sessions) { return { DB: fakeDb(sessions), LOGS: fakeR2() }; }

test("a session's log is stored under its report's day and counted once", async () => {
  const e = env([{ log_id: ID, day: TODAY }]);
  const res = await worker.fetch(post(ID), e);
  assert.equal(res.status, 204);
  const key = `logs/${TODAY}/${ID}.log.gz`;
  assert.deepEqual([...e.LOGS.objects.keys()], [key]);
  assert.equal(e.DB.t.sessions[0].log_bytes, LOG.length);
  assert.equal(e.DB.t.sessions[0].log_key, key);
  assert.equal((await worker.fetch(post(ID), e)).status, 409);
});

test("ids no session has, bad ids, junk and old sessions are refused", async () => {
  const e = env([{ log_id: ID, day: "2026-01-01" }]);
  assert.equal((await worker.fetch(post(OTHER), e)).status, 404);
  assert.equal((await worker.fetch(post("nope"), e)).status, 400);
  assert.equal((await worker.fetch(post(ID), e)).status, 410);
  const e2 = env([{ log_id: ID, day: TODAY }]);
  assert.equal((await worker.fetch(post(ID, Buffer.from("plain text, not gzip, long enough to check")), e2)).status, 400);
  assert.equal((await worker.fetch(post(ID, Buffer.alloc(MAX_LOG_GZ + 1, 0x1f)), e2)).status, 413);
  assert.equal(e2.LOGS.objects.size, 0);
  assert.equal((await worker.fetch(new Request(`https://x/v1/log?id=${ID}`), e2)).status, 405);
});

test("one address block gets its day's share of logs", async () => {
  const ids = Array.from({ length: BLOCK_DAY_LOGS + 1 }, (_, i) => i.toString(16).padStart(32, "0"));
  const e = env(ids.map((id) => ({ log_id: id, day: TODAY })));
  for (const id of ids.slice(0, BLOCK_DAY_LOGS)) assert.equal((await worker.fetch(post(id), e)).status, 204);
  const res = await worker.fetch(post(ids[BLOCK_DAY_LOGS]), e);
  assert.equal(res.status, 429);
  assert.ok(Number(res.headers.get("retry-after")) >= 60);
  assert.equal((await worker.fetch(post(ids[BLOCK_DAY_LOGS], LOG, "198.51.100.9"), e)).status, 204);
});

test("a session event's logId is checked and counted", () => {
  const r = good();
  const s = r.events.find((e) => e.type === "session");
  s.logId = ID;
  const v = validate(r);
  const vs = v.events.find((e) => e.type === "session");
  assert.equal(vs.log_id, ID);
  const without = validate(good());
  assert.equal(rowsOf(v), rowsOf(without) + 1);
  s.logId = "ABC";
  assert.throws(() => validate(r), Invalid);
});

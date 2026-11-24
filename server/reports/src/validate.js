/**
 * Checks a report before anything is stored, and returns it trimmed to the fields the tables have.
 * Anything unexpected (a missing field, a wrong type, too long a string, too many events) rejects
 * the whole report: the app is open source, so anyone can post here, and a half-read report is
 * worse than none.
 */

export const MAX_BYTES = 64 * 1024;
export const MAX_EVENTS = 200;

const MD5 = /^[0-9a-f]{32}$/;
const INSTALL = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const HEADER = /^[A-Za-z0-9 ]{4}$/;

export class Invalid extends Error {}

/** A whole log's id: 32 lower-case hex characters, made by the app (a random UUID without its dashes). */
export function logId(v) {
  if (typeof v !== "string" || !/^[0-9a-f]{32}$/.test(v)) throw new Invalid("logId not 32 hex characters");
  return v;
}

function driverId(v) {
  if (typeof v !== "string" || !/^[a-z0-9]{1,16}$/.test(v)) throw new Invalid("driverId not a driver id");
  return v;
}

function str(v, name, max, { optional = true } = {}) {
  if (v === undefined || v === null) {
    if (optional) return null;
    throw new Invalid(`${name} missing`);
  }
  if (typeof v !== "string") throw new Invalid(`${name} not a string`);
  if (v.length > max) throw new Invalid(`${name} too long`);
  // Control characters have no business in any of these.
  if (/[\u0000-\u001f\u007f]/.test(v)) throw new Invalid(`${name} has control characters`);
  return v;
}

function num(v, name, min, max, { int = false } = {}) {
  if (v === undefined || v === null) return null;
  if (typeof v !== "number" || !Number.isFinite(v)) throw new Invalid(`${name} not a number`);
  if (int && !Number.isInteger(v)) throw new Invalid(`${name} not a whole number`);
  if (v < min || v > max) throw new Invalid(`${name} out of range`);
  return v;
}

function bool(v, name) {
  if (v === undefined || v === null) return null;
  if (typeof v !== "boolean") throw new Invalid(`${name} not true or false`);
  return v ? 1 : 0;
}

function md5(v, name) {
  const s = str(v, name, 32, { optional: false });
  if (!MD5.test(s)) throw new Invalid(`${name} not an MD5`);
  return s;
}

function header(v) {
  const s = str(v, "header", 4);
  if (s !== null && !HEADER.test(s)) throw new Invalid("header not a game code");
  return s;
}

function oneOf(v, name, allowed) {
  const s = str(v, name, 16, { optional: false });
  if (!allowed.includes(s)) throw new Invalid(`${name} not one of ${allowed.join(", ")}`);
  return s;
}

/** A flat object of setting names to strings, numbers or booleans, stored as JSON. */
function settings(v) {
  if (v === undefined || v === null) return null;
  if (typeof v !== "object" || Array.isArray(v)) throw new Invalid("settings not an object");
  const keys = Object.keys(v);
  if (keys.length > 60) throw new Invalid("too many settings");
  const out = {};
  for (const k of keys.sort()) {
    str(k, "setting name", 48);
    const x = v[k];
    if (typeof x === "string") out[k] = str(x, `setting ${k}`, 64);
    else if (typeof x === "number" && Number.isFinite(x)) out[k] = x;
    else if (typeof x === "boolean") out[k] = x;
    else throw new Invalid(`setting ${k} not a plain value`);
  }
  return JSON.stringify(out);
}

function event(e) {
  if (typeof e !== "object" || e === null || Array.isArray(e)) throw new Invalid("event not an object");
  switch (e.type) {
    case "session":
      return {
        type: "session",
        md5: md5(e.md5, "md5"),
        header: header(e.header),
        renderer: str(e.renderer, "renderer", 16),
        scale: num(e.scale, "scale", 1, 16, { int: true }),
        settings: settings(e.settings),
        seconds: num(e.seconds, "seconds", 0, 7 * 24 * 3600, { int: true }),
        emu_fps: num(e.emuFps, "emuFps", 0, 1000),
        drawn_fps: num(e.drawnFps, "drawnFps", 0, 1000),
        dupes: num(e.dupes, "dupes", 0, 1000),
        underruns: num(e.underruns, "underruns", 0, 100000, { int: true }),
        ended: oneOf(e.ended, "ended", ENDED),
        // Added 2026-11-08 (migrations/0002-session-details.sql); each may be missing (an older app).
        frames: num(e.frames, "frames", 0, 1e9, { int: true }),
        first_frame_ms: num(e.firstFrameMs, "firstFrameMs", 0, 600000, { int: true }),
        frames_timed: num(e.framesTimed, "framesTimed", 0, 1e9, { int: true }),
        p95_ms: num(e.p95Ms, "p95Ms", 0, 100, { int: true }),
        p99_ms: num(e.p99Ms, "p99Ms", 0, 100, { int: true }),
        slow_frames: num(e.slowFrames, "slowFrames", 0, 1e9, { int: true }),
        lag_ms: num(e.lagMs, "lagMs", 0, 1000),
        lag_max_ms: num(e.lagMaxMs, "lagMaxMs", 0, 1000),
        thermal_max: num(e.thermalMax, "thermalMax", 0, 6, { int: true }),
        headroom_max: num(e.headroomMax, "headroomMax", 0, 10),
        thermal_drops: num(e.thermalDrops, "thermalDrops", 0, 1e6, { int: true }),
        rdp_sync: bool(e.rdpSync, "rdpSync"),
        cpu_core: e.cpuCore === undefined || e.cpuCore === null ? null : oneOf(e.cpuCore, "cpuCore", ["dynarec", "interpreter"]),
        refresh_hz: num(e.refreshHz, "refreshHz", 0, 500, { int: true }),
        cheats: num(e.cheats, "cheats", 0, 1000, { int: true }),
        from_state: bool(e.fromState, "fromState"),
        // Added 2026-11-09 (migrations/0004-gpu-rules.sql): why the device ran this driver and renderer. Auto's rule
        // for each (GpuRules: "Adreno 6xx-8xx: bundled Turnip"), the start-up trial's level (GpuTrial: 0 Auto's own
        // pick, 1 the system driver, 2 GLideN64 too; missing when the trial didn't run) and whether Auto picked
        // ("auto") or Settings did ("user", then the rules say what Auto would have picked).
        driver_rule: str(e.driverRule, "driverRule", 120),
        renderer_rule: str(e.rendererRule, "rendererRule", 120),
        trial_level: num(e.trialLevel, "trialLevel", 0, 2, { int: true }),
        picked_by: e.pickedBy === undefined || e.pickedBy === null ? null : oneOf(e.pickedBy, "pickedBy", ["auto", "user"]),
        // Added 2026-11-15 (migrations/0007-driver-speed.sql): the mean work a frame (ms, not fast-forward) and the
        // driver that ran ("turnip", "armsx2", "system", "custom"), for devtools/driver-order.py's ranking.
        work_ms: num(e.workMs, "workMs", 0, 200),
        driver_id: e.driverId === undefined || e.driverId === null ? null : driverId(e.driverId),
        // Added 2026-11-14 (migrations/0006-netplay-log.sql): a netplay room's figures, and the log excerpt the app sends
        // with a session that ended badly or played in a room.
        netplay: netplay(e.netplay),
        log: e.log === undefined || e.log === null ? null : logText(e.log),
        // Added 2026-11-24 (migrations/0008-session-logs.sql): the id the session's whole log is posted under (/v1/log).
        log_id: e.logId === undefined || e.logId === null ? null : logId(e.logId),
      };
    case "nocover":
    case "replaced":
      return {
        type: "cover",
        md5: md5(e.md5, "md5"),
        kind: e.type === "nocover" ? "none" : "replaced",
        header: header(e.header),
        title: str(e.title, "title", 200),
        cover: e.type === "replaced" ? str(e.cover, "cover", 64) : null,
      };
    case "crash":
      return {
        type: "crash",
        md5: e.md5 === undefined || e.md5 === null ? null : md5(e.md5, "md5"),
        where_: oneOf(e.where, "where", ["core", "launcher", "host"]),
        summary: crashText(e.summary),
      };
    default:
      throw new Invalid("unknown event type");
  }
}

/**
 * How a session ended. quit and crash from the start; since 2026-11-14 also next (Joey64 restarted the process for
 * another game or settings), stuck (the core hung), and Android's record of a process that ended mid-game: anr,
 * killed (memory or resources), closed (swiped away or force stopped), signal, gone (no record).
 */
export const ENDED = ["quit", "next", "stuck", "crash", "anr", "killed", "closed", "signal", "gone"];

const NET_ENDS = ["lost", "unreachable", "full", "mismatch", "hostclosed", "outofstep", "left"];
const NET_NUMBERS = {
  me: [1, 4, true], players: [1, 4, true], delay: [0, 60, true], delayMax: [0, 60, true], pingAvg: [0, 10000, true],
  pingMax: [0, 10000, true], seconds: [0, 7 * 24 * 3600, true], waits: [0, 1e6, true], waitSeconds: [0, 7 * 24 * 3600, false],
  pausedSeconds: [0, 7 * 24 * 3600, false], outOfStep: [0, 1e6, true], left: [0, 4, true],
};

/** A netplay room's figures (the app's reports/NetStats): known keys only, stored as JSON. */
function netplay(v) {
  if (v === undefined || v === null) return null;
  if (typeof v !== "object" || Array.isArray(v)) throw new Invalid("netplay not an object");
  const out = { role: oneOf(v.role, "netplay role", ["host", "join"]) };
  for (const k of Object.keys(v)) {
    if (k === "role") continue;
    if (k === "end") { out.end = oneOf(v.end, "netplay end", NET_ENDS); continue; }
    const r = NET_NUMBERS[k];
    if (!r) throw new Invalid(`netplay ${k} unknown`);
    out[k] = num(v[k], `netplay ${k}`, r[0], r[1], { int: r[2] });
  }
  return JSON.stringify(out);
}

/** A log excerpt: line breaks and tabs, nothing else below space; at most 16,000 characters. */
function logText(v) {
  if (typeof v !== "string") throw new Invalid("log not a string");
  if (v.length > 16000) throw new Invalid("log too long");
  if (/[\u0000-\u0008\u000b-\u001f\u007f]/.test(v)) throw new Invalid("log has control characters");
  return v;
}

/** Crash text may have line breaks and tabs, nothing else below space; at most 2,000 characters. */
function crashText(v) {
  if (typeof v !== "string") throw new Invalid("summary not a string");
  if (v.length > 2000) throw new Invalid("summary too long");
  if (/[\u0000-\u0008\u000b-\u001f\u007f]/.test(v)) throw new Invalid("summary has control characters");
  return v;
}

/** The report as stored: `device` and the events, each checked. Throws [Invalid]. */
export function validate(body) {
  if (typeof body !== "object" || body === null || Array.isArray(body)) throw new Invalid("not an object");
  if (body.format !== 1) throw new Invalid("unknown format");
  const install = str(body.install, "install", 36, { optional: false });
  if (!INSTALL.test(install)) throw new Invalid("install not a random id");
  const d = body.device;
  if (typeof d !== "object" || d === null || Array.isArray(d)) throw new Invalid("device not an object");
  if (!Array.isArray(body.events)) throw new Invalid("events not a list");
  if (body.events.length === 0) throw new Invalid("no events");
  if (body.events.length > MAX_EVENTS) throw new Invalid("too many events");
  return {
    install,
    app: str(body.app, "app", 32, { optional: false }),
    core: str(body.core, "core", 64),
    maker: str(d.maker, "maker", 64),
    model: str(d.model, "model", 64),
    soc: str(d.soc, "soc", 64),
    gpu: str(d.gpu, "gpu", 64),
    driver: str(d.driver, "driver", 96),
    android: num(d.android, "android", 21, 99, { int: true }),
    // Added 2026-11-08 (migrations/0003-screen.sql); each may be missing (an older app).
    screen_w: num(d.screenW, "screenW", 100, 10000, { int: true }),
    screen_h: num(d.screenH, "screenH", 100, 10000, { int: true }),
    screen_dpi: num(d.dpi, "dpi", 60, 1000, { int: true }),
    events: body.events.map(event),
  };
}

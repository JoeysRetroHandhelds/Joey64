"""Ranks the GPU drivers per GPU from the reports' sessions, for joey64-data's drivers/order.json (approved drvspeed
mockup 2026-11-15, screen 4: "Joey64 downloads a weekly list of the fastest drivers for each GPU, made from reports").

Only parallel-RDP sessions of a minute or more with a work time and a driver id count (app sessions from 2026-11-15
on). A driver is compared only on games also played on another driver of the same GPU at the same resolution (a game at
2x and at 4x counts as two; review 2026-11-15: mixing them ranked a driver by where it was played): each game's work a frame is
divided by that game's mean over its drivers, and a driver's score is the geometric mean of those over its games (lower
is faster). A driver needs MIN_GAMES such games to be ranked. GPU names are keyed as GpuOrder.key in the app.

  python -I devtools/driver-order.py --out <joey64-data>/drivers/order.json   (reads the live D1 with wrangler)
  python -I devtools/driver-order.py --rows rows.json --out order.json         (from a saved query result)
Writing the file doesn't publish it: pushing joey64-data's release branch needs Joey's go.
"""
import argparse, json, math, os, re, subprocess, sys
from collections import defaultdict

MIN_GAMES = 2
MIN_SECONDS = 60
QUERY = ("SELECT r.gpu AS gpu, s.md5 AS md5, COALESCE(s.scale, 1) AS scale, s.driver_id AS driver, SUM(s.work_ms * s.seconds) AS w, SUM(s.seconds) AS secs "
         "FROM sessions s JOIN reports r ON s.report = r.id "
         f"WHERE s.renderer = 'parallel' AND s.work_ms IS NOT NULL AND s.driver_id IS NOT NULL AND s.driver_id != 'custom' "
         f"AND s.seconds >= {MIN_SECONDS} GROUP BY r.gpu, s.md5, COALESCE(s.scale, 1), s.driver_id")


def key(name):
    s = name.lower().replace("(tm)", " ")
    s = re.sub(r"^\s*turnip\s+", "", s)
    return re.sub(r"\s+", " ", s).strip()


def query():
    here = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "server", "reports")
    out = subprocess.run(["npx", "wrangler", "d1", "execute", "joey64-reports", "--remote", "--json", "--command", QUERY],
                         cwd=here, capture_output=True, text=True, shell=(os.name == "nt"), check=True).stdout
    return json.loads(out)[0]["results"]


def rank(rows):
    # gpu -> (md5, scale) -> driver -> (work weighted by seconds, seconds); the session's GPU name may be Turnip's or the system's
    games = defaultdict(lambda: defaultdict(lambda: defaultdict(lambda: [0.0, 0.0])))
    for r in rows:
        if not r.get("gpu") or not r.get("driver") or not r.get("secs"):
            continue
        a = games[key(r["gpu"])][(r["md5"], r.get("scale") or 1)][r["driver"]]
        a[0] += float(r["w"]); a[1] += float(r["secs"])
    out = {}
    for gpu, by_game in sorted(games.items()):
        logs = defaultdict(list)
        for drivers in by_game.values():
            if len(drivers) < 2:
                continue
            means = {d: w / s for d, (w, s) in drivers.items() if s > 0 and w > 0}
            if len(means) < 2:
                continue
            g = sum(math.log(m) for m in means.values()) / len(means)
            for d, m in means.items():
                logs[d].append(math.log(m) - g)
        scored = sorted((sum(v) / len(v), d) for d, v in logs.items() if len(v) >= MIN_GAMES)
        if len(scored) >= 2:
            out[gpu] = [d for _, d in scored]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--rows", help="a saved result of the query (a list of rows, or wrangler's --json output)")
    a = ap.parse_args()
    if a.rows:
        data = json.load(open(a.rows, encoding="utf-8"))
        rows = data[0]["results"] if isinstance(data, list) and data and isinstance(data[0], dict) and "results" in data[0] else data
    else:
        rows = query()
    gpus = rank(rows)
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump({"version": 1, "gpus": gpus}, f, indent=1, sort_keys=True)
        f.write("\n")
    for g, d in gpus.items():
        print(f"{g}: {', '.join(d)}")
    print(f"{len(gpus)} GPU(s) ranked from {len(rows)} row(s) -> {a.out}")


if __name__ == "__main__":
    sys.exit(main())

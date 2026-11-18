"""Prints the reports' session logs, unpacked (the app gzips them and puts them in base64 behind "gz1:", ReportJson.packLog,
since 2026-11-18; older ones are plain text and print as they are).

  python -I devtools/report-log.py                 the newest 5 sessions with a log (reads the live D1 with wrangler)
  python -I devtools/report-log.py --last 20       the newest 20
  python -I devtools/report-log.py --md5 <rom md5> only that game's
  python -I devtools/report-log.py --rows rows.json  from a saved query result (wrangler's --json output, or a list of rows)
  python -I devtools/report-log.py --out logs/     one file a session (<report>-<rowid>.log) instead of printing

Needs the Joey64 wrangler login (server/reports/README.md, "Wrangler login").
"""
import argparse, base64, gzip, json, os, re, subprocess, sys

PACKED = "gz1:"


def unpack(s):
    if not s or not s.startswith(PACKED):
        return s or ""
    return gzip.decompress(base64.b64decode(s[len(PACKED):])).decode("utf-8")


def query(last, md5):
    where = "log IS NOT NULL"
    if md5:
        if not re.fullmatch(r"[0-9a-f]{32}", md5):
            sys.exit("--md5 needs 32 lower-case hex characters")
        where += f" AND md5 = '{md5}'"
    sql = (f"SELECT s.rowid AS row, s.report, r.day, r.app, r.model, s.md5, s.ended, s.seconds, s.log FROM sessions s "
           f"JOIN reports r ON r.id = s.report WHERE {where} ORDER BY s.rowid DESC LIMIT {int(last)}")
    here = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "server", "reports")
    out = subprocess.run(["npx", "wrangler", "d1", "execute", "joey64-reports", "--remote", "--json", "--command", sql],
                         cwd=here, capture_output=True, text=True, shell=(os.name == "nt"), check=True).stdout
    return json.loads(out)[0]["results"]


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--last", type=int, default=5)
    ap.add_argument("--md5")
    ap.add_argument("--rows")
    ap.add_argument("--out")
    a = ap.parse_args()
    if a.rows:
        with open(a.rows, encoding="utf-8") as f:
            rows = json.load(f)
        if rows and isinstance(rows[0], dict) and "results" in rows[0]:
            rows = rows[0]["results"]
    else:
        rows = query(a.last, a.md5)
    if a.out:
        os.makedirs(a.out, exist_ok=True)
    for r in rows:
        text = unpack(r.get("log"))
        head = " ".join(f"{k} {r.get(k)}" for k in ("report", "row", "day", "app", "model", "md5", "ended", "seconds") if r.get(k) is not None)
        if a.out:
            name = os.path.join(a.out, f"{r.get('report', 'x')}-{r.get('row', 'x')}.log")
            with open(name, "w", encoding="utf-8", newline="\n") as f:
                f.write(head + "\n" + text + "\n")
            print(name)
        else:
            print(f"===== {head} ({len(text)} characters)")
            print(text)


if __name__ == "__main__":
    main()

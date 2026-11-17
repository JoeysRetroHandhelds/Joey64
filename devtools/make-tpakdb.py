#!/usr/bin/env python3
"""Builds app/src/main/assets/tpakdb.tsv: which Game Boy games each Transfer Pak game takes (Joey 2026-11-17: the Game Boy
game list "should only load the games that are applicable for the game we are in, save with saves").

One line per N64 ROM MD5 (lower case, .z64 order, as the library keys games): md5 TAB group. The group names a set of
Game Boy games in controls/TransferPakDb.kt. Games the core's list (joey64-core/mupen64plus-core/data/mupen64plus.ini,
GPLv2+) marks Transferpak=Yes get their group from their GoodName; the partner games are Wikipedia's Transfer Pak table
(read 2026-11-17). Games that take any Game Boy game (PD Ultraman Battle Collection 64, Transformers Beast Wars Metals 64)
and ones not in the table (Densha de Go! 64's patch) are left out: the list shows every game for them. No-Intro copies
the ini doesn't list (assets/boxart/n64-names.tsv) join by short title when every listed copy of that title has one group.

  python devtools/make-tpakdb.py [path/to/mupen64plus.ini]
"""
import os, re, sys

here = os.path.dirname(os.path.abspath(__file__))
ini = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "..", "..", "joey64-core", "mupen64plus-core", "data", "mupen64plus.ini")
out = os.path.join(here, "..", "app", "src", "main", "assets", "tpakdb.tsv")
names = os.path.join(here, "..", "app", "src", "main", "assets", "boxart", "n64-names.tsv")

entries, cur = {}, None
for line in open(ini, encoding="utf-8", errors="replace"):
    line = line.strip()
    if line.startswith("[") and line.endswith("]"):
        cur = entries.setdefault(line[1:-1].lower(), {})
    elif cur is not None and "=" in line and not line.startswith(";"):
        k, v = line.split("=", 1)
        cur[k.strip()] = v.strip()

def field(md5, key, depth=0):
    e = entries.get(md5, {})
    if key in e or depth > 4: return e.get(key)
    ref = e.get("RefMD5")
    return field(ref.lower(), key, depth + 1) if ref else None

# GoodName start -> group, first match wins (Pocket Monsters Stadium 2 is Japan's Pokemon Stadium: Red, Green, Blue, Yellow).
RULES = [
    (r"Pocket Monsters Stadium Kin Gin", "pokemon12"),
    (r"Pocket Monsters Stadium 2 \(", "pokemon1"),
    (r"Pocket Monsters Stadium \(", "pokemon1"),
    (r"Pokemon Stadium 2 \(", "pokemon12"),
    (r"Pokemon Stadium \(", "pokemon1"),
    (r"Mario Golf", "mariogolf"),
    (r"Mario Tennis", "mariotennis"),
    (r"Mickey's Speedway USA", "mickey"),
    (r"Perfect Dark", "perfectdark"),
    (r"Mario Artist", "gbcamera"),
    (r"Choro Q 64 II", "choroq"),
    (r"Jikkyou Powerful Pro Yakyuu 2000", "powerpro2"),
    (r"Jikkyou Powerful Pro Yakyuu 6", "powerpro1"),
    (r"Nushi Tsuri 64 - Shiokaze", "kawanushi4"),
    (r"Nushi Tsuri 64", "uminushi2"),
    (r"Puyo Puyo 4", "puyosun"),
    (r"Robot Ponkottsu 64", "robopon"),
    (r"Super B-Daman", "bdaman"),
    (r"Super Robot Taisen 64", "linkbattler"),
]

def group_of(name):
    for pat, g in RULES:
        if re.match(pat, name): return g
    return None

groups = {}
for md5 in entries:
    if field(md5, "Transferpak") != "Yes": continue
    g = group_of(field(md5, "GoodName") or "")
    if g: groups[md5] = g

def short(name):
    return re.sub(r"[^a-z0-9]", "", re.split(r" \(| \[", name, maxsplit=1)[0].lower())

nointro = []
for line in open(names, encoding="utf-8"):
    parts = line.rstrip("\n").split("\t")
    if len(parts) < 2 or line.startswith("#"): continue
    nointro.append((parts[0].lower(), parts[1]))
# A No-Intro title's group, from the copies the ini also lists; a title with two groups (Pokemon Stadium 2: Japan's is
# the first Stadium) judges nothing.
by_title = {}
for md5, name in nointro:
    if md5 in groups: by_title.setdefault(short(name), set()).add(groups[md5])
added = 0
for md5, name in nointro:
    if md5 in groups: continue
    gs = by_title.get(short(name))
    if gs and len(gs) == 1:
        groups[md5] = next(iter(gs)); added += 1

with open(out, "w", newline="\n") as f:
    f.write("# Transfer Pak games and the Game Boy games they take: made by devtools/make-tpakdb.py from mupen64plus.ini (GPLv2+)\n")
    for md5 in sorted(groups): f.write(md5 + "\t" + groups[md5] + "\n")
print(f"{len(groups)} Transfer Pak games ({added} No-Intro copies by title) -> {out}")
for g in sorted(set(groups.values())): print(f"  {g}: {sum(1 for v in groups.values() if v == g)}")

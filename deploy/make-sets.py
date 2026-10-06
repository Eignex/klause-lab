#!/usr/bin/env python3
"""Draw klause-bench's named problem sets from classified problems and the lab's reference results.

    make-sets.py <features-dir> <references.csv> <sets-dir>

<features-dir> holds `klause-bench select suite=<s> per-family=1000000 features=true` output, one <suite>.jsonl per
suite. <references.csv> is the lab's reference_rows as `collection,problem,feasible,proven,elapsed_ms` (feasible
blank when undecided). Each focused set takes its SIZES count of problems of one theme, mixing difficulties by how fast the
reference proved them, spread over suites and families; a problem the reference did not prove is left out. A
problem goes in the first set that takes it, so `sweep`, which includes them all, holds each once.
"""
import csv
import json
import random
import sys
from collections import defaultdict
from datetime import date
from pathlib import Path

# Focused sets in the order they draw, each the theme it covers.
SETS = ["open-int", "linear-real", "mip", "scheduling", "routing", "packing", "globals", "sat", "maxsat", "pb"]
# Formats a set leaves out: MPS models with continuous columns are covered by `mip`, so `linear-real` is SMT and
# MiniZinc, where they would otherwise outnumber the SMT-LIB logics it is for.
EXCLUDED_FORMATS = {"linear-real": {"MPS"}}
# Each set's size: CSP/COP and SMT weigh more than MPS, SAT and PB. 250 in all.
SIZES = {"globals": 35, "scheduling": 30, "routing": 25, "packing": 25, "open-int": 35, "linear-real": 35,
         "mip": 20, "sat": 15, "pb": 15, "maxsat": 15}
# Sets made of others: `linear` is open-domain integers with linear reals as well.
UNIONS = {"linear": ["open-int", "linear-real"], "sweep": SETS}
# Difficulty tiers by the reference's time to prove. A set takes [EASY_SHARE] easy, [HARD] hard and medium for the
# rest: over the 250, about 100 easy, 140 medium and 10 hard.
TIERS = [("easy", 0, 1_000), ("medium", 1_000, 10_000), ("hard", 10_000, 60_001)]
EASY_SHARE = 0.4
HARD = 1
SEED = 1


def tier_of(elapsed_ms):
    return next((name for name, low, high in TIERS if low <= elapsed_ms < high), None)


def main():
    features_dir, references_csv, sets_dir = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])

    # Each problem's tier by how fast the reference proved it, over every reference solver.
    fastest = {}
    with references_csv.open() as f:
        for row in csv.DictReader(f):
            if row["proven"] == "1" and row["feasible"] != "":
                key = (row["collection"], row["problem"])
                fastest[key] = min(fastest.get(key, 1 << 62), int(row["elapsed_ms"]))

    # Candidates per (theme, tier), each a (suite, family, problem).
    pools = defaultdict(list)
    for path in sorted(features_dir.glob("*.jsonl")):
        for line in path.open():
            if not line.startswith("{"):
                continue
            p = json.loads(line)
            key = (p["collection"], p["problem"])
            tier = tier_of(fastest[key]) if key in fastest else None
            if tier is None:
                continue
            for theme in p.get("themes", []):
                if p["format"] not in EXCLUDED_FORMATS.get(theme, set()):
                    pools[(theme, tier)].append((p["suite"], p["family"], p["problem"]))

    rng = random.Random(SEED)
    taken = set()
    sets_dir.mkdir(parents=True, exist_ok=True)
    for name in SETS:
        easy = round(SIZES[name] * EASY_SHARE)
        quotas = {"easy": easy, "hard": HARD, "medium": SIZES[name] - easy - HARD}
        picked = {}
        # A tier short of candidates hands its quota on, easiest-first fallback last.
        for tier, _, _ in sorted(TIERS, key=lambda t: -t[1]):
            chosen = spread([c for c in pools[(name, tier)] if (c[0], c[2]) not in taken], quotas[tier], rng)
            picked[tier] = chosen
            taken.update((c[0], c[2]) for c in chosen)
            short = quotas[tier] - len(chosen)
            if short > 0:
                lower = [t for t, _, _ in TIERS if quotas[t] and t != tier and t not in picked]
                if lower:
                    quotas[lower[-1]] += short
        lines = [f"# {name}: problems with the {name} theme, drawn {date.today()} by klause-lab deploy/make-sets.py",
                 "# tiers by the reference's time to prove: easy < 1 s, medium < 10 s, hard < 60 s"]
        for tier, _, _ in TIERS:
            chosen = sorted(picked.get(tier, []))
            lines.append(f"# {tier}: {len(chosen)}")
            lines += [f"{suite}/{problem}" for suite, _, problem in chosen]
        (sets_dir / f"{name}.txt").write_text("\n".join(lines) + "\n")
        print(name, {t: len(picked.get(t, [])) for t, _, _ in TIERS}, file=sys.stderr)
    for name, parts in UNIONS.items():
        (sets_dir / f"{name}.txt").write_text(f"# {name}: " + ", ".join(parts) + "\n" + "".join(f"@{p}\n" for p in parts))


def spread(candidates, n, rng):
    """[n] of [candidates], one per (suite, family) per round, suites and families taken in a seeded order."""
    families = defaultdict(list)
    for c in candidates:
        families[(c[0], c[1])].append(c)
    for members in families.values():
        rng.shuffle(members)
    by_suite = defaultdict(list)
    for key in families:
        by_suite[key[0]].append(key)
    for keys in by_suite.values():
        rng.shuffle(keys)
    # Interleave suites, so one large suite does not fill a set alone.
    order = []
    suites = sorted(by_suite)
    rng.shuffle(suites)
    while any(by_suite[s] for s in suites):
        for s in suites:
            if by_suite[s]:
                order.append(by_suite[s].pop())
    chosen = []
    while len(chosen) < n and any(families[k] for k in order):
        for k in order:
            if families[k] and len(chosen) < n:
                chosen.append(families[k].pop())
    return chosen


if __name__ == "__main__":
    main()

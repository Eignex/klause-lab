#!/usr/bin/env python3
"""Draw klause-bench's named problem sets from classified problems and the lab's reference results.

    make-sets.py <features-dir> <references.csv> <sets-dir> [size]

<features-dir> holds `klause-bench select suite=<s> per-family=1000000 features=true` output, one <suite>.jsonl per
suite. <references.csv> is the lab's reference_rows as `collection,problem,feasible,proven,elapsed_ms` (feasible
blank when undecided). Each focused set takes [size] problems of one theme, mixing difficulties by how fast the
reference proved them, a solution it could not prove optimal counting as hard, spread over suites and families; a
problem the reference left undecided is left out. A
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
# Sets made of others: `linear` is open-domain integers with linear reals as well.
UNIONS = {"linear": ["open-int", "linear-real"], "sweep": SETS}
# Difficulty tiers by the reference's time to prove, and each tier's share of a set.
TIERS = [("easy", 0, 1_000, 0.4), ("medium", 1_000, 10_000, 0.4), ("hard", 10_000, 60_001, 0.2)]
SEED = 1


def tier_of(elapsed_ms):
    return next((name for name, low, high, _ in TIERS if low <= elapsed_ms < high), None)


def main():
    features_dir, references_csv, sets_dir = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    size = int(sys.argv[4]) if len(sys.argv) > 4 else 25

    # Each problem's tier by its best reference verdict: a proof by how fast it came; a solution with no proof of
    # optimality, within the reference's budget, is hard.
    fastest, solved = {}, set()
    with references_csv.open() as f:
        for row in csv.DictReader(f):
            key = (row["collection"], row["problem"])
            if row["proven"] == "1" and row["feasible"] != "":
                fastest[key] = min(fastest.get(key, 1 << 62), int(row["elapsed_ms"]))
            elif row["feasible"] == "1":
                solved.add(key)

    # Candidates per (theme, tier), each a (suite, family, problem).
    pools = defaultdict(list)
    for path in sorted(features_dir.glob("*.jsonl")):
        for line in path.open():
            if not line.startswith("{"):
                continue
            p = json.loads(line)
            key = (p["collection"], p["problem"])
            tier = tier_of(fastest[key]) if key in fastest else "hard" if key in solved else None
            if tier is None:
                continue
            for theme in p.get("themes", []):
                pools[(theme, tier)].append((p["suite"], p["family"], p["problem"]))

    rng = random.Random(SEED)
    taken = set()
    sets_dir.mkdir(parents=True, exist_ok=True)
    for name in SETS:
        quotas = {tier: round(size * share) for tier, _, _, share in TIERS}
        picked = {}
        # A tier short of candidates hands its quota on, easiest-first fallback last.
        for tier, _, _, _ in sorted(TIERS, key=lambda t: -t[1]):
            chosen = spread([c for c in pools[(name, tier)] if (c[0], c[2]) not in taken], quotas[tier], rng)
            picked[tier] = chosen
            taken.update((c[0], c[2]) for c in chosen)
            short = quotas[tier] - len(chosen)
            if short > 0:
                lower = [t for t, _, _, _ in TIERS if quotas[t] and t != tier and t not in picked]
                if lower:
                    quotas[lower[-1]] += short
        lines = [f"# {name}: problems with the {name} theme, drawn {date.today()} by klause-lab deploy/make-sets.py",
                 "# tiers by the reference's time to prove: easy < 1 s, medium < 10 s, hard < 60 s or solved but unproven"]
        for tier, _, _, _ in TIERS:
            chosen = sorted(picked.get(tier, []))
            lines.append(f"# {tier}: {len(chosen)}")
            lines += [f"{suite}/{problem}" for suite, _, problem in chosen]
        (sets_dir / f"{name}.txt").write_text("\n".join(lines) + "\n")
        print(name, {t: len(picked.get(t, [])) for t, _, _, _ in TIERS}, file=sys.stderr)
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

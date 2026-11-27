"""Roots Cognitive Atlas: sparse feature cues -> measured FFN groups -> GGUF ranges.
Pure Python, no model dependency. All weights are observational associations."""
import math
import random
import re
from collections import defaultdict

GROUP = 32

def normalized(values):
    clean = {k: float(v) for k, v in values.items() if math.isfinite(v) and v > 0}
    total = sum(clean.values())
    return {k: v / total for k, v in clean.items()} if total else {}

def learn(probes):
    if not probes:
        raise ValueError("No training observations")
    freq, total, conditional, pairs = defaultdict(int), defaultdict(float), defaultdict(lambda: defaultdict(float)), defaultdict(lambda: defaultdict(float))
    for p in probes:
        for g, v in p["groups"].items():
            total[g] += v / len(probes)
        features = normalized(p["features"])
        active = list(features)
        for f, w in features.items():
            freq[f] += 1
            for g, v in p["groups"].items():
                conditional[f][g] += v
            for other in active:
                if f != other:
                    pairs[f][other] += features[other]
    # Conditional excess over the corpus baseline; shrink low support.
    links = {}
    transitions = {}
    for f, n in freq.items():
        excess = {g: max(0, v / n - total[g]) for g, v in conditional[f].items()}
        links[f] = {g: v * n / (n + 2) for g, v in sorted(excess.items(), key=lambda x: (-x[1], x[0]))[:64] if v > 0}
        transitions[f] = normalized(dict(sorted(pairs[f].items(), key=lambda x: (-x[1], x[0]))[:12]))
    return {"n": len(probes), "freq": dict(freq), "baseline": dict(total), "links": links, "transitions": transitions}

def rank(cue, atlas, steps=3):
    # Restarted spreading activation: rare features carry more retrieval weight.
    seed = normalized({f: v * math.log1p(atlas["n"] / atlas["freq"][f])
                       for f, v in cue.items() if f in atlas["freq"]})
    if not seed:
        return dict(atlas["baseline"]), {}
    activation = seed
    for _ in range(steps):
        next_values = defaultdict(float, {f: .4*v for f, v in seed.items()})
        for f, v in activation.items():
            for target, strength in atlas["transitions"].get(f, {}).items():
                next_values[target] += .6*v*strength
        activation = normalized(dict(sorted(next_values.items(), key=lambda x: (-x[1],x[0]))[:512]))
    scores = defaultdict(float)
    for f, v in activation.items():
        for g, strength in atlas["links"].get(f, {}).items():
            scores[g] += v * strength
    scores = normalized(scores)
    # A global prior preserves a path when a feature has few observations.
    out = {g: .15*v for g,v in atlas["baseline"].items()}
    for g,v in scores.items():
        out[g] = out.get(g,0) + .85*v
    return normalized(out), activation

def byte_ranges(tensors, layer, group):
    """GGML ne[0] is contiguous. Gate/up rows correspond to FFN channels.
    Do not mistake down-projection columns for contiguous rows."""
    result = []
    for kind in ("ffn_gate", "ffn_up"):
        name = f"blk.{layer}.{kind}.weight"
        t = tensors.get(name)
        if not t:
            raise ValueError(f"Missing tensor {name}")
        shape = t["shape"]
        if len(shape) != 2 or shape[1] <= 0 or t["size"] % shape[1]:
            raise ValueError(f"Invalid row layout {name}")
        rows = shape[1]
        first = group * GROUP
        if not 0 <= first < rows:
            raise ValueError("Group outside tensor")
        row = t["size"] // rows
        result.append({"tensor": name, "offset": t["offset"] + first*row,
                       "length": min(GROUP, rows-first)*row, "type": t["type"]})
    return result

def plan(scores, tensors, budget):
    if not 0 < budget <= 64*1024*1024:
        raise ValueError("Budget must be 1 byte to 64 MiB")
    used, selected = 0, []
    for g, score in sorted(scores.items(), key=lambda x: (-x[1],x[0])):
        if not re.fullmatch(r"\d+:\d+", g):
            raise ValueError("Invalid group address")
        l, i = map(int,g.split(":"))
        ranges = byte_ranges(tensors,l,i)
        size = sum(r["length"] for r in ranges)
        if used+size > budget:
            continue
        used += size
        selected.append({"group":g,"score":score,"ranges":ranges})
    return selected, used

def coverage(selected, measured):
    return sum(measured.get(s["group"],0) for s in selected)

def evaluate(probes, atlas, tensors, budget, seed=701):
    results=[]
    rng=random.Random(seed)
    for p in probes:
        scores, active=rank(p["features"], atlas)
        learned, n=plan(scores,tensors,budget)
        baseline, nb=plan(atlas["baseline"],tensors,budget)
        control={g:rng.random() for g in atlas["baseline"]}
        random_plan,nr=plan(control,tensors,budget)
        oracle,no=plan(p["groups"],tensors,budget)
        results.append({"id":p["id"],"category":p["category"],"feature_coverage":sum(v for f,v in p["features"].items() if f in atlas["freq"]),
                        "atlas":coverage(learned,p["groups"]),"baseline":coverage(baseline,p["groups"]),
                        "random":coverage(random_plan,p["groups"]),"oracle":coverage(oracle,p["groups"]),
                        "bytes":{"atlas":n,"baseline":nb,"random":nr,"oracle":no}})
    means={k:sum(r[k] for r in results)/len(results) for k in ("atlas","baseline","random","oracle","feature_coverage")} if results else {}
    return {"count":len(results),"mean":means,"rows":results}

#!/usr/bin/env python3
"""Trains a model of branch probabilities on the CSVs the builds wrote, one per program, and says how well it
predicts the branches of a program it has not seen: every program is held out in turn.

  train-branches.py out-dir name=branches.csv ...

ML_WEIGHT says how much a branch counts by how often it ran: "log" (the default), "sqrt" or "count".
ML_DROP names features, by a part of their name, that the model is not to use; they stay in the file, as zero.
"""
import os, sys, math
import numpy as np
from sklearn.ensemble import HistGradientBoostingRegressor

out = sys.argv[1]
TEMPLATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "substratevm", "src", "com.oracle.svm.hosted", "src", "com", "oracle", "svm", "hosted", "crucible", "profiles", "CrucibleBranchModelData.java")
data = {}
names = None
for spec in sys.argv[2:]:
    name, path = spec.split("=", 1)
    with open(path) as f:
        header = f.readline().strip().split(",")
        rows = np.loadtxt(f, delimiter=",", ndmin=2)
    if rows.size == 0:
        continue
    names = header[2:]
    for i, feature in enumerate(names):
        if any(d in feature for d in DROP):
            rows[:, 2 + i] = 0
    data[name] = rows
    print("%-18s %7d branches, mean probability of the true side %.3f" % (name, len(rows), rows[:, 0].mean()))

WEIGHT = os.environ.get("ML_WEIGHT", "log")
DROP = [d for d in os.environ.get("ML_DROP", "").split(",") if d]

def weights(rows):
    # A branch that ran a million times should count for more than one that ran once; how much more is the question.
    if WEIGHT == "count":
        return rows[:, 1] + 1.0
    if WEIGHT == "sqrt":
        return np.sqrt(rows[:, 1]) + 1.0
    return np.log1p(rows[:, 1]) + 1.0

def fit(train):
    # Categories are small integers and are split on as numbers, which keeps a tree a list of "at most" tests.
    model = HistGradientBoostingRegressor(max_iter=100, max_leaf_nodes=31, learning_rate=0.1, min_samples_leaf=40)
    model.fit(train[:, 2:], train[:, 0], sample_weight=weights(train))
    return model

def score(p, rows):
    y = rows[:, 0]; w = weights(rows)
    p = np.clip(p, 0.0, 1.0)
    mae = np.average(np.abs(p - y), weights=w)
    # The side a compiler lays out as the fall-through: how often the prediction picks the busier one.
    decided = np.abs(y - 0.5) > 0.1
    side = np.average((p[decided] > 0.5) == (y[decided] > 0.5), weights=w[decided]) if decided.any() else float("nan")
    return mae, side

def num(v):
    v = float(v)
    return "Infinity" if v == math.inf else "-Infinity" if v == -math.inf else repr(v)

def tree_lines(model):
    trees = []
    for (predictor,) in model._predictors:
        nodes = []
        for n in predictor.nodes:
            nodes.append(num(n["value"]) if n["is_leaf"] else "%d:%s:%d:%d" % (n["feature_idx"], num(n["num_threshold"]), n["left"], n["right"]))
        trees.append(";".join(nodes))
    return trees

def text(model, path):
    open(path, "w").write(",".join(names) + "\n" + num(np.ravel(model._baseline_prediction)[0]) + "\n" + "\n".join(tree_lines(model)) + "\n")

def java(model, path, template):
    trees = tree_lines(model)
    head = open(template).read()
    head = head[:head.index("// Checkstyle: stop")]
    body = "// Checkstyle: stop\nfinal class CrucibleBranchModelData {\n\n    /** The features the trees were trained on, in order. */\n    static final String FEATURES = \"%s\";\n\n" % ",".join(names)
    body += "    /** What the sum of the trees starts from. */\n    static final double BASE = %s;\n\n    static final String[] TREES = {\n" % num(np.ravel(model._baseline_prediction)[0])
    body += "".join('                    "%s",\n' % t for t in trees)
    body += "    };\n\n    private CrucibleBranchModelData() {\n    }\n}\n// Checkstyle: resume\n"
    open(path, "w").write(head + body)

print()
print("%-18s %8s %8s | %8s %8s" % ("held out", "MAE 0.5", "side", "MAE", "side"))
tot = []
for held in data:
    train = np.vstack([rows for name, rows in data.items() if name != held])
    test = data[held]
    model = fit(train)
    text(model, out + "/model-without-%s.txt" % held)
    base = score(np.full(len(test), 0.5), test)
    got = score(model.predict(test[:, 2:]), test)
    tot.append((base[0], got[0], got[1]))
    print("%-18s %8.3f %8s | %8.3f %8.3f" % (held, base[0], "-", got[0], got[1]))
print("%-18s %8.3f %8s | %8.3f %8.3f" % ("mean", np.mean([t[0] for t in tot]), "-", np.mean([t[1] for t in tot]), np.nanmean([t[2] for t in tot])))

model = fit(np.vstack(list(data.values())))
text(model, out + "/model-all.txt")
# The exported trees have to say what the library says.
check = data[next(iter(data))][:2000, 2:]
def trees_predict(model, X):
    out = np.full(len(X), float(np.ravel(model._baseline_prediction)[0]))
    for (predictor,) in model._predictors:
        nd = predictor.nodes
        for i, x in enumerate(X):
            n = 0
            while not nd[n]["is_leaf"]:
                n = nd[n]["left"] if x[nd[n]["feature_idx"]] <= nd[n]["num_threshold"] else nd[n]["right"]
            out[i] += nd[n]["value"]
    return out
print("\nlargest difference between the exported trees and the library on 2000 branches: %.2e" % np.abs(trees_predict(model, check) - model.predict(check)).max())
if len(sys.argv) > 1 and TEMPLATE:
    java(model, out + "/CrucibleBranchModelData.java", TEMPLATE)
    print("trees written to %s/CrucibleBranchModelData.java (%d trees)" % (out, len(model._predictors)))

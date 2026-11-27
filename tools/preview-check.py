#!/usr/bin/env python3
"""Checks what tools/preview-check.sh rendered with the Android CLI.

Fails when a preview didn't render (the CLI's error, or an error image in place of the screen) or a
preview function produced no image. Warns about text that runs past the screen's left or right edge,
outside anything that scrolls sideways. Writes a summary to $GITHUB_STEP_SUMMARY when it is set.

The layout trees give the bounds of each composable's content, without its padding, so they can't
tell how big a touch target is; the Robolectric tests stay the place for that.

Usage: preview-check.py <previews.json> <render dir> <render.log> <render exit status>
"""
import glob
import json
import os
import sys

listing, render_dir, log_path, status = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
data = json.load(open(listing))
items = next(v for v in data.values() if isinstance(v, list)) if isinstance(data, dict) else data
methods = sorted({i["methodFqn"] for i in items})

errors = []
warnings = []
log = open(log_path, errors="replace").read() if os.path.exists(log_path) else ""
for line in log.splitlines():
    if line.startswith("Error rendering"):
        errors.append(line)
if status != 0 and not errors:
    errors.append(f"The Android CLI stopped with status {status}; see render.log.")

trees = sorted(glob.glob(os.path.join(render_dir, "**", "*.json"), recursive=True))
rendered = 0
for method in methods:
    cls, fn = method.rsplit(".", 1)
    found = glob.glob(os.path.join(render_dir, *cls.split("."), f"{fn}_*.png"))
    if not found:
        errors.append(f"{fn}: no image")

for path in trees:
    tree = json.load(open(path))
    name = os.path.basename(path)[:-5]
    width = tree["width"]
    roots = tree.get("components", [])
    # A preview that threw draws an error page instead; its tree starts at the preview host, not at
    # the preview function.
    if not roots or roots[0].get("name", "").startswith("androidx.compose.ui.tooling."):
        errors.append(f"{name}: rendered an error instead of the screen")
        continue
    rendered += 1

    def walk(node, sideways):
        sem = node.get("semantics", {})
        b = node["bounds"]
        text = sem.get("text")
        if text and not sideways and (b["right"] > width + 2 or b["left"] < -2):
            where = node.get("sourceLocation", {})
            warnings.append(
                f"{name} (font x{tree['fontScale']}): \"{text[:40]}\" runs past the screen edge "
                f"({where.get('fileName')}:{where.get('lineNumber')})"
            )
        scrolls_sideways = sideways or ("ScrollBy" in sem.get("actions", "") and b["width"] > width + 2)
        for child in node.get("children", []):
            walk(child, scrolls_sideways)

    for root in roots:
        walk(root, False)

for w in warnings:
    print(f"::warning::{w}")
for e in errors:
    print(f"::error::Preview failed: {e}")
summary = (
    f"Android CLI previews: {rendered} rendered from {len(methods)} preview functions, "
    f"{len(errors)} failed, {len(warnings)} text overflow warnings."
)
print(summary)
if os.environ.get("GITHUB_STEP_SUMMARY"):
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as out:
        out.write(f"### Screen previews\n\n{summary}\n")
        for line in errors + warnings:
            out.write(f"- {line}\n")
sys.exit(1 if errors else 0)

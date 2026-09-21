"""How accurately does PERCEPTION read what retrieval is keyed on?

    python tools/perception_accuracy.py run        # model calls; resumable, skips photos already answered
    python tools/perception_accuracy.py report     # scores the saved replies, no calls

Retrieval chooses reference cases by the geometry PERCEPTION reports, and decides
what is in view from its surface_in_view. The offline retrieval estimates used the
annotated geometry instead, which flatters retrieval if perception misreads it.
This measures the readings themselves.

What is sent is what the phone sends: the PERCEPTION prompt read verbatim from
Prompts.kt, the model and endpoint from local.properties, "Describe this
photograph." as the user text, and the photograph upright, reduced to a 1152 px
long edge and re-encoded as JPEG quality 90 (FrameEncoder.PERCEPTION_MAX_EDGE).
The photographs are the dataset's, not CameraX stills, so framing and exposure
differ from a session.

Photographs: by default the ctr, top and bot views of the evaluation products.
ctr is centred on the annotated date, top shows the top and bot the base, so
each has a known answer:

  geometry           the annotated package geometry, in the phone's vocabulary
  surface_in_view    top -> top, bot -> base, ctr -> from the annotated date
                     surface; surfaces whose view depends on the angle (end flap,
                     neck, shoulder, lid rim) are shown but not scored
  date_region_visible, iso_date
                     ctr only, against the verified annotation

Settled geometry is scored as the phone computes it: the most frequent reading
over the product's photographs in the order ctr, top, bot, earliest on a tie.

Replies are written to tools/runs/perception_accuracy/<run>/replies.jsonl and
contain what the model read, dates included. That directory is gitignored.
"""

from __future__ import annotations

import argparse
import base64
import csv
import io
import json
import re
import sys
import time
import urllib.error
import urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_retrieval_index as locations  # noqa: E402

TOOLS = Path(__file__).resolve().parent
REPO = TOOLS.parent
PROMPTS_KT = REPO / "app" / "src" / "main" / "java" / "com" / "rastislavkish" / "vscan" / "agent" / "Prompts.kt"
LOCAL_PROPERTIES = REPO / "local.properties"
DEFAULT_DATASET = Path(r"D:\agent images\dataset")
RUNS = TOOLS / "runs" / "perception_accuracy"

# As in app/build.gradle.kts and VisionClient.
DEFAULT_MODEL = "gemini-3.5-flash"
DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
MAX_TOKENS = 4000
USER_TEXT = "Describe this photograph."
MAX_EDGE = 1152
JPEG_QUALITY = 90
TIMEOUT_S = 120

DEFAULT_VIEWS = ("ctr", "top", "bot")

GEOMETRIES = ["carton", "jar", "can", "bottle", "bag", "cup", "tray", "multipack", "unknown"]
SURFACES = ["side", "top", "base", "label", "unclear"]

# Annotated date surface -> the surface_in_view values that are a correct reading
# of a photograph centred on it. None: the right answer depends on the angle.
_TOP = {"top"}
_BASE = {"base"}
_SIDE = {"side"}
_LABEL = {"label"}
_SIDE_OR_LABEL = {"side", "label"}
CTR_SURFACE = {
    "lid": _TOP, "cap": _TOP, "foil_lid": _TOP, "foil_lids": _TOP, "top_labels": _TOP, "top_near_cap": _TOP,
    "base": _BASE, "base_label": _BASE, "bottom_fold": _BASE,
    "narrow_side": _SIDE, "side_near_barcode": _SIDE, "upper_side": _SIDE, "upper_sidewall": _SIDE,
    "lower_side": _SIDE,
    "side_label": _SIDE_OR_LABEL, "upper_side_label": _SIDE_OR_LABEL,
    "back_label": _LABEL, "upper_back_label": _LABEL, "lower_back_label": _LABEL, "back_lower_panel": _LABEL,
    "back_above_barcode": _LABEL, "back_below_barcode": _LABEL, "back_near_barcode": _LABEL,
    "front_film": _LABEL, "front_label": _LABEL,
    "end_flap": None, "neck": None, "shoulder": None, "lid_rim": None,
}


class AccuracyError(Exception):
    """The measurement cannot be made as specified."""


# ---------------------------------------------------------------- what the phone sends

def perception_prompt(source: str) -> str:
    """PERCEPTION exactly as the Kotlin raw string holds it."""
    match = re.search(r'const val PERCEPTION="""(.*?)"""', source, re.DOTALL)
    if not match:
        raise AccuracyError("PERCEPTION not found in Prompts.kt")
    return match.group(1)


def read_properties(path: Path) -> dict[str, str]:
    values = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith(("#", "!")) and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().replace("\\:", ":")
    return values


def encode_photo(path: Path) -> str:
    from PIL import Image, ImageOps

    with Image.open(path) as image:
        image.draft("RGB", (MAX_EDGE * 2, MAX_EDGE * 2))
        upright = ImageOps.exif_transpose(image).convert("RGB")
    upright.thumbnail((MAX_EDGE, MAX_EDGE))
    out = io.BytesIO()
    upright.save(out, format="JPEG", quality=JPEG_QUALITY)
    return base64.b64encode(out.getvalue()).decode("ascii")


def strip_code_fence(text: str) -> str:
    """Mirrors VisionClient.stripCodeFence."""
    if not text.startswith("```"):
        return text
    lines = text.splitlines()
    if lines and lines[0].startswith("```"):
        lines = lines[1:]
    if lines and lines[-1].strip() == "```":
        lines = lines[:-1]
    return "\n".join(lines).strip()


def call(base_url: str, key: str, model: str, prompt: str, image_b64: str) -> dict:
    """One perception call. Returns {"perception": {...}} or {"error": "..."}, with timing and tokens."""
    body = json.dumps({
        "model": model,
        "max_tokens": MAX_TOKENS,
        "messages": [
            {"role": "system", "content": prompt},
            {"role": "user", "content": [
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_b64}"}},
                {"type": "text", "text": USER_TEXT},
            ]},
        ],
    }).encode("utf-8")
    request = urllib.request.Request(f"{base_url.rstrip('/')}/chat/completions", data=body, method="POST",
                                     headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response:
            text = response.read().decode("utf-8")
            status = response.status
    except urllib.error.HTTPError as e:
        return {"error": f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:300]}", "http_status": e.code,
                "elapsed_ms": int((time.monotonic() - started) * 1000)}
    except Exception as e:
        return {"error": f"transport: {e}", "elapsed_ms": int((time.monotonic() - started) * 1000)}

    result = {"http_status": status, "elapsed_ms": int((time.monotonic() - started) * 1000)}
    try:
        root = json.loads(text)
        if isinstance(root, list):
            root = root[0]
        if "error" in root:
            return {**result, "error": json.dumps(root["error"])[:300]}
        result["tokens"] = (root.get("usage") or {}).get("total_tokens")
        choice = root["choices"][0]
        result["finish_reason"] = choice.get("finish_reason")
        content = (choice.get("message") or {}).get("content") or ""
        result["raw"] = content
        result["perception"] = json.loads(strip_code_fence(content.strip()))
    except Exception as e:
        result["error"] = f"unparseable reply: {e}"
    return result


# ---------------------------------------------------------------- truth and scoring

def photographs(annotations: dict, rows: list[dict], manifest: dict[str, dict[str, Path]],
                side: str, views: tuple[str, ...]) -> list[dict]:
    products = {p["product_id"]: p for p in annotations.get("products") or []}
    chosen = sorted(r["product_id"] for r in rows if side == "all" or r["split"] == side)
    out = []
    for pid in chosen:
        for view in views:
            path = manifest.get(pid, {}).get(view)
            if path is None:
                raise AccuracyError(f"{pid} has no {view} photograph in the manifest")
            out.append({"product_id": pid, "view": view, "path": path, "product": products[pid]})
    return out


def expected_geometry(product: dict) -> str:
    return locations.GEOMETRY[product["package_geometry"]]


def expected_surfaces(product: dict, view: str) -> set[str] | None:
    """Correct surface_in_view values for this photograph, or None when not scored."""
    if view == "top":
        return {"top"}
    if view == "bot":
        return {"base"}
    if view != "ctr":
        return None
    accepted = CTR_SURFACE.get(product["date_surface"])
    if accepted is None:
        return None
    # Round the label is the side wall, and perception cannot be faulted for either word.
    if locations.GEOMETRY_CLASSES[expected_geometry(product)] == "rigid_round" and accepted & _SIDE_OR_LABEL:
        return set(_SIDE_OR_LABEL)
    return set(accepted)


def settled_geometry(readings: list[str]) -> str:
    """Mirrors InspectionMemory.settledGeometry: most frequent known reading, earliest on a tie."""
    known = [r.strip().lower() for r in readings if r and r.strip() and r.strip().lower() != "unknown"]
    if not known:
        return "unknown"
    counts = Counter(known)
    best = max(counts.values())
    return next(r for r in known if counts[r] == best)


def score(photos: list[dict], replies: dict[tuple[str, str], dict], views: tuple[str, ...]) -> dict:
    geometry_confusion: dict[str, Counter] = defaultdict(Counter)
    surface_by_view: dict[str, Counter] = defaultdict(Counter)
    surface_unscored: dict[str, Counter] = defaultdict(Counter)
    geometry_by_view = Counter()
    per_product: dict[str, list[str]] = defaultdict(list)
    ctr = Counter()
    failures = []
    latencies = []

    for photo in photos:
        pid, view, product = photo["product_id"], photo["view"], photo["product"]
        reply = replies.get((pid, view))
        if reply is None:
            failures.append((pid, view, "not run"))
            continue
        if "perception" not in reply or not isinstance(reply["perception"], dict):
            failures.append((pid, view, reply.get("error", "no perception")))
            continue
        latencies.append(reply.get("elapsed_ms", 0))
        seen = reply["perception"]

        truth = expected_geometry(product)
        got = str(seen.get("geometry") or "unknown").strip().lower()
        geometry_confusion[truth][got] += 1
        geometry_by_view[f"{view}_total"] += 1
        geometry_by_view[f"{view}_correct"] += got == truth
        geometry_by_view[f"{view}_same_class"] += (locations.GEOMETRY_CLASSES.get(got) == locations.GEOMETRY_CLASSES[truth])
        per_product[pid].append((views.index(view), got))

        surface = str(seen.get("surface_in_view") or "unclear").strip().lower()
        accepted = expected_surfaces(product, view)
        if accepted is None:
            surface_unscored[product["date_surface"]][surface] += 1
        else:
            surface_by_view[view]["total"] += 1
            surface_by_view[view]["correct"] += surface in accepted
            surface_by_view[view]["unclear"] += surface == "unclear"

        if view == "ctr":
            ctr["total"] += 1
            ctr["date_region_visible"] += seen.get("date_region_visible") is True
            ctr["date_legible"] += seen.get("date_legible") is True
            truth_iso = (product.get("normalized_date") or "").strip()
            got_iso = (seen.get("iso_date") or "").strip() if isinstance(seen.get("iso_date"), str) else ""
            if re.fullmatch(r"\d{4}-\d{2}(-\d{2})?", truth_iso):
                ctr["dated"] += 1
                ctr["iso_given"] += bool(got_iso)
                ctr["iso_correct"] += got_iso == truth_iso
                ctr["iso_wrong"] += bool(got_iso) and got_iso != truth_iso

    settled = Counter()
    settled_confusion: dict[str, Counter] = defaultdict(Counter)
    by_id = {p["product_id"]: p["product"] for p in photos}
    for pid, readings in per_product.items():
        truth = expected_geometry(by_id[pid])
        got = settled_geometry([g for _, g in sorted(readings)])
        settled["total"] += 1
        settled["correct"] += got == truth
        settled["same_class"] += locations.GEOMETRY_CLASSES.get(got) == locations.GEOMETRY_CLASSES[truth]
        settled_confusion[truth][got] += 1

    return {
        "photographs": len(photos),
        "answered": len(photos) - len(failures),
        "failures": failures,
        "median_latency_ms": sorted(latencies)[len(latencies) // 2] if latencies else None,
        "geometry_by_view": dict(geometry_by_view),
        "geometry_confusion": {k: dict(v) for k, v in geometry_confusion.items()},
        "settled_geometry": dict(settled),
        "settled_confusion": {k: dict(v) for k, v in settled_confusion.items()},
        "surface_by_view": {k: dict(v) for k, v in surface_by_view.items()},
        "surface_unscored": {k: dict(v) for k, v in surface_unscored.items()},
        "ctr": dict(ctr),
    }


def format_report(result: dict, views: tuple[str, ...]) -> str:
    def rate(n, d):
        return f"{n}/{d} ({n / d:.0%})" if d else "n/a"

    lines = [f"{result['answered']} of {result['photographs']} photographs answered; "
             f"median latency {result['median_latency_ms']} ms"]
    if result["failures"]:
        lines.append(f"failures: {len(result['failures'])}")
        lines += [f"  {pid} {view}: {why[:120]}" for pid, view, why in result["failures"][:10]]

    g = result["geometry_by_view"]
    lines.append("\ngeometry, per photograph (exact / same class):")
    for view in views:
        total = g.get(f"{view}_total", 0)
        lines.append(f"  {view:4} {rate(g.get(f'{view}_correct', 0), total):>14}   {rate(g.get(f'{view}_same_class', 0), total):>14}")
    s = result["settled_geometry"]
    lines.append(f"settled geometry, per product: exact {rate(s.get('correct', 0), s.get('total', 0))}, "
                 f"same class {rate(s.get('same_class', 0), s.get('total', 0))}")

    lines.append("\ngeometry confusion, all photographs (rows annotated, columns read):")
    cols = [c for c in GEOMETRIES if any(c in row for row in result["geometry_confusion"].values())]
    cols += sorted({c for row in result["geometry_confusion"].values() for c in row} - set(cols))
    lines.append("  " + " " * 10 + "".join(f"{c[:9]:>10}" for c in cols))
    for truth in [t for t in GEOMETRIES if t in result["geometry_confusion"]]:
        row = result["geometry_confusion"][truth]
        lines.append(f"  {truth:10}" + "".join(f"{row.get(c, 0) or '.':>10}" for c in cols))

    lines.append("\nsurface_in_view (correct / answered unclear):")
    for view in views:
        v = result["surface_by_view"].get(view, {})
        lines.append(f"  {view:4} {rate(v.get('correct', 0), v.get('total', 0)):>14}   unclear {v.get('unclear', 0)}")
    if result["surface_unscored"]:
        lines.append("  not scored (angle-dependent), what was read:")
        for surface, readings in sorted(result["surface_unscored"].items()):
            lines.append(f"    {surface:10} " + ", ".join(f"{k} {n}" for k, n in sorted(readings.items(), key=lambda kv: -kv[1])))

    c = result["ctr"]
    if c:
        lines.append("\nctr photographs (centred on the date):")
        lines.append(f"  date_region_visible {rate(c.get('date_region_visible', 0), c.get('total', 0))}, "
                     f"date_legible {rate(c.get('date_legible', 0), c.get('total', 0))}")
        lines.append(f"  iso_date correct {rate(c.get('iso_correct', 0), c.get('dated', 0))}, "
                     f"wrong {c.get('iso_wrong', 0)}, none given {c.get('dated', 0) - c.get('iso_given', 0)}")
    lines.append("\nDataset photographs, not CameraX stills; one reply per photograph.")
    return "\n".join(lines)


# ---------------------------------------------------------------- commands

def read_manifest(dataset: Path) -> dict[str, dict[str, Path]]:
    manifest = dataset / "manifest.csv"
    if not manifest.exists():
        raise AccuracyError(f"{manifest} not found")
    out: dict[str, dict[str, Path]] = defaultdict(dict)
    with manifest.open(encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            out[row["product_id"]][row["view"]] = dataset / row["filename"]
    return out


def load_replies(path: Path) -> dict[tuple[str, str], dict]:
    replies = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                record = json.loads(line)
                replies[(record["product_id"], record["view"])] = record
    return replies


def inputs(args):
    rows, _ = locations.read_split(args.split)
    annotations = locations.load_annotations(args.annotations)
    locations.validate_split(rows, annotations.get("products") or [])
    views = tuple(v.strip() for v in args.views.split(",") if v.strip())
    photos = photographs(annotations, rows, read_manifest(args.dataset), args.side, views)
    return photos, views


def cmd_run(args) -> int:
    try:
        photos, views = inputs(args)
        properties = read_properties(LOCAL_PROPERTIES)
        key = properties.get("geminiApiKey", "")
        if not key:
            raise AccuracyError("geminiApiKey is not set in local.properties")
        model = args.model or properties.get("geminiModel") or DEFAULT_MODEL
        base_url = properties.get("geminiBaseUrl") or DEFAULT_BASE_URL
        prompt = perception_prompt(PROMPTS_KT.read_text(encoding="utf-8"))
    except (AccuracyError, locations.BuildError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    run_dir = RUNS / args.run
    run_dir.mkdir(parents=True, exist_ok=True)
    replies_path = run_dir / "replies.jsonl"
    done = {k for k, v in load_replies(replies_path).items() if "perception" in v}
    todo = [p for p in photos if (p["product_id"], p["view"]) not in done]

    meta = {"started_at": datetime.now(timezone.utc).isoformat(timespec="seconds"), "model": model,
            "base_url": base_url, "views": list(views), "side": args.side, "photographs": len(photos),
            "max_edge": MAX_EDGE, "jpeg_quality": JPEG_QUALITY,
            "prompt_sha256": locations.sha256_bytes(prompt.encode("utf-8"))}
    (run_dir / f"run-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.json").write_text(
        json.dumps(meta, indent=1), encoding="utf-8")
    print(f"{len(todo)} of {len(photos)} photographs to call, model {model}, {args.workers} at a time -> {run_dir}")

    def work(photo):
        image = encode_photo(photo["path"])
        for attempt in range(4):
            reply = call(base_url, key, model, prompt, image)
            if reply.get("http_status") not in (429, 500, 503) or attempt == 3:
                break
            time.sleep(5 * 2 ** attempt)
        return photo, reply

    answered = failed = 0
    with ThreadPoolExecutor(max_workers=args.workers) as pool, replies_path.open("a", encoding="utf-8") as out:
        for photo, reply in pool.map(work, todo):
            record = {"product_id": photo["product_id"], "view": photo["view"], "model": model, **reply}
            out.write(json.dumps(record, ensure_ascii=False) + "\n")
            out.flush()
            answered += "perception" in reply
            failed += "perception" not in reply
            print(f"\r{answered + failed}/{len(todo)} ({failed} failed)", end="", file=sys.stderr)
    print(file=sys.stderr)

    return cmd_report(args)


def cmd_report(args) -> int:
    try:
        photos, views = inputs(args)
    except (AccuracyError, locations.BuildError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    run_dir = RUNS / args.run
    replies = load_replies(run_dir / "replies.jsonl")
    if not replies:
        print(f"error: no replies in {run_dir}; run first", file=sys.stderr)
        return 1
    result = score(photos, replies, views)
    (run_dir / "report.json").write_text(json.dumps(result, indent=1, default=list), encoding="utf-8")
    print(format_report(result, views))
    print(f"-> {run_dir / 'report.json'}")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command", required=True)
    for name, func in (("run", cmd_run), ("report", cmd_report)):
        p = sub.add_parser(name)
        p.add_argument("--run", default="evaluation-ctr-top-bot", help="run directory name under tools/runs/perception_accuracy")
        p.add_argument("--split", type=Path, default=locations.DEFAULT_SPLIT)
        p.add_argument("--side", choices=["evaluation", "reference", "all"], default="evaluation")
        p.add_argument("--views", default=",".join(DEFAULT_VIEWS))
        p.add_argument("--annotations", type=Path, default=locations.DEFAULT_ANNOTATIONS)
        p.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
        if name == "run":
            p.add_argument("--model", help="defaults to geminiModel in local.properties")
            p.add_argument("--workers", type=int, default=4)
        p.set_defaults(func=func)
    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())

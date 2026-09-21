"""Build the expiry agent's date-location retrieval index.

Reads the food-package annotations and writes the asset the phone retrieves from.

    python tools/build_retrieval_index.py split                      # once: propose a product-group split
    python tools/build_retrieval_index.py build --split tools/retrieval_split.csv
    python tools/build_retrieval_index.py check --split tools/retrieval_split.csv   # before every evaluation run
What goes in is where the date was printed on each reference package, and what
kind of package it was. What stays out is the date itself: the planner may use
other packages to decide where to look next, but a date spoken to the user has to
be read off the package in their hand. Shipping no dates in the APK makes that a
property of the asset rather than a promise of the prompt.

Evaluation packages must not be reference cases. `build --split` indexes only the
reference side of a frozen product-group split and records the other side;
`check` exits non-zero when an index and an evaluation set overlap, so a runner
can refuse a contaminated experiment instead of relying on someone remembering.

The annotations are drafts. The label status, counted separately for the product
answer and for the date location, is copied into the index and logged on the
phone at session start.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import random
import re
import sys
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "vscan-date-location-index-v1"

TOOLS = Path(__file__).resolve().parent
DEFAULT_ANNOTATIONS = Path(r"D:\agent images\dataset\annotations\annotations.draft.json")
DEFAULT_OUTPUT = TOOLS.parent / "app" / "src" / "main" / "assets" / "agent" / "date_location_index.json"
DEFAULT_SPLIT = TOOLS / "retrieval_split.csv"

# The value the annotation review page writes when a person ticks "verified".
HUMAN_VERIFIED = "HUMAN_VERIFIED"

# Verifying a product in the review page checks its date, evidence view and type,
# not where on the package the date is. A verified location therefore needs its
# own field; until the review page records it, no location counts as verified.
LOCATION_REVIEW_FIELD = "location_review_status"

REFERENCE = "reference"
EVALUATION = "evaluation"

# Annotation geometry -> the vocabulary PERCEPTION reports on the phone. Retrieval
# is keyed on what the phone can observe, so the index speaks the phone's words.
GEOMETRY = {
    "carton": "carton",
    "multipack_cartons": "multipack",
    "multipack_tubs": "multipack",
    "pouch": "bag",
    "wrapped_block": "bag",
    "wrapped_round": "bag",
    "wrapped_cylinder": "bag",
    "jar": "jar",
    "can": "can",
    "drink_can": "can",
    "aerosol_can": "can",
    "canister": "can",
    "bottle": "bottle",
    "squeeze_bottle": "bottle",
    "tub": "cup",
    "plastic_tub": "cup",
    "plastic_container": "cup",
    "container": "cup",
    "tray": "tray",
    "round_tray": "tray",
}

# The back-off level used when too few packages share the exact geometry.
GEOMETRY_CLASSES = {
    "carton": "box",
    "multipack": "box",
    "jar": "rigid_round",
    "can": "rigid_round",
    "bottle": "rigid_round",
    "cup": "rigid_round",
    "bag": "flexible",
    "tray": "flexible",
}

# Annotation surface -> a coarse target location. The annotated surface itself is
# kept alongside as the detail: a bottle's cap and its neck are both TOP, but a
# clear photograph of one says nothing about the other, and the phone needs the
# distinction to avoid treating one look as having covered both.
LOCATIONS = {
    "base": "BASE",
    "base_label": "BASE",
    "bottom_fold": "BASE",
    "lid": "TOP",
    "lid_rim": "TOP",
    "foil_lid": "TOP",
    "foil_lids": "TOP",
    "cap": "TOP",
    "neck": "TOP",
    "shoulder": "TOP",
    "top_near_cap": "TOP",
    "top_labels": "TOP",
    "end_flap": "END",
    "narrow_side": "SIDE",
    "side_label": "SIDE",
    "side_near_barcode": "SIDE",
    "upper_side": "SIDE",
    "upper_side_label": "SIDE",
    "upper_sidewall": "SIDE",
    "lower_side": "SIDE",
    "back_label": "BACK",
    "upper_back_label": "BACK",
    "lower_back_label": "BACK",
    "back_lower_panel": "BACK",
    "back_above_barcode": "BACK",
    "back_below_barcode": "BACK",
    "back_near_barcode": "BACK",
    "front_film": "FRONT",
    "front_label": "FRONT",
}

# Product names are English descriptions written by the annotator; the text the
# phone reads is whatever is printed, mostly Spanish. Descriptive English words
# would match nothing useful and occasionally something wrong, so only words that
# are plausibly printed on the package - brands, and names like kefir - are kept.
GENERIC_WORDS = {
    "bakery", "balsamic", "bars", "base", "biscuit", "biscuits", "block", "blue",
    "bottle", "boxed", "cakes", "canned", "capsules", "carton", "cartons", "cereal",
    "cheese", "chewing", "chicken", "chocolate", "cocoa", "coconut", "coffee",
    "colouring", "container", "cooking", "crackers", "cream", "crinklecut",
    "crispbread", "dairy", "dessert", "dried", "drink", "duck", "filled", "fingers",
    "flakes", "food", "fried", "frozen", "fruit", "gherkins", "glass", "green",
    "herbs", "instant", "lemon", "mashed", "meat", "metal", "milk", "mini",
    "noodles", "olive", "omelette", "onion", "organic", "package", "peanuts",
    "pieces", "pink", "plastic", "pork", "potato", "pouch", "preparation",
    "product", "promotional", "refrigerated", "rice", "roast", "salmon", "sandwich",
    "sauce", "sausages", "seafood", "small", "snack", "sponge", "spray", "stock",
    "supplement", "sweets", "tenderloin", "toast", "tomato", "tuna", "turkey",
    "wheat", "white", "wine", "yoghurt", "zero", "with", "pack", "tray", "wrapped",
    # Ordinary Spanish words on many packages: "al horno" (baked), "al dente".
    "horno", "dente",
}

MIN_TOKEN_LENGTH = 4


class BuildError(Exception):
    """The annotations cannot be turned into an index without guessing."""


def fold(text: str) -> str:
    """Lower case, accents removed. Must match DateLocationMemory.fold on the phone."""
    decomposed = unicodedata.normalize("NFD", text.lower())
    return "".join(c for c in decomposed if unicodedata.category(c) != "Mn")


def name_tokens(name: str) -> list[str]:
    words = re.findall(r"[a-z0-9]+", fold(name or ""))
    return sorted({w for w in words if len(w) >= MIN_TOKEN_LENGTH and w not in GENERIC_WORDS})


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_annotations(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


# ---------------------------------------------------------------- product groups

def product_groups(products: list[dict]) -> dict[str, str]:
    """product_id -> group id. Products that may be the same product or brand stay together.

    Two products are grouped when their names are identical after folding (two
    "Kellogg cereal" records, or two "Glass jar" records that might be the same
    jar) or when they share a brand-like name word. Over-grouping only makes a
    split more conservative; under-grouping lets a product's twin leak its date
    location into the evaluation.
    """
    parent = {p["product_id"]: p["product_id"] for p in products}

    def find(x: str) -> str:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a: str, b: str) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[max(ra, rb)] = min(ra, rb)

    by_key: dict[str, str] = {}
    for p in sorted(products, key=lambda p: p["product_id"]):
        keys = ["name:" + " ".join(re.findall(r"[a-z0-9]+", fold(p.get("product_name", ""))))]
        keys += ["token:" + t for t in name_tokens(p.get("product_name", ""))]
        for key in keys:
            if key in by_key:
                union(by_key[key], p["product_id"])
            else:
                by_key[key] = p["product_id"]

    return {pid: find(pid) for pid in parent}


def propose_split(products: list[dict], evaluation_fraction: float, seed: int) -> list[dict]:
    """A product-group split, stratified by geometry so each package type is represented on both sides."""
    groups = product_groups(products)
    by_id = {p["product_id"]: p for p in products}

    members: dict[str, list[str]] = {}
    for pid, group in groups.items():
        members.setdefault(group, []).append(pid)

    def group_class(group: str) -> str:
        geometries = [GEOMETRY[by_id[pid]["package_geometry"]] for pid in members[group]]
        return max(sorted(set(geometries)), key=geometries.count)

    rng = random.Random(seed)
    assignment: dict[str, str] = {}

    for cls in sorted({group_class(g) for g in members}):
        class_groups = sorted(g for g in members if group_class(g) == cls)
        rng.shuffle(class_groups)
        target = round(evaluation_fraction * sum(len(members[g]) for g in class_groups))
        taken = 0
        for group in class_groups:
            side = EVALUATION if taken < target else REFERENCE
            if side == EVALUATION:
                taken += len(members[group])
            assignment[group] = side

    return [{
        "product_id": pid,
        "legacy_product_id": by_id[pid].get("legacy_product_id", ""),
        "product_name": by_id[pid].get("product_name", ""),
        "geometry": GEOMETRY[by_id[pid]["package_geometry"]],
        "group": groups[pid],
        "split": assignment[groups[pid]],
    } for pid in sorted(by_id)]


def read_split(path: Path) -> tuple[list[dict], str]:
    data = path.read_bytes()
    rows = list(csv.DictReader(io.StringIO(data.decode("utf-8-sig"))))
    if not rows or not {"product_id", "split"} <= rows[0].keys():
        raise BuildError(f"{path}: expected a CSV with product_id and split columns")
    bad = sorted({r["split"] for r in rows} - {REFERENCE, EVALUATION})
    if bad:
        raise BuildError(f"{path}: unknown split values {bad}")
    return rows, sha256_bytes(data)


def validate_split(rows: list[dict], products: list[dict]) -> None:
    """The split must describe exactly these products, under their current ids, with no group divided."""
    by_id = {p["product_id"]: p for p in products}
    ids = [r["product_id"] for r in rows]

    duplicates = sorted({i for i in ids if ids.count(i) > 1})
    missing = sorted(by_id.keys() - set(ids))
    extra = sorted(set(ids) - by_id.keys())
    if duplicates or missing or extra:
        raise BuildError(f"split does not match the annotations: duplicates={duplicates} "
                         f"missing={missing} unknown={extra}")

    # Renumbering moved ids once already (original P81 removed, P90 became P81).
    # A split written before a renumbering would name the wrong packages.
    stale = sorted(r["product_id"] for r in rows
                   if r.get("legacy_product_id") and r["legacy_product_id"] != by_id[r["product_id"]].get("legacy_product_id"))
    if stale:
        raise BuildError(f"split was written against different product ids (legacy id mismatch): {stale}")

    side = {r["product_id"]: r["split"] for r in rows}
    divided = {}
    for pid, group in product_groups(products).items():
        divided.setdefault(group, set()).add(side[pid])
    divided = sorted(g for g, sides in divided.items() if len(sides) > 1)
    if divided:
        raise BuildError(f"product groups divided between reference and evaluation: {divided}")


# ---------------------------------------------------------------- build

def read_exclusions(ids: str | None, file: Path | None) -> set[str]:
    """Product ids from --exclude and --exclude-file (one per line, or a product_id column)."""
    excluded = {p.strip() for p in (ids or "").split(",") if p.strip()}

    if file is not None:
        text = file.read_text(encoding="utf-8-sig")
        first = text.splitlines()[0] if text.strip() else ""
        if "product_id" in first:
            excluded |= {row["product_id"].strip() for row in csv.DictReader(text.splitlines())
                         if row.get("product_id", "").strip()}
        else:
            excluded |= {line.strip() for line in text.splitlines()
                         if line.strip() and not line.startswith("#")}

    return excluded


def location_verified(product: dict) -> bool:
    return product.get("review_status") == HUMAN_VERIFIED and product.get(LOCATION_REVIEW_FIELD) == HUMAN_VERIFIED


def build(annotations: dict, excluded: set[str], verified_only: bool,
          split: tuple[list[dict], str, str] | None = None) -> tuple[dict, list[str]]:
    """`split` is (rows, sha256, file name). Its evaluation side is excluded and recorded."""
    products = annotations.get("products") or []
    known = {p["product_id"] for p in products}

    unknown_exclusions = sorted(excluded - known)
    if unknown_exclusions:
        raise BuildError(f"excluded products not in the annotations: {', '.join(unknown_exclusions)}")

    # Fail closed on vocabulary: a new surface silently filed as "other" would
    # quietly change what the planner is told, and nobody would notice.
    unmapped_geometry = sorted({p.get("package_geometry", "") for p in products} - GEOMETRY.keys() - {""})
    unmapped_surface = sorted({p.get("date_surface", "") for p in products} - LOCATIONS.keys() - {""})
    if unmapped_geometry or unmapped_surface:
        raise BuildError(
            "unmapped annotation values; extend GEOMETRY/LOCATIONS first: "
            f"geometry={unmapped_geometry} surface={unmapped_surface}")

    evaluation: list[str] = []
    split_record = None
    if split is not None:
        rows, digest, name = split
        validate_split(rows, products)
        evaluation = sorted(r["product_id"] for r in rows if r["split"] == EVALUATION)
        split_record = {"file": name, "sha256": digest, "evaluation_products": evaluation}

    left_out = excluded | set(evaluation)

    cases = []
    warnings = []

    for p in sorted(products, key=lambda p: p["product_id"]):
        pid = p["product_id"]
        if pid in left_out:
            continue
        if verified_only and not location_verified(p):
            continue
        if not p.get("date_surface") or not p.get("package_geometry"):
            warnings.append(f"{pid}: no date surface or geometry, skipped")
            continue

        geometry = GEOMETRY[p["package_geometry"]]
        cases.append({
            "product_id": pid,
            "geometry": geometry,
            "geometry_class": GEOMETRY_CLASSES[geometry],
            "location": LOCATIONS[p["date_surface"]],
            "annotated_geometry": p["package_geometry"],
            "annotated_surface": p["date_surface"],
            "name_tokens": name_tokens(p.get("product_name", "")),
            "review_status": p.get("review_status", "UNVERIFIED"),
            "location_review_status": p.get(LOCATION_REVIEW_FIELD, "UNVERIFIED"),
        })

    if not cases:
        reason = ""
        if verified_only:
            reason = (f" (--verified-only needs review_status and {LOCATION_REVIEW_FIELD} both {HUMAN_VERIFIED}; "
                      "the review page does not record location verification yet)")
        raise BuildError("no cases left to index" + reason)

    products_verified = sum(1 for c in cases if c["review_status"] == HUMAN_VERIFIED)
    locations_verified = sum(1 for c in cases if c["review_status"] == HUMAN_VERIFIED
                             and c["location_review_status"] == HUMAN_VERIFIED)
    metadata = annotations.get("metadata") or {}

    index = {
        "schema": SCHEMA,
        "built_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "source": {
            "annotation_schema": metadata.get("schema_version"),
            "annotations_updated_at": metadata.get("updated_at"),
            "manifest_sha256": metadata.get("manifest_sha256"),
        },
        "label_status": (f"{products_verified} of {len(cases)} product answers human-verified; "
                         f"{locations_verified} of {len(cases)} date locations human-verified"),
        "verified_only": verified_only,
        "excluded_products": sorted(left_out),
        "split": split_record,
        "geometry_classes": GEOMETRY_CLASSES,
        "cases": cases,
    }
    return index, warnings


# ---------------------------------------------------------------- check

def overlap(index: dict, evaluation: set[str]) -> list[str]:
    return sorted({c["product_id"] for c in index.get("cases", [])} & evaluation)


# ---------------------------------------------------------------- commands

def cmd_split(args) -> int:
    if args.output.exists() and not args.force:
        print(f"error: {args.output} exists. A split is frozen once used; pass --force to replace it.", file=sys.stderr)
        return 1
    products = load_annotations(args.annotations).get("products") or []
    rows = propose_split(products, args.evaluation_fraction, args.seed)

    buffer = io.StringIO()
    writer = csv.DictWriter(buffer, fieldnames=list(rows[0].keys()), lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    args.output.write_text(buffer.getvalue(), encoding="utf-8")

    evaluation = [r for r in rows if r["split"] == EVALUATION]
    groups = len({r["group"] for r in rows})
    print(f"{len(rows)} products in {groups} groups: {len(rows) - len(evaluation)} reference, "
          f"{len(evaluation)} evaluation (fraction {args.evaluation_fraction}, seed {args.seed})")
    print(f"-> {args.output}")
    return 0


def cmd_build(args) -> int:
    annotations_bytes = args.annotations.read_bytes()
    annotations = json.loads(annotations_bytes.decode("utf-8"))

    try:
        split = None
        if args.split is not None:
            rows, digest = read_split(args.split)
            split = (rows, digest, args.split.name)
        index, warnings = build(annotations, read_exclusions(args.exclude, args.exclude_file),
                                args.verified_only, split)
    except BuildError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    index["source"]["annotations_sha256"] = sha256_bytes(annotations_bytes)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(index, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    for w in warnings:
        print(f"warning: {w}", file=sys.stderr)

    counts: dict[str, int] = {}
    for c in index["cases"]:
        counts[c["location"]] = counts.get(c["location"], 0) + 1

    print(f"{len(index['cases'])} cases, {len(index['excluded_products'])} excluded; {index['label_status']}")
    print("locations: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items(), key=lambda kv: -kv[1])))
    print(f"-> {args.output}")
    if not index["excluded_products"]:
        print("NOTE: no products excluded. This index is not usable for evaluating on these packages.")
    return 0


def cmd_check(args) -> int:
    """Exit 1 when any evaluation product is a reference case in the index."""
    index = json.loads(args.index.read_text(encoding="utf-8"))

    try:
        evaluation = read_exclusions(args.products, args.products_file)
        if args.split is not None:
            rows, _ = read_split(args.split)
            evaluation |= {r["product_id"] for r in rows if r["split"] == EVALUATION}
    except BuildError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2

    if not evaluation:
        print("error: no evaluation products given", file=sys.stderr)
        return 2

    shared = overlap(index, evaluation)
    if shared:
        print(f"CONTAMINATED: {len(shared)} evaluation products are reference cases in {args.index}: "
              f"{', '.join(shared)}", file=sys.stderr)
        return 1

    print(f"ok: none of {len(evaluation)} evaluation products is among {len(index.get('cases', []))} reference cases")
    return 0


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv or argv[0].startswith("-"):
        argv.insert(0, "build")

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command", required=True)

    b = sub.add_parser("build", help="write the retrieval index")
    b.add_argument("--annotations", type=Path, default=DEFAULT_ANNOTATIONS)
    b.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    b.add_argument("--split", type=Path, help="product-group split CSV; only its reference side is indexed")
    b.add_argument("--exclude", help="comma-separated product ids to keep out as well")
    b.add_argument("--exclude-file", type=Path, help="product ids to keep out, one per line or a product_id CSV")
    b.add_argument("--verified-only", action="store_true",
                   help=f"index only products whose answer and date location are both {HUMAN_VERIFIED}")
    b.set_defaults(func=cmd_build)

    s = sub.add_parser("split", help="propose a product-group split")
    s.add_argument("--annotations", type=Path, default=DEFAULT_ANNOTATIONS)
    s.add_argument("--output", type=Path, default=DEFAULT_SPLIT)
    s.add_argument("--evaluation-fraction", type=float, default=0.3)
    s.add_argument("--seed", type=int, default=2027)
    s.add_argument("--force", action="store_true", help="replace an existing split file")
    s.set_defaults(func=cmd_split)

    c = sub.add_parser("check", help="fail if an index contains evaluation products")
    c.add_argument("--index", type=Path, default=DEFAULT_OUTPUT)
    c.add_argument("--split", type=Path, help="split CSV; its evaluation side is checked")
    c.add_argument("--products", help="comma-separated evaluation product ids")
    c.add_argument("--products-file", type=Path, help="evaluation product ids, one per line or a product_id CSV")
    c.set_defaults(func=cmd_check)

    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())

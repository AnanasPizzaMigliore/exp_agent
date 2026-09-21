"""Offline estimate: would matching packages by appearance place dates better than geometry?

    pip install -r tools/requirements-visual.txt
    python tools/evaluate_appearance_retrieval.py --split tools/retrieval_split.csv

This is the record of an option that was tried and not adopted. The phone
retrieves by geometry and brand words only (see RETRIEVAL.md); nothing here ships
in the APK.

It embeds every photograph of the reference cases and of the evaluation products
with MediaPipe's Image Embedder (mobilenet_v3_large, pinned by URL and hash), then
asks, per photograph and per product, whether the most common date location among
the retrieved cases is the annotated one:

  geometry     same-geometry cases, backing off to the class - what the phone does
  appearance   the five most similar-looking cases within the geometry class; a
               product scores by its most similar view, and "per product" uses the
               mean similarity over all of the evaluation product's photographs

It is an estimate, not the planned comparison: the labels are drafts, it uses the
annotated geometry rather than what perception would report, and it votes rather
than planning. The reference cases are built from the annotations exactly as the
phone's index is, from the reference side of the split, so no evaluation product
can be one; a split that divides a product group is refused.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import sys
import urllib.request
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_retrieval_index as locations  # noqa: E402

TOOLS = Path(__file__).resolve().parent
DEFAULT_DATASET = Path(r"D:\agent images\dataset")

# Pinned by version and hash, so a rerun cannot quietly change model.
MODEL_URL = "https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_large/float32/1/mobilenet_v3_large.tflite"
MODEL_SHA256 = "11af3c560dfeed7737cb4c03c23bf52a8403020784192d4dea0b74862a12828d"
DEFAULT_MODEL = TOOLS / "models" / "mobilenet_v3_large.tflite"

# Long edge photographs are reduced to before MediaPipe resizes them to the model's input.
DEFAULT_MAX_EDGE = 512

APPEARANCE_NEIGHBOURS = 5

# Must equal DateLocationMemory.MIN_CASES on the phone.
MIN_CASES = 5


class EvaluationError(Exception):
    """The estimate cannot be made without guessing."""


# ---------------------------------------------------------------- retrieval

def product_similarities(query, references: dict[str, list]) -> dict[str, float]:
    """Cosine similarity to each product's most similar view. References must be unit vectors."""
    import numpy as np

    q = np.asarray(query, dtype=np.float32)
    q = q / (np.linalg.norm(q) or 1.0)
    return {pid: float(max(float(q @ v) for v in views)) for pid, views in references.items() if views}


def appearance_matches(cases: list[dict], geometry_classes: dict[str, str], geometry: str,
                       scores: dict[str, float]) -> list[dict]:
    """The most similar-looking cases within the geometry class, or across all classes when it is unknown."""
    cls = geometry_classes.get(geometry)
    candidates = [c for c in cases if (cls is None or c["geometry_class"] == cls) and c["product_id"] in scores]
    candidates.sort(key=lambda c: (-scores[c["product_id"]], c["product_id"]))
    return candidates[:APPEARANCE_NEIGHBOURS]


def geometry_cases(cases: list[dict], geometry_classes: dict[str, str], geometry: str) -> list[dict]:
    """Same-geometry cases, backing off to the class. Mirrors DateLocationMemory.retrieve."""
    cls = geometry_classes.get(geometry)
    if cls is None:
        return []
    same = [c for c in cases if c["geometry"] == geometry]
    return same if len(same) >= MIN_CASES else [c for c in cases if c["geometry_class"] == cls]


def majority_location(cases: list[dict]) -> str | None:
    counts = Counter(c["location"] for c in cases)
    if not counts:
        return None
    best = max(counts.values())
    return sorted(loc for loc, n in counts.items() if n == best)[0]


# ---------------------------------------------------------------- inputs

def read_manifest(dataset: Path) -> dict[str, list[tuple[str, Path]]]:
    """product_id -> [(view, path)] in manifest order."""
    manifest = dataset / "manifest.csv"
    if not manifest.exists():
        raise EvaluationError(f"{manifest} not found")
    views: dict[str, list[tuple[str, Path]]] = {}
    with manifest.open(encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            views.setdefault(row["product_id"], []).append((row["view"], dataset / row["filename"]))
    return views


def ensure_model(path: Path) -> None:
    """The pinned default is downloaded once and always hash-checked."""
    if not path.exists():
        if path.resolve() != DEFAULT_MODEL.resolve():
            raise EvaluationError(f"{path} not found")
        path.parent.mkdir(parents=True, exist_ok=True)
        print(f"downloading {MODEL_URL}", file=sys.stderr)
        with urllib.request.urlopen(MODEL_URL) as response:
            path.write_bytes(response.read())

    if path.resolve() == DEFAULT_MODEL.resolve() and hashlib.sha256(path.read_bytes()).hexdigest() != MODEL_SHA256:
        raise EvaluationError(f"{path} does not match the pinned model hash; delete it to download again")


class Embedder:
    """MediaPipe's Image Embedder over photographs."""

    def __init__(self, model: Path, max_edge: int):
        from mediapipe.tasks.python import BaseOptions, vision

        self.max_edge = max_edge
        options = vision.ImageEmbedderOptions(base_options=BaseOptions(model_asset_path=str(model)),
                                              l2_normalize=True, quantize=False)
        self.task = vision.ImageEmbedder.create_from_options(options)

    def embed(self, path: Path):
        import mediapipe as mp
        import numpy as np
        from PIL import Image, ImageOps

        with Image.open(path) as image:
            # draft() decodes the JPEG at a reduced scale, which is most of the speed.
            image.draft("RGB", (self.max_edge, self.max_edge))
            upright = ImageOps.exif_transpose(image).convert("RGB")
        upright.thumbnail((self.max_edge, self.max_edge))

        result = self.task.embed(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.asarray(upright)))
        return np.asarray(result.embeddings[0].embedding, dtype=np.float32)

    def close(self):
        self.task.close()


# ---------------------------------------------------------------- evaluate

def reference_cases(annotations: dict, rows: list[dict]) -> tuple[list[dict], list[str]]:
    """Cases built exactly as the phone's index is, from the reference side; and the evaluation ids."""
    split = (rows, "", "split.csv")
    index, _ = locations.build(annotations, set(), False, split)
    by_id = {p["product_id"]: p for p in annotations.get("products") or []}
    evaluation = sorted(pid for pid in index["split"]["evaluation_products"] if by_id[pid].get("date_surface")
                        and by_id[pid].get("package_geometry"))
    return index["cases"], evaluation


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--split", type=Path, default=locations.DEFAULT_SPLIT)
    ap.add_argument("--annotations", type=Path, default=locations.DEFAULT_ANNOTATIONS)
    ap.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    ap.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    ap.add_argument("--max-edge", type=int, default=DEFAULT_MAX_EDGE)
    args = ap.parse_args(argv)

    try:
        rows, _ = locations.read_split(args.split)
        annotations = locations.load_annotations(args.annotations)
        cases, evaluation = reference_cases(annotations, rows)
        manifest = read_manifest(args.dataset)
        ensure_model(args.model)
    except (EvaluationError, locations.BuildError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    classes = locations.GEOMETRY_CLASSES
    by_id = {p["product_id"]: p for p in annotations.get("products") or []}

    embedder = Embedder(args.model, args.max_edge)
    try:
        references = {c["product_id"]: [embedder.embed(path) for _, path in manifest.get(c["product_id"], [])]
                      for c in cases}

        per_photo = Counter()
        per_product = Counter()
        photos = 0
        for n, pid in enumerate(evaluation, 1):
            product = by_id[pid]
            truth = locations.LOCATIONS[product["date_surface"]]
            geometry = locations.GEOMETRY[product["package_geometry"]]
            baseline = majority_location(geometry_cases(cases, classes, geometry))

            totals: Counter = Counter()
            frames = 0
            for _, path in manifest.get(pid, []):
                scores = product_similarities(embedder.embed(path), references)
                photos += 1
                frames += 1
                totals.update(scores)
                matched = appearance_matches(cases, classes, geometry, scores)
                per_photo["geometry"] += baseline == truth
                per_photo["appearance"] += majority_location(matched) == truth
                per_photo["appearance_includes"] += truth in {c["location"] for c in matched}

            mean = {k: v / frames for k, v in totals.items()} if frames else {}
            matched = appearance_matches(cases, classes, geometry, mean)
            per_product["geometry"] += baseline == truth
            per_product["appearance"] += majority_location(matched) == truth
            per_product["appearance_includes"] += truth in {c["location"] for c in matched}
            print(f"\r{n}/{len(evaluation)} evaluation products", end="", file=sys.stderr)
        print(file=sys.stderr)
    finally:
        embedder.close()

    def rate(counter, key, total):
        return f"{counter[key] / total:.3f}" if total else "n/a"

    print(f"{len(evaluation)} evaluation products, {photos} photographs, {len(cases)} reference cases")
    print("majority location among retrieved cases equals the annotated one:")
    print(f"  per photograph       geometry {rate(per_photo, 'geometry', photos)}   "
          f"appearance {rate(per_photo, 'appearance', photos)}")
    print(f"  per product (mean)   geometry {rate(per_product, 'geometry', len(evaluation))}   "
          f"appearance {rate(per_product, 'appearance', len(evaluation))}")
    print("annotated location among the appearance matches at all:")
    print(f"  per photograph {rate(per_photo, 'appearance_includes', photos)}   "
          f"per product {rate(per_product, 'appearance_includes', len(evaluation))}")
    print("Draft labels, annotated geometry, majority vote: an estimate, not the experiment.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

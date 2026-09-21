"""Tests for the retrieval index builder. No dataset or credentials needed.

    python -m unittest tools/test_build_retrieval_index.py -v
"""

from __future__ import annotations

import contextlib
import copy
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_retrieval_index as b  # noqa: E402

# P01 exactly as annotations.draft.json holds it, then as the review page leaves
# it after a person ticks "I manually verified this product-level answer".
EXPORTED_P01 = {
    "product_id": "P01",
    "product_name": "Kellogg cereal",
    "package_geometry": "carton",
    "date_text": "21/07/27",
    "normalized_date": "2027-07-21",
    "date_precision": "day",
    "date_type": "BEST_BEFORE",
    "date_surface": "end_flap",
    "evidence_view": "ctr",
    "evidence_filename": "P01_ctr.jpg",
    "type_evidence": "",
    "annotation_source": "assistant_visual_draft",
    "review_status": "UNVERIFIED",
    "reviewer": "",
    "reviewed_at": "",
    "notes": "BB marks target; adjacent P:21/07/26 is production. Visual draft; two-digit century assumed.",
    "legacy_product_id": "P01",
}


def reviewed(record: dict, location: bool = False) -> dict:
    out = copy.deepcopy(record)
    out.update(review_status="HUMAN_VERIFIED", reviewer="PH", reviewed_at="2026-09-15T10:00:00Z",
               type_evidence="CONSUMIR PREFERENTEMENTE")
    if location:
        out[b.LOCATION_REVIEW_FIELD] = "HUMAN_VERIFIED"
    return out


def product(pid, geometry="carton", surface="end_flap", name="Kellogg cereal", legacy=None):
    out = copy.deepcopy(EXPORTED_P01)
    out.update(product_id=pid, package_geometry=geometry, date_surface=surface, product_name=name,
               legacy_product_id=legacy or pid)
    return out


def annotations(*products):
    return {"metadata": {"schema_version": "local-draft-v1"}, "products": list(products)}


class BuildTest(unittest.TestCase):

    def test_no_date_reaches_the_index(self):
        index, _ = b.build(annotations(EXPORTED_P01), set(), False)
        text = json.dumps(index)
        self.assertNotIn("21/07/27", text)
        self.assertNotIn("2027-07-21", text)
        self.assertNotIn("BEST_BEFORE", text)
        self.assertNotIn("21/07/26", text)

    def test_vocabulary_is_mapped_to_the_phone_and_detail_is_kept(self):
        index, _ = b.build(annotations(product("P01", "bottle", "neck")), set(), False)
        case = index["cases"][0]
        self.assertEqual(("bottle", "rigid_round", "TOP", "neck"),
                         (case["geometry"], case["geometry_class"], case["location"], case["annotated_surface"]))

    def test_unmapped_values_fail_closed(self):
        with self.assertRaises(b.BuildError):
            b.build(annotations(product("P01", surface="inside_lid")), set(), False)
        with self.assertRaises(b.BuildError):
            b.build(annotations(product("P01", geometry="barrel")), set(), False)

    def test_excluded_products_are_left_out_and_recorded(self):
        index, _ = b.build(annotations(product("P01"), product("P02")), {"P02"}, False)
        self.assertEqual(["P01"], [c["product_id"] for c in index["cases"]])
        self.assertEqual(["P02"], index["excluded_products"])

    def test_excluding_an_unknown_product_is_an_error(self):
        with self.assertRaises(b.BuildError):
            b.build(annotations(product("P01")), {"P99"}, False)

    def test_name_tokens_keep_brands_and_drop_descriptions(self):
        self.assertEqual(["kellogg"], b.name_tokens("Kellogg cereal"))
        self.assertEqual(["colacao"], b.name_tokens("ColaCáo"))
        self.assertEqual([], b.name_tokens("Snack pouch"))

    def test_every_class_is_known_for_every_mapped_geometry(self):
        self.assertTrue(set(b.GEOMETRY.values()) <= b.GEOMETRY_CLASSES.keys())


class VerificationTest(unittest.TestCase):

    def test_the_review_pages_status_is_counted_as_verified(self):
        index, _ = b.build(annotations(reviewed(EXPORTED_P01)), set(), False)
        self.assertEqual("HUMAN_VERIFIED", index["cases"][0]["review_status"])
        self.assertTrue(index["label_status"].startswith("1 of 1 product answers human-verified"))

    def test_a_verified_answer_does_not_verify_its_location(self):
        index, _ = b.build(annotations(reviewed(EXPORTED_P01)), set(), False)
        self.assertIn("0 of 1 date locations human-verified", index["label_status"])
        with self.assertRaises(b.BuildError):
            b.build(annotations(reviewed(EXPORTED_P01)), set(), True)

    def test_verified_only_keeps_products_whose_answer_and_location_are_verified(self):
        other = product("P02")
        index, _ = b.build(annotations(reviewed(EXPORTED_P01, location=True), other), set(), True)
        self.assertEqual(["P01"], [c["product_id"] for c in index["cases"]])
        self.assertIn("1 of 1 date locations human-verified", index["label_status"])

    def test_the_old_verified_spelling_is_not_accepted(self):
        record = copy.deepcopy(EXPORTED_P01)
        record.update(review_status="VERIFIED", **{b.LOCATION_REVIEW_FIELD: "VERIFIED"})
        with self.assertRaises(b.BuildError):
            b.build(annotations(record), set(), True)


class SplitTest(unittest.TestCase):

    def collection(self):
        return [
            product("P01", "carton", name="Kellogg cereal"),
            product("P02", "carton", name="Kellogg cereal bars"),
            product("P03", "jar", "lid", name="Glass jar"),
            product("P04", "jar", "lid", name="Glass jar"),
            product("P05", "pouch", "back_label", name="Snack pouch"),
            product("P06", "pouch", "back_label", name="Rice"),
            product("P07", "can", "base", name="Mahou 0.0"),
            product("P08", "carton", "narrow_side", name="Blue carton"),
        ]

    def test_twins_and_brands_are_grouped(self):
        groups = b.product_groups(self.collection())
        self.assertEqual(groups["P01"], groups["P02"])
        self.assertEqual(groups["P03"], groups["P04"])
        self.assertNotEqual(groups["P05"], groups["P06"])

    def test_a_proposed_split_never_divides_a_group_and_is_reproducible(self):
        products = self.collection()
        rows = b.propose_split(products, 0.5, seed=7)
        b.validate_split(rows, products)
        self.assertEqual(rows, b.propose_split(products, 0.5, seed=7))
        self.assertTrue({r["split"] for r in rows} == {b.REFERENCE, b.EVALUATION})

    def test_a_divided_group_is_refused(self):
        products = self.collection()
        rows = [{"product_id": p["product_id"], "legacy_product_id": p["legacy_product_id"],
                 "split": b.EVALUATION if p["product_id"] == "P02" else b.REFERENCE} for p in products]
        with self.assertRaises(b.BuildError):
            b.validate_split(rows, products)

    def test_a_split_from_before_renumbering_is_refused(self):
        products = self.collection()
        rows = b.propose_split(products, 0.5, seed=7)
        products[6]["legacy_product_id"] = "P90"
        with self.assertRaises(b.BuildError):
            b.validate_split(rows, products)

    def test_a_split_missing_a_product_is_refused(self):
        products = self.collection()
        rows = b.propose_split(products, 0.5, seed=7)[1:]
        with self.assertRaises(b.BuildError):
            b.validate_split(rows, products)

    def test_build_indexes_only_the_reference_side_and_records_the_rest(self):
        products = self.collection()
        rows = b.propose_split(products, 0.5, seed=7)
        index, _ = b.build(annotations(*products), set(), False, (rows, "abc", "split.csv"))
        evaluation = {r["product_id"] for r in rows if r["split"] == b.EVALUATION}
        self.assertFalse(evaluation & {c["product_id"] for c in index["cases"]})
        self.assertEqual(sorted(evaluation), index["split"]["evaluation_products"])
        self.assertEqual([], b.overlap(index, evaluation))


class CommandTest(unittest.TestCase):

    def run_main(self, *argv) -> int:
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return b.main(list(argv))

    def test_check_fails_on_a_contaminated_index_and_passes_a_clean_one(self):
        products = SplitTest().collection()
        with tempfile.TemporaryDirectory() as d:
            source = Path(d, "annotations.json")
            source.write_text(json.dumps(annotations(*products)), encoding="utf-8")
            split, full, clean = Path(d, "split.csv"), Path(d, "full.json"), Path(d, "clean.json")

            self.assertEqual(0, self.run_main("split", "--annotations", str(source), "--output", str(split),
                                              "--evaluation-fraction", "0.5"))
            self.assertEqual(1, self.run_main("split", "--annotations", str(source), "--output", str(split)))
            self.assertEqual(0, self.run_main("--annotations", str(source), "--output", str(full)))
            self.assertEqual(0, self.run_main("build", "--annotations", str(source), "--output", str(clean),
                                              "--split", str(split)))

            self.assertEqual(1, self.run_main("check", "--index", str(full), "--split", str(split)))
            self.assertEqual(0, self.run_main("check", "--index", str(clean), "--split", str(split)))
            self.assertEqual(2, self.run_main("check", "--index", str(clean)))

    def test_exclusion_file_accepts_lines_or_csv(self):
        with tempfile.TemporaryDirectory() as d:
            lines = Path(d, "ids.txt")
            lines.write_text("# held out\nP01\nP02\n", encoding="utf-8")
            table = Path(d, "ids.csv")
            table.write_text("product_id,note\nP03,x\n", encoding="utf-8")
            self.assertEqual({"P01", "P02", "P04"}, b.read_exclusions("P04", lines))
            self.assertEqual({"P03"}, b.read_exclusions(None, table))


if __name__ == "__main__":
    unittest.main()

"""Tests for the offline appearance estimate. No dataset, model or MediaPipe needed (NumPy is).

    python -m unittest tools/test_evaluate_appearance_retrieval.py -v
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_retrieval_index as locations  # noqa: E402
import evaluate_appearance_retrieval as e  # noqa: E402
from test_build_retrieval_index import SplitTest, annotations  # noqa: E402

CLASSES = {"carton": "box", "jar": "rigid_round", "bottle": "rigid_round", "bag": "flexible"}


def case(pid, geometry, location):
    return {"product_id": pid, "geometry": geometry, "geometry_class": CLASSES[geometry], "location": location}


def unit(*values):
    x = np.zeros(8, dtype=np.float32)
    x[:len(values)] = values
    return x / np.linalg.norm(x)


class RetrievalTest(unittest.TestCase):

    cases = [case("P01", "carton", "END"), case("P02", "carton", "SIDE"), case("P03", "jar", "TOP"),
             case("P04", "bottle", "TOP"), case("P05", "bag", "BACK")]

    def test_a_product_scores_by_its_most_similar_view(self):
        references = {"P01": [unit(1, 0), unit(0, 1)], "P02": [unit(1, 1)]}
        scores = e.product_similarities(unit(0, 3), references)
        self.assertAlmostEqual(1.0, scores["P01"], places=5)
        self.assertAlmostEqual(2 ** -0.5, scores["P02"], places=5)

    def test_appearance_stays_within_the_geometry_class(self):
        scores = {"P01": 0.2, "P02": 0.3, "P03": 0.9, "P04": 0.8, "P05": 0.95}
        matched = e.appearance_matches(self.cases, CLASSES, "carton", scores)
        self.assertEqual(["P02", "P01"], [c["product_id"] for c in matched])

    def test_an_unknown_geometry_matches_across_classes_and_is_capped(self):
        many = self.cases + [case("P06", "carton", "BASE")]
        scores = {f"P0{i}": i / 10 for i in range(1, 7)}
        matched = e.appearance_matches(many, CLASSES, "unknown", scores)
        self.assertEqual(e.APPEARANCE_NEIGHBOURS, len(matched))
        self.assertEqual("P06", matched[0]["product_id"])

    def test_ties_break_by_product_id(self):
        matched = e.appearance_matches(self.cases, CLASSES, "carton", {"P02": 0.5, "P01": 0.5})
        self.assertEqual(["P01", "P02"], [c["product_id"] for c in matched])

    def test_the_geometry_baseline_backs_off_like_the_phone(self):
        self.assertEqual(["P03", "P04"], [c["product_id"] for c in e.geometry_cases(self.cases, CLASSES, "jar")])
        self.assertEqual([], e.geometry_cases(self.cases, CLASSES, "unknown"))

    def test_majority_ties_are_deterministic(self):
        self.assertEqual("END", e.majority_location([case("P01", "carton", "SIDE"), case("P02", "carton", "END")]))
        self.assertIsNone(e.majority_location([]))


class SplitTest_(unittest.TestCase):

    def test_reference_cases_never_include_an_evaluation_product(self):
        products = SplitTest().collection()
        rows = locations.propose_split(products, 0.5, seed=7)
        cases, evaluation = e.reference_cases(annotations(*products), rows)
        self.assertTrue(evaluation)
        self.assertFalse({c["product_id"] for c in cases} & set(evaluation))

    def test_a_divided_group_is_refused(self):
        products = SplitTest().collection()
        rows = [{"product_id": p["product_id"], "legacy_product_id": p["legacy_product_id"],
                 "split": locations.EVALUATION if p["product_id"] == "P02" else locations.REFERENCE} for p in products]
        with self.assertRaises(locations.BuildError):
            e.reference_cases(annotations(*products), rows)


if __name__ == "__main__":
    unittest.main()

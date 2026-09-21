"""Tests for the perception accuracy measurement. No calls, dataset or credentials.

    python -m unittest tools/test_perception_accuracy.py -v
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_retrieval_index as locations  # noqa: E402
import perception_accuracy as pa  # noqa: E402


def product(pid, geometry="carton", surface="narrow_side", date="2027-07-21"):
    return {"product_id": pid, "package_geometry": geometry, "date_surface": surface, "normalized_date": date}


def photo(p, view):
    return {"product_id": p["product_id"], "view": view, "path": Path("x.jpg"), "product": p}


def reply(geometry, surface, iso=None, visible=True):
    return {"perception": {"geometry": geometry, "surface_in_view": surface, "iso_date": iso,
                           "date_region_visible": visible, "date_legible": iso is not None}, "elapsed_ms": 10}


class PromptTest(unittest.TestCase):

    def test_the_prompt_is_read_verbatim_from_the_app(self):
        prompt = pa.perception_prompt(pa.PROMPTS_KT.read_text(encoding="utf-8"))
        self.assertTrue(prompt.startswith("You are the eyes of an assistant helping a blind shopper."))
        self.assertTrue(prompt.rstrip().endswith('"none"]}'))
        self.assertIn('"geometry": "carton|jar|can|bottle|bag|cup|tray|multipack|unknown"', prompt)

    def test_the_prompts_vocabulary_is_the_one_scored(self):
        prompt = pa.perception_prompt(pa.PROMPTS_KT.read_text(encoding="utf-8"))
        self.assertIn("|".join(pa.GEOMETRIES), prompt)
        self.assertIn("|".join(pa.SURFACES), prompt)

    def test_every_annotated_surface_has_a_decision(self):
        self.assertEqual(set(locations.LOCATIONS), set(pa.CTR_SURFACE))

    def test_code_fences_are_stripped_like_the_phone(self):
        self.assertEqual('{"a": 1}', pa.strip_code_fence('```json\n{"a": 1}\n```'))


class TruthTest(unittest.TestCase):

    def test_top_and_bottom_views_have_fixed_answers(self):
        p = product("P01")
        self.assertEqual({"top"}, pa.expected_surfaces(p, "top"))
        self.assertEqual({"base"}, pa.expected_surfaces(p, "bot"))

    def test_a_box_side_is_not_a_label(self):
        self.assertEqual({"side"}, pa.expected_surfaces(product("P01", "carton", "narrow_side"), "ctr"))
        self.assertEqual({"label"}, pa.expected_surfaces(product("P01", "carton", "back_label"), "ctr"))

    def test_on_a_round_package_side_and_label_are_both_right(self):
        self.assertEqual({"side", "label"}, pa.expected_surfaces(product("P01", "jar", "back_label"), "ctr"))
        self.assertEqual({"top"}, pa.expected_surfaces(product("P01", "jar", "lid"), "ctr"))

    def test_angle_dependent_surfaces_are_not_scored(self):
        self.assertIsNone(pa.expected_surfaces(product("P01", "carton", "end_flap"), "ctr"))
        self.assertIsNone(pa.expected_surfaces(product("P01", "bottle", "neck"), "ctr"))

    def test_settled_geometry_matches_the_phone(self):
        self.assertEqual("cup", pa.settled_geometry(["cup", "jar", "cup"]))
        self.assertEqual("jar", pa.settled_geometry(["jar", "cup"]))
        self.assertEqual("cup", pa.settled_geometry(["unknown", "cup"]))
        self.assertEqual("unknown", pa.settled_geometry(["unknown", ""]))


class ScoreTest(unittest.TestCase):

    def test_scores_geometry_surface_and_dates(self):
        box = product("P01", "carton", "narrow_side")
        jar = product("P02", "jar", "lid", date="2027-01")
        photos = [photo(box, v) for v in pa.DEFAULT_VIEWS] + [photo(jar, v) for v in pa.DEFAULT_VIEWS]
        replies = {
            ("P01", "ctr"): reply("carton", "side", "2027-07-21"),
            ("P01", "top"): reply("multipack", "top"),
            ("P01", "bot"): reply("carton", "label"),
            ("P02", "ctr"): reply("can", "top", "2027-02"),
            ("P02", "top"): reply("jar", "top"),
            # P02 bot never answered
        }
        result = pa.score(photos, replies, pa.DEFAULT_VIEWS)

        self.assertEqual(5, result["answered"])
        self.assertEqual([("P02", "bot", "not run")], result["failures"])
        self.assertEqual({"correct": 1, "same_class": 2, "total": 2}, result["settled_geometry"])
        self.assertEqual(1, result["geometry_by_view"]["top_correct"])
        self.assertEqual(2, result["geometry_by_view"]["top_same_class"])
        self.assertEqual({"total": 2, "correct": 2, "unclear": 0}, result["surface_by_view"]["ctr"])
        self.assertEqual({"total": 1, "correct": 0, "unclear": 0}, result["surface_by_view"]["bot"])
        self.assertEqual(1, result["ctr"]["iso_correct"])
        self.assertEqual(1, result["ctr"]["iso_wrong"])
        self.assertIn("settled geometry", pa.format_report(result, pa.DEFAULT_VIEWS))

    def test_a_failed_reply_is_a_failure_not_a_reading(self):
        p = product("P01")
        result = pa.score([photo(p, "ctr")], {("P01", "ctr"): {"error": "HTTP 429"}}, ("ctr",))
        self.assertEqual(0, result["answered"])
        self.assertEqual("HTTP 429", result["failures"][0][2])


if __name__ == "__main__":
    unittest.main()

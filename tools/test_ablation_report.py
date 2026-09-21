#!/usr/bin/env python3
"""That the scorer reads a run the way the app writes one.

    python -m unittest tools/test_ablation_report.py -v

Each test builds the smallest log that can distinguish a right answer from the
wrong one the scorer used to give, so a regression names itself.
"""

import unittest

import ablation_report as report


def row(kind, session="s1", **fields):
    base = {"kind": kind, "session_id": session, "ts": fields.pop("ts", 0.0)}
    base.update(fields)
    return base


def session_of(rows, session="s1"):
    found = report.sessions_of(rows)
    return next(s for s in found if s.id == session)


class BlockLabels(unittest.TestCase):

    def test_explicit_block_label_is_used(self):
        block, source = report.block_of({"block_label": "A_idle", "run_label": "P25"})
        self.assertEqual("A_idle", block)
        self.assertEqual("block_label", source)

    def test_legacy_combined_label_is_still_scoreable(self):
        """Sessions logged before the field existed wrote "A_idle_can"."""
        block, source = report.block_of({"run_label": "A_idle_can"})
        self.assertEqual("A_idle", block)
        self.assertEqual("run_label prefix", source)

    def test_a_product_label_alone_is_not_a_block(self):
        self.assertEqual((None, None), report.block_of({"run_label": "P25"}))

    def test_an_empty_label_is_not_a_block(self):
        """The bug that produced two unlabelled sessions on 18 September."""
        self.assertEqual((None, None), report.block_of({"run_label": ""}))

    def test_an_unknown_block_is_left_unscored(self):
        self.assertEqual((None, None), report.block_of({"run_label": "D_improvised"}))


class WhichControllerDrove(unittest.TestCase):

    def test_session_start_gate_outranks_the_logged_arm(self):
        """ablation_arms.live_arm was a hardcoded literal in every older log."""
        s = session_of([
            row("session_start", gate="E_no_settling_hold"),
            row("ablation_arms", arms="E_full,E_no_onset", live_arm="E_full"),
            ])
        self.assertEqual("E_no_settling_hold", s.live_arm)

    def test_the_logged_arm_is_used_when_the_run_predates_the_gate_field(self):
        s = session_of([
            row("session_start"),
            row("ablation_arms", arms="E_full", live_arm="E_full"),
            ])
        self.assertEqual("E_full", s.live_arm)


class LiveCaptures(unittest.TestCase):

    def test_only_accepted_reservations_count_as_captures(self):
        s = session_of([
            row("session_start", gate="E_full"),
            row("capture_reserved", ts=1.0, capture_token=1, instruction_id=1, automatic=True),
            row("capture_accepted", ts=1.8, capture_token=1, instruction_id=1, automatic=True),
            # Reserved, then refused at the callback: no photograph was taken.
            row("capture_reserved", ts=3.0, capture_token=2, instruction_id=2, automatic=True),
            row("capture_rejected", ts=3.8, capture_token=2, instruction_id=2, automatic=True),
            ])
        self.assertEqual(1, len(s.live_captures))
        self.assertEqual(1, s.live_captures[0]["capture_token"])

    def test_the_capture_instant_is_the_reservation_not_the_acceptance(self):
        """Acceptance is ~800 ms later, when CameraX returns; the controller
        decided at reservation, and that is what an offset is measured from."""
        s = session_of([
            row("session_start", gate="E_full"),
            row("capture_reserved", ts=1.0, capture_token=1, instruction_id=1, automatic=True),
            row("capture_accepted", ts=1.8, capture_token=1, instruction_id=1, automatic=True),
            ])
        self.assertEqual(1.0, s.automatic[0]["ts"])

    def test_manual_captures_are_separated_from_automatic_ones(self):
        s = session_of([
            row("session_start", gate="E_full"),
            row("capture_reserved", ts=1.0, capture_token=1, instruction_id=1, automatic=False),
            row("capture_accepted", ts=1.8, capture_token=1, instruction_id=1, automatic=False),
            ])
        self.assertEqual(1, len(s.live_captures))
        self.assertEqual(0, len(s.automatic))


class SpuriousCaptures(unittest.TestCase):

    def _session(self, reason, relation):
        return session_of([
            row("session_start", gate="E_full", block_label="A_idle"),
            row("capture_reserved", ts=1.0, capture_token=1, instruction_id=1,
                automatic=True, reason=reason),
            row("capture_accepted", ts=1.8, capture_token=1, instruction_id=1,
                automatic=True, reason=reason),
            row("stability_at_capture", ts=1.9, frame_id=1, instruction_relation=relation),
            ])

    def test_a_capture_on_an_unmoved_view_is_spurious(self):
        scored = report.score_session(self._session("post_action_change", "same"))
        self.assertEqual(1, scored["spurious"])

    def test_a_capture_after_real_movement_is_not(self):
        scored = report.score_session(self._session("post_action_change", "different"))
        self.assertEqual(0, scored["spurious"])

    def test_the_opening_look_is_never_spurious(self):
        """It is not supposed to wait for movement, so an unchanged view proves
        nothing about it. Counting it made every session look broken."""
        scored = report.score_session(self._session("initial_observation", "same"))
        self.assertEqual(0, scored["spurious"])

    def test_a_hold_still_retry_is_never_spurious(self):
        scored = report.score_session(self._session("retry_observation", "same"))
        self.assertEqual(0, scored["spurious"])


class RepeatedCaptures(unittest.TestCase):

    def test_two_captures_for_one_instruction_count_as_one_repeat(self):
        s = session_of([
            row("session_start", gate="E_full", block_label="C_normal"),
            row("capture_reserved", ts=1.0, capture_token=1, instruction_id=1,
                automatic=True, reason="post_action_change"),
            row("capture_accepted", ts=1.8, capture_token=1, instruction_id=1, automatic=True),
            row("capture_reserved", ts=4.0, capture_token=2, instruction_id=1,
                automatic=True, reason="post_action_change"),
            row("capture_accepted", ts=4.8, capture_token=2, instruction_id=1, automatic=True),
            ])
        self.assertEqual(1, report.score_session(s)["repeats"])


class IdleShadowCaptures(unittest.TestCase):
    """The verdict that fired once in 603 rows before it was repaired."""

    def _session(self, reason_on_row, live_reason):
        shadow = row("ablation_capture", ts=2.0, arm="E_no_onset", at_ns=2,
                     instruction_id=1, after_live_capture=False,
                     instruction_relation="same")
        if reason_on_row is not None:
            shadow["capture_reason"] = reason_on_row
        return session_of([
            row("session_start", gate="E_full", block_label="A_idle"),
            row("ablation_arms", arms="E_no_onset", live_arm="E_full"),
            row("capture_reserved", ts=1.0, capture_token=1, instruction_id=1,
                automatic=True, reason=live_reason),
            row("capture_accepted", ts=1.8, capture_token=1, instruction_id=1, automatic=True),
            shadow,
            ])

    def _idle(self, session):
        """Re-derive the arm table's idle count without capturing stdout."""
        count = 0
        for r in session.shadow:
            if r.get("instruction_relation") != "same":
                continue
            reason = r.get("capture_reason") or session.reason_for(r.get("instruction_id"))
            if reason == report.NEEDS_MOVEMENT:
                count += 1
        return count

    def test_an_unmoved_capture_under_a_movement_reason_is_idle(self):
        self.assertEqual(1, self._idle(self._session("post_action_change", "post_action_change")))

    def test_an_unmoved_opening_look_is_not_idle(self):
        self.assertEqual(0, self._idle(self._session("initial_observation", "initial_observation")))

    def test_a_legacy_row_recovers_its_reason_from_the_instruction(self):
        """Rows written before capture_reason existed still have to be scored."""
        self.assertEqual(1, self._idle(self._session(None, "post_action_change")))

    def test_a_legacy_opening_look_recovers_its_reason_too(self):
        self.assertEqual(0, self._idle(self._session(None, "initial_observation")))


class Answered(unittest.TestCase):

    def test_a_session_that_ended_with_a_date_is_answered(self):
        s = session_of([
            row("session_start", gate="E_full"),
            row("session_finish", ts=9.0, date="26/11/26"),
            ])
        self.assertTrue(s.answered)

    def test_a_session_that_abstained_is_not(self):
        s = session_of([
            row("session_start", gate="E_full"),
            row("session_finish", ts=9.0, date=None),
            ])
        self.assertFalse(s.answered)

    def test_a_session_that_was_stopped_is_not(self):
        s = session_of([
            row("session_start", gate="E_full"),
            row("session_stop", ts=9.0),
            ])
        self.assertFalse(s.answered)


if __name__ == "__main__":
    unittest.main()


class DatePrecision(unittest.TestCase):
    """A session is judged only on what the annotation actually asserts."""

    def test_a_full_date_must_match_completely(self):
        self.assertTrue(report.dates_agree("2027-07-21", "2027-07-21"))
        self.assertFalse(report.dates_agree("2027-07-21", "2027-07-22"))

    def test_the_wrong_year_is_wrong(self):
        """The failure mode perception actually has: the year read two early."""
        self.assertFalse(report.dates_agree("2028-05-12", "2026-05-12"))

    def test_a_month_only_annotation_ignores_the_day(self):
        """A package printed "04-2027" has no day to get right."""
        self.assertTrue(report.dates_agree("2027-04", "2027-04-15"))
        self.assertFalse(report.dates_agree("2027-04", "2027-05-15"))

    def test_a_day_month_annotation_ignores_the_year(self):
        self.assertTrue(report.dates_agree("--09-12", "2027-09-12"))
        self.assertFalse(report.dates_agree("--09-12", "2027-09-13"))

    def test_a_missing_answer_is_unscored_not_wrong(self):
        self.assertIsNone(report.dates_agree("2027-07-21", None))
        self.assertIsNone(report.dates_agree(None, "2027-07-21"))


class RetrievalConditions(unittest.TestCase):

    def _condition(self, retrieval):
        return session_of([row("session_start", retrieval=retrieval)]).condition

    def test_the_three_conditions_are_named(self):
        self.assertEqual("No retrieval",
                         self._condition({"enabled": False, "name_matching": True}))
        self.assertEqual("Geometry counts",
                         self._condition({"enabled": True, "name_matching": False}))
        self.assertEqual("Geometry + names",
                         self._condition({"enabled": True, "name_matching": True}))

    def test_a_log_without_the_field_is_not_the_off_condition(self):
        """Absent is not "No retrieval"; pooling them would invent a condition."""
        self.assertIsNone(self._condition(None))


class Leakage(unittest.TestCase):
    """The check RETRIEVAL.md says nothing on the phone can make."""

    def _session(self, product, excluded, active=True):
        return session_of([row("session_start", run_label=product, retrieval={
            "enabled": True, "active": active, "name_matching": False,
            "excluded_products": excluded,
            })])

    def test_a_product_inside_the_reference_index_is_flagged(self):
        s = self._session("P02", ["P01", "P25"])
        self.assertEqual([s], report.leaked_sessions([s]))

    def test_a_held_out_product_is_not(self):
        s = self._session("P25", ["P01", "P25"])
        self.assertEqual([], report.leaked_sessions([s]))

    def test_nothing_is_flagged_when_retrieval_did_not_run(self):
        """With no retrieval there is no index to leak from."""
        s = self._session("P02", ["P01", "P25"], active=False)
        self.assertEqual([], report.leaked_sessions([s]))


class Answers(unittest.TestCase):

    def test_the_answering_turn_carries_the_iso_date(self):
        s = session_of([
            row("session_start", run_label="P25"),
            row("observation", ts=1.0, frame_id=1, finished=False,
                policy={"action": "TIP", "iso_date": None}),
            row("observation", ts=2.0, frame_id=2, finished=True,
                policy={"action": "ANSWER", "iso_date": "2026-12-31"}),
            ])
        self.assertEqual("2026-12-31", s.answer["policy"]["iso_date"])

    def test_a_session_with_no_answering_turn_has_none(self):
        s = session_of([
            row("session_start", run_label="P25"),
            row("observation", ts=1.0, frame_id=1, finished=False,
                policy={"action": "TIP"}),
            ])
        self.assertIsNone(s.answer)

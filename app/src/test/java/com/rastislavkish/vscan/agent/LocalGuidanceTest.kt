/*
* Copyright (C) 2026 VScan contributors
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, version 3.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package com.rastislavkish.vscan.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
* What LocalGuidance is allowed to decide without the planner.
*
* The cases that matter here are the refusals. Answering a frame held too far
* away with "move closer" saves six seconds; answering the wrong frame locally
* would take a decision away from the only component that can weigh a date
* against what the session already knows, and a blind user cannot catch that.
*
* Distance is mechanical enough to serve here, and so is a tremor - but only
* once the capture gate has measured one, which is the distinction the blur
* cases below are about. Glare and a covered code have no approved command at
* all, so they go to the planner.
*/
class LocalGuidanceTest {

    private fun perception(
        legible: Boolean=false,
        problems: List<String> =listOf("too_far"),
        ) = Perception(
        geometry="jar",
        surface_in_view="side",
        date_region_visible=true,
        date_legible=legible,
        problems=problems,
        )

    @Test
    fun aFrameHeldTooFarAwayIsHandledLocally() {
        val policy=LocalGuidance.policyFor(perception(), null, 0)

        // The command is the action; choosing it is choosing to carry on.
        assertEquals("MOVE_CLOSER", policy?.action)
        assertTrue(policy!!.utterance.isNotBlank())
        }

    @Test
    fun aLegibleDateAlwaysGoesToThePlanner() {
        // The one case that must never be decided here: whether a date the model
        // claims to have read may be spoken to the user.
        assertNull(LocalGuidance.policyFor(perception(legible=true), null, 0))
        }

    @Test
    fun aCleanFrameWithNothingOnItGoesToThePlanner() {
        // No photography problem to fix means the date is on another face, which
        // is a question about the package, not the camera.
        assertNull(LocalGuidance.policyFor(perception(problems=listOf("none")), null, 0))
        assertNull(LocalGuidance.policyFor(perception(problems=listOf()), null, 0))
        }

    @Test
    fun thePlannerGetsEveryThirdTurn() {
        assertTrue(LocalGuidance.policyFor(perception(), null, 0)!=null)
        assertTrue(LocalGuidance.policyFor(perception(), null, 1)!=null)

        // Without this the session has no way to abstain: ABSTAIN is the
        // planner's decision alone.
        assertNull(LocalGuidance.policyFor(perception(), null, LocalGuidance.MAX_CONSECUTIVE))
        }

    @Test
    fun anIgnoredInstructionGoesToThePlanner() {
        val ignored=Verification(action_status=ActionStatus.NOT_COMPLETED)

        assertNull(LocalGuidance.policyFor(perception(), ignored, 0))
        }

    @Test
    fun aHalfFinishedMovementGoesToThePlanner() {
        // The planner is told to ask for the REST of the movement. This does not
        // know what was asked, so answering here would replace the remainder of
        // a turn with "move closer" and lose the half the user had done.
        val partial=Verification(action_status=ActionStatus.PARTIAL)

        assertNull(LocalGuidance.policyFor(perception(), partial, 0))
        }

    @Test
    fun aCarriedOutInstructionStillAllowsTheFastPath() {
        val done=Verification(action_status=ActionStatus.COMPLETED)

        assertTrue(LocalGuidance.policyFor(perception(), done, 0)!=null)
        }

    @Test
    fun distanceOutranksTheRestTheWayThePlannerChose() {
        // Observed: on frames reporting [too_far, cut_off, blur] the planner said
        // "move the phone closer", not "hold still".
        val policy=LocalGuidance.policyFor(
            perception(problems=listOf("too_far", "cut_off", "blur")),
            null,
            0,
            )

        assertTrue(policy!!.utterance.contains("closer"))
        }

    @Test
    fun anUnknownProblemGoesToThePlanner() {
        assertNull(LocalGuidance.policyFor(perception(problems=listOf("something_new")), null, 0))
        }

    @Test
    fun aDefectWithNoCommandToFixItGoesToThePlanner() {
        // Neither of these has an approved command, so this path has nothing to
        // offer but invented wording.
        assertNull(LocalGuidance.policyFor(perception(problems=listOf("glare")), null, 0))
        assertNull(LocalGuidance.policyFor(perception(problems=listOf("occluded_by_hand")), null, 0))

        // Still served when a distance problem is reported alongside one.
        assertEquals("MOVE_CLOSER", LocalGuidance.policyFor(
            perception(problems=listOf("glare", "too_far")), null, 0)?.action)
        }

    @Test
    fun blurAloneIsStillThePlannersUntilTheHandHasBeenMeasured() {
        // The reason blur was taken away from this path in the first place: low
        // light depresses sharpness exactly as an unsteady hand does, and
        // bracing does nothing at all for a dim shelf. Unmeasured has to read
        // the same as steady, or the fast path starts guessing the cause.
        assertNull(LocalGuidance.policyFor(perception(problems=listOf("blur")), null, 0))
        assertNull(LocalGuidance.policyFor(
            perception(problems=listOf("blur")), null, 0, handUnsteady=false))
        }

    @Test
    fun aMeasuredTremorIsAnsweredWithoutThePlanner() {
        // Six seconds of planner latency, on a turn whose remedy is known from
        // the phone's own sensors before the picture was even sent.
        val policy=LocalGuidance.policyFor(
            perception(problems=listOf("blur")), null, 0, handUnsteady=true)

        assertEquals("ELBOWS_IN", policy?.action)
        assertEquals("Elbows in.", policy?.utterance)
        assertEquals(ExpectedChange.NONE, policy?.expected_change)
        }

    @Test
    fun aMeasuredTremorDoesNotOutrankMovingCloser() {
        // The ordering the planner itself chose on frames reporting both: moving
        // closer usually fixes the blur as well, and it makes the date bigger
        // whether the hand was the problem or not.
        assertEquals("MOVE_CLOSER", LocalGuidance.policyFor(
            perception(problems=listOf("blur", "too_far")), null, 0, handUnsteady=true)?.action)
        }

    // ------------------------------------------------------ the brace warrant
    //
    // Each of these is a firing from the first participant session, S01/V03.

    @Test
    fun aCleanFrameIsNeverAReasonToBrace() {
        // Frame 1, 11:28:52: problems ["none"], view_quality usable, and the
        // planner asked for a brace anyway because the latched flag said the
        // hand was unsteady. There was nothing for bracing to fix - the date
        // was simply on another face - and the turn was spent on posture.
        assertFalse(LocalGuidance.braceWarranted(
            handMeasuredUnsteady=true,
            alreadyBraced=false,
            perception=perception(problems=listOf("none")),
            status=ActionStatus.CANNOT_DETERMINE,
            ))
        }

    @Test
    fun anOutstandingMovementOutranksPosture() {
        // Frame 3, 11:29:16: genuinely blurred, but the user had just turned the
        // cup the wrong way (wrong_face). Bracing there abandons the turn they
        // are halfway through, and leaves them braced at the wrong face.
        for (status in LocalGuidance.UNFINISHED_MOVEMENT)
        assertFalse("$status left outstanding", LocalGuidance.braceWarranted(
            handMeasuredUnsteady=true,
            alreadyBraced=false,
            perception=perception(problems=listOf("blur")),
            status=status,
            ))
        }

    @Test
    fun theBraceIsAskedForAtMostOnce() {
        // Posture is changed once or not at all. S01 was asked twice in
        // twenty-four seconds, by which point they had either braced or could
        // not, and the second ask could only cost a turn.
        assertFalse(LocalGuidance.braceWarranted(
            handMeasuredUnsteady=true,
            alreadyBraced=true,
            perception=perception(problems=listOf("blur")),
            status=ActionStatus.COMPLETED,
            ))
        }

    @Test
    fun aMeasuredUnsteadyHandOnASmearedFrameIsTheCaseItIsFor() {
        assertTrue(LocalGuidance.braceWarranted(
            handMeasuredUnsteady=true,
            alreadyBraced=false,
            perception=perception(problems=listOf("blur")),
            status=ActionStatus.COMPLETED,
            ))

        // And never on the frame alone: a dim shelf smears a picture the same
        // way, and bracing does nothing for that one.
        assertFalse(LocalGuidance.braceWarranted(
            handMeasuredUnsteady=false,
            alreadyBraced=false,
            perception=perception(problems=listOf("blur")),
            status=ActionStatus.COMPLETED,
            ))
        }

    @Test
    fun aSteadyHandOnADimShelfIsNeverToldToBrace() {
        // The whole point of the condition. Every problem that is not blur must
        // answer the same way whether the hand was measured or not.
        for (problems in listOf(
            listOf("too_far"),
            listOf("cut_off"),
            listOf("glare"),
            listOf("occluded_by_hand"),
            listOf("none"),
            )) {
            assertEquals(
                "\"$problems\" changed answer on a measurement about the hand",
                LocalGuidance.policyFor(perception(problems=problems), null, 0)?.action,
                LocalGuidance.policyFor(perception(problems=problems), null, 0,
                    handUnsteady=true)?.action,
                )
            }
        }

    @Test
    fun noInstructionAsksTheUserToJudgeSomethingVisual() {
        // The user cannot see the package or the result of moving it. An
        // instruction whose stopping condition is visual - "until the shine is
        // gone", "until it fits in the frame" - cannot be carried out and gives
        // them no way to say so.
        val unusable=listOf(
            "shine", "glare", "frame", "fits", "centre", "center",
            "focus", "blurry", "sharp", "visible", "showing", "look at",
            )

        for (problems in listOf(
            listOf("too_far"),
            listOf("cut_off"),
            )) {
            val said=LocalGuidance.policyFor(perception(problems=problems), null, 0)!!
            .utterance.lowercase()

            for (word in unusable)
            assertTrue("\"$said\" asks the user to judge \"$word\"", !said.contains(word))
            }
        }

    @Test
    fun everyInstructionIsShortEnoughToActOn() {
        // Guidance was measured at 2.1-3.4s of speech per instruction, on turns
        // that took 8s. Anything much over a dozen words is another second
        // before the user can start moving.
        for (problems in listOf(
            listOf("too_far"),
            listOf("cut_off"),
            )) {
            val said=LocalGuidance.policyFor(perception(problems=problems), null, 0)!!.utterance

            // The approved templates carry a distance and a pause request, so
            // they run longer than a bare imperative. GuidanceTemplatesTest
            // holds the full set to the same bound.
            assertTrue("too long: \"$said\"", said.split(" ").size<=14)
            }
        }

    @Test
    fun theFastPathNeverAnswersAndNeverAbstains() {
        for (problems in listOf(
            listOf("too_far"),
            listOf("cut_off"),
            )) {
            val policy=LocalGuidance.policyFor(perception(problems=problems), null, 0)

            // Always a command, never ANSWER, ABSTAIN, or a sentence of its own.
            assertNotNull(GuidanceTemplates.parse(policy?.action))
            assertNull(policy?.iso_date)
            assertNull(policy?.date_string)
            assertNull(policy?.date_type)
            assertNull(policy?.abstain_reason)
            }
        }
    }

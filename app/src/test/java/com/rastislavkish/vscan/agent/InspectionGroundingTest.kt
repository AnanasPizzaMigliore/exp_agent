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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
* What counts as having looked, and what counts as a date that may be spoken.
*
* Both are about not claiming more than a frame showed: a sharp picture is not a
* search of a face, and a planner's date is not a reading of the package.
*/
class InspectionGroundingTest {

    private fun seen(
        surface: String="base",
        dateRegion: Boolean=false,
        problems: List<String> =listOf("none"),
        dateString: String?=null,
        iso: String?=null,
        ) = Perception(
        geometry="can",
        surface_in_view=surface,
        date_region_visible=dateRegion,
        date_legible=dateString!=null,
        date_string=dateString,
        iso_date=iso,
        looks_like=if (dateString!=null) "date" else "none",
        problems=problems,
        )

    // ------------------------------------------------------------------
    // Clear looks
    // ------------------------------------------------------------------

    @Test
    fun aUsableUnobstructedLookWithoutADateCounts() {
        val memory=InspectionMemory()
        memory.record(seen(), ViewQuality.USABLE)

        assertEquals(setOf("base"), memory.surfacesSearchedWithoutDate())
        }

    @Test
    fun usableQualityAloneIsNotASearch() {
        val memory=InspectionMemory()
        memory.record(seen(problems=listOf("occluded_by_hand")), ViewQuality.USABLE)
        memory.record(seen(surface="top", problems=listOf("cut_off")), ViewQuality.USABLE)
        memory.record(seen(surface="side"), ViewQuality.POOR)

        assertTrue(memory.surfacesSearchedWithoutDate().isEmpty())
        // Still recorded as well inspected by quality, which is exactly the gap.
        assertEquals(setOf("base", "top"), memory.wellInspectedSurfaces().toSet())
        }

    @Test
    fun aFaceWhereADateRegionWasSeenIsNeverSearchedWithoutDate() {
        val memory=InspectionMemory()
        memory.record(seen(dateRegion=true), ViewQuality.POOR)
        memory.record(seen(), ViewQuality.USABLE)

        assertTrue(memory.surfacesSearchedWithoutDate().isEmpty())
        }

    @Test
    fun theCurrentFrameCountsBeforeItIsRecorded() {
        val memory=InspectionMemory()

        assertEquals(setOf("top"), memory.surfacesSearchedWithoutDate(seen(surface="top"), ViewQuality.USABLE))
        assertTrue(memory.surfacesSearchedWithoutDate(seen(surface="unclear"), ViewQuality.USABLE).isEmpty())
        }

    @Test
    fun aDateRegionInTheCurrentFrameWithdrawsItsFace() {
        val memory=InspectionMemory()
        memory.record(seen(), ViewQuality.USABLE)

        assertTrue(memory.surfacesSearchedWithoutDate(seen(dateRegion=true), ViewQuality.POOR).isEmpty())
        }

    // ------------------------------------------------------------------
    // The package's geometry across a session
    // ------------------------------------------------------------------

    private fun turn(frameId: Int, geometry: String, surface: String="unclear", guidance: String?=null) = Turn(
        frameId=frameId,
        instructionId=frameId,
        guidance=guidance,
        perception=Perception(geometry=geometry, surface_in_view=surface),
        actionStatus=ActionStatus.COMPLETED,
        viewQuality=ViewQuality.USABLE,
        whatChanged=null,
        instruction="",
        rationale=null,
        latencyMs=0,
        tokens=0,
        )

    @Test
    fun aSingleMisreadingDoesNotChangeTheSettledGeometry() {
        // The 2026-09-14 session: cup, then one frame read as tray.
        val memory=InspectionMemory()
        memory.history.add(turn(1, "cup"))

        assertEquals("cup", memory.settledGeometry("tray"))
        }

    @Test
    fun repeatedReadingsDoChangeIt() {
        val memory=InspectionMemory()
        memory.history.add(turn(1, "tray"))
        memory.history.add(turn(2, "cup"))

        assertEquals("cup", memory.settledGeometry("cup"))
        }

    @Test
    fun unknownReadingsNeitherCountNorOverride() {
        val memory=InspectionMemory()
        memory.history.add(turn(1, "unknown"))

        assertEquals("unknown", memory.settledGeometry(null))
        assertEquals("jar", memory.settledGeometry("jar"))

        memory.history.add(turn(2, "jar"))
        assertEquals("jar", memory.settledGeometry("unknown"))
        }

    // ------------------------------------------------------------------
    // A tip that never shows its face
    // ------------------------------------------------------------------

    /** The 2026-09-14 17:15 session up to frame 4, as memory held it. */
    private fun stuckOnTheLid(): InspectionMemory {
        val memory=InspectionMemory()
        val frames=listOf(
            turn(1, "cup", "side", "TURN_UP"),
            turn(2, "cup", "base", "TURN_DOWN"),
            turn(3, "jar", "base", "TURN_DOWN"),
            )
        for (frame in frames) {
            memory.record(frame.perception, ViewQuality.USABLE)
            memory.history.add(frame)
            }
        return memory
        }

    @Test
    fun aThirdTipTowardsAFaceNeverSeenIsReplaced() {
        assertTrue(stuckOnTheLid().repeatedTipWithoutReaching(GuidanceAction.TURN_DOWN, "base"))
        }

    @Test
    fun aSecondTipIsStillAllowed() {
        val memory=stuckOnTheLid()
        memory.history.removeAt(memory.history.lastIndex)

        assertFalse(memory.repeatedTipWithoutReaching(GuidanceAction.TURN_DOWN, "base"))
        }

    @Test
    fun aTipIsNotReplacedOnceItsFaceHasBeenSeen() {
        val memory=stuckOnTheLid()

        assertFalse(memory.repeatedTipWithoutReaching(GuidanceAction.TURN_DOWN, "top"))

        memory.record(Perception(surface_in_view="top"), ViewQuality.POOR)
        assertFalse(memory.repeatedTipWithoutReaching(GuidanceAction.TURN_DOWN, "base"))
        }

    @Test
    fun turnsAreNeverReplaced() {
        // A quarter turn is expected to keep showing "side".
        val memory=InspectionMemory()
        memory.history.add(turn(1, "cup", "side", "TURN_LEFT"))
        memory.history.add(turn(2, "cup", "side", "TURN_LEFT"))

        assertFalse(memory.repeatedTipWithoutReaching(GuidanceAction.TURN_LEFT, "side"))
        }

    @Test
    fun theReplacementHoldsUntilTheFaceArrives() {
        // Session b7012d7a, 2026-09-16, frames 1 to 4. The planner asked for the
        // base four times; the third was replaced with a quarter turn, and that
        // quarter turn ended the run of TURN_UPs, so the fourth went out as
        // another TURN_UP. The base was never reached by an instruction.
        val memory=InspectionMemory()
        memory.history.add(turn(1, "can", "side", "TURN_UP"))
        memory.history.add(turn(2, "can", "top", "TURN_UP"))

        // Third: two have been spoken without the base ever arriving.
        assertTrue(memory.repeatedTipWithoutReaching(GuidanceAction.TURN_UP, "top"))

        // The replacement is what gets recorded, and it must not reset anything.
        memory.history.add(turn(3, "can", "side", "TURN_LEFT"))

        assertTrue(
            "a quarter turn in between is not the base arriving",
            memory.repeatedTipWithoutReaching(GuidanceAction.TURN_UP, "side"),
            )

        // Only the face itself lifts it.
        memory.record(Perception(surface_in_view="base"), ViewQuality.USABLE)
        assertFalse(memory.repeatedTipWithoutReaching(GuidanceAction.TURN_UP, "base"))
        }

    @Test
    fun theControlArmLetsTheReplacementLapse() {
        // The pre-16-September rule, kept for R4's baseline: a quarter turn in
        // between broke the run, so the fourth TURN_UP went out unaltered.
        val memory = InspectionMemory()
        memory.history.add(turn(1, "can", "side", "TURN_UP"))
        memory.history.add(turn(2, "can", "top", "TURN_UP"))
        memory.history.add(turn(3, "can", "side", "TURN_LEFT"))

        assertFalse(
            "sticky=false must reproduce the behaviour being compared against",
            memory.repeatedTipWithoutReaching(GuidanceAction.TURN_UP, "side", sticky = false),
            )
        assertTrue(memory.repeatedTipWithoutReaching(GuidanceAction.TURN_UP, "side", sticky = true))
        }

    @Test
    fun aReplacedTipDoesNotCountTowardsReplacingTheNextOne() {
        // History records what was spoken. TURN_LEFT stood in for a TURN_UP
        // here, and must not also be evidence that TURN_LEFT is stuck.
        val memory=InspectionMemory()
        memory.history.add(turn(1, "can", "side", "TURN_LEFT"))
        memory.history.add(turn(2, "can", "side", "TURN_LEFT"))

        assertFalse(memory.repeatedTipWithoutReaching(GuidanceAction.TURN_DOWN, "side"))
        }

    @Test
    fun theSettledGeometryOutvotesOneOddFrame() {
        // Frame 4 read "unknown" after frame 3 read "jar". Nothing spoken names
        // the package now, but the planner is still told one geometry per
        // session and the common-placement rules turn on it.
        assertEquals("cup", stuckOnTheLid().settledGeometry("unknown"))
        }

    // ------------------------------------------------------------------
    // Which planner replies can be acted on
    // ------------------------------------------------------------------

    @Test
    fun aCommandWithoutWordingIsAccepted() {
        // The phone renders the words for a command, so utterance is empty by
        // design. Demanding it here refused two replies in a row on 2026-09-14.
        assertEquals(null, InspectionAgent.policyProblem(Policy(action="TURN_UP", utterance="")))
        assertEquals(null, InspectionAgent.policyProblem(Policy(action="MOVE_LEFT", utterance="")))
        }

    @Test
    fun aContinueWithNothingAtAllToSayIsRefused() {
        // CONTINUE is the planner writing the sentence itself, so it is the one
        // action whose wording nothing else can supply.
        assertEquals("The planner returned nothing to say", InspectionAgent.policyProblem(Policy(action="CONTINUE", utterance=" ")))
        assertEquals(null, InspectionAgent.policyProblem(Policy(action="CONTINUE", utterance="Hold still.")))
        }

    @Test
    fun answersAndAbstentionsDoNotNeedFallbackWording() {
        assertEquals(null, InspectionAgent.policyProblem(Policy(action="ANSWER", utterance="")))
        assertEquals(null, InspectionAgent.policyProblem(Policy(action="ABSTAIN", utterance="", abstain_reason="illegible")))
        assertTrue(InspectionAgent.policyProblem(Policy(action="GUESS", utterance="x"))!!.startsWith("Unknown action"))
        assertTrue(InspectionAgent.policyProblem(Policy(action="WAVE_IT_AROUND", utterance="x"))!!.startsWith("Unknown action"))
        assertTrue(InspectionAgent.policyProblem(Policy(action="TURN_QUARTER", utterance="x"))!!.startsWith("Unknown action"))
        }

    // ------------------------------------------------------------------
    // The date that would be spoken
    // ------------------------------------------------------------------

    private fun answer(dateString: String?, iso: String?) =
    Policy(action="ANSWER", utterance="x", date_string=dateString, iso_date=iso, date_type=DateType.BEST_BEFORE)

    @Test
    fun aMatchingReadingIsAccepted() {
        val frame=seen(dateString="21/07/27", iso="2027-07-21")

        assertTrue(InspectionAgent.answerDateMatchesPerception(answer("21/07/27", "2027-07-21"), frame))
        assertTrue(InspectionAgent.answerDateMatchesPerception(answer("21.07.27", "2027-07-21"), frame))
        }

    @Test
    fun aDifferentDateStringIsRefusedWhenIsoDatesAreMissing() {
        // The hole this closes: without ISO dates nothing compared the strings,
        // and date_string is exactly what gets spoken when iso_date is absent.
        val frame=seen(dateString="21/07/27")

        assertFalse(InspectionAgent.answerDateMatchesPerception(answer("12/08/27", null), frame))
        assertTrue(InspectionAgent.answerDateMatchesPerception(answer("21/07/27", null), frame))
        }

    @Test
    fun anIsoDateThePerceptionDidNotGiveIsRefused() {
        assertFalse(InspectionAgent.answerDateMatchesPerception(answer("21/07/27", "2027-07-21"), seen(dateString="21/07/27")))
        }

    @Test
    fun matchingIsoDatesWithContradictoryStringsAreRefused() {
        val frame=seen(dateString="21/07/27", iso="2027-07-21")

        assertFalse(InspectionAgent.answerDateMatchesPerception(answer("12/08/27", "2027-07-21"), frame))
        }

    @Test
    fun anAnswerWithNoDateIsRefused() {
        assertFalse(InspectionAgent.answerDateMatchesPerception(answer(null, null), seen(dateString="21/07/27")))
        assertFalse(InspectionAgent.answerDateMatchesPerception(answer("BB", null), seen(dateString="BB")))
        }
    }

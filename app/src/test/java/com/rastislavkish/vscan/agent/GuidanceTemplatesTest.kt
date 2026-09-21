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
* Properties every spoken instruction must hold, checked over the whole set.
*
* This is the payoff of rendering speech on the phone instead of letting the
* model write it: these are now guarantees rather than hopes expressed in a
* prompt.
*
* What is spoken is the command itself - "Turn left." - so most of these are now
* checks that nothing has crept back in around it.
*/
class GuidanceTemplatesTest {

    /**
    * The two commands whose name does not say the movement.
    *
    * Every other command is a direction the hand can carry out from the word
    * alone. "Up" and "down" are not: a tip has two faces moving in opposite
    * directions and the word picks neither, so these two say which face they
    * want and are held to a different standard below.
    */
    private val TIPS=setOf(GuidanceAction.TURN_UP, GuidanceAction.TURN_DOWN)

    @Test
    fun everyActionRendersSomething() {
        for (action in GuidanceAction.entries) {
            val said=GuidanceTemplates.render(action)

            assertTrue("$action produced nothing", said.isNotBlank())
            assertTrue("$action is not a sentence", said.endsWith("."))
            }
        }

    @Test
    fun whatIsSpokenIsTheCommandItself() {
        // MOVE_CLOSER is heard as "Move closer." A command the user hears as
        // something else is a command the planner cannot reason about.
        //
        // The two tips are excluded, and it is worth being clear about what that
        // costs. This property was the reason the wording was allowed to be
        // mechanical, and the tips give it up because they are the two commands
        // whose name does not say the movement. Everything below still holds them
        // to the rest of the contract.
        for (action in GuidanceAction.entries-TIPS) {
            val expected=action.name.lowercase().replace("_", " ")
            .replaceFirstChar { it.uppercase() }+"."

            assertEquals(expected, GuidanceTemplates.render(action))
            }
        }

    @Test
    fun eachTipNamesTheFaceItWants() {
        // "Turn up." was heard, on 2026-09-16, as a reason to tip a can the other
        // way; its top arrived, the planner wanted the base, and it asked four
        // times. The convention that decides which way "up" goes is written in
        // POLICY, where the user cannot read it, so the words say the face.
        assertTrue(GuidanceTemplates.render(GuidanceAction.TURN_UP).contains("bottom"))
        assertTrue(GuidanceTemplates.render(GuidanceAction.TURN_DOWN).contains("top"))

        // And the face named is the one the rest of the system is waiting for.
        // These drifting apart is the whole failure, in one assertion.
        assertEquals("base", GuidanceTemplates.targetSurface(GuidanceAction.TURN_UP))
        assertEquals("top", GuidanceTemplates.targetSurface(GuidanceAction.TURN_DOWN))
        assertEquals("top", GuidanceTemplates.invertedSurface(GuidanceAction.TURN_UP))
        assertEquals("base", GuidanceTemplates.invertedSurface(GuidanceAction.TURN_DOWN))

        // Only tips name a face. A quarter turn is executable from the word.
        for (action in GuidanceAction.entries-TIPS) {
            assertNull("$action should name no face", GuidanceTemplates.targetSurface(action))
            assertNull("$action should have no inverse", GuidanceTemplates.invertedSurface(action))
            }
        }

    @Test
    fun theOppositeFaceArrivingIsTheWrongFace() {
        // Frame 2 of session b7012d7a: the base was asked for and the top came
        // round. The verifier called that "completed" while writing "the top of
        // the can is now visible", so this is decided here instead, from the two
        // labels, where there is nothing to be talked out of.
        assertTrue(GuidanceTemplates.tipWentTheWrongWay(GuidanceAction.TURN_UP, "top"))
        assertTrue(GuidanceTemplates.tipWentTheWrongWay(GuidanceAction.TURN_DOWN, "base"))

        // Case and stray spaces are perception's, not a different answer.
        assertTrue(GuidanceTemplates.tipWentTheWrongWay(GuidanceAction.TURN_UP, " TOP "))
        }

    @Test
    fun onlyTheOppositeFaceCounts() {
        // The face asked for, arriving. Nothing to report.
        assertFalse(GuidanceTemplates.tipWentTheWrongWay(GuidanceAction.TURN_UP, "base"))

        // A side or an unreadable label can mean a half-finished turn, a package
        // that rolled, or perception being unsure. Those are the verifier's to
        // judge, and claiming them here would turn every uncertain frame into a
        // wrong face.
        for (surface in listOf("side", "label", "unclear", ""))
        assertFalse(
            "\"$surface\" is not the opposite face",
            GuidanceTemplates.tipWentTheWrongWay(GuidanceAction.TURN_UP, surface),
            )

        // A quarter turn names no face, so nothing can answer it wrongly.
        for (action in GuidanceAction.entries-TIPS)
        assertFalse("$action names no face", GuidanceTemplates.tipWentTheWrongWay(action, "top"))

        // Nothing outstanding: the opening instruction, a recovery prompt, or a
        // sentence the planner wrote itself.
        assertFalse(GuidanceTemplates.tipWentTheWrongWay(null, "top"))
        }

    @Test
    fun theControlArmSpeaksWhatTheOldBuildSpoke() {
        // R4 compares grounded instructions against the wording that produced
        // the 2026-09-16 failure. If this drifts, the comparison stops having a
        // baseline and starts having two versions of the fix.
        assertEquals("Turn up.", GuidanceTemplates.render(GuidanceAction.TURN_UP, nameTheFace = false))
        assertEquals("Turn down.", GuidanceTemplates.render(GuidanceAction.TURN_DOWN, nameTheFace = false))

        // Only the tips differ between the arms. A difference anywhere else
        // would be a second, uncontrolled change inside the comparison.
        for (action in GuidanceAction.entries - TIPS)
        assertEquals(
            "$action must be identical in both arms",
            GuidanceTemplates.render(action),
            GuidanceTemplates.render(action, nameTheFace = false),
            )
        }

    @Test
    fun nothingIsSaidBeyondTheCommand() {
        // Two words and a full stop. Every extra word is another second of
        // speech before the user can start moving, on a turn that already waits
        // several seconds for the model.
        //
        // The tips buy their extra words with a face the hand can find, and six
        // is the whole budget: enough for "Turn the bottom towards the phone.",
        // not enough for an explanation to creep back in.
        for (action in GuidanceAction.entries) {
            val said=GuidanceTemplates.render(action)
            val words=said.split(" ").size

            if (action in TIPS)
            assertTrue("$action is longer than six words: \"$said\"", words<=6)
            else assertEquals("$action is not two words: \"$said\"", 2, words)
            }
        }

    @Test
    fun noInstructionAsksForAVisualJudgement() {
        // The user cannot see the package or the effect of moving it. Anything
        // whose stopping condition is visual is unfollowable, and they have no
        // way to tell us so.
        val unusable=listOf(
            "shine", "glare", "frame", "fits", "centre", "center", "focus",
            "blurry", "visible", "showing", "look at", "see ", "aligned",
            )

        for (action in GuidanceAction.entries) {
            val said=GuidanceTemplates.render(action).lowercase()

            for (word in unusable)
            assertTrue("$action says \"$said\" which needs \"$word\"", !said.contains(word))
            }
        }

    @Test
    fun noInstructionAsksForUnboundedMovement() {
        // A reply takes several seconds. "Keep turning until I say stop" would
        // be two full rotations finished before anything could be said.
        for (action in GuidanceAction.entries) {
            val said=GuidanceTemplates.render(action).lowercase()

            assertTrue("$action is unbounded: \"$said\"", !said.contains("until i"))
            assertTrue("$action is unbounded: \"$said\"", !said.contains("keep"))
            assertTrue("$action is unbounded: \"$said\"", !said.contains("continue"))
            }
        }

    @Test
    fun slidesExpectPositionAndTurnsExpectViewpoint() {
        // The capture gate waits on this. Getting it wrong either fires the
        // camera mid-rotation or deadlocks waiting for movement nobody asked for.
        assertEquals(ExpectedChange.SCALE, GuidanceTemplates.expectedChange(GuidanceAction.MOVE_CLOSER))
        assertEquals(ExpectedChange.SCALE, GuidanceTemplates.expectedChange(GuidanceAction.MOVE_FARTHER))
        assertEquals(ExpectedChange.POSITION, GuidanceTemplates.expectedChange(GuidanceAction.MOVE_LEFT))
        assertEquals(ExpectedChange.POSITION, GuidanceTemplates.expectedChange(GuidanceAction.MOVE_DOWN))
        assertEquals(ExpectedChange.VIEWPOINT, GuidanceTemplates.expectedChange(GuidanceAction.TURN_LEFT))
        assertEquals(ExpectedChange.VIEWPOINT, GuidanceTemplates.expectedChange(GuidanceAction.TURN_UP))
        assertEquals(ExpectedChange.VIEWPOINT, GuidanceTemplates.expectedChange(GuidanceAction.TURN_BACK))
        }

    @Test
    fun theVocabularyIsElevenDirectionsAndOneWayOfHolding() {
        // Six ways to move the package, five ways to turn it, and the brace.
        // Anything else - a finger moved aside, a tilt against glare - has to be
        // written by the planner, and the caller falls back to its utterance.
        //
        // ELBOWS_IN is the only member that is not a direction, and it is listed
        // here rather than left to the planner because a tremor is measured by
        // the phone on every turn. A defect the phone can recognise that often
        // deserves wording that was checked once, not wording reinvented each
        // time it comes up.
        val expected=setOf(
            "MOVE_LEFT", "MOVE_RIGHT", "MOVE_UP", "MOVE_DOWN", "MOVE_CLOSER", "MOVE_FARTHER",
            "TURN_LEFT", "TURN_RIGHT", "TURN_UP", "TURN_DOWN", "TURN_BACK",
            "ELBOWS_IN",
            )

        assertEquals(expected, GuidanceTemplates.names)
        }

    @Test
    fun theBraceIsSpokenLikeEveryOtherCommandAndAsksForNoMovement() {
        // Two words, imperative, carryable by a hand that cannot see anything -
        // the same contract the ten directions meet, which is why the action is
        // named for the words rather than the words written for the name.
        assertEquals("Elbows in.", GuidanceTemplates.render(GuidanceAction.ELBOWS_IN))

        // And NONE, which no direction returns. A gate told to wait for movement
        // after "Elbows in." waits for one that is never coming, and deadlocks
        // until the stall deadline - the exact failure the command exists for,
        // arriving the other way round.
        assertEquals(ExpectedChange.NONE,
            GuidanceTemplates.expectedChange(GuidanceAction.ELBOWS_IN))

        for (action in GuidanceAction.entries-GuidanceAction.ELBOWS_IN)
        assertTrue("$action must ask for a movement",
            ExpectedChange.involvesMovement(GuidanceTemplates.expectedChange(action)))
        }

    @Test
    fun theBraceNeverTellsAnyoneToHoldStill() {
        // A hand with a tremor is already trying. "Hold still" asks for effort
        // that is not what is missing, and puts the failure on the user; the
        // brace is mechanical and works whether or not they can manage either.
        val said=GuidanceTemplates.render(GuidanceAction.ELBOWS_IN).lowercase()

        for (word in listOf("still", "steady", "try", "careful", "relax"))
        assertTrue("the brace says \"$said\", which asks for \"$word\"", !said.contains(word))
        }

    @Test
    fun unknownActionsAreRefused() {
        assertNull(GuidanceTemplates.parse(null))
        assertNull(GuidanceTemplates.parse(""))
        assertNull(GuidanceTemplates.parse("SPIN_IT_ROUND"))
        assertNull(GuidanceTemplates.parse("TURN_QUARTER"))
        assertNotNull(GuidanceTemplates.parse("turn_left"))
        }
    }

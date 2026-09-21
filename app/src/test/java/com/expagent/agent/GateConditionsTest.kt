/*
* Copyright (C) 2026 exp agent contributors
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

package com.expagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
* A live condition and the shadow arm of the same name must be the same
* controller.
*
* The ablation compares sessions run under a named gate against arms evaluated
* in shadow under that name. If the two constructions drift, the comparison
* silently stops being a comparison, and nothing in the log would show it.
*/
class GateConditionsTest {

    @Test
    fun theFullConditionIsTheShippedController() {
        val shipped=EventCaptureController()
        val condition=GateConditions.of(GateConditions.FULL)!!

        assertEquals(shipped.onsetNs, condition.onsetNs)
        assertEquals(shipped.actionHoldNs, condition.actionHoldNs)
        assertEquals(shipped.responseHoldNs, condition.responseHoldNs)
        assertEquals(shipped.initialHoldNs, condition.initialHoldNs)
        assertEquals(shipped.maxGapNs, condition.maxGapNs)
        assertEquals(shipped.quietGraceNs, condition.quietGraceNs)
        }

    @Test
    fun eachAblationZeroesExactlyOneHold() {
        // An ablation that changed two things at once would not isolate either.
        val shipped=EventCaptureController()
        val cases=mapOf(
            "E_no_onset" to { c: EventCaptureController -> c.onsetNs },
            "E_no_settling_hold" to { c: EventCaptureController -> c.actionHoldNs },
            "E_no_match_hold" to { c: EventCaptureController -> c.responseHoldNs },
            "E_no_tremor_tolerance" to { c: EventCaptureController -> c.quietGraceNs },
            )

        for ((name, zeroed) in cases) {
            val c=GateConditions.of(name)!!
            assertEquals("$name should zero its own hold", 0L, zeroed(c))

            val holds=listOf<(EventCaptureController) -> Long>(
                { it.onsetNs }, { it.actionHoldNs }, { it.responseHoldNs },
                { it.initialHoldNs }, { it.maxGapNs }, { it.stallNs },
                { it.quietGraceNs })
            val changed=holds.count { it(c)!=it(shipped) }
            assertEquals("$name changed more than one parameter", 1, changed)
            }
        }

    @Test
    fun everyNamedConditionCanBeBuilt() {
        for (name in GateConditions.names)
        assertNotNull("$name is named but cannot be built", GateConditions.of(name))
        }

    /** A stale product label is the one study mistake nothing else would show. */
    @Test
    fun theSamePackageTwiceRunningForOneParticipantIsDetectable() {
        // Three packages, one participant, the spinner never changed for the
        // second: the session records V01 twice and the pair is silently lost.
        val first=GateConditions.runKey("S03", "V01")
        val forgotten=GateConditions.runKey("S03", "V01")
        val changed=GateConditions.runKey("S03", "V02")

        assertEquals(first, forgotten)
        assertNotEquals(first, changed)
        }

    @Test
    fun aCounterbalancedOrderDoesNotLookLikeAStaleLabel() {
        // S03 finishes on V03 and S04 legitimately begins on it. Keyed on the
        // package alone this fires at every participant boundary, and a warning
        // that cries wolf is one nobody reads by the third day of a study.
        assertNotEquals(
            GateConditions.runKey("S03", "V03"),
            GateConditions.runKey("S04", "V03"),
            )
        }

    @Test
    fun ordinaryUseHasNothingToGoStale() {
        // No participant and no package is somebody using the app, not a
        // session in the study, and it must never be flagged as a repeat.
        assertEquals("", GateConditions.runKey("", null))

        // A participant set without a package is still a study session, and one
        // whose package nobody chose - which the empty run_label already says.
        assertNotEquals("", GateConditions.runKey("S03", null))
        }

    @Test
    fun anUnknownNameIsRefusedRatherThanDefaulted() {
        // The session refuses to start on null. Falling back to the shipped gate
        // would record a run as an ablation that was not one.
        assertNull(GateConditions.of("E_no_such_thing"))
        assertNull(GateConditions.of(""))
        assertNull(GateConditions.of(null))
        }

    @Test
    fun theQualityComparatorCanDriveALiveSession() {
        val quality=GateConditions.of(GateConditions.QUALITY)

        assertTrue(GateConditions.QUALITY in GateConditions.names)
        assertNotNull(quality)
        assertEquals(CaptureGateMode.QUALITY_AWARE, quality!!.mode)
        assertEquals(false, quality.checkResponseFreshness)

        // Its shadow twin is built from the same factory, so diagnostics before
        // the divergence point are decisions by the actual live implementation.
        assertTrue(GateConditions.QUALITY in ShadowArms().names)
        }

    @Test
    fun everyLiveConditionHasAShadowArmOfTheSameName() {
        val arms=ShadowArms().names
        for (name in GateConditions.names)
        assertTrue("$name has no shadow arm to compare against", name in arms)
        }
    }

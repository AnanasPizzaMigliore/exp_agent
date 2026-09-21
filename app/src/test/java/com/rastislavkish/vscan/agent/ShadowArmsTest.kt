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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
* That the arms measure the session they are riding along with.
*
* The load-bearing check is the first one. E_full is the same controller with
* the same constants as the live gate, so if the harness feeds the arms anything
* the live gate did not get - a different instant, a different relation, a
* missed lifecycle call - E_full stops agreeing with it. Every other arm's
* number is only worth as much as that agreement.
*/
class ShadowArmsTest {

    private val arms=ShadowArms()
    private val live=EventCaptureController()

    private var now=0L
    private val liveCaptures=mutableListOf<Long>()
    private val armCaptures=mutableMapOf<String, MutableList<Long>>()

    private fun begin(reason: CaptureReason=CaptureReason.INITIAL_OBSERVATION, expected: String?=null) {
        live.start("session-a")
        arms.start("session-a")
        live.arm(reason, 1, expected, now)
        arms.arm(reason, 1, expected, now)
        }

    /** One measurement to both, exactly as StabilityMonitor delivers it. */
    private fun frames(
        ms: Int,
        moving: Boolean=false,
        reliable: Boolean=true,
        view: ViewRelation=if (moving) ViewRelation.DIFFERENT else ViewRelation.SAME,
        submitted: ViewRelation=view,
        sinceUpload: ViewRelation=submitted,
        ) {
        repeat(ms/100) {
            now+=100_000_000L

            live.sample(now, view, quiet=!moving, reliable=reliable, submittedView=submitted)
            live.noteStableFrame(now, now)

            if (live.phase==EventPhase.READY) {
                val ticket=live.reserve(automatic=true, nowNs=now)

                if (ticket!=null&&live.acceptCapture(ticket, now)) {
                    liveCaptures+=now
                    arms.noteLiveCapture()
                    }
                }

            for (would in arms.sample(now, view, submitted, quiet=!moving, reliable=reliable,
                stableFrameId=now, sinceUpload=sinceUpload))
            armCaptures.getOrPut(would.arm) { mutableListOf() }.add(would.atNs)
            }
        }

    @Test
    fun theFullArmAgreesWithTheLiveControllerOnAnAction() {
        begin(CaptureReason.POST_ACTION_CHANGE, ExpectedChange.VIEWPOINT)
        frames(800, moving=true)
        // Still, but showing a different face: the turn happened and is over.
        frames(2000, view=ViewRelation.DIFFERENT)

        assertTrue("the live controller never captured", liveCaptures.isNotEmpty())
        assertEquals("E_full disagrees with the live controller",
            liveCaptures, armCaptures["E_full"])
        }

    @Test
    fun theFullArmAgreesWhenNothingHappens() {
        begin(CaptureReason.POST_ACTION_CHANGE, ExpectedChange.VIEWPOINT)
        // Tremor against an unchanged view: an action was asked for and never
        // carried out, so neither the live gate nor its twin may capture.
        repeat(6) {
            frames(200, moving=true, view=ViewRelation.SAME)
            frames(800)
            }

        assertTrue(liveCaptures.isEmpty())
        assertNull(armCaptures["E_full"])
        }

    @Test
    fun theBaselineFiresWhereTheControllerWaits() {
        // The regrip: still, but not finished. The controller holds; Q does not.
        begin(CaptureReason.POST_ACTION_CHANGE, ExpectedChange.VIEWPOINT)
        frames(800, moving=true)
        frames(700, view=ViewRelation.DIFFERENT)
        frames(800, moving=true)
        frames(2000, view=ViewRelation.DIFFERENT)

        val q=armCaptures["Q_quality_aware"] ?: listOf<Long>()

        assertTrue("Q never captured", q.isNotEmpty())
        assertTrue("Q did not fire before the live controller", q.first()<liveCaptures.first())
        }

    /**
    * The baseline's duplicate test reads the reference that outlives the turn.
    *
    * Between turns the request-scoped `submitted` anchor is deliberately null,
    * so it reports UNKNOWN. Q used to be handed that, and "not DIFFERENT" is
    * how it declines: on the device it fired once or twice a session where the
    * gate arms fired five to eleven times, which is not the policy it is meant
    * to stand for. It has to decide on the last frame actually uploaded.
    */
    @Test
    fun theBaselineJudgesDuplicatesAgainstTheLastUploadNotTheRequest() {
        begin(CaptureReason.POST_ACTION_CHANGE, ExpectedChange.VIEWPOINT)
        // One capture, so the duplicate test is armed at all.
        frames(800, moving=true)
        frames(2000, view=ViewRelation.DIFFERENT)
        val first=(armCaptures["Q_quality_aware"] ?: listOf<Long>()).size
        assertTrue("Q never made a first capture", first>0)

        // The turn ends: the request anchor is dropped and reads UNKNOWN, while
        // the view genuinely differs from what was last sent.
        arms.release()
        arms.arm(CaptureReason.POST_ACTION_CHANGE, 2, ExpectedChange.VIEWPOINT, now)
        frames(3000, view=ViewRelation.SAME,
            submitted=ViewRelation.UNKNOWN, sinceUpload=ViewRelation.DIFFERENT)

        assertTrue("Q declined a view that differs from the last upload",
            (armCaptures["Q_quality_aware"] ?: listOf<Long>()).size>first)
        }

    /** The other half: a genuine duplicate is still declined. */
    @Test
    fun theBaselineStillDeclinesAViewIdenticalToTheLastUpload() {
        begin(CaptureReason.POST_ACTION_CHANGE, ExpectedChange.VIEWPOINT)
        frames(800, moving=true)
        frames(2000, view=ViewRelation.DIFFERENT)
        val first=(armCaptures["Q_quality_aware"] ?: listOf<Long>()).size

        arms.release()
        arms.arm(CaptureReason.POST_ACTION_CHANGE, 2, ExpectedChange.VIEWPOINT, now)
        frames(3000, view=ViewRelation.SAME,
            submitted=ViewRelation.UNKNOWN, sinceUpload=ViewRelation.SAME)

        assertEquals("Q re-sent the frame it had already uploaded",
            first, (armCaptures["Q_quality_aware"] ?: listOf<Long>()).size)
        }

    @Test
    fun everyArmIsDrivenAndNamedOnce() {
        val names=ShadowArms.defaultArms().map { it.name }

        assertEquals("arm names are not unique", names.size, names.toSet().size)
        assertTrue("the live configuration is not among the arms", "E_full" in names)
        assertEquals(7, names.size)
        }

    @Test
    fun anArmThatNeverCapturedHasNoOpinionOnFreshness() {
        begin(CaptureReason.POST_ACTION_CHANGE, ExpectedChange.VIEWPOINT)
        frames(500)

        // Null, not false: nothing was submitted, so there is no reply to judge
        // and the row must not read as a rejection.
        for ((arm, verdict) in arms.freshness(now))
        assertNull("$arm judged a reply it never asked for", verdict)
        }

    @Test
    fun stoppingTheSessionStopsEveryArm() {
        begin()
        frames(800)
        arms.stop()
        live.stop()

        val before=armCaptures.values.sumOf { it.size }
        frames(2000)

        assertEquals("an arm kept capturing after the session stopped",
            before, armCaptures.values.sumOf { it.size })
        }
    }

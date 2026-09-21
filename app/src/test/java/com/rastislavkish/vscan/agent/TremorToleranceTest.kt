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

import kotlin.math.floor

import org.junit.Assert.*
import org.junit.Test

/**
* The tremor mechanism, and the two things it must not cost.
*
* Every claim here is paired: a trembling hand gets its photograph, and the
* same code still refuses a package being turned over and a hand that is going
* somewhere. A tolerance that only ever said yes would pass the first half of
* this file, and is exactly what widening the matcher's box would have bought.
*/
class TremorToleranceTest {

    // ---------------------------------------------------------------- baseline

    private fun baseline(rms: List<Double>, stepNs: Long=50_000_000L): TremorBaseline {
        val result=TremorBaseline()
        var now=0L
        for (value in rms) {
            now+=stepNs
            result.add(now, value)
            }
        return result
        }

    @Test fun anUncalibratedBaselineIsTheFixedLimit() {
        assertEquals(HandheldViewMatcher.QUIET_GYRO_LIMIT, TremorBaseline().quietLimit, 1e-9)
        assertEquals(HandheldViewMatcher.QUIET_GYRO_LIMIT,
            baseline(List(6) { 0.9 }).quietLimit, 1e-9)
        }

    @Test fun aSteadyHandIsMeasuredExactlyAsItWasBefore() {
        // The whole point of the floor: nothing about a steady user's sessions
        // changes, so the mechanism cannot regress them.
        val calm=baseline(List(80) { 0.02 })
        assertTrue(calm.calibrated)
        assertEquals(HandheldViewMatcher.QUIET_GYRO_LIMIT, calm.quietLimit, 1e-9)
        }

    @Test fun aHandThatNeverReachesTheFixedLimitGetsOneItCanReach() {
        // A hand whose calm sits at 0.7 rad/s is quiet under the fixed limit
        // only by a hair, and any peak of the tremor loses it.
        val shaky=baseline(List(80) { if (it%2==0) 0.70 else 1.40 })
        assertTrue(shaky.calibrated)
        assertTrue("the limit must relax for this hand",
            shaky.quietLimit>HandheldViewMatcher.QUIET_GYRO_LIMIT)
        assertTrue("and never past the point where the frame is smeared anyway",
            shaky.quietLimit<=TremorBaseline.CEILING)
        }

    @Test fun theRelaxationIsBoundedHoweverBadlyTheHandShakes() {
        assertEquals(TremorBaseline.CEILING, baseline(List(80) { 6.0 }).quietLimit, 1e-9)
        }

    @Test fun aTroughIsToldFromAPeakButNoEvidenceNeverHoldsUpACapture() {
        assertTrue("nothing measured yet must not block the shutter",
            TremorBaseline().atTrough(9.0))

        val cycling=baseline(List(80) { if (it%2==0) 0.20 else 1.20 })
        assertTrue(cycling.atTrough(0.20))
        assertFalse(cycling.atTrough(1.20))
        }

    // ------------------------------------------------------------- steadiness

    private fun feed(window: SteadinessWindow, residuals: List<Double>): Steadiness {
        var now=0L
        var verdict=Steadiness.UNKNOWN
        for (residual in residuals) {
            now+=100_000_000L
            verdict=window.add(now, ViewComparison(ViewRelation.UNKNOWN, residual, 12, 0))
            }
        return verdict
        }

    @Test fun aWindowWithTooLittleInItClaimsNothing() {
        assertEquals(Steadiness.UNKNOWN, feed(SteadinessWindow(), listOf(0.02, 0.40, 0.03)))
        }

    @Test fun wobblingAboutOnePlaceReadsAsOscillating() {
        // Away, home, away, home: the residual against a pinned view keeps
        // falling back to where it started.
        assertEquals(Steadiness.OSCILLATING,
            feed(SteadinessWindow(), (0 until 14).map { if (it%2==0) 0.45 else 0.03 }))
        }

    @Test fun goingSomewhereReadsAsDrifting() {
        // It matched at the start and does not any more, which is the one thing
        // an oscillation never does.
        assertEquals(Steadiness.DRIFTING, feed(SteadinessWindow(),
            listOf(0.02, 0.05, 0.09, 0.16, 0.24, 0.33, 0.44, 0.55, 0.66, 0.77)))
        }

    @Test fun aReferencePinnedOnNothingUsableIsGivenUpRatherThanHeldForever() {
        // Nothing ever matched it, so it is not evidence of movement either.
        // DRIFTING is how the caller is told to pin a fresh one.
        assertEquals(Steadiness.DRIFTING, feed(SteadinessWindow(), List(24) { 0.80 }))
        }

    @Test fun unmeasurableFramesAreDroppedRatherThanCountedAsMovement() {
        // A blurred frame carries no residual, only ViewComparison's default.
        // Entered as a large one it would read as the view having left.
        val window=SteadinessWindow()
        var now=0L
        repeat(15) { i ->
            now+=100_000_000L
            window.add(now, if (i%3==2) ViewComparison(ViewRelation.UNKNOWN, 1.0, 1, 0)
            else ViewComparison(ViewRelation.UNKNOWN, if (i%2==0) 0.40 else 0.03, 12, 0))
            }
        assertEquals(Steadiness.OSCILLATING, window.add(now+100_000_000L,
            ViewComparison(ViewRelation.UNKNOWN, 0.03, 12, 0)))
        }

    // ------------------------------------------------------------------ holds

    private class Gate(grace: Long=150_000_000L) {
        val controller=EventCaptureController(quietGraceNs=grace)
        var now=0L

        fun start() {
            controller.start("tremor")
            controller.arm(CaptureReason.INITIAL_OBSERVATION, 1, null, now)
            }

        /**
        * [oscillating] defaults true because these tests are a tremulous hand,
        * which is what the steadiness window reports about one. The tolerance
        * is licensed by that verdict and not by the mere shortness of a break -
        * see the drift case below for why it has to be.
        */
        fun run(ms: Int, quiet: Boolean=true, reliable: Boolean=true,
            change: ViewRelation=ViewRelation.SAME, oscillating: Boolean=true) {
            repeat(ms/50) {
                now+=50_000_000L
                controller.sample(now, change, quiet, reliable, oscillating=oscillating)
                }
            }

        /**
        * A break every [everyMs], as a hand that will not quite settle.
        *
        * Each round ends on quiet, because an absorbed break is deliberately
        * never the instant the gate fires on - that is its own test below - so
        * a stream ending mid-break would be asking for the wrong thing.
        */
        fun flutter(ms: Int, everyMs: Int, breakMs: Int, reliable: Boolean=true) {
            var spent=0
            while (spent<ms) {
                run(breakMs, quiet=false, reliable=reliable)
                run(everyMs)
                spent+=everyMs+breakMs
                }
            }
        }

    @Test fun aHoldSurvivesTheBriefBreaksAHandWithATremorPutsInIt() {
        val gate=Gate()
        gate.start()
        gate.flutter(1200, everyMs=250, breakMs=100)
        assertNotNull("a tremulous hold must still complete",
            gate.controller.reserve(true, gate.now))
        }

    @Test fun theSameStreamUnderTheAblationStillStalls() {
        // E_no_tremor_tolerance. If this ever passed, the mechanism would be
        // being measured against a copy of itself.
        val gate=Gate(grace=0L)
        gate.start()
        gate.flutter(1200, everyMs=250, breakMs=100)
        assertNull(gate.controller.reserve(true, gate.now))
        }

    @Test fun anUnmeasurableFlickerIsAbsorbedTheSameWay() {
        // Blur is how a tremor reads when it costs a frame its texture.
        val gate=Gate()
        gate.start()
        gate.flutter(1200, everyMs=250, breakMs=100, reliable=false)
        assertNotNull(gate.controller.reserve(true, gate.now))
        }

    @Test fun aSustainedBreakStillRestartsTheHold() {
        val gate=Gate()
        gate.start()
        gate.run(500)
        gate.run(400, quiet=false)
        gate.run(400)
        assertNull("400ms of movement is not a tremor blip",
            gate.controller.reserve(true, gate.now))
        }

    @Test fun aHandTheWindowWillNotCallOscillatingGetsNoTolerance() {
        // The conservative half, and it used to be written the wrong way round.
        //
        // The refusal was once keyed on the view reading DIFFERENT against the
        // instruction anchor, on the reasoning that a tremor wobbles in place.
        // But DIFFERENT is the correct, permanent state after a user completes
        // a movement that was asked of them, so the tolerance switched itself
        // off for the rest of every such turn - 52% of samples in the second
        // participant run, and nine of thirteen refused captures.
        //
        // The licence is positive evidence of a wobble instead.
        val gate=Gate()
        gate.start()
        gate.run(550, oscillating=false)
        gate.run(50, quiet=false, oscillating=false)
        gate.run(100, oscillating=false)
        assertNull(gate.controller.reserve(true, gate.now))
        }

    @Test fun aBreakAfterACompletedMovementIsStillAbsorbed() {
        // The regression the rewrite above fixes, kept as its own case: the
        // view is DIFFERENT from the instruction anchor for the whole turn
        // because the user did what was asked, and the hand still gets its
        // tolerance.
        val gate=Gate()
        gate.start()
        gate.run(550, change=ViewRelation.DIFFERENT)
        gate.run(50, quiet=false, change=ViewRelation.DIFFERENT)
        gate.run(100, change=ViewRelation.DIFFERENT)
        assertNotNull("a completed movement must not disable the tolerance",
            gate.controller.reserve(true, gate.now))
        }

    @Test fun aSlowDriftIsNotAbsorbedIntoAHold() {
        // Why the licence cannot be duration alone. A view creeping a few
        // pixels a frame reads unquiet about one sample in three, every break
        // short enough to absorb and the total just inside the budget. Without
        // the window's verdict this accumulates a hold and photographs a moving
        // package.
        val gate=Gate()
        gate.start()
        repeat(40) {
            gate.run(100, oscillating=false)
            gate.run(50, quiet=false, oscillating=false)
            }
        assertNull(gate.controller.reserve(true, gate.now))
        }

    @Test fun aStreamThatIsBrokenHalfTheTimeNeverCountsAsAHold() {
        // Each break is inside the grace; together they are not a quiet hold,
        // and without the running total this would pass forever.
        val gate=Gate()
        gate.start()
        gate.flutter(4000, everyMs=100, breakMs=100)
        assertNull(gate.controller.reserve(true, gate.now))
        }

    @Test fun anAbsorbedBreakIsNeverItselfTheInstantToFireOn() {
        val gate=Gate()
        gate.start()
        gate.run(800)
        assertEquals(EventPhase.READY, gate.controller.phase)
        gate.run(50, quiet=false)
        assertEquals("a blip holds at settling rather than firing",
            EventPhase.SETTLING, gate.controller.phase)
        assertNull(gate.controller.reserve(true, gate.now))
        gate.run(50)
        assertEquals(EventPhase.READY, gate.controller.phase)
        }

    // ------------------------------------------------------------- end to end

    private val width=320
    private val height=240

    /** The renderer from HandheldViewMatcherTest, for scenes it can compare. */
    private fun texture(x: Double, y: Double): Double {
        val gx=x/9.0
        val gy=y/9.0
        val ix=floor(gx).toInt()
        val iy=floor(gy).toInt()
        val fx=gx-ix
        val fy=gy-iy
        fun point(a: Int, b: Int): Double {
            var hash=a*73856093 xor (b*19349663) xor 83492791
            hash=hash xor (hash ushr 13)
            hash*=1274126177
            return 25.0+(hash ushr 16 and 255)*0.80
            }
        return (point(ix, iy)*(1-fx)+point(ix+1, iy)*fx)*(1-fy)+
            (point(ix, iy+1)*(1-fx)+point(ix+1, iy+1)*fx)*fy
        }

    /** The whole frame moved by [shift], which is the phone moving, not the package. */
    private fun scene(shift: Double): ByteArray = ByteArray(width*height) { index ->
        texture(index%width-shift, (index/width).toDouble()).toInt().coerceIn(0, 255).toByte()
        }

    /**
    * The monitor's decision, reproduced on the JVM.
    *
    * Mirrors what StabilityMonitor.process does with a measurement, so this
    * exercises the composition rather than any one piece of it: the matcher,
    * the window, the re-pin, and the gate.
    */
    private inner class Session(private val tremorTolerant: Boolean) {
        val tracker=HandheldViewTracker()
        val window=SteadinessWindow()
        val gate=EventCaptureController()
        var now=0L
        var everOscillated=false
        var everDrifted=false

        /**
        * StabilityMonitor.tremulous, latched the same way.
        *
        * Worth mirroring rather than assuming, because two things downstream
        * now act on it - the camera binds a different capture use case, and the
        * planner is allowed to ask for a brace - and both of those are wrong for
        * a hand that never needed the tolerance.
        */
        var tremulous=false

        fun open() {
            gate.start("handheld")
            gate.arm(CaptureReason.INITIAL_OBSERVATION, 1, null, now)
            feed(scene(0.0))
            tracker.arm()
            }

        fun feed(bytes: ByteArray) {
            now+=100_000_000L
            val measured=HandheldViewTracker.measure(tracker.snapshot(),
                ViewFingerprint.from(bytes, width, height))
            if (!tracker.accept(measured))
            return

            val steadiness=window.add(now, measured.tremor)
            if (steadiness==Steadiness.DRIFTING) {
                everDrifted=true
                tracker.repinTremor()
                window.clear()
                }
            if (steadiness==Steadiness.OSCILLATING)
            everOscillated=true

            val matched=HandheldViewMatcher.quiet(measured.settling, 0.25)
            val quiet=matched||(tremorTolerant&&steadiness==Steadiness.OSCILLATING)
            val reliable=measured.settling.relation!=ViewRelation.UNKNOWN||
                (tremorTolerant&&steadiness==Steadiness.OSCILLATING)

            if (quiet&&!matched)
            tremulous=true

            gate.sample(now, measured.instruction.relation, quiet, reliable,
                measured.submitted.relation)
            }

        /** A wobble of 14 pixels: well outside the matcher's two-thumbnail-pixel box. */
        fun tremble(frames: Int) {
            repeat(frames) { i -> feed(scene(if (i%2==0) 0.0 else 14.0)) }
            }

        /** A hand the matcher can register on its own, frame after frame. */
        fun hold(frames: Int) {
            repeat(frames) { i -> feed(scene(if (i%2==0) 0.0 else 2.0)) }
            }
        }

    @Test fun aTremorTooLargeForTheMatcherStillReachesACapture() {
        val session=Session(tremorTolerant=true)
        session.open()
        session.tremble(30)

        assertTrue("the wobble must be recognised as one", session.everOscillated)
        assertNotNull("a trembling hand must get its photograph",
            session.gate.reserve(true, session.now))
        }

    @Test fun theSameTremorIsWhatTheMatcherAloneCannotHandle() {
        // The failure this exists to fix, kept as a test so it cannot quietly
        // stop being one: identical frames, tolerance off, no capture ever.
        val session=Session(tremorTolerant=false)
        session.open()
        session.tremble(30)

        assertNull(session.gate.reserve(true, session.now))
        }

    @Test fun theTremorVerdictIsLatchedOnlyWhereTheToleranceWasNeeded() {
        val trembling=Session(tremorTolerant=true)
        trembling.open()
        trembling.tremble(30)
        assertTrue("a hand the matcher could not register must be reported",
            trembling.tremulous)

        // And the half that keeps the capture path honest. A hand the matcher
        // handles on its own gets the quality capture mode and no burst, so
        // this must not latch on a small, registrable jitter.
        val steady=Session(tremorTolerant=true)
        steady.open()
        steady.hold(30)
        assertFalse("a steady hand must not be photographed as a trembling one",
            steady.tremulous)
        assertNotNull("and must still reach a capture of its own",
            steady.gate.reserve(true, steady.now))
        }

    @Test fun aHandThatIsGoingSomewhereIsNotATremor() {
        // Three pixels a frame, which no single comparison would call movement.
        // Against a pinned view it never comes home, and that is the difference.
        val session=Session(tremorTolerant=true)
        session.open()
        repeat(30) { i -> session.feed(scene(3.0*(i+1))) }

        assertTrue("a view that keeps leaving must be given up on", session.everDrifted)
        assertFalse("drift must never read as wobbling in place", session.everOscillated)
        assertNull(session.gate.reserve(true, session.now))
        }
    }

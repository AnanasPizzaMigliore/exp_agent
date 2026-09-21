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

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class HandheldViewMatcherTest {
    private val width=320
    private val height=240

    private fun texture(x: Double, y: Double, seed: Int): Double {
        val gx=x/9.0
        val gy=y/9.0
        val ix=floor(gx).toInt()
        val iy=floor(gy).toInt()
        val fx=gx-ix
        val fy=gy-iy
        fun point(a: Int, b: Int): Double {
            var hash=a*73856093 xor (b*19349663) xor (seed*83492791)
            hash=hash xor (hash ushr 13)
            hash*=1274126177
            return 25.0+(hash ushr 16 and 255)*0.80
            }
        return (point(ix, iy)*(1-fx)+point(ix+1, iy)*fx)*(1-fy)+
            (point(ix, iy+1)*(1-fx)+point(ix+1, iy+1)*fx)*fy
        }

    /** Render independent camera/background and package transformations. */
    private fun scene(dx: Double=0.0, dy: Double=0.0, angle: Double=0.0, scale: Double=1.0,
        productDx: Double=dx, productDy: Double=dy, seed: Int=1,
        brightness: Double=0.0, contrast: Double=1.0): ByteArray {
        val c=cos(Math.toRadians(angle))
        val s=sin(Math.toRadians(angle))
        return ByteArray(width*height) { index ->
            val x=index%width-(width-1)/2.0
            val y=index/width-(height-1)/2.0
            val px=(c*(x-productDx)+s*(y-productDy))/scale+(width-1)/2.0
            val py=(-s*(x-productDx)+c*(y-productDy))/scale+(height-1)/2.0
            val value=if (px in 72.0..247.0&&py in 42.0..197.0) texture(px, py, seed)
            else texture(c*(x-dx)+s*(y-dy), -s*(x-dx)+c*(y-dy), 23)
            (value*contrast+brightness).toInt().coerceIn(0, 255).toByte()
            }
        }

    private fun fingerprint(bytes: ByteArray): ViewFingerprint = ViewFingerprint.from(bytes, width, height)
    private fun match(a: ByteArray, b: ByteArray): ViewComparison = HandheldViewMatcher.compare(fingerprint(a), fingerprint(b))
    private fun observe(tracker: HandheldViewTracker, bytes: ByteArray): HandheldViewTracker.Measurement {
        val result=HandheldViewTracker.measure(tracker.snapshot(), fingerprint(bytes))
        assertTrue(tracker.accept(result))
        return result
        }

    @Test fun unchangedViewMatches() {
        assertEquals(ViewRelation.SAME, match(scene(), scene()).relation)
        }

    @Test fun smallTranslationAndRotationAreTolerated() {
        val result=match(scene(), scene(dx=3.0, dy=-2.0, angle=1.5))
        assertEquals(result.toString(), ViewRelation.SAME, result.relation)
        }

    @Test fun independentlyTremblingPhoneAndPackageAreTolerated() {
        val result=match(scene(), scene(dx=3.0, dy=-2.0, productDx=-3.0, productDy=2.0))
        assertEquals(result.toString(), ViewRelation.SAME, result.relation)
        }

    @Test fun modestExposureChangeDoesNotProveMovement() {
        val result=match(scene(), scene(brightness=12.0, contrast=0.90))
        assertEquals(result.toString(), ViewRelation.SAME, result.relation)
        }

    @Test fun oppositeTremorExtremesMatchEachOther() {
        val result=match(scene(dx=2.0, productDx=-2.0, angle=0.5),
            scene(dx=-2.0, productDx=2.0, angle=-0.5))
        assertEquals(result.toString(), ViewRelation.SAME, result.relation)
        }

    @Test fun substantialPackageTranslationRemainsVisibleAgainstUnchangedBackground() {
        val result=match(scene(), scene(productDx=32.0))
        assertEquals(result.toString(), ViewRelation.DIFFERENT, result.relation)
        }

    @Test fun meaningfulPackageScaleChangeIsNotAlignedAway() {
        val result=match(scene(), scene(scale=1.30))
        assertEquals(result.toString(), ViewRelation.DIFFERENT, result.relation)
        }

    @Test fun differentPackageFaceIsDetectedDespiteMatchingBackground() {
        val result=match(scene(), scene(seed=57))
        assertEquals(result.toString(), ViewRelation.DIFFERENT, result.relation)
        }

    @Test fun blankOrIncompatibleViewsAreUnknown() {
        val blank=ByteArray(width*height) { 120 }
        assertEquals(ViewRelation.UNKNOWN, match(blank, blank).relation)
        assertEquals(ViewRelation.UNKNOWN, HandheldViewMatcher.compare(null, fingerprint(scene())).relation)
        val small=ViewFingerprint.from(ByteArray(20*20), 20, 20)
        assertEquals(ViewRelation.UNKNOWN, HandheldViewMatcher.compare(small, fingerprint(scene())).relation)
        }

    @Test fun fingerprintOwnsCameraBytesAndRespectsRowStride() {
        val bytes=scene()
        val padded=ByteArray((width+8)*height)
        for (y in 0 until height) bytes.copyInto(padded, y*(width+8), y*width, (y+1)*width)
        val reference=ViewFingerprint.from(padded, width, height, width+8)
        padded.fill(0)
        assertEquals(ViewRelation.SAME, HandheldViewMatcher.compare(reference, fingerprint(bytes)).relation)
        }

    @Test fun continuousTwoHandJitterAllowsFirstCaptureButNotAnotherActionCapture() {
        val tracker=HandheldViewTracker()
        val gate=EventCaptureController()
        var now=0L
        gate.start("handheld")
        gate.arm(CaptureReason.INITIAL_OBSERVATION, 1, null, now)
        observe(tracker, scene())
        tracker.arm()
        fun jitter(count: Int) {
            repeat(count) { i ->
                now+=100_000_000L
                val sign=if (i%2==0) 1.0 else -1.0
                val measured=observe(tracker, scene(dx=2.0*sign, productDx=-2.0*sign, angle=0.5*sign))
                val quiet=HandheldViewMatcher.quiet(measured.settling, gyroRms=0.25)
                gate.sample(now, measured.instruction.relation, quiet,
                    measured.settling.relation!=ViewRelation.UNKNOWN, measured.submitted.relation)
                }
            }
        jitter(25)
        val first=requireNotNull(gate.reserve(true, now))
        assertTrue(gate.acceptCapture(first, now))
        tracker.capture()
        jitter(20)
        assertTrue(gate.responseIsFresh(first, now))
        gate.release(first)
        tracker.release()
        gate.arm(CaptureReason.POST_ACTION_CHANGE, 2, ExpectedChange.VIEWPOINT, now)
        tracker.arm()
        jitter(40)
        assertEquals(0L, gate.eventId)
        assertNull(gate.reserve(true, now))
        }

    @Test fun slowDeliberateMotionAccumulatesAgainstFixedInstructionAndSettlingAnchors() {
        val tracker=HandheldViewTracker()
        observe(tracker, scene())
        tracker.arm()
        var changed=false
        var interruptedSettling=false
        for (shift in 1..40) {
            val measured=observe(tracker, scene(productDx=shift.toDouble()))
            if (measured.instruction.relation==ViewRelation.DIFFERENT) changed=true
            if (shift>2&&measured.settling.relation!=ViewRelation.SAME) interruptedSettling=true
            }
        assertTrue("One-pixel steps must eventually count as a changed view", changed)
        assertTrue("A stable anchor must not roll forward on every tiny step", interruptedSettling)
        }

    @Test fun restoredViewMatchesSubmittedReferenceNotTheLastMovingFrame() {
        val tracker=HandheldViewTracker()
        observe(tracker, scene())
        tracker.capture()
        assertEquals(ViewRelation.DIFFERENT, observe(tracker, scene(seed=57)).submitted.relation)
        assertEquals(ViewRelation.SAME, observe(tracker, scene(dx=2.0, productDx=-2.0)).submitted.relation)
        }

    @Test fun deliberatePackageMovementSettlesIntoOneCaptureThenTracksCurrentResponseView() {
        val tracker=HandheldViewTracker()
        val gate=EventCaptureController()
        var now=0L
        gate.start("package-motion")
        gate.arm(CaptureReason.POST_ACTION_CHANGE, 1, ExpectedChange.POSITION, now)
        observe(tracker, scene())
        tracker.arm()
        fun feed(bytes: ByteArray, count: Int) {
            repeat(count) {
                now+=100_000_000L
                val measured=observe(tracker, bytes)
                gate.sample(now, measured.instruction.relation,
                    HandheldViewMatcher.quiet(measured.settling, 0.25),
                    measured.settling.relation!=ViewRelation.UNKNOWN, measured.submitted.relation)
                }
            }
        feed(scene(), 12)
        gate.noteStableFrame(17, now)
        assertNull(gate.reserve(true, now))
        feed(scene(productDx=32.0), 14)
        val ticket=requireNotNull(gate.reserve(true, now))
        assertEquals(17L, ticket.beforeFrameId)
        assertNull(gate.reserve(true, now))
        assertTrue(gate.acceptCapture(ticket, now))
        tracker.capture()
        feed(scene(seed=57), 12)
        assertFalse(gate.responseIsFresh(ticket, now))
        feed(scene(productDx=32.0), 12)
        assertTrue(gate.responseIsFresh(ticket, now))
        }

    @Test fun measurementsFromBeforeAnInstructionOrCaptureTransitionAreDiscarded() {
        val tracker=HandheldViewTracker()
        observe(tracker, scene())
        var work=HandheldViewTracker.measure(tracker.snapshot(), fingerprint(scene(seed=57)))
        tracker.arm()
        assertFalse(tracker.accept(work))
        work=HandheldViewTracker.measure(tracker.snapshot(), fingerprint(scene(seed=57)))
        tracker.capture()
        assertFalse(tracker.accept(work))
        work=HandheldViewTracker.measure(tracker.snapshot(), fingerprint(scene(seed=57)))
        tracker.clear()
        assertFalse(tracker.accept(work))
        assertNull(tracker.snapshot().instruction)
        assertNull(tracker.snapshot().submitted)
        }

    @Test fun largeGyroMotionOnlyVetoesCaptureQuality() {
        val comparison=match(scene(), scene())
        assertTrue(HandheldViewMatcher.quiet(comparison, 0.25))
        assertFalse(HandheldViewMatcher.quiet(comparison, 1.20))
        assertEquals(ViewRelation.SAME, comparison.relation)
        }
    }

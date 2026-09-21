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

import org.junit.Assert.assertTrue
import org.junit.Test

/**
* The claims the stability design rests on, checked rather than asserted in a
* comment.
*
* Each test here corresponds to a specific reason the obvious implementation was
* rejected: tile-max over global mean, mean-removed differencing over raw pixel
* difference, region max over frame average, and unknown as a state distinct
* from zero.
*/
class FrameAnalysisTest {

    private val w=320
    private val h=240

    private fun blank(value: Int=128) = ByteArray(w*h) { value.toByte() }

    /** Fine checkerboard over the whole frame: sharp everywhere. */
    private fun sharp(): ByteArray = ByteArray(w*h) { i ->
        val x=i%w
        val y=i/w
        (if ((x/2+y/2)%2==0) 40 else 210).toByte()
        }

    /** The same pattern smeared, as a moving hand would leave it. */
    private fun blurred(): ByteArray {
        val source=sharp()
        val out=ByteArray(w*h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum=0
            var n=0
            for (dx in -3..3) {
                val sx=x+dx
                if (sx<0||sx>=w) continue
                sum+=source[y*w+sx].toInt() and 0xff
                n++
                }
            out[y*w+x]=(sum/n).toByte()
            }
        return out
        }

    /** A plain lid with one small patch of fine print on it. */
    private fun plainWithSmallDetail(): ByteArray {
        val out=blank(200)
        for (y in 100 until 130) for (x in 150 until 190) {
            out[y*w+x]=(if ((x/2+y/2)%2==0) 60 else 220).toByte()
            }
        return out
        }

    // ------------------------------------------------------------------
    // Sharpness
    // ------------------------------------------------------------------

    @Test
    fun blurIsSeparatedFromSharpness() {
        val a=FrameQuality.estimate(sharp(), w, h).sharpnessTileMax
        val b=FrameQuality.estimate(blurred(), w, h).sharpnessTileMax

        assertTrue("sharp $a should far exceed blurred $b", a>b*4)
        }

    @Test
    fun aSmallSharpRegionSurvivesTheGlobalAverage() {
        // The plain-lid case, and the reason this reports tile MAX rather than a
        // frame average. A date code occupies a tiny part of the frame; averaged
        // over a blank lid it would vanish, and the sharpest real frames would
        // score as blurred.
        val q=FrameQuality.estimate(plainWithSmallDetail(), w, h)

        assertTrue("max ${q.sharpnessTileMax} should stand well above median ${q.sharpnessTileMedian}",
            q.sharpnessTileMax>q.sharpnessTileMedian*10)
        }

    @Test
    fun darknessIsFlaggedRatherThanTreatedAsBlur() {
        // Low light also depresses the Laplacian. It gets its own flag because
        // the remedy differs: "hold still" would send the user to do the wrong
        // thing entirely.
        assertTrue(FrameQuality.estimate(blank(8), w, h).exposureSuspect)
        assertTrue(!FrameQuality.estimate(sharp(), w, h).exposureSuspect)
        }

    // ------------------------------------------------------------------
    // Motion
    // ------------------------------------------------------------------

    @Test
    fun identicalFramesReportNoMotion() {
        val f=sharp()
        val m=FrameMotion.between(f, f, w, h, elapsedSeconds=0.1)

        assertTrue("got ${m.regionMax}", m.regionMax<0.01)
        assertTrue(!m.unknown)
        }

    @Test
    fun aShiftedViewReportsMotion() {
        val a=sharp()
        val b=ByteArray(w*h)
        for (y in 0 until h) for (x in 0 until w) {
            b[y*w+x]=a[y*w+((x+6)%w)]
            }

        val m=FrameMotion.between(a, b, w, h, elapsedSeconds=0.1)

        assertTrue("got ${m.regionMax}", m.regionMax>0.3)
        }

    @Test
    fun aUniformBrightnessChangeIsNotMotion() {
        // An aisle light, a cloud, the flashlight coming on. Raw pixel
        // differencing would call all of these movement and hold the gate shut
        // while the user stood perfectly still.
        val a=sharp()
        val b=ByteArray(w*h) { i -> ((a[i].toInt() and 0xff)+30).coerceAtMost(255).toByte() }

        val m=FrameMotion.between(a, b, w, h, elapsedSeconds=0.1)

        assertTrue("brightness shift read as motion: ${m.regionMax}", m.regionMax<0.15)
        }

    @Test
    fun movementInOneRegionIsNotAveragedAway() {
        // The user holds the phone still and turns the package with their other
        // hand. Most of the frame is unchanged; a whole-frame average would
        // report stillness straight through the only thing that moved.
        val a=sharp()
        val b=a.copyOf()
        for (y in 40 until 80) for (x in 40 until 80) {
            b[y*w+x]=(255-(a[y*w+x].toInt() and 0xff)).toByte()
            }

        val m=FrameMotion.between(a, b, w, h, elapsedSeconds=0.1)

        assertTrue("max ${m.regionMax} should exceed median ${m.regionMedian}",
            m.regionMax>m.regionMedian*3)
        assertTrue(m.regionMax>0.2)
        }

    @Test
    fun aTexturelessViewIsUnknownNotStill() {
        // A blank wall differences to zero and looks perfectly still. Reporting
        // that as stability would open the gate on a frame in which nothing
        // could be measured at all.
        val a=blank()
        val m=FrameMotion.between(a, a, w, h, elapsedSeconds=0.1)

        assertTrue("reliability ${m.reliability}", m.unknown)
        }
    }

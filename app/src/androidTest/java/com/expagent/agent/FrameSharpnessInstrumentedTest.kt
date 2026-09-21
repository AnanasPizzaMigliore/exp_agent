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

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
* Choosing between frames of one burst.
*
* Instrumented rather than a JVM test because the ranking runs through
* BitmapFactory, and a fake of that would be testing the fake. What is checked
* is only what the burst relies on: that a smeared frame ranks below a crisp one
* of the same scene, and that nothing here can lose a frame.
*/
@RunWith(AndroidJUnit4::class)
class FrameSharpnessInstrumentedTest {

    private val width=240
    private val height=180

    /**
    * Fine vertical stripes, optionally smeared horizontally.
    *
    * A date code is high-frequency detail in a small part of the frame, and a
    * horizontal smear is what a hand moving during the exposure does to it.
    * [blur] is the number of columns averaged together, so 1 is a sharp frame.
    */
    private fun stripes(blur: Int): ByteArray {
        val bitmap=Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until height) for (x in 0 until width) {
                var sum=0
                for (offset in 0 until blur)
                sum+=if (((x+offset)/2)%2==0) 235 else 20
                val value=sum/blur
                bitmap.setPixel(x, y, (0xff shl 24) or (value shl 16) or (value shl 8) or value)
                }

            val out=ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            return out.toByteArray()
            }
        finally { bitmap.recycle() }
        }

    @Test fun aSmearedFrameScoresBelowASharpOneOfTheSameScene() {
        val sharp=FrameSharpness.estimate(stripes(1))!!
        val smeared=FrameSharpness.estimate(stripes(4))!!

        assertTrue(
            "sharp ${sharp.sharpnessTileMax} should beat smeared ${smeared.sharpnessTileMax}",
            sharp.sharpnessTileMax>smeared.sharpnessTileMax,
            )
        }

    @Test fun theSharpestOfABurstIsTheOneChosen() {
        assertEquals(1, FrameSharpness.sharpest(listOf(stripes(4), stripes(1), stripes(6))))
        assertEquals(2, FrameSharpness.sharpest(listOf(stripes(6), stripes(5), stripes(1))))
        }

    @Test fun oneUndecodableFrameDoesNotLoseTheBurst() {
        val chosen=FrameSharpness.sharpest(listOf(ByteArray(12), stripes(1), stripes(5)))

        assertEquals(1, chosen)
        }

    @Test fun aBurstOfNothingUsableStillReturnsAFrameToSend() {
        // Falls back to the first, which is the frame a single shot would have
        // produced. Taking a burst must never be worse than not taking one.
        assertEquals(0, FrameSharpness.sharpest(listOf(ByteArray(12), ByteArray(9))))

        // Nothing at all is the one case with no frame to return.
        assertNull(FrameSharpness.sharpest(listOf()))
        }
    }

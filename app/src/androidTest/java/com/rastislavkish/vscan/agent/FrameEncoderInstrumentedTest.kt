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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrameEncoderInstrumentedTest {
    private fun input(): ByteArray {
        val bitmap=Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 40) for (x in 0 until 80)
            bitmap.setPixel(x, y, if (x<40) Color.RED else Color.BLUE)
            val out=ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            return out.toByteArray()
            }
        finally { bitmap.recycle() }
        }

    @Test fun cameraRotationIsAppliedEvenWhenNoDownscaleIsNeeded() {
        val bytes=Base64.getDecoder().decode(FrameEncoder.encodeScaled(input(), 128, 90))
        val bitmap=BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        try {
            assertEquals(40, bitmap.width)
            assertEquals(80, bitmap.height)
            assertTrue(Color.red(bitmap.getPixel(20, 10))>200)
            assertTrue(Color.blue(bitmap.getPixel(20, 70))>200)
            }
        finally { bitmap.recycle() }
        }

    @Test fun downscaleKeepsTheRequestedLongEdgeAfterRotation() {
        val bytes=Base64.getDecoder().decode(FrameEncoder.encodeScaled(input(), 40, 90))
        val bitmap=BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        try {
            assertEquals(20, bitmap.width)
            assertEquals(40, bitmap.height)
            }
        finally { bitmap.recycle() }
        }
    }

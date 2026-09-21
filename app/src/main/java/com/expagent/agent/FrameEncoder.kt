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
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
* Turns captured JPEG bytes into what each call actually needs.
*
* This ran on the laptop with PIL. On the phone it is the one piece of the port
* that costs real time and memory, so it does as little as possible: the
* perception frame is already the right size coming out of CameraX and is passed
* through untouched.
*/
object FrameEncoder {

    /**
    * Ceiling for the frame the perception call reads.
    *
    * Measured, not guessed. Against a synthetic lid with a small, low-contrast
    * code, perception latency and accuracy went:
    *
    *     1568px  74K  6.5s  2638 prompt tokens  read correctly
    *     1152px  45K  3.7s  1630 prompt tokens  read correctly
    *      768px  24K  3.7s   910 prompt tokens  read correctly
    *      512px  13K  3.6s   590 prompt tokens  read "10 MAR" for "16 MAR"
    *
    * Nearly all the speed is in the first step down; below ~1150 latency stops
    * improving while the margin before a misread quietly disappears. 512 is the
    * warning: it did not fail loudly, it returned a confident wrong day, which
    * is the one outcome a blind user cannot catch.
    *
    * So: take the free half, and keep three times the margin over the size that
    * broke. This only ever shrinks a larger frame - it never upscales or crops.
    */
    const val PERCEPTION_MAX_EDGE=1152

    /**
    * Verification only has to spot gross layout change - a lid where a side
    * panel was - not read glyphs, so it gets a much smaller pair of images.
    * On a metered mobile connection this is the difference between a turn that
    * costs one large upload and one that costs three.
    */
    const val VERIFY_MAX_EDGE=768

    fun encode(jpeg: ByteArray): String =
    Base64.getEncoder().encodeToString(jpeg)

    /** Base64 of the frame, bounded to [maxEdge] on its long side. */
    fun encodeScaled(jpeg: ByteArray, maxEdge: Int, rotationDegrees: Int=0): String =
    encode(oriented(jpeg, maxEdge, rotationDegrees))

    /**
    * The frame as the model reads it: bounded to [maxEdge] and turned upright.
    *
    * The same bytes [encodeScaled] sends, handed back before base64 so that the
    * photograph written to the run directory can be the evidence the call was
    * actually made on. Saving the untouched CameraX buffer instead put the
    * still on disk in sensor orientation while its `_before` keyframe, which
    * CameraAnalysisFrame rotates, was upright - a pair the observation row
    * describes as comparable and which no viewer could lay side by side.
    */
    fun oriented(jpeg: ByteArray, maxEdge: Int, rotationDegrees: Int=0): ByteArray =
    scale(jpeg, maxEdge, rotationDegrees) ?: jpeg

    private fun scale(jpeg: ByteArray, maxEdge: Int, rotationDegrees: Int): ByteArray? {
        // Read the header only, so an oversized frame is never fully decoded
        // just to discover it needs shrinking.
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)

        val longest=maxOf(bounds.outWidth, bounds.outHeight)
        if (longest<=0||(longest<=maxEdge&&rotationDegrees==0))
        return null

        // inSampleSize halves in powers of two and is far cheaper than decoding
        // at full size; the exact scale is finished off below.
        val options=BitmapFactory.Options().apply {
            inSampleSize=Integer.highestOneBit(maxOf(1, longest/maxEdge))
            }

        val decoded=BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return null

        val current=maxOf(decoded.width, decoded.height)
        val bitmap=if (current>maxEdge) {
            val ratio=maxEdge.toFloat()/current
            val exact=Bitmap.createScaledBitmap(
                decoded,
                maxOf(1, Math.round(decoded.width*ratio)),
                maxOf(1, Math.round(decoded.height*ratio)),
                true,
                )
            if (exact!==decoded) decoded.recycle()
            exact
            }
        else decoded

        // CameraX ImageProxy pixels are not target-rotated. Normalise both still
        // sizes so they share the orientation of the pre-event analysis keyframe.
        val oriented=if (rotationDegrees==0) bitmap else Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true,
            )
        try {
            val out=ByteArrayOutputStream()
            check(oriented.compress(Bitmap.CompressFormat.JPEG, 90, out))
            return out.toByteArray()
            }
        finally {
            if (oriented!==bitmap) oriented.recycle()
            bitmap.recycle()
            }
        }
    }

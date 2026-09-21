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

/**
* How sharp one captured still is, for choosing between several of them.
*
* [FrameQuality] does the measuring and says of itself that it rejects nothing.
* That still holds: nothing here rejects a frame either. Every candidate this
* ranks was already taken, and the worst outcome available is that the sharpest
* of them is the one that gets sent - which is the frame that would have been
* sent anyway if the burst had been a single shot that happened to land well.
*
* Ranking is all that is claimed. The absolute figure is content-dependent, as
* [QualityEstimate.sharpnessTileMax] explains at length, so it says nothing
* about whether a date is readable. Between frames of the SAME package taken a
* few hundred milliseconds apart it is a fair comparison, because the content
* is held constant and motion blur is the only thing varying - which is exactly
* and only the question a burst asks.
*/
object FrameSharpness {

    /**
    * The size the comparison is made at.
    *
    * Motion blur is a low-pass, and halving the resolution of two frames does
    * not change which of them was blurred more - but decoding three 1536px
    * stills at full size, on the phone, in front of a waiting user, is a cost
    * with nothing to show for it. The ranking is what is needed, not the number.
    */
    const val MAX_EDGE=768

    /**
    * The sharpest of [candidates], or null when none of them could be decoded.
    *
    * Ties and failures both fall back to the first frame, which is the one a
    * single-shot capture would have produced. A burst can therefore never do
    * worse than not taking one.
    */
    fun sharpest(candidates: List<ByteArray>): Int? {
        if (candidates.isEmpty())
        return null

        var best: Int?=null
        var bestScore=Double.NEGATIVE_INFINITY

        for ((index, jpeg) in candidates.withIndex()) {
            val score=estimate(jpeg)?.sharpnessTileMax ?: continue

            if (score>bestScore) {
                bestScore=score
                best=index
                }
            }

        return best ?: 0
        }

    /** [FrameQuality] over a decoded still, or null if it could not be read. */
    fun estimate(jpeg: ByteArray): QualityEstimate? {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)

        val longest=maxOf(bounds.outWidth, bounds.outHeight)
        if (longest<=0)
        return null

        val options=BitmapFactory.Options().apply {
            inSampleSize=Integer.highestOneBit(maxOf(1, longest/MAX_EDGE))
            inPreferredConfig=Bitmap.Config.ARGB_8888
            }

        val bitmap=BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return null

        try {
            val width=bitmap.width
            val height=bitmap.height
            if (width<3||height<3)
            return null

            val pixels=IntArray(width*height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            // The same integer luma every YUV camera hands over, so the tile
            // statistics mean what they mean for an analysis frame.
            val luma=ByteArray(width*height) { index ->
                val pixel=pixels[index]
                val r=(pixel shr 16) and 0xff
                val g=(pixel shr 8) and 0xff
                val b=pixel and 0xff
                (((77*r+150*g+29*b) shr 8) and 0xff).toByte()
                }

            return FrameQuality.estimate(luma, width, height, width)
            }
        finally {
            bitmap.recycle()
            }
        }
    }

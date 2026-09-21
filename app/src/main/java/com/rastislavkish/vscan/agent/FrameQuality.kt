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

/**
* How usable one analysis frame looks, judged locally.
*
* Deliberately free of Android types: it takes a luminance plane as bytes so it
* can be tested on the JVM. Anything that needs a Bitmap or an ImageProxy would
* only be testable on a device, and thresholds that cannot be tested are
* thresholds nobody will dare change.
*
* NOTHING HERE REJECTS ANYTHING. These are measurements, fed to the log in
* shadow mode so that a threshold can later be chosen from evidence. A guessed
* sharpness cut-off would stall a blind user's session in a shop, which is a
* worse outcome than the round trip it saves.
*/
data class QualityEstimate(

    /**
    * Highest per-tile Laplacian variance in the frame.
    *
    * Max, not mean, and this is the whole point. Variance of Laplacian is
    * content-dependent: a plain white jar lid carrying one small dot-matrix code
    * has low high-frequency energy even in perfect focus, while a busy printed
    * label keeps a high figure even when smeared. A global average would
    * therefore reject the sharpest plain lids and accept blurred labels - it
    * would fail hardest on exactly the packages this app exists for.
    *
    * The question worth asking is not "is this frame sharp on average" but "is
    * any region of it resolving fine detail", because a date code is small.
    */
    val sharpnessTileMax: Double,

    /** Median tile, kept only so the log can show how unusual the max was. */
    val sharpnessTileMedian: Double,

    val meanLuma: Double,
    val darkFraction: Double,
    val brightFraction: Double,
    ) {

    /**
    * Too dark or too blown out for sharpness to mean anything.
    *
    * Recorded rather than acted on: low light also depresses the Laplacian, and
    * telling someone to hold still when the real problem is a dim shelf sends
    * them to do the wrong thing. The remedy differs, so the model decides.
    */
    val exposureSuspect: Boolean
    get() = meanLuma<40.0||meanLuma>215.0||darkFraction>0.6||brightFraction>0.4
    }

object FrameQuality {

    const val TILES_X=6
    const val TILES_Y=8

    private const val DARK=32
    private const val BRIGHT=224

    /**
    * [luma] is a Y plane, [rowStride] bytes per row (>= width). Values are
    * unsigned 0..255 stored in a signed byte, as every Android camera hands
    * them over.
    */
    fun estimate(
        luma: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int=width,
        ): QualityEstimate {
        if (width<3||height<3)
        return QualityEstimate(0.0, 0.0, 0.0, 1.0, 0.0)

        var sum=0L
        var dark=0L
        var bright=0L
        val pixels=width.toLong()*height

        for (y in 0 until height) {
            val row=y*rowStride
            for (x in 0 until width) {
                val v=luma[row+x].toInt() and 0xff
                sum+=v
                if (v<DARK) dark++
                if (v>BRIGHT) bright++
                }
            }

        val tiles=ArrayList<Double>(TILES_X*TILES_Y)
        val tileW=width/TILES_X
        val tileH=height/TILES_Y

        if (tileW>=3&&tileH>=3) {
            for (ty in 0 until TILES_Y) for (tx in 0 until TILES_X) {
                tiles.add(laplacianVariance(
                    luma, rowStride,
                    tx*tileW, ty*tileH,
                    tileW, tileH,
                    ))
                }
            }

        tiles.sort()

        return QualityEstimate(
            sharpnessTileMax=tiles.lastOrNull() ?: 0.0,
            sharpnessTileMedian=if (tiles.isEmpty()) 0.0 else tiles[tiles.size/2],
            meanLuma=sum.toDouble()/pixels,
            darkFraction=dark.toDouble()/pixels,
            brightFraction=bright.toDouble()/pixels,
            )
        }

    /** Variance of the 4-neighbour Laplacian over one tile's interior. */
    private fun laplacianVariance(
        luma: ByteArray,
        rowStride: Int,
        x0: Int,
        y0: Int,
        w: Int,
        h: Int,
        ): Double {
        var sum=0.0
        var sumSq=0.0
        var n=0

        for (y in y0+1 until y0+h-1) {
            val row=y*rowStride
            val up=(y-1)*rowStride
            val down=(y+1)*rowStride

            for (x in x0+1 until x0+w-1) {
                val c=luma[row+x].toInt() and 0xff
                val l=luma[row+x-1].toInt() and 0xff
                val r=luma[row+x+1].toInt() and 0xff
                val u=luma[up+x].toInt() and 0xff
                val d=luma[down+x].toInt() and 0xff

                val lap=(4*c-l-r-u-d).toDouble()
                sum+=lap
                sumSq+=lap*lap
                n++
                }
            }

        if (n<2)
        return 0.0

        val mean=sum/n
        return sumSq/n-mean*mean
        }
    }

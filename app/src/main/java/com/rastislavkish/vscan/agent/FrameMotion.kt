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

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
* How much the view changed between two analysis frames.
*
* Why not optical flow: sparse Lucas-Kanade would give displacement vectors, but
* the only implementation worth using arrives with OpenCV, and that is tens of
* megabytes of native code added to a 22 MB accessibility app published on
* F-Droid, where every native blob is also a reproducible-build problem. The
* question here is not "how far did it move and in which direction" but "did the
* view change enough to be worth another look", and a block measure answers that
* at a fraction of the cost. If direction ever turns out to matter, that is the
* moment to pay for a tracker.
*
* Android-free so it can be tested on the JVM.
*/
data class MotionEstimate(

    /**
    * Largest per-block change, 0 for identical frames and roughly 1 when a
    * block changed by about its own contrast.
    *
    * Max rather than mean because of a specific failure: the user holds the
    * phone still and turns the package with their other hand. Most of the frame
    * - shelf, sleeve, background - is unchanged, and a whole-frame average would
    * report stillness through the one thing we actually care about moving.
    */
    val regionMax: Double,

    val regionMedian: Double,

    /**
    * Fraction of blocks with enough texture for their difference to mean
    * anything. A frame of blank wall produces confident-looking zeros.
    */
    val reliability: Double,

    /** Seconds between the two frames, as measured, not as configured. */
    val elapsedSeconds: Double,
    ) {

    /**
    * Too little texture to make a claim. Reported as its own state rather than
    * being folded into "no motion": absence of evidence would otherwise open
    * the stability gate on a frame nothing could be measured in.
    */
    val unknown: Boolean
    get() = reliability<0.15

    /** Per-second, so a dropped analysis frame does not read as sudden motion. */
    val regionMaxPerSecond: Double
    get() = if (elapsedSeconds>0.0) regionMax/elapsedSeconds else 0.0
    }

object FrameMotion {

    const val BLOCKS_X=8
    const val BLOCKS_Y=6

    /**
    * Below this standard deviation a block is treated as textureless and
    * excluded, rather than contributing a meaningless ratio.
    */
    private const val TEXTURE_FLOOR=6.0

    /**
    * Compare two luminance planes of identical geometry.
    *
    * Each block has its own mean subtracted before differencing, so a uniform
    * brightness shift - a cloud, an aisle light, the flashlight coming on -
    * cancels instead of registering as movement. Geometric change survives it.
    */
    fun between(
        previous: ByteArray,
        current: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int=width,
        elapsedSeconds: Double=0.0,
        ): MotionEstimate {
        val blockW=width/BLOCKS_X
        val blockH=height/BLOCKS_Y

        if (blockW<2||blockH<2)
        return MotionEstimate(0.0, 0.0, 0.0, elapsedSeconds)

        val scores=ArrayList<Double>(BLOCKS_X*BLOCKS_Y)
        var informative=0
        var blocks=0

        for (by in 0 until BLOCKS_Y) for (bx in 0 until BLOCKS_X) {
            blocks++

            val x0=bx*blockW
            val y0=by*blockH

            var sumP=0.0
            var sumC=0.0
            var sumSqC=0.0
            var n=0

            for (y in y0 until y0+blockH) {
                val row=y*rowStride
                for (x in x0 until x0+blockW) {
                    sumP+=previous[row+x].toInt() and 0xff
                    val c=current[row+x].toInt() and 0xff
                    sumC+=c
                    sumSqC+=c.toDouble()*c
                    n++
                    }
                }

            if (n==0)
            continue

            val meanP=sumP/n
            val meanC=sumC/n
            val variance=max(0.0, sumSqC/n-meanC*meanC)
            val deviation=sqrt(variance)

            if (deviation<TEXTURE_FLOOR)
            continue

            informative++

            var diff=0.0
            for (y in y0 until y0+blockH) {
                val row=y*rowStride
                for (x in x0 until x0+blockW) {
                    val p=(previous[row+x].toInt() and 0xff)-meanP
                    val c=(current[row+x].toInt() and 0xff)-meanC
                    diff+=abs(c-p)
                    }
                }

            scores.add(diff/n/deviation)
            }

        scores.sort()

        return MotionEstimate(
            regionMax=scores.lastOrNull() ?: 0.0,
            regionMedian=if (scores.isEmpty()) 0.0 else scores[scores.size/2],
            reliability=if (blocks==0) 0.0 else informative.toDouble()/blocks,
            elapsedSeconds=elapsedSeconds,
            )
        }
    }

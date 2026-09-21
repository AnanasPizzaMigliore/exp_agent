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

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class ViewRelation { SAME, DIFFERENT, UNKNOWN }

/** Owned, area-averaged luminance; never references a camera buffer. */
class ViewFingerprint private constructor(val width: Int, val height: Int, val pixels: FloatArray) {
    companion object {
        fun from(luma: ByteArray, width: Int, height: Int, rowStride: Int=width): ViewFingerprint {
            require(width>0&&height>0&&rowStride>=width)
            require(luma.size>=(height-1)*rowStride+width)
            val step=ceil(maxOf(width, height)/96.0).toInt().coerceAtLeast(1)
            val w=width/step
            val h=height/step
            val pixels=FloatArray(w*h)
            for (y in 0 until h) for (x in 0 until w) {
                var sum=0
                for (dy in 0 until step) for (dx in 0 until step)
                sum+=luma[(y*step+dy)*rowStride+x*step+dx].toInt() and 255
                pixels[y*w+x]=sum.toFloat()/(step*step)
                }
            return ViewFingerprint(w, h, pixels)
            }
        }
    }

data class ViewComparison(
    val relation: ViewRelation,
    val residualP75: Double=1.0,
    val informativeTiles: Int=0,
    val changedTiles: Int=0,
    )

/**
* Bounded local registration, not object recognition or optical flow.
*
* Each tile may shift independently by two thumbnail pixels and rotate by up to
* two degrees. This accommodates independently trembling foreground/background.
* There is NO scale fitting, and no unbounded alignment: genuine accumulated
* translation, scale changes and new faces must not be registered away.
*
* Multiple informative tiles are required. The largest raw pixel difference is
* deliberately not a decision statistic. Weak texture/marginal matches are UNKNOWN.
*/
object HandheldViewMatcher {
    const val SHIFT_RADIUS=2
    const val QUIET_GYRO_LIMIT=0.8 // rad/s; capture veto only, never action evidence
    private const val MIN_TEXTURE=7.0

    /**
    * The residual at which two views count as the same place.
    *
    * Public because [SteadinessWindow] counts returns to a pinned view against
    * it. A second, privately chosen "close enough" over there would be a second
    * definition of the same word, free to drift away from this one.
    */
    const val SAME_RESIDUAL=0.18

    /** The residual at which tiles count as showing a different place. */
    const val CHANGED_RESIDUAL=0.35

    /**
    * The smallest fingerprint compare() can actually answer about.
    *
    * Tiles are (w-8)/4 by (h-8)/3 and are sampled every second pixel, and a tile
    * with fewer than 12 samples is skipped as uninformative. At 32x24 - the size
    * this guard used to accept - every tile yields 3x3=9 samples, so all twelve
    * are skipped and the result is UNKNOWN whatever the two images are. 36 is
    * the first width whose tiles reach 12.
    *
    * No real comparison changes: a 640x480 analysis frame reduces to 91x68, well
    * above either figure. What changes is that the function no longer accepts a
    * size at which its answer is decided in advance.
    */
    private const val MIN_WIDTH=36
    private const val MIN_HEIGHT=24
    private val angles=doubleArrayOf(0.0, -1.0, 1.0, -2.0, 2.0).map { Math.toRadians(it) }

    fun compare(reference: ViewFingerprint?, current: ViewFingerprint): ViewComparison {
        if (reference==null||reference.width!=current.width||reference.height!=current.height||
            reference.width<MIN_WIDTH||reference.height<MIN_HEIGHT) return ViewComparison(ViewRelation.UNKNOWN)
        val w=reference.width
        val h=reference.height
        val margin=4
        val tileW=(w-2*margin)/4
        val tileH=(h-2*margin)/3
        val scores=ArrayList<Double>()

        for (ty in 0 until 3) for (tx in 0 until 4) {
            val xs=ArrayList<Int>()
            val ys=ArrayList<Int>()
            val values=ArrayList<Double>()
            var sumP=0.0
            var sumPP=0.0
            for (y in margin+ty*tileH until margin+(ty+1)*tileH step 2)
            for (x in margin+tx*tileW until margin+(tx+1)*tileW step 2) {
                val p=reference.pixels[y*w+x].toDouble()
                xs.add(x); ys.add(y); values.add(p)
                sumP+=p
                sumPP+=p*p
                }
            val n=values.size
            if (n<12) continue
            val varianceP=sumPP-sumP*sumP/n
            if (varianceP/n<MIN_TEXTURE*MIN_TEXTURE) continue
            var best=1.0

            for (angle in angles) {
                val c=cos(angle)
                val s=sin(angle)
                // Rotate around the image centre; tile translations can additionally
                // compensate the object's hand independently of the phone's hand.
                val qx=DoubleArray(n)
                val qy=DoubleArray(n)
                for (i in 0 until n) {
                    val x=xs[i]-(w-1)/2.0
                    val y=ys[i]-(h-1)/2.0
                    qx[i]=c*x-s*y+(w-1)/2.0
                    qy[i]=s*x+c*y+(h-1)/2.0
                    }
                for (dy in -SHIFT_RADIUS..SHIFT_RADIUS) for (dx in -SHIFT_RADIUS..SHIFT_RADIUS) {
                    var sumQ=0.0
                    var sumQQ=0.0
                    var sumPQ=0.0
                    var valid=true
                    for (i in 0 until n) {
                        val x=qx[i]+dx
                        val y=qy[i]+dy
                        if (x<0||y<0||x>=w-1||y>=h-1) { valid=false; break }
                        val ix=x.toInt()
                        val iy=y.toInt()
                        val fx=x-ix
                        val fy=y-iy
                        val p=current.pixels
                        val q=(p[iy*w+ix]*(1-fx)+p[iy*w+ix+1]*fx)*(1-fy)+
                            (p[(iy+1)*w+ix]*(1-fx)+p[(iy+1)*w+ix+1]*fx)*fy
                        sumQ+=q
                        sumQQ+=q*q
                        sumPQ+=values[i]*q
                        }
                    if (!valid) continue
                    val varianceQ=sumQQ-sumQ*sumQ/n
                    if (varianceQ/n<MIN_TEXTURE*MIN_TEXTURE) continue
                    val correlation=(sumPQ-sumP*sumQ/n)/sqrt(varianceP*varianceQ)
                    best=minOf(best, (1.0-correlation).coerceIn(0.0, 1.0))
                    }
                if (best<0.005) break
                }
            scores.add(best)
            }
        if (scores.size<3) return ViewComparison(ViewRelation.UNKNOWN, informativeTiles=scores.size)
        scores.sort()
        val p75=scores[((scores.size-1)*0.75).toInt()]
        val changed=scores.count { it>CHANGED_RESIDUAL }
        val strong=scores.count { it>0.65 }
        val relation=when {
            changed>=maxOf(2, ceil(scores.size*0.30).toInt())||strong>=2 -> ViewRelation.DIFFERENT
            p75<SAME_RESIDUAL&&changed<=scores.size/5 -> ViewRelation.SAME
            else -> ViewRelation.UNKNOWN
            }
        return ViewComparison(relation, p75, scores.size, changed)
        }

    /**
    * Camera motion may prevent a sharp capture, but cannot prove product progress.
    *
    * [limit] is the speed this particular hand counts as quiet below, which
    * [TremorBaseline] reads off the user over a session and which is never
    * stricter than [QUIET_GYRO_LIMIT]. Left at the default, this is the test
    * exactly as it was.
    */
    fun quiet(comparison: ViewComparison, gyroRms: Double?=null,
        limit: Double=QUIET_GYRO_LIMIT): Boolean =
    comparison.relation==ViewRelation.SAME&&(gyroRms==null||gyroRms<limit)
    }

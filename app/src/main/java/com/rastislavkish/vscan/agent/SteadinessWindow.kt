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

/** What a run of residuals against one pinned view says about the hand. */
enum class Steadiness {

    /** Not enough of it seen yet to make either claim. */
    UNKNOWN,

    /** Wobbling about a fixed pose: it keeps coming back to where it was. */
    OSCILLATING,

    /** It has left, and is not coming back. */
    DRIFTING,
    }

/**
* Bounded oscillation, told apart from going somewhere.
*
* This is the part a tremor actually needs. [HandheldViewMatcher] answers one
* question about one pair of views - are these the same place, to within two
* thumbnail pixels and two degrees - and a tremor spends most of its cycle
* outside that box. Frame by frame the answer is therefore DIFFERENT or
* UNKNOWN, [HandheldViewTracker] re-anchors on every one of them, and the
* settling anchor never survives long enough for anything to settle against.
*
* Widening the box is the obvious fix and the wrong one: the same widening that
* forgives a tremor is what stops the gate noticing that a package is being
* turned over, which is the one thing the gate is for.
*
* The distinction that does hold up is not in any single pair. A trembling hand
* crosses its own starting pose again and again, so against a PINNED reference
* the residual keeps falling back to where it began. A hand that is going
* somewhere crosses it once and then does not. So this keeps the reference
* still, watches the series, and asks whether the view keeps coming home.
*
* Absolute rather than comparative, which is what makes it safe immediately
* after the reference is re-pinned: at that moment the view really is at the
* reference, and saying so is correct. What stops a mid-movement re-pin from
* reading as steady is [MIN_SPAN_NS] - the claim needs most of a second of
* samples against one reference, and a movement has moved on well inside that.
*
* Android-free, so it can be tested on the JVM.
*/
class SteadinessWindow(
    private val windowNs: Long=1_500_000_000L,
    ) {

    private val samples=ArrayDeque<Pair<Long, Double>>()

    fun clear() {
        samples.clear()
        }

    /**
    * One comparison against the pinned view, and the verdict so far.
    *
    * Comparisons with too few informative tiles carry no measured residual -
    * [ViewComparison] reports its default - so they are dropped rather than
    * entered as a large one. A blurred frame is absence of evidence, and
    * counting it as evidence of movement is how a tremor ends up looking like
    * a package being turned over.
    */
    fun add(nowNs: Long, comparison: ViewComparison): Steadiness {
        if (comparison.informativeTiles>=MIN_INFORMATIVE_TILES) {
            if (samples.lastOrNull()?.first?.let { nowNs<=it }!=true)
            samples.addLast(Pair(nowNs, comparison.residualP75))

            val cutoff=nowNs-windowNs
            while (samples.isNotEmpty()&&samples.first().first<cutoff)
            samples.removeFirst()
            }

        return verdict()
        }

    private fun verdict(): Steadiness {
        if (samples.size<MIN_SAMPLES)
        return Steadiness.UNKNOWN

        val first=samples.first().first
        val last=samples.last().first
        val span=last-first
        if (span<MIN_SPAN_NS)
        return Steadiness.UNKNOWN

        val midpoint=first+span/2
        val early=samples.count { it.first<=midpoint&&it.second<=HandheldViewMatcher.SAME_RESIDUAL }
        val late=samples.count { it.first>midpoint&&it.second<=HandheldViewMatcher.SAME_RESIDUAL }

        // A reference nothing has matched for a whole window is not evidence of
        // movement either - it may have been pinned on a blurred frame. Saying
        // DRIFTING is how the caller is told to pin a fresh one and start again,
        // which is the only way out of that.
        val exhausted=span>=windowNs

        return when {
            late>=MIN_RETURNS -> Steadiness.OSCILLATING
            early>=MIN_RETURNS||exhausted -> Steadiness.DRIFTING
            else -> Steadiness.UNKNOWN
            }
        }

    companion object {

        /** Two crossings, so one chance alignment is not a hand holding still. */
        const val MIN_RETURNS=2

        private const val MIN_SAMPLES=8
        private const val MIN_SPAN_NS=700_000_000L
        private const val MIN_INFORMATIVE_TILES=3
        }
    }

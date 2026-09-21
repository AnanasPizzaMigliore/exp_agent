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
* The hand that is actually holding the phone, as a threshold.
*
* [HandheldViewMatcher.QUIET_GYRO_LIMIT] is one number for everybody. For a
* steady hand it is generous, and for a hand with a tremor it can be a limit
* that is simply never reached: the session then sits in SETTLING until the
* stall deadline and the only remedy the app has is to ask the user to press
* Capture themselves, which bypasses every visual gate there is.
*
* So the limit is read off the user instead. Their own angular speed over the
* last few seconds says what their calm looks like, and the gate asks for calm
* rather than for stillness.
*
* Two properties keep this from becoming a way of accepting anything:
*
*   - It NEVER tightens. The fixed limit is the floor, so a steady hand is
*     measured exactly as it is today and nothing about their sessions changes.
*   - It never relaxes past [CEILING], where the exposure is smeared whoever is
*     holding the phone and no amount of patience would have produced a
*     readable frame.
*
* Android-free, so it can be tested on the JVM.
*/
class TremorBaseline(
    private val windowNs: Long=6_000_000_000L,
    private val troughWindowNs: Long=1_000_000_000L,
    ) {

    private val samples=ArrayDeque<Pair<Long, Double>>()

    fun clear() {
        samples.clear()
        }

    fun add(nowNs: Long, angularSpeedRms: Double) {
        if (samples.lastOrNull()?.first?.let { nowNs<=it }==true)
        return

        samples.addLast(Pair(nowNs, angularSpeedRms))

        val cutoff=nowNs-windowNs
        while (samples.isNotEmpty()&&samples.first().first<cutoff)
        samples.removeFirst()
        }

    /**
    * Whether enough of this user's hand has been seen to say anything about it.
    *
    * Both a count and a span, because neither alone is evidence: forty samples
    * arriving in a fifth of a second are one tremor cycle seen closely, and two
    * seconds holding three samples is a stream that dropped.
    */
    val calibrated: Boolean
    get() = samples.size>=MIN_SAMPLES&&span()>=MIN_SPAN_NS

    /**
    * The angular speed below which this user's hand counts as quiet.
    *
    * The low percentile is what they manage when they are trying, and the slack
    * above it is the room a tremor needs to keep crossing it. An uncalibrated
    * baseline answers the fixed limit, which is what the gate used before.
    */
    val quietLimit: Double
    get() {
        if (!calibrated)
        return HandheldViewMatcher.QUIET_GYRO_LIMIT

        return (percentile(samples.map { it.second }, CALM_PERCENTILE)*SLACK)
        .coerceIn(HandheldViewMatcher.QUIET_GYRO_LIMIT, CEILING)
        }

    /**
    * Whether this instant is a trough of the tremor rather than a peak of it.
    *
    * A tremor is an oscillation, so within any second of holding there are
    * moments measurably quieter than the rest of it, and a capture is worth
    * spending on one of those. This only ever says "not yet" - the caller
    * bounds how long it will wait - because a hand that never reaches its own
    * trough must still get a photograph.
    *
    * Answers true when there is nothing to compare against: no evidence is not
    * a reason to hold up a capture.
    */
    fun atTrough(angularSpeedRms: Double): Boolean {
        val recent=samples.filter { it.first>=(samples.lastOrNull()?.first ?: 0L)-troughWindowNs }
        if (recent.size<MIN_TROUGH_SAMPLES)
        return true

        val floor=percentile(recent.map { it.second }, TROUGH_PERCENTILE)
        return angularSpeedRms<=maxOf(TROUGH_FLOOR, floor*TROUGH_SLACK)
        }

    private fun span(): Long {
        val first=samples.firstOrNull() ?: return 0L
        val last=samples.lastOrNull() ?: return 0L
        return last.first-first.first
        }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        if (values.isEmpty())
        return 0.0

        val sorted=values.sorted()
        return sorted[((sorted.size-1)*fraction).toInt()]
        }

    companion object {

        /** Beyond this the frame is smeared whoever is holding the phone. */
        const val CEILING=2.0

        private const val CALM_PERCENTILE=0.30
        private const val SLACK=1.6
        private const val MIN_SAMPLES=40
        private const val MIN_SPAN_NS=2_000_000_000L
        private const val MIN_TROUGH_SAMPLES=8
        private const val TROUGH_PERCENTILE=0.20
        private const val TROUGH_SLACK=1.25

        /** Below this nothing is gained by waiting for a quieter instant. */
        private const val TROUGH_FLOOR=0.05
        }
    }

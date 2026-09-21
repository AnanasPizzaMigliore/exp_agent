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

/** Single-owner references. Worker measurements use an immutable snapshot and
* are rejected if an instruction/capture/lifecycle transition changed its epoch.
*/
class HandheldViewTracker {
    data class Anchors(
        val revision: Long,
        val instruction: ViewFingerprint?,
        val settling: ViewFingerprint?,
        val submitted: ViewFingerprint?,
        val lastUpload: ViewFingerprint?,
        val tremor: ViewFingerprint?,
        )
    data class Measurement(
        val anchors: Anchors,
        val current: ViewFingerprint,
        val instruction: ViewComparison,
        val settling: ViewComparison,
        val submitted: ViewComparison,
        val lastUpload: ViewComparison,
        val tremor: ViewComparison,
        )

    private var revision=0L
    private var latest: ViewFingerprint?=null
    private var instruction: ViewFingerprint?=null
    private var settling: ViewFingerprint?=null
    private var submitted: ViewFingerprint?=null

    /**
    * The view of the frame most recently handed to the model, kept until the
    * next one replaces it.
    *
    * Distinct from [submitted], which exists only while a request is in flight
    * and is deliberately dropped at [release] - that is the right lifetime for
    * asking whether an arriving reply still concerns the current view. It is the
    * wrong lifetime for asking whether there is anything new to send, which is
    * a question between turns. Sharing one reference left that test reading
    * UNKNOWN for half of all samples.
    */
    private var lastUpload: ViewFingerprint?=null

    /**
    * A view held still on purpose, for [SteadinessWindow] to measure against.
    *
    * Every other anchor here either rolls forward or belongs to a turn. This one
    * does neither: it is pinned and left alone, because the question it serves -
    * does the view keep coming back to where it was - has no answer at all
    * against a reference that moves with the hand. [settling] rolling on each
    * non-matching frame is exactly right for accumulating slow drift and exactly
    * wrong for recognising an oscillation, so the two cannot be the same anchor.
    *
    * Re-pinned only by [repinTremor], when the window reports the view has left
    * for good, and dropped at the epoch changes where no earlier view is
    * relevant any more.
    */
    private var tremor: ViewFingerprint?=null

    fun clear() {
        revision+=1
        latest=null
        instruction=null
        settling=null
        submitted=null
        lastUpload=null
        tremor=null
        }

    fun arm() {
        revision+=1
        instruction=latest
        settling=null
        submitted=null
        tremor=null
        }

    /** Freeze the nearest available analysis view when CameraX returns. This is
    * a low-resolution proxy, not pixel-exact registration to the uploaded JPEG.
    */
    fun capture() {
        revision+=1
        submitted=latest
        lastUpload=latest
        tremor=null
        }

    fun release() {
        revision+=1
        submitted=null
        }

    /**
    * Pin the tremor reference to the current view.
    *
    * Called from the same place, in the same order, as [accept] - the monitor
    * decides this from the measurement it has just accepted - so unlike the
    * epoch changes above there is no in-flight measurement this could strand,
    * and it deliberately does not bump the revision.
    */
    fun repinTremor() {
        tremor=latest
        }

    fun snapshot(): Anchors = Anchors(revision, instruction, settling, submitted, lastUpload, tremor)

    fun accept(measurement: Measurement): Boolean {
        if (measurement.anchors.revision!=revision) return false
        latest=measurement.current
        if (instruction==null) instruction=measurement.current
        // Do not roll a matching anchor: slow drift must accumulate beyond the
        // small jitter allowance rather than looking quiet frame by frame.
        if (measurement.settling.relation!=ViewRelation.SAME) settling=measurement.current
        if (tremor==null) tremor=measurement.current
        return true
        }

    companion object {
        /** Pure worker operation; never mutates the live tracker. */
        fun measure(anchors: Anchors, current: ViewFingerprint): Measurement = Measurement(
            anchors, current,
            HandheldViewMatcher.compare(anchors.instruction, current),
            HandheldViewMatcher.compare(anchors.settling, current),
            HandheldViewMatcher.compare(anchors.submitted, current),
            HandheldViewMatcher.compare(anchors.lastUpload, current),
            HandheldViewMatcher.compare(anchors.tremor, current),
            )
        }
    }

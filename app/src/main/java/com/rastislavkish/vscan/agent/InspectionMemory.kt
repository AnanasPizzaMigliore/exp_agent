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

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
* What one inspection has learned about the package in the user's hand.
*
* This is the piece that used to live on the laptop, and the reason a guidance
* server existed at all: VScan's TabAdapter.consultConfig() builds a fresh
* Conversation on every call, so the app had nowhere to keep "I already saw the
* lid clearly and there was no date on it". It keeps it here now.
*
* Held by InspectionController and only ever touched under that controller's
* mutex.
*/

/**
* A face of the package we have looked at, and how well we saw it.
*
* bestQuality is deliberately not a boolean: a face seen only through glare is
* not a face that can be ruled out.
*/
data class RegionRecord(
    val surface: String,
    var bestQuality: ViewQuality,
    var dateRegionVisible: Boolean,
    var dateLegible: Boolean,
    var timesSeen: Int=1,

    /**
    * Looks at this face that were usable, unobstructed and showed no date region.
    *
    * Separate from bestQuality because a sharp frame is not a search: a usable
    * picture of half a base with a thumb over it has good quality and poor
    * coverage, and a usable picture in which a date region was seen is the
    * opposite of "not found here".
    */
    var clearLooksWithoutDate: Int=0,
    )

/** One capture and everything decided because of it. */
data class Turn(
    val frameId: Int,
    val instructionId: Int?,
    val perception: Perception,
    val actionStatus: ActionStatus,
    val viewQuality: ViewQuality,
    val whatChanged: String?,
    val instruction: String,
    val rationale: String?,
    val latencyMs: Long,
    val tokens: Int,

    /** The approved action actually spoken, after any override. Null for free wording. */
    val guidance: String?=null,
    )

class InspectionMemory(val productHint: String?=null) {

    var geometry="unknown"
    val regions=LinkedHashMap<String, RegionRecord>()

    var candidateDate: String?=null
    var candidateIso: String?=null
    var candidateType: DateType?=null
    val unresolved=mutableListOf<String>()

    val history=mutableListOf<Turn>()
    var tokensSpent=0

    /**
    * Whether this session has already asked the user to brace their elbows.
    *
    * Bracing is a one-time change of posture, not a movement of the package:
    * once it has been asked for, the user has either done it or cannot, and
    * asking again buys nothing and costs a turn. In the first participant run
    * it was asked twice in twenty-four seconds.
    */
    var braced=false

    /** Fold one observation into what we know about the package's faces. */
    fun record(perception: Perception, quality: ViewQuality) {
        val surface=perception.surface_in_view.ifBlank { "unclear" }
        val existing=regions[surface]

        val clearWithoutDate=if (isClearLookWithoutDate(perception, quality)) 1 else 0

        if (existing==null) {
            regions[surface]=RegionRecord(
                surface=surface,
                bestQuality=quality,
                dateRegionVisible=perception.date_region_visible,
                dateLegible=perception.date_legible,
                clearLooksWithoutDate=clearWithoutDate,
                )
            return
            }

        existing.timesSeen+=1
        existing.clearLooksWithoutDate+=clearWithoutDate

        // Only ever upgrade the recorded quality: one clean look at a face is
        // what licenses ruling it out, and a later glare-ruined frame of the
        // same face must not erase that.
        if (rank(quality)>rank(existing.bestQuality))
        existing.bestQuality=quality

        existing.dateRegionVisible=existing.dateRegionVisible||perception.date_region_visible
        existing.dateLegible=existing.dateLegible||perception.date_legible
        }

    fun wellInspectedSurfaces(): List<String> =
    regions.values.filter { it.bestQuality==ViewQuality.USABLE }.map { it.surface }

    /**
    * Faces looked at clearly, with nothing obstructing them, where no date region
    * was ever seen - including, when given, the frame not yet recorded.
    *
    * Still not "the date is not there": one clear look can miss small print at
    * the edge of the frame, and perception does not say how much of the face was
    * in view. Callers may lower a face's priority on this, never rule it out.
    */
    fun surfacesSearchedWithoutDate(current: Perception?=null, currentQuality: ViewQuality?=null): Set<String> {
        val searched=regions.values
        .filter { it.clearLooksWithoutDate>0 && !it.dateRegionVisible }
        .map { it.surface }
        .toMutableSet()

        if (current!=null && currentQuality!=null) {
            val surface=current.surface_in_view.ifBlank { "unclear" }

            if (current.date_region_visible)
            searched.remove(surface)
            else if (isClearLookWithoutDate(current, currentQuality) && regions[surface]?.dateRegionVisible!=true)
            searched.add(surface)
            }

        searched.remove("unclear")
        return searched
        }

    /** Compact state handed to the planner. No images: that call is text-only. */
    fun belief(): JsonElement = buildJsonObject {
        put("geometry", geometry)
        put("product_hint", productHint)
        putJsonArray("surfaces_inspected_well") {
            for (surface in wellInspectedSurfaces()) add(surface)
            }
        putJsonObject("surfaces_seen") {
            for ((name, record) in regions) putJsonObject(name) {
                put("quality", record.bestQuality.name.lowercase())
                put("date_region_visible", record.dateRegionVisible)
                put("date_legible", record.dateLegible)
                put("times_seen", record.timesSeen)
                put("clear_looks_without_date", record.clearLooksWithoutDate)
                }
            }
        put("candidate_date", candidateDate)
        put("candidate_iso", candidateIso)
        put("candidate_type", candidateType?.name)
        putJsonArray("unresolved") {
            for (item in unresolved) add(item)
            }
        put("turns", history.size)
        }

    /**
    * The last few turns, as the planner sees them.
    *
    * date_type is carried even though the planner rarely reads it, because
    * answerIsGrounded() uses this same list to accept a type that an earlier,
    * cleaner frame established. Leaving it out silently disables that.
    */
    fun visibleHistory(limit: Int=6): JsonElement = buildJsonArray {
        for (turn in history.takeLast(limit)) add(buildJsonObject {
            put("frame_id", turn.frameId)
            put("surface_in_view", turn.perception.surface_in_view)

            // What was actually read there, so a face can be recognised by its
            // markings rather than by its label.
            //
            // The label cannot carry that weight. In session 2ff09b48 frames 4
            // and 7 are the same base of the same tub - identical embossed
            // "800ML" and "10765-4", the second simply closer - and perception
            // called the first "base" and the second "top". The planner then
            // asked the user to turn back to a face they were already holding
            // up. The text repeated exactly across both, which the label did
            // not, so the text is the thing to compare.
            put("text_read", turn.perception.text_read)
            put("date_region_visible", turn.perception.date_region_visible)
            put("date_legible", turn.perception.date_legible)
            put("date_type", turn.perception.date_type?.name)
            put("looks_like", turn.perception.looks_like)
            putJsonArray("problems") {
                for (problem in turn.perception.problems) add(problem)
                }
            put("instruction", turn.instruction)
            put("action_status", turn.actionStatus.name.lowercase())
            put("view_quality", turn.viewQuality.name.lowercase())
            put("what_changed", turn.whatChanged)
            })
        }

    /**
    * The package's geometry as this session has seen it: the most frequent known
    * reading across recorded turns and [current], the earliest one on a tie.
    *
    * One perception call's geometry is not the package's. On 2026-09-14 a cup
    * was read as "tray" for a single frame, retrieval switched to bag and tray
    * cases, and the planner asked for the other broad face of a cup. With this,
    * a lone misreading after one good one loses the tie, and it takes repeated
    * readings to change what the session believes it is holding.
    */
    fun settledGeometry(current: String?=null): String {
        val known=(history.map { it.perception.geometry }+listOfNotNull(current))
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() && it!="unknown" }

        if (known.isEmpty())
        return "unknown"

        val counts=known.groupingBy { it }.eachCount()
        val most=counts.values.max()

        return known.first { counts[it]==most }
        }

    /**
    * True when [guidance] has already been spoken [REPEATS_BEFORE_OVERRIDE] times
    * without its face ever being reported in view.
    *
    * On 2026-09-14 the user tipped a cup to show its lid, the verification call
    * said the lid was showing, and perception labelled the frame "base" - an
    * upturned cup's lid and base look alike. Memory never recorded "top", so the
    * planner asked for the same tip three times while the date sat on a side it
    * never asked to see. Repeating a movement the agent cannot tell has worked
    * costs the user a regrip each time and learns nothing.
    *
    * This used to ask whether the last two turns in a row were both this tip, and
    * that made it fire once and then let go. On 2026-09-16 it replaced a third
    * TURN_UP with a quarter turn, which put TURN_LEFT at the end of the history,
    * which broke the run - so the fourth TURN_UP went out unaltered and the base
    * was never asked for in a way that reached it. Counting how often the tip has
    * been spoken, rather than how recently, holds until the face actually
    * arrives: that is the condition the override exists to wait for, and a turn
    * spent elsewhere in between is not progress towards it.
    *
    * Counted over what was SPOKEN, so a tip that this replaced does not count
    * towards replacing the next one.
    *
    * Only tips are covered. A quarter turn or the other broad face is expected to
    * leave surface_in_view unchanged ("side" stays "side"), so a repeat there is
    * not evidence of being stuck.
    */
    fun repeatedTipWithoutReaching(
        guidance: GuidanceAction,
        currentSurface: String,

        /**
        * False restores the pre-16-September rule - the last two turns in a row
        * - for the control arm of the instruction-grounding comparison.
        */
        sticky: Boolean=true,
        ): Boolean {
        // One definition of which face a tip is after, shared with the wording
        // the user hears and with the verification call. See GuidanceTemplates.
        val target=GuidanceTemplates.targetSurface(guidance) ?: return false

        if (currentSurface.lowercase()==target || regions.containsKey(target))
        return false

        if (!sticky) {
            val previous=history.takeLast(REPEATS_BEFORE_OVERRIDE)
            return previous.size==REPEATS_BEFORE_OVERRIDE &&
            previous.all { it.guidance==guidance.name }
            }

        return history.count { it.guidance==guidance.name }>=REPEATS_BEFORE_OVERRIDE
        }

    /** Every date_type ever read, for the grounding guard's corroboration check. */
    fun seenDateTypes(): Set<DateType> =
    history.mapNotNull { it.perception.date_type }.toSet()

    private fun rank(quality: ViewQuality): Int = when (quality) {
        ViewQuality.UNUSABLE -> 0
        ViewQuality.POOR -> 1
        ViewQuality.USABLE -> 2
        }

    companion object {

        /**
        * Times a tip may be spoken, in a session, before further ones are
        * replaced - until its face is seen, not merely until the next turn.
        */
        const val REPEATS_BEFORE_OVERRIDE=2

        /** Problems that mean part of the face, or its print, was not really seen. */
        private val COVERAGE_PROBLEMS=setOf("glare", "blur", "too_far", "occluded_by_hand", "cut_off")

        fun isClearLookWithoutDate(perception: Perception, quality: ViewQuality): Boolean =
        quality==ViewQuality.USABLE &&
        !perception.date_region_visible &&
        perception.surface_in_view.isNotBlank() && perception.surface_in_view!="unclear" &&
        perception.problems.none { it in COVERAGE_PROBLEMS }
        }
    }

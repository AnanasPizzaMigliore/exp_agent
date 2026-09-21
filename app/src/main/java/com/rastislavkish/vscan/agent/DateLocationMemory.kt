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

import java.security.MessageDigest
import java.text.Normalizer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
* Retrieval over annotated reference packages: where was the date printed on
* packages like this one?
*
* The planner's search order used to come only from the placement heuristics in
* its prompt. This supplies the same kind of knowledge from photographed packages
* instead, conditioned on what the phone can observe right now - the package
* geometry, and optionally brand words printed on it.
*
* What comes back is a set of target locations, not instructions. Which movement
* reaches a target depends on what the camera is looking at, and perception often
* cannot say: "label" does not distinguish front from back. Each target therefore
* carries whether it may already be in view and the approved actions that can
* reach it from here, with "unknown" kept as an answer rather than guessed away.
*
* A face this session has looked at clearly without seeing a date lowers the
* weight of the cases whose date is on that face. It does not remove them: one
* clear look is not an exhaustive search, and the difference between "not found"
* and "not adequately inspected" is exactly what the agent has to keep.
*
* What it deliberately does not carry is a date. The index is built by
* tools/build_retrieval_index.py with the dates left out, so no retrieved case can
* be copied into an answer however the planner is prompted.
*
* Android-free so it can be tested on the JVM. Loading the asset is the caller's.
*/

@Serializable
data class DateLocationCase(
    val product_id: String,
    val geometry: String,
    val geometry_class: String,
    val location: String,

    /** The surface as annotated, e.g. "neck" within TOP. */
    val annotated_surface: String="",
    val name_tokens: List<String> =listOf(),
    val review_status: String="UNVERIFIED",
    val location_review_status: String="UNVERIFIED",
    )

@Serializable
data class DateLocationSplit(
    val file: String?=null,
    val sha256: String?=null,
    val evaluation_products: List<String> =listOf(),
    )

@Serializable
data class DateLocationIndex(
    val schema: String,
    val built_at: String?=null,
    val label_status: String?=null,
    val verified_only: Boolean=false,
    val excluded_products: List<String> =listOf(),
    val split: DateLocationSplit?=null,
    val geometry_classes: Map<String, String> =mapOf(),
    val cases: List<DateLocationCase> =listOf(),
    )

/** What retrieval is conditioned on. All of it observable on the phone. */
data class RetrievalQuery(
    val geometry: String,
    val text: String?,

    /** Perception's surface_in_view for the current frame. */
    val surfaceInView: String="unclear",

    /** From InspectionMemory.surfacesSearchedWithoutDate(). */
    val searchedWithoutDate: Collection<String> =listOf(),
    )

/** One place the date might be, as the planner sees it. */
data class RetrievalTarget(
    val location: String,
    val cases: Int,
    val details: Map<String, Int>,

    /** Cases whose annotated surface a clear look this session already showed without a date. */
    val onFacesLookedAt: Int,

    /** yes, possibly, no or unknown. */
    val inView: String,

    /** Approved guidance that can reach this location from the current view. Empty when none is known. */
    val reachWith: List<String>,
    )

data class LocationRetrieval(

    /** SAME_GEOMETRY, SIMILAR_GEOMETRY, or NAME_ONLY when the geometry is unknown. */
    val match: String,
    val geometry: String,
    val surfaceInView: String,
    val cases: List<DateLocationCase>,
    val targets: List<RetrievalTarget>,
    val searchedWithoutDate: Set<String>,
    val nameMatches: List<DateLocationCase>,
    val nameTargets: List<RetrievalTarget>,
    val matchedWords: Set<String>,
    val sparse: Boolean,
    ) {

    /**
    * What the planner sees. No product ids and no product names: which reference
    * package a case came from is audit information, and a name is an invitation
    * to assume this is that product.
    */
    fun toPolicyJson(): JsonElement = buildJsonObject {
        put("source", "other packages from a small reference collection with draft labels; not observations of this package")
        put("match", match.lowercase())
        put("geometry", geometry)
        put("surface_in_view", surfaceInView)
        put("cases", cases.size)
        put("sparse", sparse)
        putJsonArray("faces_looked_at_clearly_without_a_date") {
            for (surface in searchedWithoutDate) add(surface)
            }
        putJsonArray("targets") {
            for (target in targets) add(targetJson(target))
            }
        if (nameMatches.isNotEmpty()) putJsonObject("name_matched_cases") {
            putJsonArray("matched_words") {
                for (word in matchedWords) add(word)
                }
            put("cases", nameMatches.size)
            putJsonArray("targets") {
                for (target in nameTargets) add(targetJson(target))
                }
            }
        }

    /** What the experiment log keeps: everything above, plus which cases it was. */
    fun toLogJson(): JsonElement = buildJsonObject {
        put("match", match.lowercase())
        put("geometry", geometry)
        put("surface_in_view", surfaceInView)
        put("sparse", sparse)
        putJsonArray("case_ids") {
            for (case in cases) add(case.product_id)
            }
        putJsonArray("searched_without_date") {
            for (surface in searchedWithoutDate) add(surface)
            }
        putJsonArray("targets") {
            for (target in targets) add(targetJson(target))
            }
        putJsonArray("name_matched_ids") {
            for (case in nameMatches) add(case.product_id)
            }
        putJsonArray("matched_words") {
            for (word in matchedWords) add(word)
            }
        }

    private fun targetJson(target: RetrievalTarget): JsonObject = buildJsonObject {
        put("location", target.location)
        put("cases", target.cases)
        putJsonObject("details") {
            for ((detail, count) in target.details) put(detail, count)
            }
        put("cases_on_faces_already_looked_at", target.onFacesLookedAt)
        put("in_view", target.inView)
        putJsonArray("reach_with") {
            for (action in target.reachWith) add(action)
            }
        }
    }

class DateLocationMemory(val index: DateLocationIndex, val sha256: String) {

    val size: Int
    get() = index.cases.size

    /**
    * Cases for the package in view, or null when retrieval has nothing to go on.
    *
    * Backs off from the exact geometry to its class when fewer than
    * [MIN_CASES] match, and says so with sparse=true when even the class is thin.
    * An unknown geometry gets no geometry-based cases at all: "somewhere on some
    * package" is what the prompt already knows, and presenting it as retrieval
    * would lend it weight it has not earned.
    *
    * [matchNames] off gives the geometry-only condition.
    */
    fun retrieve(query: RetrievalQuery, matchNames: Boolean=true): LocationRetrieval? {
        val geometry=query.geometry.trim().lowercase()
        val geometryClass=index.geometry_classes[geometry]
        val surface=query.surfaceInView.trim().lowercase().ifBlank { "unclear" }
        val words=if (matchNames) tokens(query.text) else setOf()

        val (match, cases)=when {
            geometryClass==null -> Pair("NAME_ONLY", listOf())
            else -> {
                val same=index.cases.filter { it.geometry==geometry }

                if (same.size>=MIN_CASES)
                Pair("SAME_GEOMETRY", same)
                else
                Pair("SIMILAR_GEOMETRY", index.cases.filter { it.geometry_class==geometryClass })
                }
            }

        // A brand on a box says little about the same brand's jar, so name
        // matches stay within the geometry class whenever the class is known.
        val nameMatches=if (words.isEmpty()) listOf() else index.cases.filter { case ->
            (geometryClass==null||case.geometry_class==geometryClass) && case.name_tokens.any { it in words }
            }

        if (cases.isEmpty() && nameMatches.isEmpty())
        return null

        val searched=query.searchedWithoutDate.map { it.lowercase() }.toSortedSet()

        return LocationRetrieval(
            match=match,
            geometry=if (geometryClass==null) "unknown" else geometry,
            surfaceInView=surface,
            cases=cases,
            targets=targets(cases, geometryClass, surface, searched),
            searchedWithoutDate=searched,
            nameMatches=nameMatches,
            nameTargets=targets(nameMatches, geometryClass, surface, searched),
            matchedWords=nameMatches.flatMap { it.name_tokens }.filter { it in words }.toSortedSet(),
            sparse=cases.size<MIN_CASES,
            )
        }

    companion object {

        const val SCHEMA="vscan-date-location-index-v1"
        const val ASSET_PATH="agent/date_location_index.json"

        /** Fewer same-geometry cases than this and retrieval backs off to the class. */
        const val MIN_CASES=5

        /**
        * Annotated surfaces a clear view of the top, or of the base, actually
        * shows. A neck, a shoulder or a lid rim is around the top rather than on
        * it and needs a side-on view, so a look from above does not cover them.
        */
        private val SEEN_FROM_TOP=setOf("lid", "cap", "foil_lid", "foil_lids", "top_labels", "top_near_cap")
        private val SEEN_FROM_BASE=setOf("base", "base_label", "bottom_fold")

        private val format=Json { ignoreUnknownKeys=true }
        private val WORD=Regex("[a-z0-9]+")
        private val MARKS=Regex("\\p{Mn}+")

        fun parse(text: String): DateLocationMemory {
            val index=format.decodeFromString(DateLocationIndex.serializer(), text)

            if (index.schema!=SCHEMA)
            throw IllegalArgumentException("Unsupported retrieval index schema ${index.schema}")

            val digest=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return DateLocationMemory(index, digest.joinToString("") { "%02x".format(it) })
            }

        /**
        * Whether a clear look at these faces already showed this case's surface.
        *
        * Only surfaces that are unambiguously on the top or the base. "side" and
        * "label" do not say which side, so they cover nothing; an end flap may be
        * at either end, so it is covered only once both ends have been seen.
        */
        fun coveredBy(case: DateLocationCase, searched: Set<String>): Boolean = when {
            case.location=="END" -> "top" in searched && "base" in searched
            case.annotated_surface in SEEN_FROM_TOP -> "top" in searched
            case.annotated_surface in SEEN_FROM_BASE -> "base" in searched
            else -> false
            }

        /**
        * Whether a location may already be in view, and what reaches it otherwise.
        *
        * Answers only from perception's surface_in_view, which cannot tell front
        * from back or one side from another. So a broad face in view makes FRONT
        * and BACK each "possibly", never "yes".
        */
        fun reach(location: String, geometryClass: String?, surfaceInView: String): Pair<String, List<String>> {
            val round=geometryClass=="rigid_round"
            val surface=surfaceInView.lowercase()

            // TURN_UP brings the base to the phone and TURN_DOWN brings the
            // top: each is named for where the face now at the phone travels,
            // not for the face that arrives. See GuidanceAction.
            if (surface=="unclear") return when (location) {
                "BASE" -> Pair("unknown", listOf("TURN_UP"))
                "TOP" -> Pair("unknown", listOf("TURN_DOWN"))
                "END" -> Pair("unknown", listOf("TURN_DOWN", "TURN_UP"))
                else -> Pair("unknown", listOf())
                }

            // A box or a bag seen end-on is reported as a side. Measured on
            // 2026-09-17 over the evaluation products: ends of round packages
            // were named in 27 of 30 photographs, boxes in eight of 20 and bags
            // and trays in two of 20, the rest almost all "side". Telling the
            // prompt how to tell an end from a side did not fix it (three
            // readings corrected, one broken, p=.63), so the reading cannot be
            // relied on and the belief has to absorb that instead.
            //
            // So on a non-round package a "side" or "label" reading is not
            // evidence that the ends are turned away; it is uninformative about
            // them. Saying "no" there asserts something perception did not
            // establish, and asserting it is what lets a face the camera may be
            // looking at now be treated as still to reach.
            val endsUncertain=!round && surface in setOf("side", "label")

            return when (location) {
                "BASE" -> when {
                    surface=="base" -> Pair("yes", listOf())
                    endsUncertain -> Pair("unknown", listOf("TURN_UP"))
                    else -> Pair("no", listOf("TURN_UP"))
                    }
                "TOP" -> when {
                    surface=="top" -> Pair("yes", listOf())
                    endsUncertain -> Pair("unknown", listOf("TURN_DOWN"))
                    else -> Pair("no", listOf("TURN_DOWN"))
                    }
                "END" -> when (surface) {
                    "top" -> Pair("possibly", listOf("TURN_UP"))
                    "base" -> Pair("possibly", listOf("TURN_DOWN"))
                    else -> if (endsUncertain) Pair("unknown", listOf("TURN_DOWN", "TURN_UP"))
                    else Pair("no", listOf("TURN_DOWN", "TURN_UP"))
                    }
                // Around a round package, every side and label position is one
                // more quarter turn away, and either direction reaches it.
                "SIDE", "FRONT", "BACK" -> if (round) when (surface) {
                    "side", "label" -> Pair("possibly", listOf("TURN_LEFT", "TURN_RIGHT"))
                    else -> Pair("no", listOf())
                    }
                else when (location) {
                    "SIDE" -> when (surface) {
                        "side" -> Pair("possibly", listOf("TURN_LEFT", "TURN_RIGHT"))
                        "label" -> Pair("no", listOf("TURN_LEFT", "TURN_RIGHT"))
                        else -> Pair("no", listOf())
                        }
                    else -> when (surface) {
                        "label" -> Pair("possibly", listOf("TURN_BACK"))
                        "side" -> Pair("no", listOf("TURN_LEFT", "TURN_RIGHT"))
                        else -> Pair("no", listOf())
                        }
                    }
                else -> Pair("unknown", listOf())
                }
            }

        /** Most cases first; ties alphabetical so the prompt is stable turn to turn. */
        fun targets(
            cases: List<DateLocationCase>,
            geometryClass: String?,
            surfaceInView: String,
            searched: Set<String>,
            ): List<RetrievalTarget> =
        cases.groupBy { it.location }
        .entries.sortedWith(compareBy({ -it.value.size }, { it.key }))
        .map { (location, group) ->
            val (inView, actions)=reach(location, geometryClass, surfaceInView)

            RetrievalTarget(
                location=location,
                cases=group.size,
                details=group.groupingBy { it.annotated_surface.ifBlank { location.lowercase() } }.eachCount()
                .entries.sortedWith(compareBy({ -it.value }, { it.key }))
                .associateTo(LinkedHashMap()) { it.key to it.value },
                onFacesLookedAt=group.count { coveredBy(it, searched) },
                inView=inView,
                reachWith=actions,
                )
            }

        /** Lower case, accents removed. Must match fold() in the index builder. */
        fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")

        fun tokens(text: String?): Set<String> =
        if (text.isNullOrBlank()) setOf() else WORD.findAll(fold(text)).map { it.value }.toSet()
        }
    }

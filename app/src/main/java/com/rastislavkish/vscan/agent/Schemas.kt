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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
* The shapes the three model calls are asked to return.
*
* Everything the model says is coerced through here before it reaches the rest
* of the app, so a malformed reply fails at the boundary rather than halfway
* through an inspection.
*/

/** Did the user carry out the outstanding instruction? */
@Serializable
enum class ActionStatus {

    @SerialName("completed") COMPLETED,
    @SerialName("partial") PARTIAL,
    @SerialName("not_completed") NOT_COMPLETED,

    /**
    * The movement was carried out, and it went the opposite way.
    *
    * Kept apart from every other status because it is the only one that is not
    * about the user. A tip that produces the exact opposite face means the
    * instruction was understood differently from the way it was meant, and the
    * remedy is a different instruction, not the same one again. On 2026-09-16 a
    * can turned to show its top when the base had been asked for; verification
    * called that "completed" while writing "the top of the can is now visible"
    * in the same breath, and the planner - told its instruction had succeeded -
    * asked three more times.
    *
    * Decided on the phone from the face that arrived, not taken on trust from
    * the verifier, which had all the evidence and still said completed.
    */
    @SerialName("wrong_face") WRONG_FACE,

    /**
    * A first-class outcome, not an error. Two frames that differ only by camera
    * shake genuinely do not say whether the package was turned, and pretending
    * otherwise is how a session convinces itself it inspected a face it never
    * saw.
    */
    @SerialName("cannot_determine") CANNOT_DETERMINE,
    }

/**
* How usable the current frame is, recorded separately from what it shows.
*
* Kept apart from ActionStatus on purpose: "bottom visible but wrecked by glare"
* must never collapse into "bottom inspected, no date there".
*/
@Serializable
enum class ViewQuality {

    @SerialName("usable") USABLE,
    @SerialName("poor") POOR,
    @SerialName("unusable") UNUSABLE,
    }

@Serializable
enum class DateType {

    @SerialName("USE_BY") USE_BY,
    @SerialName("BEST_BEFORE") BEST_BEFORE,
    @SerialName("PRODUCTION") PRODUCTION,
    @SerialName("LOT_CODE") LOT_CODE,
    }

/** What the phone knew when the shutter fired. Recorded for the experiment log. */
@Serializable
data class CaptureMetadata(
    val width: Int?=null,
    val height: Int?=null,
    val rotation_degrees: Int?=null,
    val flash_fired: Boolean?=null,
    val device_ms: Int?=null,

    /**
    * Shutter press to the frame arriving. Camera work that happens before the
    * turn's own clock starts, and so was invisible in latency_ms.
    */
    val press_to_image_ms: Int?=null,

    /**
    * True when the agent asked for this frame rather than the user pressing.
    *
    * Recorded on the capture itself so an observation row says how it was
    * triggered without needing to be joined against auto_capture by timestamp.
    * Automatic and manual turns are different experimental conditions and the
    * log has to be able to separate them.
    */
    val automatic: Boolean?=null,

    /**
    * How many frames this capture took, and which of them was sent.
    *
    * Only a trembling hand gets more than one - see FrameSharpness - and the
    * pair is recorded because a burst is a claim that needs checking: if the
    * chosen index is the first one nearly every time, the burst is costing a
    * blind user time in a shop and buying nothing, and nothing else in the log
    * would say so.
    */
    val burst_frames: Int?=null,
    val burst_chosen: Int?=null,
    )

/** What is visible in one photograph. */
@Serializable
data class Perception(
    val geometry: String="unknown",
    val surface_in_view: String="unclear",
    val date_region_visible: Boolean=false,
    val date_legible: Boolean=false,
    val text_read: String?=null,
    val date_string: String?=null,
    val iso_date: String?=null,
    val date_type: DateType?=null,
    val looks_like: String="none",
    val problems: List<String> =listOf(),
    )

/** Result of comparing the previous accepted frame with the current one. */
@Serializable
data class Verification(
    val action_status: ActionStatus=ActionStatus.CANNOT_DETERMINE,
    val view_changed: Boolean=false,
    val what_changed: String?=null,
    val view_quality: ViewQuality=ViewQuality.POOR,
    )

/** What to say next. */
@Serializable
data class Policy(

    /**
    * One of the eleven commands in [GuidanceAction], or ANSWER, ABSTAIN or
    * CONTINUE.
    *
    * A command IS the decision to keep going, so there is no separate field
    * saying which instruction to speak: choosing MOVE_CLOSER is choosing to
    * continue by moving closer. The words for it are rendered on the phone
    * rather than written by the model, so that the package keeps one name for
    * the whole session and no instruction can ask the user to judge something
    * they cannot see.
    *
    * CONTINUE is the one case the vocabulary cannot express - glare, a covered
    * code, holding still, putting the package down - and there the planner
    * writes the sentence itself in [utterance].
    */
    val action: String="CONTINUE",
    val arg: String?=null,
    val utterance: String="",
    val rationale: String?=null,
    val date_string: String?=null,
    val iso_date: String?=null,
    val date_type: DateType?=null,
    val abstain_reason: String?=null,

    /**
    * What the instruction asks the user to change, so the phone knows what it
    * is waiting for before looking again.
    *
    * Added after a user reported the camera firing mid-rotation: without this
    * the capture gate fell back to a timer, and a timer cannot tell "tip the can
    * over" - where it must wait for the movement to finish - from "hold still",
    * where waiting for movement would deadlock forever.
    */
    val expected_change: String?=null,
    )

/** The movement an instruction asks for, as the capture gate sees it. */
object ExpectedChange {

    /** Turning, tipping, rotating: a large change, and it takes time. */
    const val VIEWPOINT="VIEWPOINT"

    /** Closer or further. */
    const val SCALE="SCALE"

    /** Sliding, re-framing, moving a hand out of the way. */
    const val POSITION="POSITION"

    /** Hold still, change the lighting: no movement is being asked for. */
    const val NONE="NONE"

    val all=setOf(VIEWPOINT, SCALE, POSITION, NONE)

    /** True when the gate should wait for movement to happen and then stop. */
    fun involvesMovement(value: String?): Boolean =
    value!=null&&value!=NONE
    }

/**
* One completed turn, as the UI sees it.
*
* This is what used to come back over HTTP from the guidance server. It stayed a
* distinct type after the port because it is the only thing the fragment is
* allowed to act on - the raw Perception and Policy never leave the agent.
*/
data class InspectionOutcome(
    val frameId: Int,
    val actionStatus: ActionStatus,
    val viewQuality: ViewQuality,
    val instruction: String,
    val instructionId: Int,
    val dateText: String?=null,
    val isoDate: String?=null,
    val dateType: DateType?=null,
    val finished: Boolean=false,
    val abstained: Boolean=false,
    val abstainReason: String?=null,
    )

/** Raised when the model answers, but not with something we can act on. */
class ModelOutputException(
    message: String,

    /** Which call produced it, when the reply was received but refused. */
    val stage: String?=null,

    /**
    * The parsed reply that was refused. Without it the log could say only that a
    * reply was unusable, never how - which is how a planner legitimately leaving
    * its fallback wording empty went undiagnosed for a week.
    */
    val raw: String?=null,
    ): Exception(message)

/**
* Raised when the request never got an answer at all.
*
* Kept apart from ModelOutputException because the two call for different things
* to be said: one means try again in a moment, the other means the model is
* misbehaving and trying again will probably do the same.
*/
class TransportException(message: String): Exception(message)

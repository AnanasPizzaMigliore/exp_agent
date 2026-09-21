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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
* Perception, action verification and planning.
*
* Three model calls, deliberately kept apart:
*
*     PERCEPTION(frame)                  -> what is visible right now
*     VERIFY(previous, current, asked)   -> did the requested movement happen
*     POLICY(belief, obs, verify)        -> what to say next        (no image)
*
* Fusing them into one call would be cheaper and would make the failures
* unreadable: when a session goes wrong you need to know whether it mis-saw the
* package, mis-judged whether the user moved it, or mis-decided what to ask for
* next, and one blob of output cannot tell you.
*/
class InspectionAgent(private val client: VisionClient) {

    private val format=Json {
        ignoreUnknownKeys=true
        coerceInputValues=true
        }

    val model: String
    get() = client.model

    suspend fun perceive(imageBase64: String): Pair<Perception, Int> {
        val reply=client.call(Prompts.perception(), listOf(
            ImagePart(imageBase64),
            TextPart("Describe this photograph."),
            ), stage="perception")

        val sanitised=sanitisePerception(reply.json)

        val perception=try {
            format.decodeFromJsonElement(Perception.serializer(), sanitised)
            }
        catch (e: Exception) {
            throw ModelOutputException("Invalid perception output", "perception", reply.json.toString())
            }

        return Pair(perception, reply.tokens)
        }

    suspend fun verify(
        previousBase64: String,
        currentBase64: String,
        instruction: String,

        /**
        * The face the instruction was meant to bring to the camera, when it was
        * a tip; null when the action does not ask for a particular face.
        *
        * Without it the verifier judges the sentence instead of the goal, and a
        * package tipped the opposite way satisfies the sentence: on 2026-09-16
        * it reported "completed" and "the top of the can is now visible" on a
        * turn that had asked for the base.
        */
        expectedSurface: String?=null,
        ): Pair<Verification, Int> {
        val reply=client.call(Prompts.VERIFICATION, listOf(
            TextPart("The user was asked: \"$instruction\""),
            TextPart(
            if (expectedSurface!=null)
            "That instruction was meant to bring the $expectedSurface of the package to the camera."
            else "That instruction did not ask for any particular face."
            ),
            TextPart("First photograph (before):"),
            ImagePart(previousBase64),
            TextPart("Second photograph (after):"),
            ImagePart(currentBase64),
            ), stage="verification")

        val verification=try {
            format.decodeFromJsonElement(Verification.serializer(), sanitiseVerification(reply.json))
            }
        catch (e: Exception) {
            throw ModelOutputException("Invalid verification output", "verification", reply.json.toString())
            }

        return Pair(verification, reply.tokens)
        }

    suspend fun plan(
        belief: JsonElement,
        perception: Perception,
        verification: Verification?,
        history: JsonElement,

        /**
        * True for the retrieval condition, even on a turn where nothing was
        * retrieved: the prompt belongs to the condition, not to the turn.
        */
        withRetrieval: Boolean=false,
        retrieved: JsonElement?=null,

        /**
        * Whether the capture gate has measured this user's hand as trembling.
        *
        * A measurement, not an inference from the picture. The planner is told
        * not to choose ELBOWS_IN without it, because a dim shelf smears a frame
        * in exactly the way an unsteady hand does and bracing does nothing for
        * that one - which is the same distinction [FrameQuality] declines to
        * make from a frame alone.
        */
        handUnsteady: Boolean=false,
        ): Pair<Policy, Int> {
        val payload=buildJsonObject {
            put("belief", belief)
            put("hand_unsteady", handUnsteady)
            put("latest_observation", format.encodeToJsonElement(Perception.serializer(), perception))
            put(
                "last_action_outcome",
                if (verification!=null)
                format.encodeToJsonElement(Verification.serializer(), verification)
                else JsonPrimitive(null as String?),
                )
            put("history", history)

            if (withRetrieval && retrieved!=null)
            put("retrieved_cases", retrieved)
            }

        val reply=client.call(
        if (withRetrieval) Prompts.POLICY_WITH_RETRIEVAL else Prompts.POLICY,
        listOf(TextPart(payload.toString())),
        stage="policy",
        modelOverride=client.policyModel,
        )

        val policy=try {
            format.decodeFromJsonElement(Policy.serializer(), sanitisePolicy(reply.json))
            }
        catch (e: Exception) {
            throw ModelOutputException("Invalid policy output", "policy", reply.json.toString())
            }

        policyProblem(policy)?.let { problem ->
            throw ModelOutputException(problem, "policy", reply.json.toString())
            }

        return Pair(policy, reply.tokens)
        }

    /**
    * Refuse an ANSWER the current frame does not actually support.
    *
    * The failure this prevents is the expensive one: a confidently spoken date
    * that was inferred from what a package of this kind usually says, rather
    * than read off the one in the user's hand. A blind shopper has no way to
    * catch that mistake, which is exactly why it must not be made.
    */
    fun answerIsGrounded(policy: Policy, perception: Perception, seenTypes: Set<DateType>): Boolean {
        if (!perception.date_legible)
        return false

        if (perception.looks_like !in setOf("date", "mixed"))
        return false

        if (!answerDateMatchesPerception(policy, perception))
        return false

        val type=policy.date_type ?: return false

        if (perception.date_type!=null && type==perception.date_type)
        return true

        // A type established on an earlier, cleaner frame is acceptable
        // corroboration: the user has usually moved closer since, which loses the
        // "USE BY" caption while keeping the digits.
        return type in seenTypes
        }

    /**
    * Quality for the first frame of a session, where there is nothing to compare
    * against and so no verification to take it from.
    */
    fun qualityFrom(perception: Perception): ViewQuality {
        val problems=perception.problems.filter { it.isNotBlank() && it!="none" }.toSet()

        if (problems.isEmpty())
        return ViewQuality.USABLE

        if (("blur" in problems||"too_far" in problems) && !perception.date_legible)
        return ViewQuality.POOR

        return ViewQuality.POOR
        }

    // ------------------------------------------------------------------
    // The model is asked for a closed vocabulary and mostly obliges. These
    // repair the ways it does not, so that one stray enum value costs a field
    // rather than the whole turn.
    // ------------------------------------------------------------------

    private fun sanitisePerception(raw: JsonObject): JsonObject = buildJsonObject {
        for ((key, value) in raw) {
            if (key=="_raw")
            continue

            when (key) {
                "date_type" -> put(key, enumOrNull(value, DATE_TYPES))
                "problems" -> put(key, asStringArray(value))
                else -> put(key, value)
                }
            }
        }

    private fun sanitiseVerification(raw: JsonObject): JsonObject = buildJsonObject {
        for ((key, value) in raw) when (key) {
            "action_status" -> put(key, enumOrNull(value, ACTION_STATUSES) ?: JsonPrimitive("cannot_determine"))
            "view_quality" -> put(key, enumOrNull(value, VIEW_QUALITIES) ?: JsonPrimitive("poor"))
            else -> put(key, value)
            }
        }

    private fun sanitisePolicy(raw: JsonObject): JsonObject = buildJsonObject {
        for ((key, value) in raw) when (key) {
            // The planner may only report a consumption date. PRODUCTION and
            // LOT_CODE are things to steer away from, never things to announce.
            "date_type" -> put(key, enumOrNull(value, setOf("USE_BY", "BEST_BEFORE")))
            "abstain_reason" -> put(key, enumOrNull(value, ABSTAIN_REASONS))
            "expected_change" -> put(key, enumOrNull(value, ExpectedChange.all))
            // Case and stray spaces are the model's, not a different decision.
            // Anything still unrecognised is left verbatim so that
            // policyProblem can name it rather than silently continuing.
            "action" -> put(key, normalisedAction(value) ?: value)
            "arg" -> put(key, if (asString(value)=="null") JsonPrimitive(null as String?) else value)
            else -> put(key, value)
            }
        }

    private fun normalisedAction(value: JsonElement): JsonElement? {
        val text=asString(value)?.trim()?.uppercase() ?: return null

        return if (text in ACTIONS) JsonPrimitive(text) else null
        }

    private fun asString(value: JsonElement): String? =
    (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun enumOrNull(value: JsonElement, allowed: Set<String>): JsonElement {
        val text=asString(value)
        return if (text!=null && text in allowed) JsonPrimitive(text) else JsonPrimitive(null as String?)
        }

    /** The model sometimes answers "glare" where the schema asks for ["glare"]. */
    private fun asStringArray(value: JsonElement): JsonElement {
        val single=asString(value)

        return if (single!=null) buildJsonArray { add(JsonPrimitive(single)) } else value
        }

    companion object {

        /** Every action the planner may return: the commands, then the rest. */
        val ACTIONS: Set<String>
        get() = GuidanceTemplates.names+setOf("CONTINUE", "ANSWER", "ABSTAIN")

        /**
        * Why a decoded policy cannot be acted on, or null when it can.
        *
        * utterance is required only where it is what would be spoken: a bare
        * CONTINUE, which is what the planner returns when no command fits and it
        * is writing the sentence itself. A planner that picks TURN_UP and leaves
        * utterance empty is doing what it was told - the phone renders those
        * words. This used to demand utterance on every reply, and turned exactly
        * those replies into "The planner returned nothing to say" - twice in a
        * row on 2026-09-14, ending the inspection. An ANSWER is spoken from its
        * date fields and an ABSTAIN from its reason, so neither needs it either.
        */
        fun policyProblem(policy: Policy): String? {
            if (policy.action !in ACTIONS)
            return "Unknown action ${policy.action}"

            if (policy.action=="CONTINUE" && policy.utterance.isBlank())
            return "The planner returned nothing to say"

            return null
            }

        /**
        * The date that would be spoken must be the date this frame read.
        *
        * The planner reading a different date from the one just perceived means one
        * of them is invented, and neither is safe to speak. This used to be checked
        * only when both sides gave an ISO date, so a planner that left iso_date out
        * could speak any date_string at all: the outcome falls back to date_string
        * exactly when iso_date is missing, which is when nothing compared it.
        *
        * What is spoken is iso_date when present, date_string otherwise, so that
        * value must be backed by the same field of the perception. Where both sides
        * also carry the other field, they must not contradict each other. Printed
        * strings are compared by their digits, so "21/07/27" and "21.07.27" agree.
        */
        fun answerDateMatchesPerception(policy: Policy, perception: Perception): Boolean {
            val policyIso=policy.iso_date?.trim()?.takeIf { it.isNotEmpty() }
            val perceivedIso=perception.iso_date?.trim()?.takeIf { it.isNotEmpty() }
            val policyDigits=digits(policy.date_string)
            val perceivedDigits=digits(perception.date_string)

            if (policyIso!=null) {
                if (policyIso!=perceivedIso)
                return false
                }
            else {
                if (policyDigits.isEmpty() || policyDigits!=perceivedDigits)
                return false
                }

            if (policyDigits.isNotEmpty() && perceivedDigits.isNotEmpty() && policyDigits!=perceivedDigits)
            return false

            return true
            }

        private fun digits(text: String?): String =
        text?.filter { it.isDigit() } ?: ""

        private val DATE_TYPES=setOf("USE_BY", "BEST_BEFORE", "PRODUCTION", "LOT_CODE")
        private val ACTION_STATUSES=setOf("completed", "partial", "not_completed", "wrong_face", "cannot_determine")
        private val VIEW_QUALITIES=setOf("usable", "poor", "unusable")
        private val ABSTAIN_REASONS=setOf("illegible", "no_date_exists", "budget")
        }
    }

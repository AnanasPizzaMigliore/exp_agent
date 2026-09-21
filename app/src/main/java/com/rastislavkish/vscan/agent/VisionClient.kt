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

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText

import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

import com.rastislavkish.vscan.BuildConfig

/** One part of a multimodal user message. */
sealed class Part
data class TextPart(val text: String): Part()
data class ImagePart(val jpegBase64: String): Part()

/** What one call cost and what it said. */
data class ModelReply(val json: JsonObject, val tokens: Int)

/**
* What one call cost in wall clock and bytes.
*
* Separate from ModelReply because it is emitted for failed calls too: a turn
* that times out is precisely the one whose duration you want recorded, and it
* has no reply to hang the numbers off.
*/
data class CallTrace(
    val stage: String,
    val model: String,
    val elapsedMs: Long,
    val requestBytes: Int,
    val imageCount: Int,
    val imageBytes: Int,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val finishReason: String? = null,
    val error: String? = null,

    /** HTTP status, when the request got far enough to have one. */
    val httpStatus: Int? = null,

    /**
    * First 200 characters of a reply that could not be parsed. Set only on
    * failure: "the model did not return JSON" is unactionable on its own, and
    * the difference between a 429 page, an empty body and a chatty preamble is
    * the whole diagnosis.
    */
    val bodySnippet: String? = null,
    )

/**
* Talks to the vision model directly from the phone.
*
* Deliberately not built on core/openai/Conversation. That class is organised
* around a Config and a ProvidersManager, keeps a growing message list, and
* resolves vscan- model identifiers through a provider table - all of which
* exists to serve the scan screen's "ask a question about this picture" flow.
* The agent wants the opposite: three independent, stateless calls whose
* conversation history is deliberately NOT accumulated, because each one is
* given exactly the context the agent chose for it.
*/
class VisionClient(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
    private val baseUrl: String = BuildConfig.GEMINI_BASE_URL,
    val model: String = BuildConfig.GEMINI_MODEL,
    val policyModel: String = BuildConfig.GEMINI_POLICY_MODEL,
    ) {

    private val format=Json { ignoreUnknownKeys=true }

    /**
    * Called once per call, success or failure, with its measured cost. A hook
    * rather than a logger reference: this class does no I/O it is not asked to
    * do, and the caller already owns the run directory.
    */
    var onTrace: ((CallTrace) -> Unit)?=null

    private val client=HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis=CALL_TIMEOUT_MS
            connectTimeoutMillis=CONNECT_TIMEOUT_MS
            socketTimeoutMillis=CALL_TIMEOUT_MS
            }
        }

    val configured: Boolean
    get() = apiKey.isNotBlank()

    /**
    * One call. Returns the parsed JSON object the prompt asked for.
    *
    * A reasoning model can burn its whole budget before emitting anything, which
    * surfaces as finish_reason "length" with empty content rather than an error.
    * That is treated as a failure here, not as an empty observation.
    */
    suspend fun call(
        system: String,
        parts: List<Part>,
        stage: String = "call",
        modelOverride: String? = null,
        ): ModelReply {
        if (!configured)
        throw ModelOutputException("No vision API key in this build. Set geminiApiKey in local.properties and rebuild.")

        val callModel=modelOverride?.takeIf { it.isNotBlank() } ?: model

        val payload=buildJsonObject {
            put("model", callModel)
            put("max_tokens", MAX_TOKENS)
            putJsonArrayOfMessages(system, parts)
            }

        val body=payload.toString()
        val images=parts.filterIsInstance<ImagePart>()

        // Sizes are taken from what is actually about to go on the wire, not
        // from the encoder's intent: the point of measuring is to catch the turn
        // where those two disagree.
        var trace=CallTrace(
            stage=stage,
            model=callModel,
            elapsedMs=0,
            requestBytes=body.length,
            imageCount=images.size,
            imageBytes=images.sumOf { it.jpegBase64.length/4*3 },
            )

        val startedAt=System.nanoTime()

        try {
            val text=try {
                val response=client.post("${baseUrl.trimEnd('/')}/chat/completions") {
                    header("Content-Type", "application/json")
                    bearerAuth(apiKey)
                    setBody(body)
                    }
                trace=trace.copy(httpStatus=response.status.value)
                response.bodyAsText()
                }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                // The cause is kept: "no route to host" and "certificate expired"
                // need different things done about them, and collapsing both into
                // one sentence cost an afternoon once already.
                throw TransportException(e.message ?: e.toString())
                }

            // Gemini returns errors as a single-element ARRAY - [{"error": ...}] -
            // where a success is a plain object. Parsing straight to jsonObject
            // threw on those, so a "quota exceeded, retry in 17s" arrived at the
            // user as "the model returned something unusable": the one message
            // that says what to do, replaced by one that does not.
            val element=try {
                format.parseToJsonElement(text)
                }
            catch (e: Exception) {
                trace=trace.copy(bodySnippet=text.take(200))
                throw ModelOutputException("The model did not return JSON")
                }

            val root=(element as? JsonObject)
            ?: (element as? JsonArray)?.firstOrNull() as? JsonObject
            ?: run {
                trace=trace.copy(bodySnippet=text.take(200))
                throw ModelOutputException("The model did not return JSON")
                }

            (root["error"] as? JsonObject)?.let { error ->
                val message=(error["message"] as? JsonPrimitive)?.content ?: "unknown error"
                val status=(error["status"] as? JsonPrimitive)?.content

                trace=trace.copy(bodySnippet=text.take(200))

                // RESOURCE_EXHAUSTED is not "something went wrong": it is a
                // quota the user can wait out or raise, and the provider even
                // says how long. Worth saying so plainly.
                if (status=="RESOURCE_EXHAUSTED")
                throw Exception("The vision service is rate limited right now. $message")

                throw Exception(message)
                }

            val usage=root["usage"] as? JsonObject
            val tokens=usageField(usage, "total_tokens")

            trace=trace.copy(
                promptTokens=usageField(usage, "prompt_tokens"),
                completionTokens=usageField(usage, "completion_tokens"),
                totalTokens=tokens,
                )

            val choice=(root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw ModelOutputException("The model returned no choices")

            val finishReason=(choice["finish_reason"] as? JsonPrimitive)?.content
            val content=((choice["message"] as? JsonObject)?.get("content") as? JsonPrimitive)
            ?.content?.trim() ?: ""

            trace=trace.copy(finishReason=finishReason)

            if (finishReason=="length" && content.isEmpty())
            throw ModelOutputException("Reasoning exceeded the token limit")

            if (content.isEmpty())
            throw ModelOutputException("The model returned nothing")

            val parsed=try {
                format.parseToJsonElement(stripCodeFence(content)).jsonObject
                }
            catch (e: Exception) {
                trace=trace.copy(bodySnippet=content.take(200))
                throw ModelOutputException("The model did not return JSON: ${content.take(120)}")
                }

            return ModelReply(parsed, tokens)
            }
        catch (e: Exception) {
            trace=trace.copy(error=e.message ?: e.toString())
            throw e
            }
        finally {
            // In finally so an abandoned turn - the user pressed Stop mid-call -
            // still reports how long it had been waiting.
            trace=trace.copy(elapsedMs=(System.nanoTime()-startedAt)/1_000_000)
            try {
                onTrace?.invoke(trace)
                }
            catch (e: Exception) {

                }
            }
        }

    private fun usageField(usage: JsonObject?, name: String): Int =
    (usage?.get(name) as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    /**
    * Open the connection before the user needs it.
    *
    * A session starts a few seconds before the first Capture - the user is
    * still lining the package up - and that idle window is free. GET /models
    * rather than a token-costing completion: DNS, TCP and the TLS handshake are
    * the point, and the body is thrown away.
    *
    * Never throws. Failing to warm up is not a reason to fail a session, and
    * being offline is already reported before this runs.
    */
    suspend fun warmUp() {
        if (!configured)
        return

        try {
            client.get("${baseUrl.trimEnd('/')}/models") {
                bearerAuth(apiKey)
                }.bodyAsText()
            }
        catch (e: Exception) {

            }
        }

    fun close() {
        client.close()
        }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArrayOfMessages(
        system: String,
        parts: List<Part>,
        ) {
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", system)
                })
            add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    for (part in parts) when (part) {
                        is TextPart -> add(buildJsonObject {
                            put("type", "text")
                            put("text", part.text)
                            })
                        is ImagePart -> add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:image/jpeg;base64,${part.jpegBase64}")
                                }
                            })
                        }
                    })
                })
            })
        }

    private fun stripCodeFence(text: String): String {
        if (!text.startsWith("```"))
        return text

        var lines=text.lines()
        if (lines.isNotEmpty() && lines.first().startsWith("```"))
        lines=lines.drop(1)
        if (lines.isNotEmpty() && lines.last().trim()=="```")
        lines=lines.dropLast(1)

        return lines.joinToString("\n").trim()
        }

    companion object {

        const val CONNECT_TIMEOUT_MS=10_000L

        /**
        * Per call, not per turn. A turn makes three of these, so the user can
        * still wait a while - but each one is bounded, which is what stops a
        * stalled request leaving someone standing in an aisle indefinitely.
        *
        * 120s is sized to the slowest model this has been pointed at rather than
        * the fastest. Measured perception latency, laptop-side: gemini-3.5-flash
        * and qwen3-vl-plus answer in 3-4s, qwen3.8-max in 87-114s. At 45s the
        * latter failed every single call, which looked like a network fault and
        * was not one. A generous ceiling makes a slow model merely slow; a tight
        * one makes it indistinguishable from broken.
        */
        const val CALL_TIMEOUT_MS=120_000L

        const val MAX_TOKENS=4000
        }
    }

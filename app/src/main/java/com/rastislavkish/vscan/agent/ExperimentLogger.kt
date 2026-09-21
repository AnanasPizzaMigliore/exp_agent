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

import android.content.Context
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
* The experiment record, one JSON object per line.
*
* This used to be a file on the laptop. Now that the agent runs on the phone it
* lands in the app's external files directory, which is readable over adb
* without root and survives an app upgrade:
*
*     adb pull /sdcard/Android/data/com.rastislavkish.vscan/files/runs/events.jsonl
*
* Frames are written next to it only when the user has turned that on. These are
* photographs taken in a shop; treat them as consented research data with a
* retention policy, not as a cache.
*/
class ExperimentLogger(context: Context, private val storeImages: Boolean) {

    private val directory: File? = try {
        File(context.getExternalFilesDir(null), "runs").apply { mkdirs() }
        }
    catch (e: Exception) {
        null
        }

    private val eventLog: File? = directory?.let { File(it, "events.jsonl") }
    private var traceWriter: BufferedWriter?=null
    private var traceSession: String?=null
    private var traceRowsSinceFlush=0

    /**
    * Never throws. A logging failure - full storage, a revoked directory - must
    * not take down an inspection the user is halfway through; the run is simply
    * unrecorded, and that is the lesser loss.
    */
    fun log(kind: String, sessionId: String, fields: JsonObject) {
        val target=eventLog ?: return

        val record=buildJsonObject {
            put("ts", System.currentTimeMillis()/1000.0)
            put("elapsed_realtime_ns", SystemClock.elapsedRealtimeNanos())
            put("kind", kind)
            put("session_id", sessionId)
            for ((key, value) in fields) put(key, value)
            }

        try {
            target.appendText(record.toString()+"\n")
            }
        catch (e: Exception) {

            }
        }

    fun log(kind: String, sessionId: String, vararg fields: Pair<String, JsonElement>) {
        log(kind, sessionId, buildJsonObject { for ((key, value) in fields) put(key, value) })
        }

    /**
    * High-rate, replayable controller record kept apart from the cumulative log.
    *
    * A separate per-session file avoids mixing old schemas and lets the replay
    * scorer consume exactly one physical trace. Samples are buffered so writing
    * an 8 KB fingerprint on every analysis frame does not become part of the
    * controller's latency; non-sample lifecycle rows flush immediately.
    */
    @Synchronized
    fun trace(kind: String, sessionId: String, fields: JsonObject) {
        val root=directory ?: return

        try {
            if (traceSession!=sessionId||traceWriter==null) {
                closeTrace()
                val folder=File(root, "traces/$sessionId").apply { mkdirs() }
                traceWriter=BufferedWriter(OutputStreamWriter(
                    FileOutputStream(File(folder, "trace.jsonl"), true), Charsets.UTF_8))
                traceSession=sessionId
                }

            val record=buildJsonObject {
                put("schema_version", CONTROLLER_TRACE_SCHEMA)
                put("elapsed_realtime_ns", SystemClock.elapsedRealtimeNanos())
                put("kind", kind)
                put("session_id", sessionId)
                for ((key, value) in fields) put(key, value)
                }
            traceWriter?.write(record.toString())
            traceWriter?.newLine()
            traceRowsSinceFlush+=1
            if (kind!="controller_sample"||traceRowsSinceFlush>=TRACE_FLUSH_ROWS) {
                traceWriter?.flush()
                traceRowsSinceFlush=0
                }
            }
        catch (e: Exception) {
            closeTrace()
            }
        }

    @Synchronized
    fun closeTrace() {
        try { traceWriter?.flush() } catch (e: Exception) { }
        try { traceWriter?.close() } catch (e: Exception) { }
        traceWriter=null
        traceSession=null
        traceRowsSinceFlush=0
        }

    /** Persist a frame, but only with consent. Returns the path written, if any. */
    fun saveFrame(sessionId: String, frameId: Int, jpeg: ByteArray, before: Boolean=false): String? {
        if (!storeImages)
        return null

        val root=directory ?: return null

        return try {
            val folder=File(root, "images/$sessionId").apply { mkdirs() }
            val name=if (before) "frame_%04d_before.jpg" else "frame_%04d.jpg"
            val file=File(folder, name.format(frameId))
            file.writeBytes(jpeg)
            file.absolutePath
            }
        catch (e: Exception) {
            null
            }
        }

    /** What the run directory holds, for a screen that has to say so out loud. */
    data class LogStatus(
        val sessions: Int,
        val bytes: Long,
        val lastWriteMs: Long,
        val images: Int,
        ) {

        val empty: Boolean
        get() = bytes<=0L
        }

    companion object {

        /**
        * Read the run directory back.
        *
        * Exists because a study may be run with no computer in the room. Until
        * now nothing on the phone could say whether anything was being
        * recorded: [log] swallows every failure by design - a full volume or a
        * directory that went away looks exactly like a session nobody started -
        * and the directory itself cannot be opened from the phone at all, since
        * Android 11 hides Android/data from the Files app and from MTP.
        *
        * Streamed rather than read in. The file grows for the whole study and
        * this is called on a screen the operator opens between participants.
        */
        fun status(context: Context): LogStatus {
            val root=try {
                File(context.getExternalFilesDir(null), "runs")
                }
            catch (e: Exception) {
                null
                }
            ?: return LogStatus(0, 0L, 0L, 0)

            val log=File(root, "events.jsonl")
            if (!log.exists())
            return LogStatus(0, 0L, 0L, imageCount(root))

            var sessions=0
            try {
                log.forEachLine { if (it.contains(SESSION_START)) sessions+=1 }
                }
            catch (e: Exception) {

                }

            return LogStatus(sessions, log.length(), log.lastModified(), imageCount(root))
            }

        /** Frames on disk, which share the volume with the log and can crowd it. */
        private fun imageCount(root: File): Int = try {
            File(root, "images").listFiles()?.sumOf { it.listFiles()?.size ?: 0 } ?: 0
            }
        catch (e: Exception) {
            0
            }

        /** As the record is written: no spaces, so this matches a whole line. */
        private const val SESSION_START="\"kind\":\"session_start\""
        const val CONTROLLER_TRACE_SCHEMA=2
        private const val TRACE_FLUSH_ROWS=25

        fun text(value: String?): JsonElement = JsonPrimitive(value)
        fun number(value: Int): JsonElement = JsonPrimitive(value)
        fun number(value: Long): JsonElement = JsonPrimitive(value)
        fun flag(value: Boolean): JsonElement = JsonPrimitive(value)
        }
    }

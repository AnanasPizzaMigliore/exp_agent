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

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
* Says one instruction at a time, out loud.
*
* The rest of VScan announces through Toast and lets TalkBack read it. That is
* fine for a result you asked for; it is not fine for a guidance loop, where the
* user's hands are busy turning a package and an instruction has to arrive
* without them going looking for it. So this speaks directly.
*
* Two things it does deliberately:
*
* - Every utterance flushes the queue. Instructions supersede each other; the
*   one that matters is the newest, and a backlog read out in order would have
*   the user acting on advice about a frame two captures old.
* - It speaks on the accessibility usage stream, the same one TalkBack uses, so
*   the two duck around each other instead of talking over each other. They can
*   still overlap - TalkBack reading a button label while an instruction
*   arrives - which is why the instruction is also shown in the status line and
*   can be repeated on demand.
*/
class GuidanceSpeaker(context: Context) {

    private var tts: TextToSpeech?=null
    private var ready=false
    private var initFailed=false
    private val mainHandler=Handler(Looper.getMainLooper())

    /** Main-thread callback covering queued speech as well as audible playback. */
    var onBusyChanged: ((Boolean) -> Unit)?=null

    /**
    * Reports what the speech actually did, from the engine's own callbacks.
    *
    * tts.speak() is asynchronous and returns almost immediately, so the time
    * from having an answer to handing it over says nothing about when the user
    * heard anything. Only onStart does. Arguments: event ("start" or "done"),
    * and milliseconds since the utterance was handed to the engine.
    */
    var onAudio: ((event: String, sinceDispatchMs: Long) -> Unit)?=null

    private var sequence=0
    private val dispatchedAt=HashMap<String, Long>()

    /** Spoken as soon as init finishes, if something was said before it did. */
    private var pending: String?=null

    /** What "Repeat" repeats. Survives an interruption on purpose. */
    var lastUtterance: String?=null
    private set

    init {
        tts=TextToSpeech(context.applicationContext) { status ->
            if (status==TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.getDefault())
                tts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        report("start", utteranceId)
                        }
                    override fun onDone(utteranceId: String?) {
                        report("done", utteranceId)
                        }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        report("stopped", utteranceId)
                        }
                    @Deprecated("Required by the base class")
                    override fun onError(utteranceId: String?) {
                        report("error", utteranceId)
                        }
                    })

                ready=true

                val queued=pending
                pending=null
                if (queued!=null)
                speakNow(queued)
                }
            else {
                initFailed=true
                pending=null
                onBusyChanged?.invoke(false)
                }
            }
        }

    fun speak(text: String) {
        if (text.isBlank())
        return

        lastUtterance=text
        if (initFailed) return

        if (!ready) {
            pending=text
            onBusyChanged?.invoke(true)
            return
            }

        speakNow(text)
        }
    /**
    * Say something transient - "Looking", a progress cue - without it becoming
    * the outstanding instruction.
    *
    * Separate from speak() on purpose: lastUtterance is what Repeat repeats, and
    * a user who presses Repeat while waiting wants to hear what they were asked
    * to do, not that the phone is busy.
    */
    fun notice(text: String) {
        if (text.isBlank())
        return

        if (!ready)
        return

        speakNow(text)
        }
    /** Say the outstanding instruction again, unchanged. */
    fun repeat() {
        val text=lastUtterance ?: return
        speak(text)
        }
    /** Cut off whatever is being said. Used by stop and pause. */
    fun interrupt() {
        pending=null
        synchronized(dispatchedAt) { dispatchedAt.clear() }
        tts?.stop()
        onBusyChanged?.invoke(false)
        }
    fun shutdown() {
        interrupt()
        tts?.shutdown()
        tts=null
        ready=false
        }

    private fun speakNow(text: String) {
        onBusyChanged?.invoke(true)
        // A fresh id per utterance: QUEUE_FLUSH means the previous one may never
        // reach onDone, and a shared id would make the two indistinguishable.
        sequence+=1
        val id="$UTTERANCE_ID-$sequence"

        synchronized(dispatchedAt) {
            // Only the newest matters; flushing discards the rest.
            dispatchedAt.clear()
            dispatchedAt[id]=System.nanoTime()
            }

        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)!=TextToSpeech.SUCCESS)
        report("error", id)
        }

    /** Called on a TTS binder thread, not the main thread. */
    private fun report(event: String, utteranceId: String?) {
        val id=utteranceId ?: return

        val started=synchronized(dispatchedAt) { dispatchedAt[id] } ?: return

        val elapsed=(System.nanoTime()-started)/1_000_000
        mainHandler.post {
            // A flushed utterance's late callback must not unblock its replacement.
            val current=synchronized(dispatchedAt) { dispatchedAt.containsKey(id) }
            if (current) {
                onAudio?.invoke(event, elapsed)
                if (event!="start") {
                    synchronized(dispatchedAt) { dispatchedAt.remove(id) }
                    onBusyChanged?.invoke(false)
                    }
                }
            }
        }

    companion object {

        private const val UTTERANCE_ID="vscan-guidance"
        }
    }

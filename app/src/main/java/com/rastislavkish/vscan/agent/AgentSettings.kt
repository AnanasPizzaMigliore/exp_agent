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
import android.content.SharedPreferences

/**
* What the on-device expiry agent is allowed to do.
*
* A fourth SharedPreferences file rather than a few more keys in Settings: the
* three upstream stores are VScan's own, and keeping the research build's
* configuration separate means a merge from upstream never has to think about
* it. Same singleton-over-preferences shape as the other three.
*
* The provider credentials are NOT here. They come from local.properties via
* BuildConfig, so they are fixed at build time and there is nothing to type.
*/
class AgentSettings(
    val preferences: SharedPreferences
    ) {

    /**
    * Keep each captured frame in the run directory alongside events.jsonl. Off
    * by default and never turned on implicitly: these are photographs taken in
    * a shop, and the consent has to be the user's.
    */
    var storeImages=false

    /** Speak instructions through TextToSpeech as well as toasting them. */
    var speakGuidance=true

    /**
    * Run the shadow-mode stability analysis.
    *
    * Worth its own switch because it is the one feature here with a running
    * cost: it binds an ImageAnalysis use case, which keeps the camera streaming
    * for the whole session. Until now this app powered the sensor only for
    * individual stills, and a blind user may hold a session open in a shop for
    * minutes. Measures only - it never withholds a capture.
    */
    var visualStability=true

    /**
    * Submit a frame on the agent's own initiative once the view has settled
    * after an instruction, instead of waiting for a button press.
    *
    * Requires visualStability: the decision is made from those measurements.
    * Manual capture always remains available and always overrides - a user who
    * presses the button gets a photograph taken, settled or not.
    */
    var autoCapture=true

    /**
    * Give the planner the date locations of similar reference packages.
    *
    * A switch because it is an experimental condition, not a preference: runs
    * with and without it are compared, and session_start records which one a
    * run was.
    */
    var retrievalMemory=true

    /**
    * Also match brand words printed on the package, not only its geometry.
    * Off gives the geometry-only condition. Ignored when retrievalMemory is off.
    */
    var retrievalNameMatching=true

    /**
    * Run the ablation arms beside the live controller and log what each would
    * have captured.
    *
    * Costs six small state machines per analysis frame and nothing else: the
    * arms never touch the camera, never speak, and cannot change what the
    * session does. Off by default because it is a measurement, not a feature,
    * and because it writes a log row on every arm's would-be capture.
    */
    var ablationArms=false

    /**
    * Name the face a tip is after, check the face that arrives, and keep a
    * replaced tip replaced until its face is seen.
    *
    * An experimental condition rather than a preference, so it is recorded in
    * session_start and can be overridden for one session by [RunConfig]. Off
    * gives the behaviour of builds before 16 September 2026: "Turn up." with
    * the convention unstated, a verification call not told which face was asked
    * for, and an override that lapses the moment it fires.
    *
    * One switch for all three, because they are one change - an instruction
    * whose meaning is shared with the person carrying it out - and because
    * separating them needs more sessions than the comparison has.
    */
    var instructionGrounding=true

    /**
    * Which capture controller drives the camera, by name from [GateConditions].
    *
    * On the settings screen so the ablation can be run from the phone alone. A
    * run_config.json written from a laptop still overrides it for one session,
    * which is what an automated block uses; this is for running the study by
    * hand.
    */
    var gateCondition=GateConditions.FULL

    /**
    * The scripted behaviour this session is being run under, or empty for
    * ordinary use. Recorded in the log as run_label and read by nothing else.
    *
    * It has to be set deliberately, because a stale label is worse than none:
    * it attaches a verdict to a session whose behaviour nobody scripted.
    */
    var blockLabel=""

    /**
    * Which study package is in the user's hand, by identifier from
    * [GateConditions.studyLabels], or empty for ordinary use.
    *
    * Recorded as run_label and read by nothing else. A run_config.json written
    * from a laptop still overrides it for one session; this exists so a paired
    * study can be run from the phone alone.
    */
    var productLabel=""

    /**
    * Which participant is holding the phone, by pseudonym from
    * [GateConditions.participants], or empty outside a study. Written to the log
    * as participant and read by nothing else.
    */
    var participant=""

    /**
    * The participant and package of the session before this one, as one key.
    *
    * Run state rather than configuration, and it lives here only because this
    * is the agent's single store. It exists to catch one specific mistake: the
    * product spinner is sticky, so moving to the next package without changing
    * it logs the new session under the old package's name, with nothing in the
    * events to say so and no way to recover which package was in the hand.
    *
    * Persisted rather than held in memory so the check survives the app being
    * restarted or reinstalled between two packages - which, in a study where
    * the build is being changed between sessions, is the normal case rather
    * than the exception.
    */
    var lastRunKey=""

    fun load() {
        storeImages=preferences.getBoolean("storeImages", false)
        speakGuidance=preferences.getBoolean("speakGuidance", true)
        visualStability=preferences.getBoolean("visualStability", true)
        autoCapture=preferences.getBoolean("autoCapture", true)
        retrievalMemory=preferences.getBoolean("retrievalMemory", true)
        retrievalNameMatching=preferences.getBoolean("retrievalNameMatching", true)
        ablationArms=preferences.getBoolean("ablationArms", false)
        instructionGrounding=preferences.getBoolean("instructionGrounding", true)
        gateCondition=preferences.getString("gateCondition", GateConditions.FULL) ?: GateConditions.FULL
        blockLabel=preferences.getString("blockLabel", "") ?: ""
        productLabel=preferences.getString("productLabel", "") ?: ""
        participant=preferences.getString("participant", "") ?: ""
        lastRunKey=preferences.getString("lastRunKey", "") ?: ""
        }
    fun save() {
        preferences.edit()
        .putBoolean("storeImages", storeImages)
        .putBoolean("speakGuidance", speakGuidance)
        .putBoolean("visualStability", visualStability)
        .putBoolean("autoCapture", autoCapture)
        .putBoolean("retrievalMemory", retrievalMemory)
        .putBoolean("retrievalNameMatching", retrievalNameMatching)
        .putBoolean("ablationArms", ablationArms)
        .putBoolean("instructionGrounding", instructionGrounding)
        .putString("gateCondition", gateCondition)
        .putString("blockLabel", blockLabel)
        .putString("productLabel", productLabel)
        .putString("participant", participant)
        .putString("lastRunKey", lastRunKey)
        .commit()
        }

    companion object {

        private var instance: AgentSettings?=null

        fun getInstance(context: Context): AgentSettings {
            if (instance==null) {
                val preferences=context.getSharedPreferences("VScanAgent", Context.MODE_PRIVATE)
                instance=AgentSettings(preferences)
                instance?.load()
                }

            return instance!!
            }
        }
    }

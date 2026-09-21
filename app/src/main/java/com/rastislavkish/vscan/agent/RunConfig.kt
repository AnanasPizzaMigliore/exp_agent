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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
* What this particular session is, for an experiment run from a laptop.
*
* Read from a file in the app's external files directory, beside events.jsonl,
* because that is somewhere adb can write without root and without the app
* being involved:
*
*     adb shell "echo '{\"label\":\"P25\",\"instruction_grounding\":false}' \
*       > /sdcard/Android/data/com.rastislavkish.vscan/files/run_config.json"
*
* A settings screen would do the same job in forty-eight taps, each of them a
* chance to run a session under the wrong condition and not find out until the
* log is scored. SharedPreferences would be the obvious place instead, except
* that the app caches them, so a value written underneath it from adb is not
* reliably the value the next session reads.
*
* Everything here is recorded verbatim in session_start. Nothing here changes
* what the agent is capable of; [instruction_grounding] selects between two
* implementations that both already exist, and [label] is inert.
*/
@Serializable
data class RunConfig(

    /** Operator pseudonym for a scripted controller run; never a real name. */
    val operator_id: String?=null,

    /** Identifier shared by the matched E/Q sessions of one scripted case. */
    val pair_id: String?=null,

    /**
    * Which package is in the user's hand, e.g. "P25".
    *
    * Deliberately NOT product_hint. That one is a hint to the agent: it reaches
    * the planner through belief() and is joined into the text retrieval matches
    * names against, so putting a product id there would both tell the agent the
    * answer's neighbourhood and corrupt the retrieval condition. This is written
    * to the log and read by nothing else, which is what makes it safe to set
    * while an experimental comparison is running.
    */
    val label: String?=null,

    /**
    * The scripted behaviour this session is being performed under, by name from
    * [GateConditions.blocks]. Null leaves [AgentSettings.blockLabel] to decide;
    * empty means ordinary use and gets no verdict.
    *
    * Separate from [label] because they are independent facts - which package
    * was in the hand, and what the person holding it was asked to do - and a
    * single field made an operator write "A_idle_can" to record both. An
    * unrecognised name is not refused, unlike [gate]: the scorer leaves its
    * captures counted and unscored, which is better than a wrong verdict.
    */
    val block_label: String?=null,

    /**
    * Name the face in tip instructions, check the face that arrives, and keep a
    * replaced tip replaced. Null leaves [AgentSettings.instructionGrounding] to
    * decide.
    *
    * One switch for all three because they are one change - an instruction whose
    * meaning is shared with the user - and because forty-eight sessions cannot
    * separate three factors.
    */
    val instruction_grounding: Boolean?=null,

    /** Freeze retrieval for an experimental session; null follows settings. */
    val retrieval_memory: Boolean?=null,

    /**
    * Whether the agent takes frames on its own initiative. Null leaves
    * [AgentSettings.autoCapture] to decide.
    *
    * There is no switch for this on the settings screen, and the controller
    * ablation needs it off: with automatic capture on, the live gate consumes
    * each arming event and calls the model, so a session yields about five
    * decisions per shadow arm instead of a dozen and costs tokens for them.
    * With it off, Pause and Resume re-arm all six arms for nothing.
    */
    val auto_capture: Boolean?=null,

    /**
    * Which capture controller drives the camera this session, by name from
    * [GateConditions]. Null leaves the shipped one in place.
    *
    * This is what makes the controller ablation a measurement of real sessions
    * rather than of what a shadow arm would have done. An unrecognised name is
    * refused loudly at session start rather than silently falling back, because
    * a run labelled as an ablation that quietly used the full controller is
    * worse than a run that did not start.
    */
    val gate: String?=null,
    ) {

    companion object {

        const val FILE_NAME="run_config.json"

        private val format=Json { ignoreUnknownKeys=true }

        /**
        * The config for the next session, or an empty one.
        *
        * Never throws. A run started with a half-written or malformed file
        * should record that it had no config and carry on, not fail to start:
        * the file is written by a script between sessions, and losing a session
        * to a syntax error is worse than losing the label for it.
        */
        fun read(context: Context): RunConfig {
            val file=File(context.getExternalFilesDir(null), FILE_NAME)

            return try {
                if (file.exists()) format.decodeFromString(serializer(), file.readText())
                else RunConfig()
                }
            catch (e: Exception) {
                RunConfig()
                }
            }
        }
    }

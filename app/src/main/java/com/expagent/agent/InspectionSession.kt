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

/**
* Where an inspection has got to, on the device side.
*
* VScan's TabAdapter.consultConfig() builds a fresh Conversation per call, so
* there is no cross-turn memory in the app to hang this off. The reasoning
* memory - which faces have been looked at, how well - lives on the backend;
* what lives here is only what the UI has to answer immediately: whose session
* this is, which frame is next, which instruction the user is currently acting
* on, and whether anything is still in flight.
*/
enum class InspectionPhase {

    /** No session. The expiry controls do nothing. */
    IDLE,

    /** An instruction has been spoken and we are waiting for the user to capture. */
    WAITING,

    /** A frame is with the backend. */
    SENDING,

    /** The user paused. Captures are refused until they resume. */
    PAUSED,

    /** A date was read, or the agent gave up. Nothing more will be sent. */
    FINISHED,
    }

class InspectionSession(val sessionId: String) {

    var phase=InspectionPhase.WAITING

    /**
    * Strictly increasing within a session; the backend rejects anything at or
    * below the last frame it accepted, which is what makes a late duplicate
    * harmless rather than confusing.
    */
    private var frameCounter=0

    /** The instruction the next capture is an answer to. */
    var instructionId: Int?=null
    var instruction: String=""

    /**
    * The approved command behind [instruction], when there was one.
    *
    * The spoken words are not enough to check the next frame against. What the
    * verification call needs to know is which face was asked for, and only the
    * command says that; the sentence says it in English, which is what the
    * verifier was already failing to hold the user to. Null for the opening
    * instruction, for a recovery prompt, and for anything the planner worded
    * itself - none of those ask for a particular face.
    */
    var instructionAction: GuidanceAction?=null

    var framesSent=0
    var lastDateText: String?=null
    var lastDateType: String?=null

    val active: Boolean
    get() = phase!=InspectionPhase.IDLE && phase!=InspectionPhase.FINISHED

    /** Set the moment the user asks to stop, before the network catches up. */
    var stopRequested=false

    fun nextFrameId(): Int {
        frameCounter+=1
        return frameCounter
        }

    /**
    * Human-readable state for the status line. Kept short on purpose: TalkBack
    * reads it on every change, and a sentence there competes with the spoken
    * instruction the user is actually trying to follow.
    */
    fun statusText(): String = when (phase) {
        InspectionPhase.IDLE -> "Not inspecting"
        InspectionPhase.WAITING -> "Waiting for you"
        InspectionPhase.SENDING -> "Checking image"
        InspectionPhase.PAUSED -> "Paused"
        InspectionPhase.FINISHED -> "Finished"
        }
    }

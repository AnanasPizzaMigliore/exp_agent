/*
* Copyright (C) 2026 exp agent contributors
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, version 3.
*/

package com.expagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lifecycle regressions for the live quality-aware comparator. */
class QualityAwareCaptureControllerTest {
    private var now=0L

    private fun newGate(priorUpload: Boolean=false)=EventCaptureController(
        mode=CaptureGateMode.QUALITY_AWARE,
        qualityStartsWithPriorUpload=priorUpload,
        checkResponseFreshness=false,
        )

    private fun frames(
        gate: EventCaptureController,
        ms: Int,
        quiet: Boolean=true,
        reliable: Boolean=true,
        sinceUpload: ViewRelation=ViewRelation.UNKNOWN,
        ) {
        repeat(ms/100) {
            now+=100_000_000L
            gate.sample(
                now,
                change=ViewRelation.UNKNOWN,
                quiet=quiet,
                reliable=reliable,
                submittedView=ViewRelation.UNKNOWN,
                lastUploadedView=sinceUpload,
                )
            gate.noteStableFrame(now, now)
            }
        }

    private fun openingCapture(gate: EventCaptureController): EventCaptureTicket {
        gate.start("session-a")
        gate.arm(CaptureReason.INITIAL_OBSERVATION, 1, ExpectedChange.NONE, now)
        frames(gate, 700)
        val ticket=requireNotNull(gate.reserve(automatic=true, nowNs=now))
        assertTrue(gate.acceptCapture(ticket, now))
        return ticket
        }

    @Test
    fun stableOpeningViewCanBeCaptured() {
        val gate=newGate()
        gate.start("session-a")
        gate.arm(CaptureReason.INITIAL_OBSERVATION, 1, ExpectedChange.NONE, now)

        frames(gate, 600)
        assertNull(gate.reserve(automatic=true, nowNs=now))
        frames(gate, 100)
        assertNotNull(gate.reserve(automatic=true, nowNs=now))
        }

    @Test
    fun unchangedViewIsSuppressedAcrossRequestCompletionAndRearming() {
        val gate=newGate()
        val first=openingCapture(gate)
        assertTrue(gate.release(first))
        gate.arm(CaptureReason.POST_ACTION_CHANGE, 2, ExpectedChange.VIEWPOINT, now)

        frames(gate, 3_000, sinceUpload=ViewRelation.SAME)

        assertNull(gate.reserve(automatic=true, nowNs=now))
        }

    @Test
    fun stableChangedViewCanBeCapturedAfterRearming() {
        val gate=newGate()
        val first=openingCapture(gate)
        assertTrue(gate.release(first))
        gate.arm(CaptureReason.POST_ACTION_CHANGE, 2, ExpectedChange.VIEWPOINT, now)

        frames(gate, 2_000, sinceUpload=ViewRelation.DIFFERENT)

        assertNotNull(gate.reserve(automatic=true, nowNs=now))
        }

    @Test
    fun oneEligibleFrameCanReserveOnlyOneTicket() {
        val gate=newGate()
        gate.start("session-a")
        gate.arm(CaptureReason.INITIAL_OBSERVATION, 1, ExpectedChange.NONE, now)
        frames(gate, 700)

        assertNotNull(gate.reserve(automatic=true, nowNs=now))
        assertNull(gate.reserve(automatic=true, nowNs=now))
        assertNull(gate.reserve(automatic=false, nowNs=now))
        }

    @Test
    fun manualReservationWinsWithoutADoubleAutomaticReservation() {
        val gate=newGate()
        gate.start("session-a")
        gate.arm(CaptureReason.INITIAL_OBSERVATION, 1, ExpectedChange.NONE, now)
        frames(gate, 700)

        val manual=requireNotNull(gate.reserve(automatic=false, nowNs=now))
        assertFalse(manual.automatic)
        assertNull(gate.reserve(automatic=true, nowNs=now))
        }

    @Test
    fun movementDuringCameraWorkRejectsTheStillWithoutRecordingAnUpload() {
        val gate=newGate()
        gate.start("session-a")
        gate.arm(CaptureReason.INITIAL_OBSERVATION, 1, ExpectedChange.NONE, now)
        frames(gate, 700)
        val ticket=requireNotNull(gate.reserve(automatic=true, nowNs=now))

        frames(gate, 400, quiet=false, sinceUpload=ViewRelation.DIFFERENT)

        assertFalse(gate.acceptCapture(ticket, now))
        assertTrue(gate.release(ticket))
        gate.arm(CaptureReason.RETRY_OBSERVATION, 2, ExpectedChange.NONE, now)
        frames(gate, 2_000, sinceUpload=ViewRelation.UNKNOWN)
        assertNotNull("a rejected still must not become the duplicate reference",
            gate.reserve(automatic=true, nowNs=now))
        }

    @Test
    fun qualityBaselineIntentionallyAcceptsAResponseAfterTheViewMoves() {
        val gate=newGate()
        val ticket=openingCapture(gate)

        frames(gate, 1_000, quiet=false, sinceUpload=ViewRelation.DIFFERENT)

        assertTrue(gate.responseIsFresh(ticket, now))
        }

    @Test
    fun staleCallbackCannotReleaseANewerSessionReservation() {
        val gate=newGate()
        val old=openingCapture(gate)
        gate.stop()
        gate.start("session-b")
        gate.arm(CaptureReason.INITIAL_OBSERVATION, 1, ExpectedChange.NONE, now)
        frames(gate, 700)
        val fresh=requireNotNull(gate.reserve(automatic=true, nowNs=now))

        assertFalse(gate.release(old))
        assertTrue(gate.owns(fresh))
        }
    }

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

import org.junit.Assert.*
import org.junit.Test

class EventCaptureControllerTest {
    private val gate=EventCaptureController()
    private var now=0L

    private fun start(reason: CaptureReason=CaptureReason.INITIAL_OBSERVATION, expected: String?=null) {
        gate.start("session-a")
        gate.arm(reason, 1, expected, now)
        }

    private fun frames(ms: Int, high: Boolean=false, reliable: Boolean=true,
        view: ViewRelation=if (high) ViewRelation.DIFFERENT else ViewRelation.SAME,
        submitted: ViewRelation=view) {
        repeat(ms/100) {
            now+=100_000_000L
            gate.sample(now, change=view, quiet=!high, reliable=reliable, submittedView=submitted)
            }
        }

    private fun action() {
        start(CaptureReason.POST_ACTION_CHANGE, ExpectedChange.VIEWPOINT)
        frames(700)
        gate.noteStableFrame(17, now)
        }

    private fun readyTicket(): EventCaptureTicket {
        start()
        frames(700)
        return requireNotNull(gate.reserve(true, now))
        }

    @Test fun firstObservationNeedsSettlingButNotMovement() {
        start()
        frames(600)
        assertNull(gate.reserve(true, now))
        frames(100)
        assertNotNull(gate.reserve(true, now))
        }

    @Test fun movementDeadlineReportsStallWithoutSendingUnchangedView() {
        action()
        frames(15_000)
        assertTrue(gate.stalled)
        assertNull(gate.reserve(true, now))
        }

    @Test fun briefShakeDoesNotCompleteAnEvent() {
        action()
        frames(100, high=true)
        frames(1500)
        assertNull(gate.reserve(true, now))
        }

    @Test fun movementThenSettlingProducesOneIdentifiedCapture() {
        action()
        frames(400, high=true)
        frames(1000, view=ViewRelation.DIFFERENT)
        val ticket=requireNotNull(gate.reserve(true, now))
        assertEquals(17L, ticket.beforeFrameId)
        assertNotNull(ticket.movementStartedAtNs)
        assertEquals(1L, ticket.eventId)
        assertEquals(1, ticket.instructionId)
        assertNull(gate.reserve(true, now))
        assertNull(gate.reserve(false, now))
        }

    @Test fun pauseInsideRotationRestartsSettling() {
        action()
        frames(400, high=true)
        frames(500, view=ViewRelation.DIFFERENT)
        assertNull(gate.reserve(true, now))
        frames(400, high=true)
        frames(900, view=ViewRelation.DIFFERENT)
        assertNull(gate.reserve(true, now))
        frames(100, view=ViewRelation.DIFFERENT)
        assertNotNull(gate.reserve(true, now))
        }

    @Test fun movementDuringSpeechIsObservedButSubmissionWaits() {
        action()
        gate.setSpeaking(true)
        frames(400, high=true)
        frames(1000, view=ViewRelation.DIFFERENT)
        assertNull(gate.reserve(true, now))
        gate.setSpeaking(false)
        assertNotNull(gate.reserve(true, now))
        }

    @Test fun manualCaptureBypassesVisualEvidenceNotConcurrency() {
        start(CaptureReason.POST_ACTION_CHANGE, ExpectedChange.SCALE)
        val ticket=requireNotNull(gate.reserve(false, now))
        assertFalse(ticket.automatic)
        assertNull(gate.reserve(false, now))
        assertTrue(gate.acceptCapture(ticket, now))
        assertNull(gate.reserve(false, now))
        }

    @Test fun holdStillAllowsARefreshWithoutMovement() {
        start(CaptureReason.POST_ACTION_CHANGE, ExpectedChange.NONE)
        frames(1000)
        assertNotNull(gate.reserve(true, now))
        }

    @Test fun unknownExpectedChangeDoesNotSilentlyMeanHoldStill() {
        start(CaptureReason.POST_ACTION_CHANGE)
        frames(2000)
        assertNull(gate.reserve(true, now))
        }

    @Test fun resumedMovementInvalidatesCaptureEvenIfItSettlesBeforeCallback() {
        val ticket=readyTicket()
        frames(100, high=true)
        frames(1000)
        assertFalse(gate.acceptCapture(ticket, now))
        assertTrue(gate.release(ticket))
        gate.arm(CaptureReason.RETRY_OBSERVATION, 1, ExpectedChange.NONE, now)
        frames(1000)
        assertNotNull(gate.reserve(true, now))
        }

    @Test fun aCaptureIsNotUnmadeByWhatHappensAfterTheShutter() {
        // This asserted the opposite until the first participant run, where the
        // second test threw away four captures of eighteen - three of them on
        // samples reading settling=same with the gyroscope between 0.03 and
        // 0.15 rad/s, which is to say on hands that were fine.
        //
        // reserve() has already required READY and isStable before the shutter
        // fires. By the time the camera returns, the frames have existed for a
        // second or more, and neither a lapsed hold nor a gap in analysis is
        // evidence about a photograph that was taken before either happened.
        val ticket=readyTicket()
        now+=1_000_000_000L
        assertTrue(gate.acceptCapture(ticket, now))
        }

    @Test fun aViewThatLeftDuringTheCaptureStillInvalidatesIt() {
        // The one thing still worth refusing, because it is the only one that is
        // about the photograph: the frame is of somewhere else.
        val ticket=readyTicket()
        frames(400, high=true)
        assertFalse(gate.acceptCapture(ticket, now))
        }

    @Test fun unreliableViewCannotTriggerAutomaticCapture() {
        start()
        frames(3000, reliable=false)
        assertNull(gate.reserve(true, now))
        }

    @Test fun analysisGapRestartsSettlingAndClearsPreEventReference() {
        start()
        frames(600)
        now+=1_000_000_000L
        gate.sample(now, change=ViewRelation.SAME, quiet=true, reliable=true)
        assertNull(gate.reserve(true, now))
        assertNull(gate.lastStableFrameId)
        frames(700)
        assertNotNull(gate.reserve(true, now))
        }

    @Test fun oldSamplesAreIgnored() {
        start()
        frames(700)
        gate.sample(now-1, change=ViewRelation.DIFFERENT, quiet=false, reliable=true)
        assertNotNull(gate.reserve(true, now))
        }

    @Test fun pauseInvalidatesPendingCameraCallbackAndResumeRearms() {
        val ticket=readyTicket()
        gate.pause()
        assertNull(gate.reserve(false, now))
        assertFalse(gate.acceptCapture(ticket, now))
        gate.resume(2, now)
        frames(1000)
        val fresh=requireNotNull(gate.reserve(true, now))
        assertEquals(2, fresh.instructionId)
        assertFalse(gate.release(ticket))
        assertTrue(gate.owns(fresh))
        }

    @Test fun oldSessionCallbackCannotClearNewCameraReservation() {
        val old=readyTicket()
        gate.stop()
        gate.start("session-b")
        gate.arm(CaptureReason.INITIAL_OBSERVATION, 1, null, now)
        frames(700)
        val fresh=requireNotNull(gate.reserve(true, now))
        assertFalse(gate.release(old))
        assertFalse(gate.acceptCapture(old, now))
        assertTrue(gate.owns(fresh))
        }

    @Test fun failedRequestCanReleaseAndRearmWithoutAnotherMovement() {
        val ticket=readyTicket()
        assertTrue(gate.acceptCapture(ticket, now))
        assertTrue(gate.release(ticket))
        gate.arm(CaptureReason.RETRY_OBSERVATION, 1, ExpectedChange.NONE, now)
        frames(1000)
        assertNotNull(gate.reserve(true, now))
        }

    @Test fun movementDuringRequestMakesResponseStaleWithoutQueuingUploads() {
        val ticket=readyTicket()
        assertTrue(gate.acceptCapture(ticket, now))
        frames(400, high=true)
        frames(1000, view=ViewRelation.DIFFERENT)
        assertFalse(gate.responseIsFresh(ticket))
        assertNull(gate.reserve(true, now))
        }

    @Test fun briefTremorDuringRequestDoesNotAutomaticallyInvalidateAnswer() {
        val ticket=readyTicket()
        assertTrue(gate.acceptCapture(ticket, now))
        frames(100, high=true)
        frames(1000)
        assertTrue(gate.responseIsFresh(ticket))
        }

    @Test fun stoppedGateHasNoResidualBusyOrSpeechState() {
        val ticket=readyTicket()
        gate.acceptCapture(ticket, now)
        gate.setSpeaking(true)
        gate.stop()
        assertFalse(gate.speaking)
        assertNull(gate.activeTicket)
        assertFalse(gate.responseIsFresh(ticket))
        }

    @Test fun releasingSuccessDoesNotAutomaticallyRecaptureSameView() {
        val ticket=readyTicket()
        gate.acceptCapture(ticket, now)
        gate.release(ticket)
        frames(2000)
        assertNull(gate.reserve(true, now))
        }

    @Test fun manualRetryAfterDisarmingCarriesTheNewInstructionId() {
        val ticket=readyTicket()
        gate.acceptCapture(ticket, now)
        gate.release(ticket)
        gate.disarm(instruction=3)
        val manual=requireNotNull(gate.reserve(false, now))
        assertEquals(3, manual.instructionId)
        }

    @Test fun pausedRequestCannotBecomeFreshAfterResume() {
        val old=readyTicket()
        gate.acceptCapture(old, now)
        gate.pause()
        gate.resume(2, now)
        frames(1000)
        val fresh=requireNotNull(gate.reserve(true, now))
        gate.acceptCapture(fresh, now)
        frames(400)
        assertFalse(gate.responseIsFresh(old))
        assertFalse(gate.release(old))
        assertTrue(gate.responseIsFresh(fresh))
        }

    @Test fun sustainedOutAndBackDoesNotSubmitTheOriginalView() {
        action()
        frames(600, high=true)
        frames(2000)
        assertNull(gate.reserve(true, now))
        }

    @Test fun settledDifferentViewCanConfirmActionWithoutContinuingToMove() {
        action()
        frames(100, high=true)
        frames(1000, view=ViewRelation.DIFFERENT)
        assertNotNull(gate.reserve(true, now))
        }

    @Test fun restoredViewCanRecoverFreshnessAfterSustainedDisplacement() {
        val ticket=readyTicket()
        gate.acceptCapture(ticket, now)
        frames(1200, high=true)
        assertFalse(gate.responseIsFresh(ticket))
        frames(300)
        assertFalse(gate.responseIsFresh(ticket))
        frames(100)
        assertTrue(gate.responseIsFresh(ticket))
        }

    @Test fun automaticAnswerRequiresObservedRecentMatchNotJustNoMotion() {
        val ticket=readyTicket()
        gate.acceptCapture(ticket, now)
        assertFalse(gate.responseIsFresh(ticket))
        frames(1000, submitted=ViewRelation.UNKNOWN)
        assertFalse(gate.responseIsFresh(ticket))
        frames(400)
        assertTrue(gate.responseIsFresh(ticket))
        assertFalse(gate.responseIsFresh(ticket, now+400_000_000L))
        }

    @Test fun gyroVetoAloneNeverConfirmsAProductEvent() {
        action()
        frames(1500, high=true, view=ViewRelation.SAME)
        frames(2000)
        assertEquals(0L, gate.eventId)
        assertNull(gate.reserve(true, now))
        }

    @Test fun manualUnknownViewRemainsUsableButCannotEraseKnownDisplacement() {
        start()
        val ticket=requireNotNull(gate.reserve(false, now))
        gate.acceptCapture(ticket, now)
        frames(1000, reliable=false, submitted=ViewRelation.UNKNOWN)
        assertTrue(gate.responseIsFresh(ticket))
        frames(100, submitted=ViewRelation.DIFFERENT)
        frames(1000, reliable=false, submitted=ViewRelation.UNKNOWN)
        assertFalse(gate.responseIsFresh(ticket))
        frames(400)
        assertTrue(gate.responseIsFresh(ticket))
        }

    @Test fun analysisGapBreaksTheResponseMatchHold() {
        val ticket=readyTicket()
        gate.acceptCapture(ticket, now)
        frames(400)
        assertTrue(gate.responseIsFresh(ticket))
        now+=1_000_000_000L
        gate.sample(now, ViewRelation.SAME, true, true, ViewRelation.SAME)
        assertFalse(gate.responseIsFresh(ticket))
        frames(400)
        assertTrue(gate.responseIsFresh(ticket))
        }

    @Test fun aGapDoesNotHideKnownDisplacementFromAManualRequest() {
        start()
        val ticket=requireNotNull(gate.reserve(false, now))
        gate.acceptCapture(ticket, now)
        frames(400)
        now+=1_000_000_000L
        gate.sample(now, ViewRelation.DIFFERENT, false, true, ViewRelation.DIFFERENT)
        assertFalse(gate.responseIsFresh(ticket))
        frames(400, reliable=false, submitted=ViewRelation.UNKNOWN)
        assertFalse(gate.responseIsFresh(ticket))
        }

    @Test fun bufferStaysBoundedAndPreservesSelectedSnapshotReference() {
        val buffer=StableFrameBuffer(3)
        for (id in 1L..3L) buffer.add(StableSnapshot(id, id, byteArrayOf(id.toByte())))
        val pinned=buffer.get(1)
        buffer.add(StableSnapshot(4, 4, byteArrayOf(4)))
        assertEquals(3, buffer.size)
        assertNull(buffer.get(1))
        assertArrayEquals(byteArrayOf(1), pinned!!.jpeg)
        buffer.clear()
        assertEquals(0, buffer.size)
        }
    }

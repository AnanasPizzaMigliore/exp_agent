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

enum class CaptureReason {
    INITIAL_OBSERVATION,
    POST_ACTION_CHANGE,
    USER_REQUEST,
    RETRY_OBSERVATION,
    }

enum class EventPhase {
    INACTIVE, WAITING_FOR_MOVEMENT, TRACKING_MOVEMENT, SETTLING,
    READY, CAPTURING, REQUEST_RUNNING, PAUSED,
    }

/**
* The decision rule that owns the otherwise shared capture lifecycle.
*
* QUALITY_AWARE is the live comparator: it waits for a stable, non-duplicate
* view, but has no instruction-relative movement state and does not reject a
* returned response because the view subsequently changed. Keeping it in this
* controller means both study arms use the same tickets, camera reservation,
* capture rejection, pause/stop handling and one-request-at-a-time rules.
*/
enum class CaptureGateMode { INSTRUCTION_CONDITIONED, QUALITY_AWARE }

/** Immutable identity carried through CameraX and the model turn. No image bytes. */
data class EventCaptureTicket(
    val sessionId: String,
    val instructionId: Int?,
    val eventId: Long,
    val token: Long,
    val reason: CaptureReason,
    val automatic: Boolean,
    val beforeFrameId: Long?,
    val movementStartedAtNs: Long?,
    val settledAtNs: Long?,
    val reservedAtNs: Long,
    )

/**
* Android-free, single-owner event gate. All times use one monotonic clock.
*
* Inspired by the pre/action/post event monitor in arXiv:2603.23950, III-C1.
* Activity is supplied by the caller: this is NOT an optical-flow implementation
* or a semantic action-completion detector. A timeout reports a stall, never
* fabricates movement. Manual capture bypasses visual gates, not lifecycle gates.
*/
class EventCaptureController(
    val onsetNs: Long=250_000_000L,
    val initialHoldNs: Long=600_000_000L,
    val actionHoldNs: Long=900_000_000L,
    val maxGapNs: Long=300_000_000L,
    val stallNs: Long=10_000_000_000L,
    val responseHoldNs: Long=300_000_000L,

    /**
    * How long one break in the quiet may last before the hold restarts.
    *
    * The hold used to be all-or-nothing: a single non-quiet sample nulled it,
    * and a hand with a tremor was asked for six hundred to nine hundred
    * unbroken milliseconds it may never produce. It has quiet instants; what it
    * does not have is a quiet interval. The session then reached only the stall
    * deadline, and the app's answer to that is to invite a manual capture,
    * which takes the photograph with no visual gate at all - the worst frame of
    * the three outcomes, handed to the user as if it were the safe one.
    *
    * So a break shorter than this is absorbed and the hold carries on. Two
    * things stop that from becoming a hold that never restarts. It is well
    * under [onsetNs], so anything long enough to be confirmed as real movement
    * is far too long to be absorbed here. And absorption is refused outright
    * when the view no longer matches the instruction reference - a tremor
    * wobbles in place, so a view that has actually left is not one.
    *
    * Zero restores the previous behaviour exactly, which is the
    * E_no_tremor_tolerance ablation.
    */
    val quietGraceNs: Long=150_000_000L,

    /** Which decision rule is allowed to move the shared lifecycle to READY. */
    val mode: CaptureGateMode=CaptureGateMode.INSTRUCTION_CONDITIONED,

    /** Minimum separation between accepted captures in the quality baseline. */
    val qualityMinIntervalNs: Long=1_500_000_000L,

    /** For replay fragments that begin after an earlier upload. Live runs use false. */
    val qualityStartsWithPriorUpload: Boolean=false,

    /** False only for a condition intentionally ablating response freshness. */
    val checkResponseFreshness: Boolean=true,
    ) {

    var phase=EventPhase.INACTIVE
    private set
    var sessionId=""
    private set
    var beforeFrameId: Long?=null
    private set
    var lastStableFrameId: Long?=null
    private set
    var activeTicket: EventCaptureTicket?=null
    private set
    var speaking=false
    private set
    var stalled=false
    private set
    val eventId: Long get() = eventSequence
    val currentMovementStartNs: Long? get() = movementStartedAtNs

    private var tokenSequence=0L
    private var eventSequence=0L
    private var instructionId: Int?=null
    private var reason: CaptureReason?=null
    private var needsMovement=false
    private var armedAtNs=0L
    private var lastAtNs: Long?=null
    private var quietSinceNs: Long?=null

    /** Start of the break in progress, or null while the view is quiet. */
    private var unquietSinceNs: Long?=null

    /** Time spent broken since [quietSinceNs], so a flicker cannot pass as quiet. */
    private var unquietTotalNs=0L
    private var onsetSinceNs: Long?=null
    private var onsetBeforeId: Long?=null
    private var movementStartedAtNs: Long?=null
    private var burstConfirmed=false
    private var eventObserved=false
    private var captureInvalidated=false
    private var requestRelation=ViewRelation.UNKNOWN
    private var requestSameSinceNs: Long?=null

    /** Start of the run away from the submitted view, or null while it matches. */
    private var requestAwaySinceNs: Long?=null
    private var requestDisplaced=false
    private var latestReliable=false

    /** Quality-aware state deliberately outlives an instruction and rearming. */
    private var qualityHasUploaded=false
    private var qualityLastAcceptedAtNs: Long?=null

    fun start(session: String) {
        stop()
        sessionId=session
        qualityHasUploaded=qualityStartsWithPriorUpload
        qualityLastAcceptedAtNs=null
        phase=EventPhase.WAITING_FOR_MOVEMENT
        }

    fun stop() {
        sessionId=""
        phase=EventPhase.INACTIVE
        reason=null
        activeTicket=null
        speaking=false
        lastAtNs=null
        lastStableFrameId=null
        beforeFrameId=null
        qualityHasUploaded=false
        qualityLastAcceptedAtNs=null
        resetEvent()
        }

    fun arm(reason: CaptureReason, instruction: Int?, expected: String?, nowNs: Long) {
        if (sessionId.isEmpty()||phase==EventPhase.PAUSED||activeTicket!=null)
        return
        this.reason=reason
        instructionId=instruction
        needsMovement=mode==CaptureGateMode.INSTRUCTION_CONDITIONED&&
            reason==CaptureReason.POST_ACTION_CHANGE&&expected!=ExpectedChange.NONE
        armedAtNs=nowNs
        resetEvent()
        phase=if (needsMovement) EventPhase.WAITING_FOR_MOVEMENT else EventPhase.SETTLING
        }

    fun disarm(instruction: Int?=instructionId) {
        instructionId=instruction
        reason=null
        if (activeTicket==null&&phase!=EventPhase.PAUSED&&phase!=EventPhase.INACTIVE)
        phase=EventPhase.WAITING_FOR_MOVEMENT
        }

    fun pause() {
        if (phase==EventPhase.INACTIVE) return
        activeTicket=null
        reason=null
        speaking=false
        resetEvent()
        phase=EventPhase.PAUSED
        }

    fun resume(instruction: Int?, nowNs: Long) {
        if (phase!=EventPhase.PAUSED) return
        lastAtNs=null
        lastStableFrameId=null
        phase=EventPhase.WAITING_FOR_MOVEMENT
        arm(CaptureReason.RETRY_OBSERVATION, instruction, ExpectedChange.NONE, nowNs)
        }

    fun setSpeaking(value: Boolean) {
        speaking=value
        }

    /** Change is relative to a FIXED instruction view, not the preceding frame.
    * A changed view can already be quiet: these are independent signals.
    */
    fun sample(nowNs: Long, change: ViewRelation, quiet: Boolean, reliable: Boolean,
        submittedView: ViewRelation=ViewRelation.UNKNOWN,

        /**
        * Whether the view is wobbling about one place rather than going
        * somewhere - SteadinessWindow's OSCILLATING verdict.
        *
        * The licence for absorbing a break, and it has to be positive evidence
        * rather than the absence of a long one. Duration alone cannot separate
        * a tremor from a slow drift: a view creeping three pixels a frame
        * registers as unquiet about one sample in three, every one of them
        * short, and the running total alone lets that through as a hold. The
        * window can tell the two apart because it asks whether the view keeps
        * coming back, so the gate asks the window.
        */
        oscillating: Boolean=false,

        /** Current view against the most recently accepted upload. */
        lastUploadedView: ViewRelation=submittedView) {
        if (mode==CaptureGateMode.QUALITY_AWARE) {
            sampleQualityAware(nowNs, quiet, reliable, lastUploadedView)
            return
            }
        if (phase==EventPhase.INACTIVE||phase==EventPhase.PAUSED) return
        val last=lastAtNs
        if (last!=null&&nowNs<=last) return
        lastAtNs=nowNs
        val gap=last!=null&&nowNs-last>maxGapNs
        val delta=if (last!=null&&!gap) nowNs-last else 0L
        latestReliable=reliable&&!gap

        // Decided once per sample, because it has a cost - the break it charges
        // against the hold's budget - and three places below ask about it.
        val absorbed=(!quiet||!latestReliable)&&oscillating&&absorb(nowNs, delta, change, gap)
        if (quiet&&latestReliable)
        unquietSinceNs=null

        if (phase==EventPhase.REQUEST_RUNNING) {
            // A gap breaks the quiet hold, but does not erase an observed mismatch
            // against the fixed submitted reference (including manual captures).
            requestRelation=submittedView

            // One DIFFERENT sample used to condemn the whole reply, and the
            // only way back was 300ms of unbroken SAME - which is the interval
            // a tremulous hand cannot produce. So a wobble during a model call
            // threw away a completed turn: three of them in the second run,
            // 24000 tokens and about twenty seconds of a blind user's time. A
            // view that has genuinely left stays away for longer than an onset.
            if (requestRelation==ViewRelation.DIFFERENT) {
                if (requestAwaySinceNs==null) requestAwaySinceNs=nowNs

                // Only an automatic capture gets the benefit of the doubt. It
                // was gated visually before it fired, and a tremor crosses the
                // match boundary constantly during a model call. A manual one
                // was never gated at all, so a single observed displacement is
                // all the evidence there is and it counts.
                if (activeTicket?.automatic!=true||nowNs-requestAwaySinceNs!!>=onsetNs)
                requestDisplaced=true
                }
            else requestAwaySinceNs=null
            if (requestRelation==ViewRelation.SAME&&quiet&&latestReliable) {
                if (requestSameSinceNs==null) requestSameSinceNs=nowNs
                if (nowNs-requestSameSinceNs!!>=responseHoldNs) requestDisplaced=false
                }
            else requestSameSinceNs=null
            }

        if (!latestReliable) {
            onsetSinceNs=null
            // We cannot establish a pre-event image across an unobserved gap.
            lastStableFrameId=null
            // Blur is how a tremor reads when it costs a frame its texture, so a
            // brief unmeasurable run is absorbed like any other break. A gap is
            // not: absorb refuses those, because nothing was observed to absorb.
            if (!absorbed)
            restartHold()
            if (activeTicket==null&&reason!=null) phase=EventPhase.SETTLING
            updateStall(nowNs)
            return
            }

        // Sustained motion during the capture invalidates it; a tremor does not.
        //
        // Both halves matter. Judging this on the instruction relation instead
        // rejected every capture taken after a completed movement, because that
        // relation is DIFFERENT for the whole of such a turn by design. Judging
        // it on any unquiet sample rejected photographs taken by hands that
        // were measurably fine. The absorbed run length is the thing that
        // distinguishes them.
        if (phase==EventPhase.CAPTURING&&!quiet&&!absorbed)
        captureInvalidated=true

        if (change==ViewRelation.DIFFERENT&&activeTicket==null&&reason!=null) {
            if (onsetSinceNs==null) {
                onsetSinceNs=nowNs
                onsetBeforeId=lastStableFrameId
                }
            if (!burstConfirmed&&nowNs-onsetSinceNs!!>=onsetNs) {
                burstConfirmed=true
                eventObserved=true
                eventSequence+=1
                beforeFrameId=onsetBeforeId
                movementStartedAtNs=onsetSinceNs
                }
            }
        else {
            onsetSinceNs=null
            if (change==ViewRelation.SAME&&activeTicket==null) {
                burstConfirmed=false
                eventObserved=false
                beforeFrameId=null
                movementStartedAtNs=null
                }
            }

        if (quiet) {
            if (quietSinceNs==null) {
                quietSinceNs=nowNs
                unquietTotalNs=0L
                }
            if (activeTicket==null&&reason!=null) {
                val held=nowNs-quietSinceNs!!
                val required=if (reason==CaptureReason.INITIAL_OBSERVATION) initialHoldNs else actionHoldNs
                phase=when {
                    needsMovement&&(!eventObserved||change!=ViewRelation.DIFFERENT) -> EventPhase.WAITING_FOR_MOVEMENT
                    held<required -> EventPhase.SETTLING
                    else -> EventPhase.READY
                    }
                }
            }
        else if (absorbed) {
            // The hold survives the break; the instant itself is still not one to
            // photograph on, so this can hold at SETTLING but never reach READY.
            if (activeTicket==null&&reason!=null)
            phase=EventPhase.SETTLING
            }
        else {
            restartHold()
            if (activeTicket==null&&reason!=null)
            phase=if (change==ViewRelation.DIFFERENT) EventPhase.TRACKING_MOVEMENT else EventPhase.SETTLING
            }
        updateStall(nowNs)
        }

    /**
    * Condition Q: stable, sufficiently separated and different from the last
    * accepted upload. It intentionally knows nothing about requested movement.
    */
    private fun sampleQualityAware(
        nowNs: Long,
        quiet: Boolean,
        reliable: Boolean,
        lastUploadedView: ViewRelation,
        ) {
        if (phase==EventPhase.INACTIVE||phase==EventPhase.PAUSED) return

        val last=lastAtNs
        if (last!=null&&nowNs<=last) return
        lastAtNs=nowNs
        val gap=last!=null&&nowNs-last>maxGapNs
        latestReliable=reliable&&!gap

        // The photograph is rejected only when an observed movement overlaps
        // the camera operation. Request-running views cannot make Q submit a
        // second image, and Q deliberately has no returned-view freshness test.
        if (phase==EventPhase.CAPTURING&&latestReliable&&!quiet)
        captureInvalidated=true
        if (activeTicket!=null) return

        if (reason==null) {
            phase=EventPhase.WAITING_FOR_MOVEMENT
            restartHold()
            return
            }

        if (!latestReliable||!quiet) {
            restartHold()
            phase=EventPhase.SETTLING
            updateStall(nowNs)
            return
            }

        if (quietSinceNs==null) quietSinceNs=nowNs
        val held=nowNs-quietSinceNs!!
        val intervalReady=qualityLastAcceptedAtNs?.let {
            nowNs-it>=qualityMinIntervalNs
            } ?: true
        val novel=!qualityHasUploaded||lastUploadedView==ViewRelation.DIFFERENT

        phase=if (held>=initialHoldNs&&intervalReady&&novel)
            EventPhase.READY
        else EventPhase.SETTLING
        updateStall(nowNs)
        }

    /**
    * Whether this break may be absorbed without restarting the hold.
    *
    * Three ways to be refused: there is no hold to protect yet; nothing was
    * observed at all, so there is nothing to make the claim from; or the break
    * is simply too long, either at a stretch or added up over the hold.
    *
    * It used to refuse a fourth case - the view reading DIFFERENT against the
    * instruction reference - on the reasoning that a tremor wobbles in place
    * and a view that has left is not one. That conflated two different things.
    * DIFFERENT means the view is no longer where the instruction anchor was; it
    * does NOT mean the view is moving now. After a user completes a movement
    * that was asked of them, DIFFERENT is the correct and permanent state for
    * the rest of the turn - it is what onset detection exists to notice - so
    * the tolerance switched itself off on exactly the captures that follow an
    * instruction. Measured over the second participant run it was off for 52%
    * of samples, and 9 of 13 rejected captures were post_action_change.
    *
    * What actually separates a tremor from a movement here is duration, and
    * that is what [quietGraceNs] and the running total already test: a real
    * movement holds the view unquiet for far longer than the grace.
    *
    * The running total is what stops an alternating stream from holding
    * indefinitely on breaks that are each individually short. A long hold may
    * spend [MAX_UNQUIET_FRACTION] of itself broken; a short one is allowed one
    * full grace regardless, or the first break would always end it.
    */
    private fun absorb(nowNs: Long, delta: Long, change: ViewRelation, gap: Boolean): Boolean {
        if (quietGraceNs<=0L||gap)
        return false

        val since=quietSinceNs ?: return false

        if (unquietSinceNs==null) unquietSinceNs=nowNs
        val run=nowNs-unquietSinceNs!!
        val spent=unquietTotalNs+delta
        val budget=maxOf(quietGraceNs, ((nowNs-since)*MAX_UNQUIET_FRACTION).toLong())

        if (run>quietGraceNs||spent>budget)
        return false

        unquietTotalNs=spent
        return true
        }

    private fun restartHold() {
        quietSinceNs=null
        unquietSinceNs=null
        unquietTotalNs=0L
        }

    fun isStable(nowNs: Long): Boolean = latestReliable&&lastAtNs?.let { nowNs-it<=maxGapNs }==true&&
    quietSinceNs?.let { nowNs-it>=initialHoldNs }==true

    fun noteStableFrame(id: Long, nowNs: Long) {
        if (isStable(nowNs)) lastStableFrameId=id
        }

    /** Reservation happens BEFORE CameraX is invoked, for manual captures too. */
    fun reserve(automatic: Boolean, nowNs: Long): EventCaptureTicket? {
        if (phase in setOf(EventPhase.INACTIVE, EventPhase.PAUSED, EventPhase.CAPTURING, EventPhase.REQUEST_RUNNING))
        return null
        if (automatic&&(phase!=EventPhase.READY||speaking||!isStable(nowNs))) return null
        val ticket=EventCaptureTicket(
            sessionId, instructionId, eventSequence, ++tokenSequence,
            if (automatic) reason ?: return null else CaptureReason.USER_REQUEST,
            automatic, beforeFrameId, movementStartedAtNs, quietSinceNs, nowNs,
            )
        activeTicket=ticket
        captureInvalidated=false
        phase=EventPhase.CAPTURING
        return ticket
        }

    fun owns(ticket: EventCaptureTicket): Boolean = activeTicket==ticket&&sessionId==ticket.sessionId

    /**
    * Keep the photograph this ticket reserved.
    *
    * Deliberately does NOT re-test stability. [reserve] already required
    * phase==READY and [isStable] before the shutter fired, and by the time this
    * is called the frames have existed for a second or more - on a burst,
    * measured at a median of 1.4s. Asking the hand to still be holding its
    * quiet at that point is a second, independent test of something that can no
    * longer affect the picture, and under a tremor it is close to a coin flip.
    * The photons are long gone; what is left is a file, and whether that file
    * is any good is a question about the file.
    *
    * What survives is the one thing that is still about the photograph: if the
    * view LEFT while the camera was working, the frame is of somewhere else.
    */
    fun acceptCapture(ticket: EventCaptureTicket, nowNs: Long): Boolean {
        if (!owns(ticket)||phase!=EventPhase.CAPTURING) return false
        if (ticket.automatic&&captureInvalidated) return false
        if (mode==CaptureGateMode.QUALITY_AWARE) {
            qualityHasUploaded=true
            qualityLastAcceptedAtNs=nowNs
            }
        phase=EventPhase.REQUEST_RUNNING
        requestRelation=ViewRelation.UNKNOWN
        requestSameSinceNs=null
        requestAwaySinceNs=null
        requestDisplaced=false
        onsetSinceNs=null
        burstConfirmed=false
        return true
        }

    fun responseIsFresh(ticket: EventCaptureTicket, nowNs: Long=lastAtNs ?: 0L): Boolean {
        if (!owns(ticket)||phase!=EventPhase.REQUEST_RUNNING) return false
        if (!checkResponseFreshness) return true
        // Keep explicit manual capture available without texture/sensors. Once a
        // different view IS observed, unknown evidence must not clear that warning.
        if (!ticket.automatic&&requestRelation==ViewRelation.UNKNOWN) return !requestDisplaced
        return requestRelation==ViewRelation.SAME&&!requestDisplaced&&
            lastAtNs?.let { nowNs-it in 0..maxGapNs }==true&&
            requestSameSinceNs?.let { nowNs-it>=responseHoldNs }==true
        }

    /** A late callback may never release a newer reservation. */
    fun release(ticket: EventCaptureTicket): Boolean {
        if (!owns(ticket)) return false
        activeTicket=null
        reason=null
        resetEvent()
        phase=EventPhase.WAITING_FOR_MOVEMENT
        return true
        }

    private fun updateStall(nowNs: Long) {
        if (reason!=null&&activeTicket==null&&!speaking&&nowNs-armedAtNs>=stallNs&&phase!=EventPhase.READY)
        stalled=true
        }

    private fun resetEvent() {
        beforeFrameId=null
        onsetBeforeId=null
        onsetSinceNs=null
        movementStartedAtNs=null
        restartHold()
        burstConfirmed=false
        eventObserved=false
        captureInvalidated=false
        requestRelation=ViewRelation.UNKNOWN
        requestSameSinceNs=null
        requestAwaySinceNs=null
        requestDisplaced=false
        stalled=false
        latestReliable=false
        }

    companion object {

        /** The most of a hold that may have been spent broken, over its length. */
        const val MAX_UNQUIET_FRACTION=0.35
        }
    }

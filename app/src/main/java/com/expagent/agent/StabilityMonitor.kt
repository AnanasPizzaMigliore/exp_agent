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

import android.os.SystemClock
import android.util.Base64

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ViewState { UNKNOWN, MOVING, SETTLING, STABLE }

/**
* Android adapter for EventCaptureController. Measurements run off the UI thread;
* gate transitions, camera reservations and lifecycle changes have one main-thread
* owner. A bounded colour buffer preserves event evidence without cloud streaming.
*
* Bounded tile registration tolerates two-hand jitter. Raw FrameMotion is logged
* for comparison only. This is not the paper's optical-flow estimator.
* Thresholds are engineering defaults requiring calibration on the target phone.
*/
class StabilityMonitor(
    private val scope: CoroutineScope,
    private val sensors: MotionSensorMonitor,
    ) {
    private data class Frame(val data: CameraAnalysisFrame, val atNs: Long, val generation: Long)

    /**
    * The live capture gate.
    *
    * A var so that one session can be run under an ablated controller and
    * measured for real rather than in shadow. The shadow arms are paired by
    * construction but counterfactual after the live capture, which is a fair
    * objection to resting a mechanism claim on them; replacing this lets the
    * same ablation be the thing that actually drives the camera.
    *
    * Only [useGate] may write it, and only between sessions.
    */
    var gate=EventCaptureController()
    private set

    /**
    * The condition name of [gate], for the log.
    *
    * Carried beside the controller because an [EventCaptureController] is only
    * its constants and cannot say which named condition it is. It was written
    * into ablation_arms as the literal "E_full" until 18 September, so eight
    * sessions that really did run E_no_settling_hold are recorded there as
    * having run the shipped gate. session_start.gate was right throughout, and
    * is what those sessions have to be scored on.
    */
    var gateName=GateConditions.FULL
    private set

    /**
    * Run the next session under a different capture controller.
    *
    * Refused while a session is running: the gate owns ticket and phase state
    * that the controller and the fragment are both holding references into, and
    * swapping it underneath them would strand an outstanding capture.
    */
    fun useGate(name: String, controller: EventCaptureController) {
        check(!running) { "the capture gate cannot be replaced mid-session" }
        gate=controller
        gateName=name
        }

    /**
    * The ablation arms, or null when the measurement is switched off.
    *
    * Set before [start]. They are given the same samples as [gate] at the same
    * instants and are never allowed to reserve a capture, so nothing here can
    * change what the session does. See [ShadowArms] for what their decisions do
    * and do not mean.
    */
    var shadows: ShadowArms?=null
    var onCaptureRequest: ((EventCaptureTicket) -> Unit)?=null
    var onStall: (() -> Unit)?=null
    var logger: ExperimentLogger?=null
    private set

    val sessionId: String get() = gate.sessionId
    @Volatile private var running=false
    val acceptsAnalysisFrames: Boolean get() = running
    @Volatile private var generation=0L
    private var monitoring=false
    private val frames=Channel<Frame>(Channel.CONFLATED)
    private var loop: Job?=null
    private var previous: Frame?=null
    private var latest: Sample?=null
    private var state=ViewState.UNKNOWN
    private var lastHeartbeatNs=0L
    private var stallReported=false
    private var controllerTrace=false
    private var traceSampleId=0L
    private val snapshots=StableFrameBuffer()
    private val views=HandheldViewTracker()
    private var pinnedBefore: StableSnapshot?=null
    private var capturedBefore: StableSnapshot?=null

    /**
    * What this user's hand does, and whether it is doing it in one place.
    *
    * The baseline outlives an instruction and a capture - it is a fact about
    * the person, and throwing it away every turn would mean never finishing the
    * calibration - so it is cleared only when the session ends. The window is
    * tied to a pinned view instead, and is cleared wherever that view is.
    */
    private val tremorBaseline=TremorBaseline()
    private val steady=SteadinessWindow()

    /** When the gate first became ready, for bounding the wait for a trough. */
    private var readySinceNs: Long?=null

    /**
    * Whether this session's hand has been measured as trembling.
    *
    * Defined as the tolerance having been load-bearing rather than as a
    * threshold on any one number: either the matcher alone called a frame
    * unquiet and the window overruled it, or this user's gyroscope earned them
    * a relaxed limit. Both mean the same thing operationally - without the
    * tremor mechanism this session would have been stalling - and that is
    * exactly the condition the capture path and the planner want to know about.
    *
    * Latched, and cleared only with the session. A hand does not stop having a
    * tremor between turns, and a flag that flickered would have the camera
    * rebinding underneath a capture.
    */
    @Volatile var tremulous=false
    private set

    /** This hand's quiet limit as it stands, for the session record. */
    val quietLimit: Double
    get() = tremorBaseline.quietLimit

    /** Whether enough of this hand was seen for [quietLimit] to mean anything. */
    val baselineCalibrated: Boolean
    get() = tremorBaseline.calibrated

    /**
    * Every tunable this session is actually running with.
    *
    * Written into session_start because a study is run by changing these, and
    * a number that was changed between two participants is invisible
    * afterwards: the log records what the gate DID, never what it was told to
    * do, and no amount of re-reading the events recovers a threshold. The app
    * version is in that row too and is the weaker signal of the two - it does
    * not move when a constant does, which is exactly when it would matter.
    *
    * Not exhaustive, and cannot be. It carries what a session is most likely to
    * be retuned on; anything changed outside this list still has to be recorded
    * by hand, by moving the version or the block label.
    */
    fun parameters(): JsonObject = buildJsonObject {
        put("gate_condition", gateName)
        put("gate_mode", gate.mode.name.lowercase())
        put("onset_ns", gate.onsetNs)
        put("initial_hold_ns", gate.initialHoldNs)
        put("action_hold_ns", gate.actionHoldNs)
        put("response_hold_ns", gate.responseHoldNs)
        put("max_gap_ns", gate.maxGapNs)
        put("stall_ns", gate.stallNs)
        put("quality_min_interval_ns", gate.qualityMinIntervalNs)
        put("quality_starts_with_prior_upload", gate.qualityStartsWithPriorUpload)
        put("response_freshness", gate.checkResponseFreshness)

        // The tremor mechanism's own constants, which are the ones with the
        // least field evidence behind them and so the likeliest to move.
        put("quiet_grace_ns", gate.quietGraceNs)
        put("max_unquiet_fraction", EventCaptureController.MAX_UNQUIET_FRACTION)
        put("shift_radius", HandheldViewMatcher.SHIFT_RADIUS)
        put("same_residual", HandheldViewMatcher.SAME_RESIDUAL)
        put("changed_residual", HandheldViewMatcher.CHANGED_RESIDUAL)
        put("quiet_gyro_limit", HandheldViewMatcher.QUIET_GYRO_LIMIT)
        put("tremor_ceiling", TremorBaseline.CEILING)
        put("steadiness_min_returns", SteadinessWindow.MIN_RETURNS)
        put("trough_wait_ns", TROUGH_WAIT_NS)
        }

    private data class Sample(
        val quality: QualityEstimate,
        val motion: MotionEstimate,
        val phone: PhoneMotion,
        val atNs: Long,
        val views: HandheldViewTracker.Measurement,
        val steadiness: Steadiness,
        val quietLimit: Double,
        )

    fun start(session: String, log: ExperimentLogger?, enableMonitoring: Boolean=true) {
        val arms=shadows
        stop()
        shadows=arms
        controllerTrace=arms!=null
        traceSampleId=0L

        // Cleared when a session BEGINS, not when one ends.
        //
        // The verdict about a hand is the last thing a session has to say about
        // itself, and several of the ways a session ends stop the monitor
        // before anything is written - InspectionController.stop() does it
        // synchronously, ahead of the coroutine that logs. Clearing here means
        // a row written after the monitor has stopped still reports the session
        // that produced it, instead of reporting a fresh, steady hand.
        tremulous=false
        tremorBaseline.clear()

        logger=log
        monitoring=enableMonitoring
        gate.start(session)
        arms?.start(session)
        if (controllerTrace) log?.trace("controller_start", session, buildJsonObject {
            put("gate", gateName)
            put("mode", gate.mode.name.lowercase())
            })
        if (arms!=null) log?.log("ablation_arms", session, buildJsonObject {
            put("arms", arms.names.joinToString(","))
            put("live_arm", gateName)
            })
        if (!enableMonitoring) return
        running=true
        sensors.start()
        loop=scope.launch {
            for (frame in frames) {
                if (!running||frame.generation!=generation) continue
                process(frame)
                }
            }
        }

    fun stop() {
        if (controllerTrace&&gate.sessionId.isNotEmpty())
        logger?.trace("controller_stop", gate.sessionId, buildJsonObject { })
        running=false
        generation+=1
        loop?.cancel()
        loop=null
        sensors.stop()
        while (frames.tryReceive().isSuccess) { }
        previous=null
        latest=null
        state=ViewState.UNKNOWN
        lastHeartbeatNs=0L
        snapshots.clear()
        views.clear()
        steady.clear()
        pinnedBefore=null
        capturedBefore=null
        readySinceNs=null
        gate.stop()
        shadows?.stop()
        controllerTrace=false
        logger=null
        }

    fun arm(reason: CaptureReason, expected: String?=null, instructionId: Int?=null) {
        if (gate.activeTicket!=null||gate.phase in setOf(EventPhase.INACTIVE, EventPhase.PAUSED)) return
        val at=now()
        gate.arm(reason, instructionId, expected, at)
        shadows?.arm(reason, instructionId, expected, at)
        if (controllerTrace) logger?.trace("controller_arm", sessionId, buildJsonObject {
            put("at_ns", at)
            put("instruction_id", instructionId)
            put("reason", reason.name.lowercase())
            put("expected_change", expected)
            })
        views.arm()
        steady.clear()
        pinnedBefore=null
        capturedBefore=null
        stallReported=false
        }

    fun pause() {
        if (controllerTrace) logger?.trace("controller_pause", sessionId, buildJsonObject { })
        running=false
        sensors.stop()
        gate.pause()
        shadows?.pause()
        generation+=1
        previous=null
        latest=null
        snapshots.clear()
        views.clear()
        steady.clear()
        pinnedBefore=null
        capturedBefore=null
        }

    fun resume(instructionId: Int?) {
        val at=now()
        gate.resume(instructionId, at)
        shadows?.resume(instructionId, at)
        if (controllerTrace) logger?.trace("controller_resume", sessionId, buildJsonObject {
            put("at_ns", at)
            put("instruction_id", instructionId)
            })
        running=monitoring
        if (monitoring) sensors.start()
        previous=null
        views.clear()
        steady.clear()
        stallReported=false
        }

    fun setSpeaking(value: Boolean) {
        gate.setSpeaking(value)
        shadows?.setSpeaking(value)
        }

    fun reserveCapture(automatic: Boolean): EventCaptureTicket? {
        val ticket=gate.reserve(automatic, now()) ?: return null
        capturedBefore=pinnedBefore?.takeIf { it.id==ticket.beforeFrameId }
            ?: snapshots.get(ticket.beforeFrameId)
        logTicket("capture_reserved", ticket)
        return ticket
        }

    fun beforeImage(ticket: EventCaptureTicket): ByteArray? =
    if (gate.owns(ticket)) capturedBefore?.jpeg else null

    fun acceptCapture(ticket: EventCaptureTicket): Boolean {
        val accepted=gate.acceptCapture(ticket, now())
        if (accepted) {
            views.capture()
            steady.clear()
            shadows?.noteLiveCapture()
            }
        logTicket(if (accepted) "capture_accepted" else "capture_rejected", ticket)
        return accepted
        }

    fun releaseCapture(ticket: EventCaptureTicket): Boolean {
        if (!gate.release(ticket)) return false
        shadows?.release()
        views.release()
        capturedBefore=null
        pinnedBefore=null
        return true
        }

    fun responseIsFresh(ticket: EventCaptureTicket): Boolean {
        val fresh=gate.responseIsFresh(ticket, now())

        if (controllerTrace) logger?.trace("response_decision", sessionId, buildJsonObject {
            put("capture_token", ticket.token)
            put("instruction_id", ticket.instructionId)
            put("accepted", fresh)
            })

        // Logged where the live session asks, so every arm is judging the same
        // reply arriving at the same moment.
        shadows?.let { arms ->
            logger?.log("ablation_freshness", sessionId, buildJsonObject {
                put("instruction_id", ticket.instructionId)
                put("live_fresh", fresh)
                for ((name, verdict) in arms.freshness(now()))
                put(name, verdict)
                })
            }

        return fresh
        }

    fun noteCapture(frameId: Int) {
        val sample=latest ?: return
        logger?.log("stability_at_capture", sessionId, buildJsonObject {
            put("frame_id", frameId)
            put("state", state.name.lowercase())
            put("age_ms", (now()-sample.atNs)/1_000_000L)
            put("motion_region_max", sample.motion.regionMax)
            put("motion_reliability", sample.motion.reliability)
            put("settling_relation", sample.views.settling.relation.name.lowercase())
            put("settling_residual_p75", sample.views.settling.residualP75)
            put("instruction_relation", sample.views.instruction.relation.name.lowercase())
            put("hand_unsteady", tremulous)
            put("steadiness", sample.steadiness.name.lowercase())
            put("tremor_residual_p75", sample.views.tremor.residualP75)
            put("quiet_limit", sample.quietLimit)
            put("gyro_rms", sample.phone.angularSpeedRms)
            put("sharpness_tile_max", sample.quality.sharpnessTileMax)
            put("exposure_suspect", sample.quality.exposureSuspect)
            put("would_have_withheld", state!=ViewState.STABLE)
            })
        }

    /** Camera executor calls this with owned bytes, after copying the ImageProxy. */
    fun offer(data: CameraAnalysisFrame, atNs: Long) {
        if (running) frames.trySend(Frame(data, atNs, generation))
        }

    private suspend fun process(frame: Frame) {
        val last=previous
        previous=frame
        val data=frame.data
        if (last==null||last.data.width!=data.width||last.data.height!=data.height||
            last.data.rotation!=data.rotation) {
            snapshots.clear()
            views.clear()
            steady.clear()
            // Invalidate capture eligibility immediately, before worker processing.
            gate.sample(frame.atNs, ViewRelation.UNKNOWN, quiet=false, reliable=false)
            }
        val gap=last?.let { (frame.atNs-it.atNs)/1_000_000_000.0 } ?: 0.0
        val anchors=views.snapshot()
        val started=now()
        val (quality, motion, measured)=withContext(Dispatchers.Default) {
            Triple(
                FrameQuality.estimate(data.luma, data.width, data.height, data.width),
                if (last!=null&&last.data.width==data.width&&last.data.height==data.height)
                    FrameMotion.between(last.data.luma, data.luma, data.width, data.height, data.width, gap)
                else MotionEstimate(0.0, 0.0, 0.0, gap),
                HandheldViewTracker.measure(anchors, ViewFingerprint.from(data.luma, data.width, data.height)),
                )
            }
        // A stopped/paused session may have changed while measurements were running.
        if (!running||frame.generation!=generation||!views.accept(measured)) return
        val phone=sensors.summary()
        val gyro=phone.angularSpeedRms.takeUnless { phone.unknown }
        if (gyro!=null) tremorBaseline.add(frame.atNs, gyro)
        val quietLimit=tremorBaseline.quietLimit
        val steadiness=steady.add(frame.atNs, measured.tremor)

        // The view has left the pinned reference for good, so the reference is
        // no longer one. Re-pinning is the only way back: the window is measured
        // entirely against it, and against a view nothing matches any more it
        // would go on answering DRIFTING however still the hand became.
        if (steadiness==Steadiness.DRIFTING) {
            views.repinTremor()
            steady.clear()
            }

        latest=Sample(quality, motion, phone, frame.atNs, measured, steadiness, quietLimit)

        // Two ways to be quiet, and the second is the whole tremor mechanism:
        // registering as the same place frame to frame, OR wobbling about one
        // place across a window of them. A tremor rarely manages the first -
        // most of its cycle is outside the matcher's two-pixel box - and this
        // is what lets it satisfy the gate without the box being widened,
        // which would have cost the gate its ability to see a package turn.
        //
        // The gyroscope still has its veto over both. Oscillating about a fixed
        // pose fast enough to smear the exposure is not a frame worth taking,
        // whichever way the stillness was established.
        val gyroQuiet=gyro==null||gyro<quietLimit
        val matched=HandheldViewMatcher.quiet(measured.settling, gyro, quietLimit)
        val quiet=matched||(steadiness==Steadiness.OSCILLATING&&gyroQuiet)

        // Latched where the tolerance actually did something: a frame the
        // matcher alone would have called unquiet, or a limit this hand earned.
        if ((quiet&&!matched)||quietLimit>HandheldViewMatcher.QUIET_GYRO_LIMIT)
        tremulous=true

        // A tremor costs frames their texture, and an unmeasurable frame reads
        // as unreliable. Bounded oscillation about a pinned view is a positive
        // measurement in its own right, so it answers that too.
        val reliable=measured.settling.relation!=ViewRelation.UNKNOWN||
            steadiness==Steadiness.OSCILLATING

        if (controllerTrace) {
            val pixels=ByteArray(measured.current.pixels.size) { index ->
                measured.current.pixels[index].toInt().coerceIn(0, 255).toByte()
                }
            logger?.trace("controller_sample", sessionId, buildJsonObject {
                put("sample_id", ++traceSampleId)
                put("at_ns", frame.atNs)
                put("width", measured.current.width)
                put("height", measured.current.height)
                put("quiet", quiet)
                put("reliable", reliable)
                put("speaking", gate.speaking)
                put("exposure_suspect", quality.exposureSuspect)
                put("sharpness_tile_max", quality.sharpnessTileMax)
                put("gyro_rms", phone.angularSpeedRms)
                put("instruction_relation", measured.instruction.relation.name.lowercase())
                put("submitted_relation", measured.submitted.relation.name.lowercase())
                put("last_upload_relation", measured.lastUpload.relation.name.lowercase())
                put("fingerprint_u8_b64", Base64.encodeToString(pixels, Base64.NO_WRAP))
                })
            }
        val oldPhase=gate.phase
        val oldEvent=gate.eventId
        gate.sample(frame.atNs, measured.instruction.relation, quiet, reliable,
            measured.submitted.relation, steadiness==Steadiness.OSCILLATING,
            measured.lastUpload.relation)
        if (gate.eventId!=oldEvent) {
            logger?.log("event_onset", sessionId, buildJsonObject {
                put("event_id", gate.eventId)
                put("movement_started_ns", gate.currentMovementStartNs)
                put("confirmed_at_ns", frame.atNs)
                put("before_frame_id", gate.beforeFrameId)
                })
            }
        state=when {
            !reliable -> ViewState.UNKNOWN
            !quiet -> ViewState.MOVING
            gate.isStable(frame.atNs) -> ViewState.STABLE
            else -> ViewState.SETTLING
            }
        val before=gate.beforeFrameId
        if (before!=null&&pinnedBefore?.id!=before) pinnedBefore=snapshots.get(before)
        if (state==ViewState.STABLE&&data.jpeg!=null) {
            snapshots.add(StableSnapshot(frame.atNs, frame.atNs, data.jpeg))
            gate.noteStableFrame(frame.atNs, frame.atNs)
            }

        // Identical measurements, identical instant, after the live gate has
        // taken its turn. The arms cannot reserve a capture, so this cannot
        // change what the session does.
        shadows?.sample(
            frame.atNs, measured.instruction.relation, measured.submitted.relation,
            // Unconditional: each arm applies its own stability test to the id,
            // rather than inheriting the live gate's verdict on it.
            quiet, reliable, frame.atNs,
            sinceUpload=measured.lastUpload.relation,
            )?.forEach { would ->
            logger?.log("ablation_capture", sessionId, buildJsonObject {
                put("arm", would.arm)
                put("at_ns", would.atNs)
                put("instruction_id", would.instructionId)
                put("after_live_capture", would.afterLiveCapture)
                put("capture_reason", would.reason?.name?.lowercase())
                // Read after the live gate has already seen this sample, so an
                // arm that fires in step with it reads "ready", not "settling".
                put("live_phase", gate.phase.name.lowercase())
                put("live_event_id", gate.eventId)
                put("instruction_relation", measured.instruction.relation.name.lowercase())
                put("quiet", quiet)
                })
            }
        if (oldPhase!=gate.phase) {
            logger?.log("event_transition", sessionId, buildJsonObject {
                put("from", oldPhase.name.lowercase())
                put("to", gate.phase.name.lowercase())
                put("at_ns", frame.atNs)
                put("event_id", gate.eventId)
                put("before_frame_id", gate.beforeFrameId)
                })
            }
        if (frame.atNs-lastHeartbeatNs>=HEARTBEAT_NS) {
            lastHeartbeatNs=frame.atNs
            logger?.log("stability_sample", sessionId, buildJsonObject {
                put("state", state.name.lowercase())
                put("event_phase", gate.phase.name.lowercase())
                put("motion_region_max", motion.regionMax)
                put("motion_region_median", motion.regionMedian)
                put("motion_reliability", motion.reliability)
                put("settling_relation", measured.settling.relation.name.lowercase())
                put("settling_residual_p75", measured.settling.residualP75)
                put("instruction_relation", measured.instruction.relation.name.lowercase())
                put("instruction_residual_p75", measured.instruction.residualP75)
                put("informative_tiles", measured.instruction.informativeTiles)
                put("changed_tiles", measured.instruction.changedTiles)
                put("submitted_relation", measured.submitted.relation.name.lowercase())
                // The persistent one the quality baseline's duplicate test
                // reads, logged beside the request-scoped one so the difference
                // between them can be checked rather than assumed.
                put("last_upload_relation", measured.lastUpload.relation.name.lowercase())
                // The tremor mechanism, in the terms it decides on: what the
                // pinned view says, what the window made of the series, and the
                // limit this user's own hand earned. Logged so a session that
                // was held by a trembling hand can be told from one that was
                // not, rather than both arriving as "settling" forever.
                put("tremor_relation", measured.tremor.relation.name.lowercase())
                put("tremor_residual_p75", measured.tremor.residualP75)
                put("steadiness", steadiness.name.lowercase())
                put("quiet_limit", quietLimit)
                put("quiet_limit_relaxed", quietLimit>HandheldViewMatcher.QUIET_GYRO_LIMIT)
                put("gyro_capture_veto", !phone.unknown&&phone.angularSpeedRms>=quietLimit)
                put("measurement_ms", (now()-started)/1_000_000.0)
                put("gap_ms", gap*1000.0)
                put("gyro_rms", phone.angularSpeedRms)
                put("sharpness_tile_max", quality.sharpnessTileMax)
                put("mean_luma", quality.meanLuma)
                put("snapshot_count", snapshots.size)
                })
            }
        if (gate.stalled&&!stallReported) {
            stallReported=true
            logger?.log("event_stalled", sessionId, buildJsonObject {
                put("event_phase", gate.phase.name.lowercase())
                put("forced_capture", false)
                })
            onStall?.invoke()
            }
        if (gate.phase!=EventPhase.READY)
        readySinceNs=null

        // Shadow/manual mode never reserves a camera slot.
        if (onCaptureRequest!=null&&gate.phase==EventPhase.READY) {
            if (readySinceNs==null) readySinceNs=frame.atNs

            // A tremor is an oscillation, so the hand is measurably quieter at
            // some instants of it than at others, and the shutter is worth
            // spending on one of those rather than on whichever frame happened
            // to complete the hold. Bounded, because a hand that never reaches
            // its own trough must still get its photograph: after this long the
            // next eligible frame is taken whatever the gyroscope says.
            if (gyro!=null&&frame.atNs-readySinceNs!!<TROUGH_WAIT_NS&&!tremorBaseline.atTrough(gyro))
            return

            val ticket=reserveCapture(automatic=true) ?: return
            onCaptureRequest?.invoke(ticket)
            }
        }

    private fun logTicket(kind: String, ticket: EventCaptureTicket) {
        val fields=buildJsonObject {
            put("event_id", ticket.eventId)
            put("capture_token", ticket.token)
            put("instruction_id", ticket.instructionId)
            put("reason", ticket.reason.name.lowercase())
            put("automatic", ticket.automatic)
            put("before_frame_id", ticket.beforeFrameId)
            put("movement_started_ns", ticket.movementStartedAtNs)
            put("settled_ns", ticket.settledAtNs)
            put("reserved_at_ns", ticket.reservedAtNs)
            }
        logger?.log(kind, ticket.sessionId, fields)
        if (controllerTrace) logger?.trace(kind, ticket.sessionId, fields)
        }

    private fun now(): Long = SystemClock.elapsedRealtimeNanos()

    companion object {
        const val HEARTBEAT_NS=1_000_000_000L

        /** The longest a ready capture waits for a quieter instant of the tremor. */
        const val TROUGH_WAIT_NS=250_000_000L
        }
    }

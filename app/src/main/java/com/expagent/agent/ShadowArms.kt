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
* The ablation arms, run alongside the live controller on the same samples.
*
* Every arm is a pure function of the sample stream, so all of them can run in
* one session: each is given the identical measurements at the identical
* instants, and only the live controller is allowed to touch the camera. The
* arms record what they WOULD have done. That makes the comparison paired by
* construction - same hands, same product, same lighting, same instruction - at
* the cost of the arms being counterfactual.
*
* WHERE THE COUNTERFACTUAL STOPS BEING TRUE. An arm that would have captured at
* a different moment would have changed everything after it: a different
* photograph means a different answer, a different instruction, and a different
* movement by the user. So within one instruction, up to the live controller's
* capture, the arms see exactly what they would have seen and their decisions
* are real. After that point in each turn they are not ground truth, and across
* a whole session they are not a recall measurement. What survives that limit is
* the half of the question that does not depend on the future: captures during
* idle time and tremor, captures taken while an action was still underway, and
* how long after eligibility each arm would have fired. Full recall needs
* recorded traces and offline replay.
*
* The arms mirror the live controller's lifecycle exactly - armed together,
* released together, paused together - so each gets at most one capture per
* instruction, as the real gate does.
*/
class ShadowArms(private val arms: List<ShadowPolicy> =defaultArms()) {

    /** What one arm would have done, for the caller to log. */
    data class WouldCapture(
        val arm: String,
        val atNs: Long,
        val instructionId: Int?,
        val afterLiveCapture: Boolean,
        /**
        * Why the arms were armed for this instruction.
        *
        * Without it a scorer cannot tell a capture that was never supposed to
        * wait for movement - the opening look, an explicit hold-still retry -
        * from one that fired although nothing had moved. Both show the current
        * view as unchanged from the instruction reference, and only the second
        * is an upload spent on nothing.
        */
        val reason: CaptureReason?,
        )

    private var liveCaptured=false

    /** The reason every arm is currently armed under; they are armed together. */
    private var armedReason: CaptureReason?=null

    fun start(session: String) {
        liveCaptured=false
        armedReason=null
        for (arm in arms) arm.start(session)
        }

    fun stop() {
        liveCaptured=false
        armedReason=null
        for (arm in arms) arm.stop()
        }

    fun arm(reason: CaptureReason, instruction: Int?, expected: String?, nowNs: Long) {
        liveCaptured=false
        armedReason=reason
        for (arm in arms) arm.arm(reason, instruction, expected, nowNs)
        }

    fun release() {
        liveCaptured=false
        armedReason=null
        for (arm in arms) arm.release()
        }

    fun pause() {
        for (arm in arms) arm.pause()
        }

    fun resume(instruction: Int?, nowNs: Long) {
        for (arm in arms) arm.resume(instruction, nowNs)
        }

    fun setSpeaking(value: Boolean) {
        for (arm in arms) arm.setSpeaking(value)
        }

    /** Called when the live controller accepts a capture, to mark the divergence point. */
    fun noteLiveCapture() {
        liveCaptured=true
        }

    /**
    * One measurement, to every arm. Returns the arms that would have captured.
    *
    * [instruction] and [submitted] are the same two relations the live
    * controller is given: the current view against the fixed instruction
    * reference, and against the frame of the request in flight.
    *
    * [sinceUpload] is the view against the last frame actually uploaded, which
    * outlives the turn. Only the quality baseline reads it, for its duplicate
    * test; the gate arms are handed [submitted] exactly as the live gate is, so
    * that their agreement with it stays the thing it was.
    */
    fun sample(
        nowNs: Long,
        instruction: ViewRelation,
        submitted: ViewRelation,
        quiet: Boolean,
        reliable: Boolean,
        stableFrameId: Long?,
        sinceUpload: ViewRelation=submitted,
        ): List<WouldCapture> {
        var fired: MutableList<WouldCapture>?=null

        for (arm in arms) {
            if (!arm.sample(nowNs, instruction, submitted, quiet, reliable, stableFrameId, sinceUpload))
            continue

            val list=fired ?: mutableListOf<WouldCapture>().also { fired=it }
            list.add(WouldCapture(arm.name, nowNs, arm.instructionId, liveCaptured, armedReason))
            }

        return fired ?: listOf()
        }

    /** Each arm's verdict on a reply arriving now, for the caller to log. */
    fun freshness(nowNs: Long): Map<String, Boolean?> =
    arms.associate { it.name to it.responseIsFresh(nowNs) }

    val names: List<String>
    get() = arms.map { it.name }

    companion object {

        /**
        * The arms, fixed here so that the phone, the synthetic harness and the
        * offline replay all score the same six things.
        *
        * E_ONSET is kept although EventAblationTest shows it changes no capture
        * decision - the settling hold subsumes it whenever the view is still
        * different when it settles. It is here to find out whether that also
        * holds on real hands, and it costs one object.
        */
        fun defaultArms(): List<ShadowPolicy> = listOf(
            GateArm("E_full", EventCaptureController()),
            // Built by the same factory as the live condition. This equality is
            // what makes a shadow diagnostic a valid pre-divergence check.
            GateArm(GateConditions.QUALITY, GateConditions.of(GateConditions.QUALITY)!!),
            GateArm("E_no_settling_hold", EventCaptureController(actionHoldNs=0L)),
            GateArm("E_no_match_hold", EventCaptureController(responseHoldNs=0L)),
            GateArm("E_no_freshness", EventCaptureController(), checkFreshness=false),
            GateArm("E_no_onset", EventCaptureController(onsetNs=0L)),
            GateArm("E_no_tremor_tolerance", EventCaptureController(quietGraceNs=0L)),
            )
        }
    }

/** One capture policy, driven by the live session's measurements. */
interface ShadowPolicy {

    val name: String

    /** The instruction this arm is currently working on, for matching in the log. */
    val instructionId: Int?

    fun start(session: String)
    fun stop()
    fun arm(reason: CaptureReason, instruction: Int?, expected: String?, nowNs: Long)
    fun release()
    fun pause()
    fun resume(instruction: Int?, nowNs: Long)
    fun setSpeaking(value: Boolean)

    /** True when this arm would have submitted this frame. */
    fun sample(
        nowNs: Long,
        instruction: ViewRelation,
        submitted: ViewRelation,
        quiet: Boolean,
        reliable: Boolean,
        stableFrameId: Long?,
        sinceUpload: ViewRelation=submitted,
        ): Boolean

    /** This arm's verdict on a reply arriving now, or null if it never captured. */
    fun responseIsFresh(nowNs: Long): Boolean?
    }

/**
* An arm that is an [EventCaptureController] with different constants.
*
* The capture is carried through reserve and accept exactly as the live gate
* does it, so the arm ends in REQUEST_RUNNING and cannot fire twice for one
* instruction. No camera is involved: the ticket is never handed anywhere.
*/
class GateArm(
    override val name: String,
    private val gate: EventCaptureController,
    /** false: this arm speaks whatever comes back, without checking the view. */
    private val checkFreshness: Boolean=true,
    ) : ShadowPolicy {

    private var ticket: EventCaptureTicket?=null
    private var armedInstruction: Int?=null

    override val instructionId: Int? get() = armedInstruction

    override fun start(session: String) {
        ticket=null
        gate.start(session)
        }

    override fun stop() {
        ticket=null
        gate.stop()
        }

    override fun arm(reason: CaptureReason, instruction: Int?, expected: String?, nowNs: Long) {
        ticket=null
        armedInstruction=instruction
        gate.arm(reason, instruction, expected, nowNs)
        }

    override fun release() {
        ticket?.let { gate.release(it) }
        ticket=null
        }

    override fun pause() {
        gate.pause()
        }

    override fun resume(instruction: Int?, nowNs: Long) {
        armedInstruction=instruction
        gate.resume(instruction, nowNs)
        }

    override fun setSpeaking(value: Boolean) {
        gate.setSpeaking(value)
        }

    override fun sample(
        nowNs: Long,
        instruction: ViewRelation,
        submitted: ViewRelation,
        quiet: Boolean,
        reliable: Boolean,
        stableFrameId: Long?,
        sinceUpload: ViewRelation,
        ): Boolean {
        gate.sample(nowNs, instruction, quiet, reliable, submitted,
            lastUploadedView=sinceUpload)
        if (stableFrameId!=null) gate.noteStableFrame(stableFrameId, nowNs)

        if (gate.phase!=EventPhase.READY) return false

        val reserved=gate.reserve(automatic=true, nowNs=nowNs) ?: return false

        // A real capture can still be rejected at the callback; an arm has no
        // camera, so acceptance here is immediate and the arm is, if anything,
        // slightly generous to itself.
        if (!gate.acceptCapture(reserved, nowNs)) return false

        ticket=reserved
        return true
        }

    override fun responseIsFresh(nowNs: Long): Boolean? {
        val held=ticket ?: return null

        return if (checkFreshness) gate.responseIsFresh(held, nowNs) else true
        }
    }

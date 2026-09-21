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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
* Which parts of the event controller are load-bearing, and for what.
*
* Every scripted trace below is replayed through the arms in [ShadowArms] - the
* same objects the phone runs beside the live session - on identical input. Each arm is then asserted to lose something the full controller keeps.
* This is the mechanism-necessity half of the ablation: it settles the arms, the
* annotation format and the scoring before any sensor data is recorded.
*
* It is NOT efficiency evidence, and no number here belongs in a results table.
* The traces are hand-written sample sequences, so they say what each control
* rule does, never what it would cost on a phone in a shop. Recall, upload rates
* and delay have to come from replaying recorded traces through these same arms.
*
* Two structural limits of a synthetic harness, stated so they are not mistaken
* for findings. An armed instruction yields at most one automatic capture
* because the gate moves to REQUEST_RUNNING and refuses to reserve again, and
* the baseline is held to the same one-request-at-a-time rule; duplicates per
* window are therefore zero for every arm and are not reported. And capture-time
* rejection is not exercised - the camera is instantaneous here - so what varies
* between arms is whether the gate offered a capture at all.
*/
class EventAblationTest {

    // ------------------------------------------------------------------
    // One analysis frame, as a capture policy sees it
    // ------------------------------------------------------------------

    /**
    * [instruction] is this view compared with the fixed reference taken when the
    * instruction was given. [sinceUpload] is the same view compared with the
    * last frame actually uploaded, which is what a policy without an instruction
    * reference has to work from. They coincide until something is submitted
    * mid-action, which is why the request traces set it explicitly.
    */
    private data class Frame(
        val atMs: Long,
        val instruction: ViewRelation,
        val quiet: Boolean,
        val reliable: Boolean=true,
        val sinceUpload: ViewRelation=instruction,
        )

    /**
    * A scripted trace with the annotation a human would supply.
    *
    * [window] is the eligible evidence window: the action has finished, the view
    * has settled, and what is in front of the camera is worth uploading. Null
    * means nothing should be captured at all. [staleAtResponse] is the
    * annotator's verdict on whether a reply arriving at [responseAtMs] concerns
    * a view that has gone.
    */
    private data class Trace(
        val name: String,
        val reason: CaptureReason,
        val expected: String?,
        val frames: List<Frame>,
        val window: LongRange?=null,
        val responseAtMs: Long?=null,
        val staleAtResponse: Boolean=false,
        )

    private data class Outcome(
        val captures: List<Long> =listOf(),
        val responseAccepted: Boolean?=null,
        val beforeFrameId: Long?=null,
        ) {
        fun inWindow(w: LongRange?): List<Long> = if (w==null) listOf() else captures.filter { it in w }
        fun outsideWindow(w: LongRange?): List<Long> = if (w==null) captures else captures.filter { it !in w }
        }

    // ------------------------------------------------------------------
    // The traces
    // ------------------------------------------------------------------

    private fun steps(
        from: Long, ms: Long,
        instruction: ViewRelation, quiet: Boolean,
        reliable: Boolean=true,
        sinceUpload: ViewRelation=instruction,
        ): List<Frame> =
    (100..ms step 100).map { Frame(from+it, instruction, quiet, reliable, sinceUpload) }

    /** Hands that never stop trembling, holding one view throughout. */
    private fun tremor(): Trace {
        val frames=mutableListOf<Frame>()
        var t=0L
        repeat(6) {
            frames+=steps(t, 200, ViewRelation.SAME, quiet=false)
            t+=200
            frames+=steps(t, 800, ViewRelation.SAME, quiet=true)
            t+=800
            }
        return Trace("idle tremor", CaptureReason.POST_ACTION_CHANGE, ExpectedChange.VIEWPOINT, frames)
        }

    /** A quarter turn, carried out once, then held. */
    private fun cleanTurn(): Trace {
        val frames=steps(0, 1000, ViewRelation.SAME, quiet=true)+
        steps(1000, 1000, ViewRelation.DIFFERENT, quiet=false)+
        steps(2000, 3000, ViewRelation.DIFFERENT, quiet=true)

        // Settled at 2100; the full controller holds 900ms beyond that.
        return Trace("clean quarter turn", CaptureReason.POST_ACTION_CHANGE, ExpectedChange.VIEWPOINT,
            frames, window=2100L..5000L)
        }

    /** The same turn, interrupted by a regrip in the middle of the movement. */
    private fun turnWithPause(): Trace {
        val frames=steps(0, 800, ViewRelation.DIFFERENT, quiet=false)+
        // Seven hundred milliseconds of stillness that is not the end of the
        // action. This is the case a plain stability detector gets wrong.
        steps(800, 700, ViewRelation.DIFFERENT, quiet=true)+
        steps(1500, 800, ViewRelation.DIFFERENT, quiet=false)+
        steps(2300, 3000, ViewRelation.DIFFERENT, quiet=true)

        return Trace("slow turn, mid-pause", CaptureReason.POST_ACTION_CHANGE, ExpectedChange.VIEWPOINT,
            frames, window=2400L..5300L)
        }

    /** Turned part way, thought better of it, and put it back as it was. */
    private fun outAndBack(): Trace {
        val frames=steps(0, 800, ViewRelation.DIFFERENT, quiet=false)+
        steps(800, 800, ViewRelation.DIFFERENT, quiet=false)+
        steps(1600, 3000, ViewRelation.SAME, quiet=true)

        // Nothing new is in front of the camera, so there is nothing to upload.
        return Trace("turn and return", CaptureReason.POST_ACTION_CHANGE, ExpectedChange.VIEWPOINT, frames)
        }

    /** Brought closer. The matcher fits no scale, so this reads as change. */
    private fun moveCloser(): Trace {
        val frames=steps(0, 600, ViewRelation.DIFFERENT, quiet=false)+
        steps(600, 3000, ViewRelation.DIFFERENT, quiet=true)

        return Trace("move closer", CaptureReason.POST_ACTION_CHANGE, ExpectedChange.SCALE,
            frames, window=700L..3600L)
        }

    /**
    * A shake while the request runs, after which the view comes back and stays.
    * The answer still describes what the camera sees, so it is usable.
    */
    private fun shakeDuringRequest(): Trace {
        // Nothing has been uploaded yet, so the opening view is new to any
        // duplicate test, however it compares with the instruction reference.
        val frames=steps(0, 700, ViewRelation.SAME, quiet=true, sinceUpload=ViewRelation.DIFFERENT)+
        steps(700, 300, ViewRelation.SAME, quiet=false, sinceUpload=ViewRelation.DIFFERENT)+
        steps(1000, 1500, ViewRelation.SAME, quiet=true, sinceUpload=ViewRelation.SAME)

        return Trace("shake during request", CaptureReason.INITIAL_OBSERVATION, null,
            frames, window=600L..800L, responseAtMs=2400L, staleAtResponse=false)
        }

    /**
    * The original pose sweeps past the camera for one sample in the middle of a
    * turn. A reply arriving at that instant is about a view already gone.
    */
    private fun passingThroughDuringRequest(): Trace {
        val frames=steps(0, 700, ViewRelation.SAME, quiet=true, sinceUpload=ViewRelation.DIFFERENT)+
        steps(700, 600, ViewRelation.SAME, quiet=false, sinceUpload=ViewRelation.DIFFERENT)+
        listOf(Frame(1400, ViewRelation.SAME, quiet=true, sinceUpload=ViewRelation.SAME))+
        steps(1400, 600, ViewRelation.SAME, quiet=false, sinceUpload=ViewRelation.DIFFERENT)

        return Trace("passing through, mid-turn", CaptureReason.INITIAL_OBSERVATION, null,
            frames, window=600L..800L, responseAtMs=1400L, staleAtResponse=true)
        }

    /** A different face is shown while the request is in flight. */
    private fun newFaceDuringRequest(): Trace {
        val frames=steps(0, 700, ViewRelation.SAME, quiet=true, sinceUpload=ViewRelation.DIFFERENT)+
        steps(700, 800, ViewRelation.SAME, quiet=false, sinceUpload=ViewRelation.DIFFERENT)+
        steps(1500, 1000, ViewRelation.SAME, quiet=true, sinceUpload=ViewRelation.DIFFERENT)

        return Trace("new face during request", CaptureReason.INITIAL_OBSERVATION, null,
            frames, window=600L..800L, responseAtMs=2400L, staleAtResponse=true)
        }

    private val traces=listOf(
        tremor(), cleanTurn(), turnWithPause(), outAndBack(), moveCloser(),
        shakeDuringRequest(), passingThroughDuringRequest(), newFaceDuringRequest(),
        )

    // ------------------------------------------------------------------
    // The arms
    // ------------------------------------------------------------------

    private data class Arm(
        val name: String,
        val qualityAware: Boolean=false,
        val onsetNs: Long=250_000_000L,
        val actionHoldNs: Long=900_000_000L,
        val responseHoldNs: Long=300_000_000L,
        /** false: speak whatever comes back, without checking the view still matches. */
        val checkFreshness: Boolean=true,
        )

    private val full=Arm("E (full)")
    private val quality=Arm("Q (quality-aware)", qualityAware=true)

    private val arms=listOf(
        full,
        quality,
        Arm("E -settling hold", actionHoldNs=0L),
        Arm("E -match hold", responseHoldNs=0L),
        Arm("E -freshness", checkFreshness=false),
        Arm("E -onset", onsetNs=0L),
        )

    // ------------------------------------------------------------------
    // The replay
    // ------------------------------------------------------------------

    /**
    * One trace through one arm. Samples arrive in timestamp order and nothing
    * consults a later frame, which is what makes this a replay rather than a
    * simulation.
    */
    private fun run(arm: Arm, trace: Trace): Outcome {
        if (arm.qualityAware) return runQuality(trace)

        val gate=EventCaptureController(
            onsetNs=arm.onsetNs,
            actionHoldNs=arm.actionHoldNs,
            responseHoldNs=arm.responseHoldNs,
            )

        gate.start("ablation")
        gate.arm(trace.reason, 1, trace.expected, 0L)

        val captures=mutableListOf<Long>()
        var accepted: Boolean?=null
        var before: Long?=null

        for (frame in trace.frames) {
            val atNs=frame.atMs*1_000_000L

            gate.sample(atNs, frame.instruction, frame.quiet, frame.reliable, frame.sinceUpload)
            gate.noteStableFrame(frame.atMs, atNs)

            if (gate.phase==EventPhase.READY) {
                val ticket=gate.reserve(automatic=true, nowNs=atNs)

                if (ticket!=null&&gate.acceptCapture(ticket, atNs)) {
                    captures+=frame.atMs
                    before=ticket.beforeFrameId
                    }
                }

            if (trace.responseAtMs==frame.atMs) {
                val ticket=gate.activeTicket
                if (ticket!=null)
                accepted=if (arm.checkFreshness) gate.responseIsFresh(ticket, atNs) else true
                }
            }

        return Outcome(captures, accepted, before)
        }

    /** The baseline has no reference to go stale, so a reply is always spoken. */
    private fun runQuality(trace: Trace): Outcome {
        // The same ticket-owning gate the phone runs. A non-opening trace begins
        // after an earlier upload, just as the corresponding live turn does.
        val gate=EventCaptureController(
            mode=CaptureGateMode.QUALITY_AWARE,
            qualityStartsWithPriorUpload=trace.reason!=CaptureReason.INITIAL_OBSERVATION,
            checkResponseFreshness=false,
            )

        gate.start("ablation")
        gate.arm(trace.reason, 1, trace.expected, 0L)

        val captures=mutableListOf<Long>()
        var accepted: Boolean?=null

        for (frame in trace.frames) {
            val atNs=frame.atMs*1_000_000L

            gate.sample(
                atNs,
                frame.instruction,
                frame.quiet,
                frame.reliable,
                submittedView=frame.sinceUpload,
                lastUploadedView=frame.sinceUpload,
                )
            gate.noteStableFrame(frame.atMs, atNs)

            if (gate.phase==EventPhase.READY) {
                val ticket=gate.reserve(automatic=true, nowNs=atNs)
                if (ticket!=null&&gate.acceptCapture(ticket, atNs))
                captures+=frame.atMs
                }

            if (trace.responseAtMs==frame.atMs) {
                val ticket=gate.activeTicket
                if (ticket!=null) accepted=gate.responseIsFresh(ticket, atNs)
                }
            }

        return Outcome(captures, accepted)
        }

    // ------------------------------------------------------------------
    // The table
    // ------------------------------------------------------------------

    @Test
    fun ablationTable() {
        val out=StringBuilder()
        out.append(String.format("%-20s %7s %9s %9s %8s %7s %9s%n",
            "arm", "recall", "captures", "off-win", "missed", "stale", "delay ms"))

        for (arm in arms) {
            var windows=0
            var hit=0
            var outside=0
            var captures=0
            var stale=0
            val delays=mutableListOf<Long>()

            for (trace in traces) {
                val outcome=run(arm, trace)
                captures+=outcome.captures.size
                outside+=outcome.outsideWindow(trace.window).size

                if (trace.window!=null) {
                    windows+=1
                    val matched=outcome.inWindow(trace.window)
                    if (matched.isNotEmpty()) {
                        hit+=1
                        delays+=matched.first()-trace.window.first
                        }
                    }

                if (trace.staleAtResponse&&outcome.responseAccepted==true) stale+=1
                }

            val median=if (delays.isEmpty()) "-" else delays.sorted()[delays.size/2].toString()

            out.append(String.format("%-20s %6d%% %9d %9d %8d %7d %9s%n",
                arm.name, 100*hit/windows, captures, outside, windows-hit, stale, median))
            }

        println()
        println("Mechanism ablation, ${traces.size} scripted traces (control decisions, not upload rates)")
        println()
        print(out)
        println()
        for (trace in traces) {
            val fired=arms.filter { run(it, trace).captures.isNotEmpty() }.map { it.name }
            println(String.format("  %-26s captured by: %s",
                trace.name, if (fired.isEmpty()) "nobody" else fired.joinToString(", ")))
            }
        println()
        }

    // ------------------------------------------------------------------
    // What the full controller has to do
    // ------------------------------------------------------------------

    @Test
    fun theFullControllerCapturesEveryEligibleWindowAndNothingElse() {
        for (trace in traces) {
            val outcome=run(full, trace)

            if (trace.window==null)
            assertTrue("${trace.name}: captured at ${outcome.captures} with no eligible window",
                outcome.captures.isEmpty())
            else {
                assertEquals("${trace.name}: expected one capture in ${trace.window}, got ${outcome.captures}",
                    1, outcome.inWindow(trace.window).size)
                assertEquals("${trace.name}: captured outside the window at ${outcome.outsideWindow(trace.window)}",
                    0, outcome.outsideWindow(trace.window).size)
                }
            }
        }

    @Test
    fun theFullControllerNeverAcceptsAnObsoleteView() {
        for (trace in traces.filter { it.staleAtResponse })
        assertEquals("${trace.name}: accepted a stale response", false, run(full, trace).responseAccepted)

        for (trace in traces.filter { it.responseAtMs!=null&&!it.staleAtResponse })
        assertEquals("${trace.name}: rejected a usable response", true, run(full, trace).responseAccepted)
        }

    // ------------------------------------------------------------------
    // What each ablation loses
    // ------------------------------------------------------------------

    @Test
    fun theBaselineCapturesTheRegripAndSpeaksStaleAnswers() {
        // Q's two failures, and they are different in kind: one wastes an upload
        // on a half-finished action, the other speaks a date about a view the
        // user has already turned away from.
        val pause=turnWithPause()

        assertTrue("Q no longer captures during the mid-action pause",
            run(quality, pause).outsideWindow(pause.window).isNotEmpty())
        assertEquals(0, run(full, pause).outsideWindow(pause.window).size)

        for (trace in traces.filter { it.staleAtResponse })
        assertEquals("${trace.name}: Q no longer accepts it", true, run(quality, trace).responseAccepted)
        }

    @Test
    fun theBaselineIsSoonerWhenNothingInterruptsTheAction() {
        // The honest half of the comparison, and the cost of the design. On an
        // action carried out in one movement, Q reaches the window and reaches
        // it sooner: waiting out a regrip that did not happen is time spent for
        // nothing. Any delay claim has to be made against this, not against a
        // periodic baseline.
        for (trace in listOf(cleanTurn(), moveCloser(), shakeDuringRequest())) {
            val e=run(full, trace).inWindow(trace.window)
            val q=run(quality, trace).inWindow(trace.window)

            assertTrue("${trace.name}: Q missed a window the controller caught", q.isNotEmpty())
            assertTrue("${trace.name}: Q was slower than the controller", q.first()<=e.first())
            }
        }

    @Test
    fun theBaselinesPrematureCaptureCostsItTheWindowThatMattered() {
        // The more interesting failure. Q spends its one in-flight request on
        // the regrip pause, and the view it actually wanted - the finished turn
        // - arrives while that request is still running. A premature capture is
        // not merely a wasted upload; at eight to nine seconds a request, it is
        // a missed observation as well.
        val trace=turnWithPause()
        val q=run(quality, trace)

        assertTrue("Q no longer captures early", q.outsideWindow(trace.window).isNotEmpty())
        assertTrue("Q no longer misses the real window", q.inWindow(trace.window).isEmpty())
        assertEquals(1, run(full, trace).inWindow(trace.window).size)
        }

    @Test
    fun withoutTheSettlingHoldAPauseLooksLikeCompletion() {
        val arm=arms.first { it.name=="E -settling hold" }
        val trace=turnWithPause()

        assertTrue("the mid-action pause no longer triggers a capture",
            run(arm, trace).outsideWindow(trace.window).isNotEmpty())
        assertEquals(0, run(full, trace).outsideWindow(trace.window).size)
        }

    @Test
    fun withoutTheMatchHoldOneFrameRevivesAStaleAnswer() {
        val arm=arms.first { it.name=="E -match hold" }
        val trace=passingThroughDuringRequest()

        assertEquals("a single matching frame no longer revives a stale response",
            true, run(arm, trace).responseAccepted)
        assertEquals(false, run(full, trace).responseAccepted)
        }

    @Test
    fun withoutFreshnessCheckingEveryStaleAnswerIsSpoken() {
        val arm=arms.first { it.name=="E -freshness" }

        for (trace in traces.filter { it.staleAtResponse })
        assertEquals("${trace.name}: stale response no longer accepted", true, run(arm, trace).responseAccepted)
        }

    /**
    * The one mechanism that changes nothing here, and why that is a result.
    *
    * Sustained onset cannot alter a capture decision whenever the view is still
    * DIFFERENT when it settles: the settling hold is 900ms and subsumes the
    * 250ms the onset asks for. What is left of the rule is which pre-event frame
    * gets pinned for verification, which no trace in this set exercises. Do not
    * report onset as a capture-efficiency mechanism; either measure it on
    * recorded traces through before-frame selection, or drop it from the paper's
    * ablation and keep it as an implementation detail.
    */
    @Test
    fun sustainedOnsetChangesNoCaptureDecisionInThisTraceSet() {
        val arm=arms.first { it.name=="E -onset" }

        for (trace in traces) {
            val a=run(arm, trace)
            val e=run(full, trace)

            assertEquals("${trace.name}: onset now changes the capture decision", e.captures, a.captures)
            assertEquals("${trace.name}: onset now changes freshness", e.responseAccepted, a.responseAccepted)
            }
        }

    @Test
    fun everyOtherArmDiffersFromTheFullController() {
        // A mechanism that changed nothing on any trace would be one to delete
        // rather than to publish. Onset is excluded and documented above.
        for (arm in arms.filter { it!=full&&it.name!="E -onset" }) {
            val differs=traces.any { trace ->
                val a=run(arm, trace)
                val e=run(full, trace)

                a.captures!=e.captures||a.responseAccepted!=e.responseAccepted
                }

            assertTrue("${arm.name} behaves exactly like the full controller", differs)
            }
        }
    }

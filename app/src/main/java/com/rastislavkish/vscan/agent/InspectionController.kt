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
import android.os.SystemClock

import com.rastislavkish.vscan.BuildConfig
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.UUID

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
* Drives one expiry inspection: start, capture, respond, stop.
*
* The whole agent now runs here rather than on a laptop, so this class owns what
* the guidance server used to: the memory of which faces have been inspected,
* the three model calls per turn, and the guard that refuses to speak a date the
* current photograph does not support.
*
* Threading
* ---------
* Everything in VScan's TabAdapter is accessed under a single mutex, and a turn
* takes three model calls - tens of seconds. Holding that mutex across them
* would freeze the UI including Stop and Pause, which are the two controls a
* user reaches for precisely when a turn is taking too long. So this keeps its
* own mutex and holds it only for state transitions, never across a call.
*
* Staleness
* ---------
* The user can stop, or start a new package, while a turn is in flight. Every
* result is checked against the session it belongs to and the frame it answers
* before any of it reaches the user. A result that loses those checks is dropped
* in silence: it is advice about a package no longer in the user's hand.
*/
class InspectionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: Listener,
    ) {

    interface Listener {

        /** Short state line, e.g. "Waiting for you". Also the buttons' cue. */
        fun onStateChanged(session: InspectionSession?)

        /** One instruction to follow. Already spoken; show it too. */
        fun onInstruction(text: String)

        /** A date was read, or the agent abstained. The session is over. */
        fun onOutcome(text: String)

        /** Something went wrong. The session may or may not still be alive. */
        fun onProblem(text: String)

        /**
        * The view has settled after an instruction and a frame is worth taking.
        * Implementations should do exactly what the shutter button does.
        *
        * Defaulted to nothing so that ScanFragment - upstream VScan's screen,
        * still compiled but unreachable in this build - does not have to grow an
        * automatic capture path it will never use.
        */
        fun onCaptureRequested(ticket: EventCaptureTicket) {

            }
        }

    private val settings=AgentSettings.getInstance(context)
    private val speaker=GuidanceSpeaker(context)
    private val client=VisionClient()
    private val agent=InspectionAgent(client)

    /** Local event monitoring and identified camera reservations. */
    val stability=StabilityMonitor(scope, MotionSensorMonitor(context))

    /**
    * Which turn the in-flight calls belong to. The trace hook fires from three
    * coroutines that do not carry the session with them, and a turn's calls are
    * the only ones in the air at a time, so a pair of fields is enough.
    */
    @Volatile private var tracedSession=""
    @Volatile private var tracedFrame=-1

    /**
    * What the gate does when it decides a frame is worth taking.
    *
    * Held rather than installed once, because whether the agent takes frames on
    * its own initiative is a per-session condition. StabilityMonitor reserves a
    * camera slot only when this is non-null - "shadow/manual mode never reserves
    * a camera slot" - so clearing it is exactly how manual operation already
    * works, and restoring it is exactly how automatic operation already works.
    */
    private val autoCaptureRequest: (EventCaptureTicket) -> Unit = { ticket ->
        logger?.log("auto_capture", tracedSession, buildJsonObject {
            put("reason", ticket.reason.name.lowercase())
            put("event_id", ticket.eventId)
            put("capture_token", ticket.token)
            })
        watchCapture(ticket)
        listener.onStateChanged(session)
        listener.onCaptureRequested(ticket)
        }

    init {
        if (settings.autoCapture)
        stability.onCaptureRequest=autoCaptureRequest

        stability.onStall={
            if (session?.phase==InspectionPhase.WAITING) {
                val message="Take your time. You can press Capture when you are ready."
                if (settings.speakGuidance) speaker.notice(message)
                listener.onInstruction(message)
                }
            }
        speaker.onBusyChanged={ busy -> stability.setSpeaking(busy) }

        // When the user actually hears something, as opposed to when the text
        // was handed to the engine. These arrive on a TTS binder thread.
        speaker.onAudio={ event, ms ->
            // speechLog, not logger: the answer is spoken after closeSession has
            // already cleared logger, so the one utterance whose length matters
            // most was the one never recorded.
            speechLog?.log("speech_audio", tracedSession, buildJsonObject {
                put("frame_id", tracedFrame)
                put("event", event)
                put("since_dispatch_ms", ms)
                })
            }

        // One line per model call, so a turn can be read as its parts rather
        // than as a single number that hides which of the three was slow.
        client.onTrace={ trace ->
            logger?.log("model_call", tracedSession, buildJsonObject {
                put("frame_id", tracedFrame)
                put("stage", trace.stage)
                put("model", trace.model)
                put("elapsed_ms", trace.elapsedMs)
                put("request_bytes", trace.requestBytes)
                put("image_count", trace.imageCount)
                put("image_bytes", trace.imageBytes)
                put("prompt_tokens", trace.promptTokens)
                put("completion_tokens", trace.completionTokens)
                put("total_tokens", trace.totalTokens)
                put("finish_reason", trace.finishReason)
                put("error", trace.error)
                put("http_status", trace.httpStatus)
                put("body_snippet", trace.bodySnippet)
                })
            }
        }

    private val mutex=Mutex()

    private var session: InspectionSession?=null
    private var memory: InspectionMemory?=null
    private var logger: ExperimentLogger?=null
    private var work: Job?=null
    private var captureWatchdog: Job?=null
    private var recoveryAttempts=0

    /**
    * Whether this session names the face, checks the face that arrives, and
    * keeps a replaced tip replaced.
    *
    * Fixed for the whole session at start() and never read from settings again,
    * so a run cannot change condition underneath itself if the file on disk is
    * rewritten while it is going.
    */
    private var grounding=true

    /** What this session is called in the experiment, from RunConfig. Inert. */
    private var runLabel: String?=null

    /** Matched controller-case identifier and operator pseudonym; both inert. */
    private var pairId: String?=null
    private var operatorId: String?=null

    /**
    * The scripted behaviour this session is performing, or null for ordinary
    * use. Kept apart from [runLabel] because the two answer different questions
    * and the scorer only understands one of them.
    */
    private var blockLabel: String?=null

    /** Whether this session takes frames on its own initiative. */
    private var autoCapture=true

    /** Which capture controller drove this session. See GateConditions. */
    private var gateCondition=GateConditions.FULL

    /** Previous accepted frame, kept for action verification. */
    private var previousFrame: String?=null
    private var lastSentFrameId=-1

    /**
    * When the last session was started, so a repeated press is ignored.
    *
    * The control is large and found by feel, and it gets pressed twice often
    * enough to matter: 5 of 28 labelled ablation sessions were sub-second
    * restarts that logged a session_start and a run label and nothing else.
    * Each one costs the user a session they believe is running, and leaves the
    * analysis a labelled trace with no captures to score.
    *
    * Guarded here rather than on the button because two fragments wire that
    * control and a laptop-driven run can call start() directly.
    */
    private var lastStartAtMs=0L

    /** Consecutive turns answered by LocalGuidance rather than the planner. */
    private var fastPathTurns=0

    /**
    * The reference packages this session plans with, or null for the
    * no-retrieval condition. Fixed at session start, so switching the setting
    * mid-session cannot mix the two conditions within one run.
    */
    private var retrieval: DateLocationMemory?=null

    /** Whether this session's retrieval also matches brand words. Fixed at start like [retrieval]. */
    private var retrievalNames=true

    /** Loaded from the APK on first use and kept; the asset cannot change while the app runs. */
    private var referenceIndex: DateLocationMemory?=null
    private var referenceIndexError: String?=null

    /**
    * Logger for speech callbacks only. Outlives closeSession because the TTS
    * engine reports onStart/onDone tens of milliseconds after the session is
    * torn down; replaced when the next session starts.
    */
    @Volatile private var speechLog: ExperimentLogger?=null

    val currentSession: InspectionSession?
    get() = session

    val active: Boolean
    get() = session?.active ?: false

    /** True only when a capture would actually be acted on. */
    val acceptingCaptures: Boolean
    get() = session?.phase==InspectionPhase.WAITING&&stability.gate.activeTicket==null

    val wantsAnalysis: Boolean
    get() = stability.acceptsAnalysisFrames

    /**
    * Whether the capture gate has measured this session's hand as trembling.
    *
    * Read by the camera path as well as the planner, because the remedies are
    * different and both are needed: the words tell the user how to hold the
    * package, and the capture settings make the best of the hold they manage.
    */
    val handUnsteady: Boolean
    get() = stability.tremulous

    // ------------------------------------------------------------------
    // Session lifecycle
    // ------------------------------------------------------------------

    fun start(productHint: String?=null) {
        // A second press within the window is the same intent as the first, not
        // a request to abandon the session that press just started. Silent,
        // because the session already announced itself: the user hears it
        // continue, which is what they wanted.
        val now=SystemClock.elapsedRealtime()
        if (now-lastStartAtMs<RESTART_DEBOUNCE_MS) return
        lastStartAtMs=now

        if (!client.configured) {
            listener.onProblem("This build has no vision API key. Set geminiApiKey in local.properties and rebuild.")
            return
            }

        // Checked up front rather than after the first capture. Finding out you
        // are offline should not cost the user a photograph and thirty seconds
        // of standing still.
        if (offline()) {
            listener.onProblem("This phone has no internet connection. Turn on Wi-Fi or mobile data and try again.")
            return
            }

        scope.launch {
            // A running session is abandoned rather than resumed: "start with a
            // different package" is one of the controls, and it must leave no
            // memory of the previous one behind.
            stopInternal(announce=false)

            // Read once, here, so the condition and the label belong to this
            // session however the file changes afterwards.
            val run=RunConfig.read(context)
            grounding=run.instruction_grounding ?: settings.instructionGrounding
            val retrievalEnabled=run.retrieval_memory ?: settings.retrievalMemory
            val references=if (retrievalEnabled) loadReferenceIndex() else null
            // A run_config written from a laptop wins for one session; otherwise
            // the settings screen decides, so the study can be run by hand.
            //
            // Two fields, not one. They shared run_label until 18 September,
            // which had two consequences visible in the log: an operator
            // recording both had to write them into one string ("A_idle_can"),
            // and `?:` falls back only on null, so a run_config carrying
            // "label": "" - which is what a script writes when it has no
            // product to name - silently erased the block label instead of
            // deferring to it. Both are emptied to null here so that an absent
            // value looks the same however it was absent.
            runLabel=(run.label ?: settings.productLabel).ifEmpty { null }
            blockLabel=(run.block_label ?: settings.blockLabel).ifEmpty { null }
            pairId=run.pair_id?.ifEmpty { null }
            operatorId=(run.operator_id ?: settings.participant).ifEmpty { null }

            // Whether this session opened on the same package as the one before
            // it, for the same participant.
            //
            // The product spinner is sticky and nothing on screen shows it, so
            // running the second package without changing it records that
            // session under the first package's name - silently, and with no
            // way to recover which package was really in the hand. This cannot
            // prevent that; it makes it visible, so a suspect pair can be
            // dropped instead of being averaged in as two packages.
            //
            // Keyed on the participant as well, because a counterbalanced order
            // legitimately has the next participant starting on the package the
            // last one finished with. Flagging that would cry wolf at every
            // participant boundary, which is how a warning stops being read.
            val runKey=GateConditions.runKey(operatorId ?: "", runLabel)
            val repeatedRunLabel=runKey.isNotEmpty()&&runKey==settings.lastRunKey

            settings.lastRunKey=runKey
            settings.save()

            // Installed or cleared for this session. The gate reserves a camera
            // slot only when a handler is present, so clearing it leaves the
            // session entirely manual while the shadow arms carry on judging
            // every sample.
            autoCapture=run.auto_capture ?: settings.autoCapture
            stability.onCaptureRequest=if (autoCapture) autoCaptureRequest else null

            // Which controller actually drives the camera. A name that is not a
            // condition stops the session: a run recorded as an ablation that
            // silently used the shipped gate would be worse than no run.
            gateCondition=run.gate ?: settings.gateCondition
            val controller=GateConditions.of(gateCondition)
            if (controller==null) {
                val known=GateConditions.names.joinToString(", ")
                listener.onProblem("Unknown gate condition: $gateCondition. Expected one of $known.")
                return@launch
                }
            stability.useGate(gateCondition, controller)

            val fresh=InspectionSession(UUID.randomUUID().toString().replace("-", ""))
            fresh.instructionId=1
            fresh.instruction=Prompts.OPENING_INSTRUCTION
            fresh.phase=InspectionPhase.WAITING

            val log=ExperimentLogger(context, settings.storeImages)

            speechLog=log

            // So the opening instruction's audio is attributed to this session
            // rather than to whatever turn ran last.
            tracedSession=fresh.sessionId
            tracedFrame=0

            mutex.withLock {
                session=fresh
                memory=InspectionMemory(productHint)
                logger=log
                previousFrame=null
                lastSentFrameId=-1
                fastPathTurns=0
                recoveryAttempts=0
                retrieval=references
                retrievalNames=settings.retrievalNameMatching
                }

            log.log("session_start", fresh.sessionId, buildJsonObject {
                put("product_hint", productHint)
                // Which package this was and which arm it ran, recorded here
                // because neither can be recovered from the events afterwards.
                put("run_label", runLabel)
                put("run_label_repeated", repeatedRunLabel)
                put("participant", operatorId)
                put("operator_id", operatorId)
                put("pair_id", pairId)
                put("block_label", blockLabel)

                // Which build, and what it was told to do.
                //
                // A study is run by changing these numbers between sessions,
                // and nothing else in the log would survive that: the events
                // record what the gate did, never what it was configured with,
                // so two participants run on different thresholds are
                // indistinguishable afterwards and the pair is lost. The
                // version is the weaker half - it does not move when a constant
                // does, which is precisely when it would have mattered.
                put("app_version", BuildConfig.VERSION_NAME)
                put("app_build", BuildConfig.VERSION_CODE)
                put("params", stability.parameters())
                put("instruction_grounding", grounding)
                put("auto_capture", autoCapture)
                put("gate", gateCondition)
                put("store_images", settings.storeImages)
                put("ablation_arms", settings.ablationArms&&settings.visualStability)
                put("model", agent.model)
                // Which condition this run was, and against exactly which
                // reference set: the index hash and its exclusions are what
                // let a result be checked for leakage afterwards.
                putJsonObject("retrieval") {
                    put("enabled", retrievalEnabled)
                    put("active", references!=null)
                    put("name_matching", settings.retrievalNameMatching)
                    put("error", if (retrievalEnabled && references==null) referenceIndexError else null)
                    put("index_sha256", references?.sha256)
                    put("index_built_at", references?.index?.built_at)
                    put("cases", references?.size)
                    put("label_status", references?.index?.label_status)
                    put("split_sha256", references?.index?.split?.sha256)
                    putJsonArray("excluded_products") {
                        for (product in references?.index?.excluded_products ?: listOf()) add(product)
                        }
                    }
                })

            if (settings.ablationArms&&settings.visualStability)
            log.trace("session_start", fresh.sessionId, buildJsonObject {
                put("operator_id", operatorId)
                put("product_id", runLabel)
                put("scenario", blockLabel)
                put("pair_id", pairId)
                put("gate", gateCondition)
                put("app_version", BuildConfig.VERSION_NAME)
                put("app_build", BuildConfig.VERSION_CODE)
                put("auto_capture", autoCapture)
                put("retrieval_enabled", retrievalEnabled)
                put("instruction_grounding", grounding)
                })

            // Measurement only: the arms never reserve a capture. Attached
            // before start so that they are armed with the first instruction.
            stability.shadows=if (settings.ablationArms&&settings.visualStability) ShadowArms() else null
            stability.start(fresh.sessionId, log, settings.visualStability)
            stability.arm(CaptureReason.INITIAL_OBSERVATION, instructionId=fresh.instructionId)

            listener.onStateChanged(fresh)
            announce(Prompts.OPENING_INSTRUCTION)

            // The user is still lining the package up. Spend that time on the
            // TLS handshake so the first capture does not.
            launch { client.warmUp() }
            }
        }

    /**
    * Run one turn against a freshly captured still.
    *
    * A second press while a turn is running is dropped, not queued: by the time
    * it were served, the instruction it answers would be stale.
    */
    fun submit(image: ByteArray, capture: CaptureMetadata, reservation: EventCaptureTicket?=null) {
        val local=session
        val store=memory
        val log=logger
        val references=retrieval
        val matchNames=retrievalNames

        if (local==null || store==null || log==null || !local.active) {
            listener.onProblem("No inspection is running")
            return
            }
        if (local.phase==InspectionPhase.PAUSED) {
            listener.onProblem("Inspection is paused")
            return
            }
        if (local.phase==InspectionPhase.SENDING) {
            listener.onProblem("Still checking the last image")
            return
            }

        // The optional fallback keeps the unreachable upstream ScanFragment compiling.
        val ticket=reservation ?: reserveCapture() ?: return
        if (!acceptsCapture(ticket)) return
        val eventBefore=stability.beforeImage(ticket)
        if (!stability.acceptCapture(ticket)) {
            captureFailed(ticket, "The view moved during capture. Hold still for another picture.")
            return
            }
        captureWatchdog?.cancel()
        captureWatchdog=null
        val frameId=local.nextFrameId()
        val answeredInstruction=ticket.instructionId
        val asked=local.instruction
        val askedAction=local.instructionAction
        // Null unless the outstanding instruction was a tip, which is the only
        // kind that names a face and so the only kind that can be answered with
        // the wrong one.
        val askedForSurface=if (!grounding) null
        else askedAction?.let { GuidanceTemplates.targetSurface(it) }
        val invertedSurface=askedAction?.let { GuidanceTemplates.invertedSurface(it) }
        val instructionReference=previousFrame
        // Set synchronously, before launch, so two callbacks cannot start two turns.
        local.phase=InspectionPhase.SENDING
        lastSentFrameId=frameId
        listener.onStateChanged(local)

        work=scope.launch {
            try {

            // Seventeen seconds of silence is indistinguishable from a phone
            // that did not register the press. Said through notice() so that
            // pressing Repeat still replays the instruction, not this.
            if (settings.speakGuidance)
            speaker.notice(WORKING_NOTICE)

            tracedSession=local.sessionId
            tracedFrame=frameId
            stability.noteCapture(frameId)

            val startedAt=System.nanoTime()
            var tokens=0
            var encodeMs=0L
            var usedFastPath=false

            // Carried out of the try block so the mutex section below does not
            // have to re-encode it.
            var nextPreviousFrame: String?=null

            // The frame exactly as perception read it - bounded and upright -
            // carried out so the photograph on disk is that image rather than a
            // second rendering of the same capture in a different orientation.
            var perceptionImage: ByteArray?=null

            // Likewise carried out for the observation log.
            var retrieved: LocationRetrieval?=null

            /**
            * Set when the phone, not the verifier, decided the tip went the
            * wrong way. Logged separately from the status it produces so that a
            * session can be read back as "the verifier missed this" rather than
            * "the verifier caught this", which are different problems.
            */
            var invertedTip=false

            val result=try {
                // Decoding and rescaling a 1536px JPEG is real CPU and memory
                // work. Both sizes are produced once, together, off the main
                // thread: on the main thread it would make Stop go numb exactly
                // when a user wants it, and doing it twice would double the cost
                // of every turn for nothing.
                val encodeStartedAt=System.nanoTime()
                val (frame, verifyFrame)=withContext(Dispatchers.Default) {
                    val upright=FrameEncoder.oriented(image, FrameEncoder.PERCEPTION_MAX_EDGE, capture.rotation_degrees ?: 0)
                    perceptionImage=upright
                    Pair(
                        FrameEncoder.encode(upright),
                        FrameEncoder.encodeScaled(image, FrameEncoder.VERIFY_MAX_EDGE, capture.rotation_degrees ?: 0),
                        )
                    }
                encodeMs=(System.nanoTime()-encodeStartedAt)/1_000_000

                val previous=if (eventBefore!=null)
                withContext(Dispatchers.Default) { FrameEncoder.encodeScaled(eventBefore, FrameEncoder.VERIFY_MAX_EDGE) }
                else instructionReference

                // perceive() reads the new frame; verify() compares the old one
                // with the new. Neither consumes the other's output - only plan()
                // needs both - so running them in sequence spent an entire model
                // call's worth of wall clock for nothing. On a phone in a shop
                // that was a third of the wait.
                //
                // coroutineScope so a failure in either cancels its sibling and
                // surfaces here, rather than escaping into the fragment's job and
                // taking the whole screen down with it.
                val (perception, perceptionTokens, verified)=coroutineScope {
                    val perceiving=async { agent.perceive(frame) }
                    val verifying=if (previous!=null && asked.isNotBlank())
                    async { agent.verify(previous, verifyFrame, asked, askedForSurface) }
                    else null

                    val seen=perceiving.await()
                    Triple(seen.first, seen.second, verifying?.await())
                    }

                tokens+=perceptionTokens

                var verification: Verification?=null

                if (verified!=null) {
                    verification=verified.first
                    tokens+=verified.second
                    }

                // The face that arrived decides this, not the verifier's opinion
                // of it. Asked for the base, handed the top: the movement
                // happened and it went the other way. The verifier is told to
                // report that itself now, and on 2026-09-16 it had every piece
                // of evidence - it wrote "the top of the can is now visible" -
                // and still returned completed on all three turns it judged. A
                // comparison of two labels is cheap, certain, and cannot be
                // talked out of.
                if (grounding &&
                    GuidanceTemplates.tipWentTheWrongWay(askedAction, perception.surface_in_view)) {
                    invertedTip=true

                    // view_quality is carried across untouched, and taken from
                    // the frame itself when there was no verification call to
                    // take it from. Turning the package the wrong way says
                    // nothing about how good the photograph is, and a default
                    // "poor" here would quietly stop the face that did arrive
                    // from counting as inspected.
                    verification=Verification(
                        action_status=ActionStatus.WRONG_FACE,
                        view_changed=true,
                        what_changed=verification?.what_changed
                        ?: "the $invertedSurface arrived where the $askedForSurface was asked for",
                        view_quality=verification?.view_quality ?: agent.qualityFrom(perception),
                        )
                    }

                nextPreviousFrame=verifyFrame

                // A frame that is merely blurred or too far away has one
                // sensible next move and does not need a model to find it. That
                // call was measured at 5-6s, a third of the wait, to produce
                // "move the phone closer". LocalGuidance declines anything that
                // involves judgement, so ANSWER and ABSTAIN still come only from
                // the planner - and so does every third turn regardless.
                // The warrant, not the latch. Read once for the turn, so the
                // fast path and the planner are offered the brace on exactly
                // the same terms - and are not offered it at all on a turn
                // where it would be the wrong thing to ask for.
                val handUnsteady=braceWarranted(
                    perception,
                    verification?.action_status ?: ActionStatus.CANNOT_DETERMINE,
                    store,
                    )

                val shortcut=LocalGuidance.policyFor(
                    perception, verification, fastPathTurns, handUnsteady)

                val (policy, policyTokens)=if (shortcut!=null) {
                    fastPathTurns+=1
                    usedFastPath=true
                    Pair(shortcut, 0)
                    }
                else {
                    fastPathTurns=0

                    // Only for the planner: LocalGuidance never searches, so a
                    // fast-path turn has no use for where other packages keep
                    // their dates.
                    retrieved=references?.retrieve(RetrievalQuery(
                        geometry=store.settledGeometry(perception.geometry),
                        text=listOfNotNull(perception.text_read, store.productHint).joinToString(" "),
                        surfaceInView=perception.surface_in_view,
                        searchedWithoutDate=store.surfacesSearchedWithoutDate(
                            perception,
                            verification?.view_quality ?: agent.qualityFrom(perception),
                            ),
                        ), matchNames=matchNames)

                    agent.plan(
                        store.belief(),
                        perception,
                        verification,
                        store.visibleHistory(),
                        withRetrieval=references!=null,
                        retrieved=retrieved?.toPolicyJson(),
                        handUnsteady=handUnsteady,
                        )
                    }
                tokens+=policyTokens

                Triple(perception, verification, policy)
                }
            catch (e: CancellationException) {
                throw e
                }
            catch (e: ModelOutputException) {
                log.log("model_error", local.sessionId, buildJsonObject {
                    put("frame_id", frameId)
                    put("error", e.message)
                    put("stage", e.stage)
                    // The refused reply itself, so the next one of these can be
                    // diagnosed from the log instead of inferred from the code.
                    put("raw_reply", e.raw?.take(MAX_RAW_REPLY_CHARS))
                    })
                recover(local, ticket, e.message ?: "The model returned something unusable")
                return@launch
                }
            catch (e: Exception) {
                val explanation=explain(e)

                log.log("upstream_error", local.sessionId, buildJsonObject {
                    put("frame_id", frameId)
                    put("error", e.message)
                    put("offline", offline())
                    })
                recover(local, ticket, explanation)
                return@launch
                }

            // Dropped in silence: the user stopped, or moved on to another
            // package, while this was in the air.
            if (!isCurrent(local) || local.stopRequested || frameId!=lastSentFrameId)
            return@launch

            if (!stability.responseIsFresh(ticket)) {
                // What was discarded, not only that something was. Withholding a
                // reply is only a benefit if the reply had gone out of date, and
                // that cannot be judged from a record saying a reply existed.
                // Twenty-seven withheld responses were logged before this, and
                // none of them can be checked.
                //
                // Enough to compare against the next observation and no more: a
                // surface, whether a date was visible, and what it would have
                // said. Not the full reply, which is large and which nothing
                // would read.
                val dropped=result.first
                log.log("stale_response", local.sessionId, buildJsonObject {
                    put("frame_id", frameId)
                    put("capture_token", ticket.token)
                    put("tokens", tokens)
                    put("instruction_id", ticket.instructionId)
                    put("withheld_surface", dropped.surface_in_view)
                    put("withheld_date_visible", dropped.date_region_visible)
                    put("withheld_date_legible", dropped.date_legible)
                    put("withheld_date", dropped.iso_date ?: dropped.date_string)
                    put("withheld_action", result.third.action)
                    })
                previousFrame=null
                recover(local, ticket, "The view changed while I was checking. Hold still for a fresh picture.")
                return@launch
                }

            val (perception, verification, policy)=result
            val latency=(System.nanoTime()-startedAt)/1_000_000

            val actionStatus=verification?.action_status ?: ActionStatus.CANNOT_DETERMINE
            val viewQuality=verification?.view_quality ?: agent.qualityFrom(perception)

            var finished=false
            var abstained=false
            var abstainReason: String?=null
            var dateText: String?=null
            var isoDate: String?=null
            var dateType: DateType?=null
            var utterance=policy.utterance
            var blocked=false

            // Approved wording, rendered here rather than written by the model:
            // one noun per session, fixed phrasing, and nothing that asks the
            // user to judge something they cannot see.
            var guidance=GuidanceTemplates.parse(policy.action)
            var guidanceOverridden: GuidanceAction?=null

            if (guidance!=null) {
                // Planner guidance is not re-asked for a tip that has already
                // failed twice to show its face; a quarter turn keeps the
                // package upright and reaches the sides the search has skipped.
                if (store.repeatedTipWithoutReaching(guidance, perception.surface_in_view, grounding)) {
                    guidanceOverridden=guidance
                    guidance=GuidanceAction.TURN_LEFT
                    }

                // A brace this frame does not call for. Enforced here rather
                // than left to the prompt, because the prompt already said
                // "when the frame is smeared AND the hand is unsteady" and the
                // model dropped the first half on the first session it ran. A
                // quarter turn is the substitute the repeated tip gets too: it
                // keeps the package upright and reaches a side the search has
                // not seen, which is progress where a second posture correction
                // is not.
                else if (guidance==GuidanceAction.ELBOWS_IN&&
                    !braceWarranted(perception, actionStatus, store)) {
                    guidanceOverridden=guidance
                    guidance=GuidanceAction.TURN_LEFT
                    }

                utterance=GuidanceTemplates.render(guidance, nameTheFace=grounding)
                }

            when (policy.action) {
                "ANSWER" -> {
                    if (agent.answerIsGrounded(policy, perception, store.seenDateTypes())) {
                        finished=true
                        dateText=policy.date_string
                        isoDate=policy.iso_date
                        dateType=policy.date_type
                        }
                    else {
                        // Refuse it and keep the session alive rather than
                        // speaking a date this frame does not support.
                        blocked=true
                        utterance="I am not certain of that date. Hold the package and phone still."
                        }
                    }
                "ABSTAIN" -> {
                    finished=true
                    abstained=true
                    abstainReason=policy.abstain_reason ?: "illegible"
                    }
                }

            mutex.withLock {
                store.record(perception, viewQuality)

                // Asked once per session, whichever path chose it.
                if (guidance==GuidanceAction.ELBOWS_IN)
                store.braced=true

                if (finished && !abstained) {
                    store.candidateDate=dateText
                    store.candidateIso=isoDate
                    store.candidateType=dateType
                    }

                store.tokensSpent+=tokens
                store.history.add(Turn(
                    frameId=frameId,
                    instructionId=answeredInstruction,
                    perception=perception,
                    actionStatus=actionStatus,
                    viewQuality=viewQuality,
                    whatChanged=verification?.what_changed,
                    instruction=utterance,
                    rationale=policy.rationale,
                    latencyMs=latency,
                    tokens=tokens,
                    guidance=if (!blocked) guidance?.name else null,
                    ))

                // After the turn is in history, so this frame's reading counts.
                store.geometry=store.settledGeometry()

                local.instructionId=(local.instructionId ?: 0)+1
                local.instruction=utterance
                // The command behind the words, and only when those words are
                // what the command renders to: a blocked answer speaks its own
                // sentence and asks for no face at all.
                local.instructionAction=if (blocked) null else guidance
                local.lastDateText=dateText
                local.lastDateType=dateType?.name
                local.framesSent+=1
                local.phase=if (finished) InspectionPhase.FINISHED else InspectionPhase.WAITING

                previousFrame=nextPreviousFrame
                }

            // The upright frame, not the raw buffer: image_path should point at
            // what perception was given. Falls back to the buffer only if the
            // encode step somehow left it unset, which would mean this line is
            // unreachable anyway.
            val savedPath=log.saveFrame(local.sessionId, frameId, perceptionImage ?: image)
            val savedBefore=eventBefore?.let { log.saveFrame(local.sessionId, frameId, it, before=true) }

            log.log("observation", local.sessionId, buildJsonObject {
                put("frame_id", frameId)
                put("answered_instruction_id", answeredInstruction)
                put("event_id", ticket.eventId)
                put("capture_token", ticket.token)
                put("capture_reason", ticket.reason.name.lowercase())
                put("before_source", if (eventBefore!=null) "event_buffer" else if (instructionReference!=null) "previous_submission" else "none")
                put("before_frame_id", ticket.beforeFrameId)
                put("capture", encode(capture))

                // Carried on every frame, not only on the closing row.
                //
                // session_finish and session_stop are not guaranteed to be
                // written: of five sessions in the first participant run, one -
                // the longest, with the most frames - ended without either, and
                // its verdict went with it. A field that only exists when a
                // session ends cleanly is a field that is missing from exactly
                // the sessions that ran longest. Here it also dates itself: the
                // frame where it first reads true is the frame the tolerance
                // started carrying the session.
                put("hand_unsteady", stability.tremulous)
                put("brace_warranted", braceWarranted(perception, actionStatus, store))
                put("perception", encode(perception))
                put("verification", if (verification!=null) encode(verification) else JsonNull)
                put("policy", encode(policy))
                put("guard_blocked_answer", blocked)
                // What was outstanding, and whether the face that came back was
                // its opposite. Without these a wrong_face in the log cannot be
                // told from one the verifier invented.
                put("asked_for_surface", askedForSurface)
                put("inverted_tip", invertedTip)
                put("action_status", actionStatus.name.lowercase())
                put("view_quality", viewQuality.name.lowercase())
                put("finished", finished)
                put("latency_ms", latency)
                put("encode_ms", encodeMs)
                put("fast_path", usedFastPath)
                put("retrieval_active", references!=null)
                put("retrieval", retrieved?.toLogJson() ?: JsonNull)
                put("action", policy.action)
                put("guidance_rendered", guidance!=null)
                put("guidance_spoken", guidance?.name)
                put("guidance_overridden", guidanceOverridden?.name)
                put("instruction_spoken", utterance)
                put("tokens", tokens)
                put("model", agent.model)
                put("image_path", savedPath)
                put("before_image_path", savedBefore)
                })

            stability.releaseCapture(ticket)
            recoveryAttempts=0

            // The instruction is the reason a further observation might help.
            // Armed here rather than when speech ends: users start moving as
            // soon as they hear "move closer", and the movement that matters
            // most happens while the sentence is still playing.
            if (!finished)
            stability.arm(
                CaptureReason.POST_ACTION_CHANGE,
                // The template knows what it asked for; trust it over the
                // model's own answer when the two could disagree.
                if (blocked) ExpectedChange.NONE else if (guidance!=null) GuidanceTemplates.expectedChange(guidance) else policy.expected_change,
                local.instructionId,
                )

            val outcome=InspectionOutcome(
                frameId=frameId,
                actionStatus=actionStatus,
                viewQuality=viewQuality,
                instruction=utterance,
                instructionId=local.instructionId ?: 0,
                dateText=dateText,
                isoDate=isoDate,
                dateType=dateType,
                finished=finished,
                abstained=abstained,
                abstainReason=abstainReason,
                )

            listener.onStateChanged(local)

            // Everything above this point is bookkeeping between the last
            // response and the first syllable the user hears; measured, because
            // "the model was slow" and "we were slow after the model" are not
            // the same bug.
            val readyAt=System.nanoTime()

            if (finished) {
                // Said one way, shown another. The ear needs the month named;
                // the screen and the log want 16/02/2025.
                val spoken=describeOutcome(outcome, forSpeech=true)
                announce(spoken)
                logSpeechDelay(local.sessionId, frameId, readyAt)
                listener.onOutcome(describeOutcome(outcome, forSpeech=false))
                closeSession(local)
                return@launch
                }

            announce(utterance)
            logSpeechDelay(local.sessionId, frameId, readyAt)
            }
            finally {
                // Stop/pause may already own a different session. Release only OUR ticket.
                if (stability.releaseCapture(ticket)&&isCurrent(local)&&local.phase==InspectionPhase.SENDING) {
                    local.phase=InspectionPhase.WAITING
                    listener.onStateChanged(local)
                    }
                }
            }
        }

    fun stop() {
        // Invalidate synchronously; do not wait for the cleanup coroutine to run.
        session?.stopRequested=true
        stability.stop()
        captureWatchdog?.cancel()
        work?.cancel()
        speaker.interrupt()
        scope.launch { stopInternal(announce=true) }
        }

    fun togglePause() {
        val local=session ?: return

        when (local.phase) {
            InspectionPhase.PAUSED -> {
                local.phase=InspectionPhase.WAITING
                previousFrame=null
                recoveryAttempts=0
                local.instructionId=(local.instructionId ?: 0)+1
                local.instruction="Hold the package and phone still for a fresh picture."
                // Asks for no face, so nothing can arrive as the wrong one.
                local.instructionAction=null
                stability.resume(local.instructionId)
                listener.onStateChanged(local)
                announce(local.instruction)
                }
            InspectionPhase.WAITING, InspectionPhase.SENDING -> {
                local.phase=InspectionPhase.PAUSED
                captureWatchdog?.cancel()
                stability.pause()
                work?.cancel()
                speaker.interrupt()
                listener.onStateChanged(local)
                listener.onInstruction("Paused")
                }
            else -> {}
            }
        }

    fun repeatInstruction() {
        val local=session

        if (local==null || !local.active) {
            listener.onProblem("No inspection is running")
            return
            }

        speaker.repeat()
        listener.onInstruction(local.instruction)
        }

    /** Leaving the foreground pauses rather than stops: the user may be taking a call. */
    fun onBackgrounded() {
        val local=session ?: return

        if (local.phase==InspectionPhase.WAITING||local.phase==InspectionPhase.SENDING) {
            local.phase=InspectionPhase.PAUSED
            captureWatchdog?.cancel()
            stability.pause()
            work?.cancel()
            listener.onStateChanged(local)
            }

        speaker.interrupt()
        }

    fun release() {
        stability.stop()
        captureWatchdog?.cancel()
        speaker.shutdown()
        work?.cancel()
        client.close()
        logger?.closeTrace()
        session=null
        memory=null
        }

    // ------------------------------------------------------------------

    private suspend fun stopInternal(announce: Boolean) {
        val local=session ?: return
        val store=memory
        val log=logger

        local.stopRequested=true
        stability.stop()
        captureWatchdog?.cancel()
        work?.cancel()
        work=null
        speaker.interrupt()

        mutex.withLock {
            session=null
            memory=null
            logger=null
            previousFrame=null
            lastSentFrameId=-1
            fastPathTurns=0
            }

        log?.log("session_stop", local.sessionId, buildJsonObject {
            put("frames_seen", store?.history?.size ?: 0)
            put("tokens", store?.tokensSpent ?: 0)

            // The same three as session_finish, because a session ended by the
            // operator is still a session that was held by somebody's hands -
            // and in a study those are not the rare ones.
            put("hand_unsteady", stability.tremulous)
            put("quiet_limit", stability.quietLimit)
            put("baseline_calibrated", stability.baselineCalibrated)
            })
        log?.trace("session_stop", local.sessionId, buildJsonObject {
            put("frames_seen", store?.history?.size ?: 0)
            put("tokens", store?.tokensSpent ?: 0)
            })
        log?.closeTrace()

        listener.onStateChanged(null)

        if (announce)
        listener.onInstruction("Inspection stopped")
        }

    /**
    * A failed turn does not end the inspection.
    *
    * Nothing about the package changed because a request timed out, and the
    * memory of which faces have been inspected is still good. The user is told
    * and invited to press Capture again.
    */
    private fun recover(local: InspectionSession, ticket: EventCaptureTicket, message: String) {
        if (!isCurrent(local)||!stability.releaseCapture(ticket))
        return

        local.phase=InspectionPhase.WAITING
        scheduleRecovery(local, message)
        }

    private fun scheduleRecovery(local: InspectionSession, message: String) {
        recoveryAttempts+=1
        val retry=settings.autoCapture&&settings.visualStability&&recoveryAttempts<=1
        val spoken=if (retry) "Hold still. I will try one more picture." else "Press Capture to try again, or stop the inspection."
        // Recovery is a new instruction, not evidence that the old movement
        // happened. Do not compare across the missing/invalid observation.
        previousFrame=null
        local.instructionId=(local.instructionId ?: 0)+1
        local.instruction=spoken
        // A recovery prompt supersedes whatever face was outstanding; keeping it
        // would have the next frame judged against a movement nobody re-asked for.
        local.instructionAction=null
        if (retry)
        stability.arm(CaptureReason.RETRY_OBSERVATION, ExpectedChange.NONE, local.instructionId)
        else stability.gate.disarm(local.instructionId)
        listener.onStateChanged(local)
        listener.onProblem("$message $spoken")
        if (settings.speakGuidance) speaker.speak(spoken)
        }

    /** The only entry for manual camera reservations. Automatic tickets arrive from the gate. */
    fun reserveCapture(): EventCaptureTicket? {
        if (!acceptingCaptures) return null
        val ticket=stability.reserveCapture(automatic=false) ?: return null
        recoveryAttempts=0
        watchCapture(ticket)
        listener.onStateChanged(session)
        return ticket
        }

    fun acceptsCapture(ticket: EventCaptureTicket): Boolean =
    session?.let { it.sessionId==ticket.sessionId&&it.instructionId==ticket.instructionId&&it.phase==InspectionPhase.WAITING }==true&&
    stability.gate.owns(ticket)

    fun captureFailed(ticket: EventCaptureTicket, message: String) {
        val local=session ?: return
        if (local.sessionId!=ticket.sessionId||!stability.releaseCapture(ticket)) return
        captureWatchdog?.cancel()
        captureWatchdog=null
        scheduleRecovery(local, message)
        }

    private fun watchCapture(ticket: EventCaptureTicket) {
        captureWatchdog?.cancel()
        captureWatchdog=scope.launch {
            delay(10_000)
            if (stability.gate.owns(ticket)&&stability.gate.phase==EventPhase.CAPTURING)
            captureFailed(ticket, "The camera did not return a picture.")
            }
        }

    /**
    * An automatic capture arrived when one could not be acted on.
    *
    * Worth a line of its own: a gate that keeps proposing captures the app then
    * drops is not working, and without this the attempts are invisible.
    */
    fun noteAutoCaptureDropped(why: String) {
        logger?.log("auto_capture_dropped", tracedSession, buildJsonObject {
            put("reason", why)
            })
        }

    /** Analysis frames from the camera. Cheap: copies nothing, queues one. */
    fun offerAnalysisFrame(frame: CameraAnalysisFrame, atNs: Long) {
        stability.offer(frame, atNs)
        }

    /**
    * Whether asking the user to brace their elbows is warranted right now.
    *
    * The first participant run asked for it twice in one session, and both were
    * wrong: once on a frame reporting no problems at all, and once while the
    * user had an outstanding turn they had just made the wrong way. The cause
    * was that stability.tremulous is LATCHED - it has to be, so the camera does
    * not rebind mid-session - and the planner was handed that same permanently
    * true flag every turn. A sticky verdict is right for the camera and wrong
    * for a decision, so the decision gets this instead.
    *
    * Four conditions, and each corresponds to one way the brace went wrong:
    *
    *   - the hand was measured as unsteady, which the frame alone cannot say
    *   - THIS frame is actually smeared, so there is something to fix
    *   - no movement is outstanding, because a turn the user has half made
    *     outranks their posture - the same rule [LocalGuidance] applies
    *   - it has not already been asked, since bracing is done once or not at all
    */
    private fun braceWarranted(
        perception: Perception,
        status: ActionStatus,
        store: InspectionMemory,
        ): Boolean =
    LocalGuidance.braceWarranted(stability.tremulous, store.braced, perception, status)

    private suspend fun closeSession(local: InspectionSession) {
        val store=memory
        val log=logger

        log?.log("session_finish", local.sessionId, buildJsonObject {
            put("frames_seen", store?.history?.size ?: 0)
            put("tokens", store?.tokensSpent ?: 0)
            put("date", store?.candidateDate)

            // The tremor verdict belongs here rather than in session_start,
            // because it is not known there: it latches while the session runs.
            // Recorded so that separating a trembling participant from a steady
            // one is a field, rather than a reconstruction from the one-a-second
            // stability_sample rows - which only exist when visual stability was
            // switched on, and so would silently miss the sessions where it was
            // not.
            put("hand_unsteady", stability.tremulous)
            put("quiet_limit", stability.quietLimit)
            put("baseline_calibrated", stability.baselineCalibrated)
            })
        log?.trace("session_finish", local.sessionId, buildJsonObject {
            put("frames_seen", store?.history?.size ?: 0)
            put("tokens", store?.tokensSpent ?: 0)
            put("date", store?.candidateDate)
            })

        stability.stop()
        log?.closeTrace()

        mutex.withLock {
            if (session===local) {
                session=null
                memory=null
                logger=null
                previousFrame=null
                lastSentFrameId=-1
                fastPathTurns=0
                }
            }
        }

    private fun isCurrent(local: InspectionSession): Boolean = session===local

    /**
    * The bundled reference index, or null with the reason kept for the log.
    *
    * A missing or unreadable index does not stop an inspection: the user in the
    * aisle still gets the agent, only without retrieval, and session_start says
    * so. The prompt follows what was actually loaded, not what was asked for.
    */
    private suspend fun loadReferenceIndex(): DateLocationMemory? {
        referenceIndex?.let { return it }

        return try {
            val text=withContext(Dispatchers.IO) {
                context.assets.open(DateLocationMemory.ASSET_PATH).bufferedReader().use { it.readText() }
                }
            val loaded=withContext(Dispatchers.Default) { DateLocationMemory.parse(text) }

            referenceIndex=loaded
            referenceIndexError=null
            loaded
            }
        catch (e: CancellationException) {
            throw e
            }
        catch (e: Exception) {
            referenceIndexError=e.message ?: e.toString()
            null
            }
        }

    /** No usable network at all, as opposed to a network that refused us. */
    private fun offline(): Boolean {
        val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
        val network=manager.activeNetwork ?: return true
        val capabilities=manager.getNetworkCapabilities(network) ?: return true

        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    /**
    * What to say about a failed turn.
    *
    * "Cannot reach the vision model" is true of a flight-mode phone and of an
    * expired certificate alike, and only one of those the user can do anything
    * about. So the common, fixable case gets named.
    */
    private fun explain(e: Exception): String {
        if (offline())
        return "This phone has no internet connection. Turn on Wi-Fi or mobile data and try again."

        if (e is TransportException)
        return "Could not reach the vision model. Check the connection and press Capture again."

        return e.message ?: "Something went wrong. Press Capture to try again."
        }

    /**
    * Time from having an answer to handing it to the speaker.
    *
    * Deliberately NOT called response_to_speech_ms any more: tts.speak() is
    * asynchronous, so this only ever measured our own bookkeeping and was being
    * read as though it measured when the user heard the answer. The audible
    * figure is the speech_audio "start" event.
    */
    private fun logSpeechDelay(sessionId: String, frameId: Int, readyAt: Long) {
        logger?.log("speech", sessionId, buildJsonObject {
            put("frame_id", frameId)
            put("response_to_dispatch_ms", (System.nanoTime()-readyAt)/1_000_000)
            })
        }

    private fun announce(text: String) {
        if (settings.speakGuidance)
        speaker.speak(text)

        listener.onInstruction(text)
        }

    private inline fun <reified T> encode(value: T) =
    LOG_JSON.encodeToJsonElement(value)

    /**
    * Turn a finished turn into one sentence.
    *
    * An abstention is reported as an abstention. "I could not read a date" and
    * "there is no date on this package" are different facts about the world, and
    * flattening either into a guess is the failure this design exists to avoid.
    */
    private fun describeOutcome(outcome: InspectionOutcome, forSpeech: Boolean): String {
        if (outcome.abstained) {
            return when (outcome.abstainReason) {
                "no_date_exists" -> "I could not find a date on this package."
                "illegible" -> "There is printing there, but I cannot read it clearly."
                "budget" -> "I have run out of things to try on this package."
                else -> "I cannot give you a date for this one."
                }
            }

        // iso_date first: date_string is whatever was printed on the package,
        // and reading that aloud verbatim is how "16-02-2025" became three and a
        // half seconds of "dash". The verbatim form is kept only as a fallback
        // for when the model gave no normalised date at all.
        val formatted=if (forSpeech)
        DateFormats.spoken(outcome.isoDate)
        else
        DateFormats.numeric(outcome.isoDate)

        val date=formatted ?: outcome.dateText ?: return "I cannot give you a date for this one."
        val label=when (outcome.dateType) {
            DateType.USE_BY -> "Use by"
            DateType.BEST_BEFORE -> "Best before"
            DateType.PRODUCTION -> "Production date"
            DateType.LOT_CODE -> "Lot code"
            else -> "Date"
            }

        return "$label $date"
        }

    companion object {

        private val LOG_JSON=Json { encodeDefaults=true }

        /** Said at capture so the wait is distinguishable from a dead button. */
        private const val WORKING_NOTICE="Looking."

        /** A planner reply is a few hundred characters; this bounds a runaway one. */
        private const val MAX_RAW_REPLY_CHARS=4000

        /**
        * How long after a start a second press is treated as the same press.
        *
        * The observed double presses were 0.76 to 1.3 seconds apart. A second
        * and a half covers those without blocking a deliberate restart, which
        * takes longer than that: the user has to hear the first instruction
        * before deciding they wanted a different package.
        */
        private const val RESTART_DEBOUNCE_MS=1500L
        }
    }

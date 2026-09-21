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

package com.rastislavkish.vscan.ui.mainactivity

import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.LayoutInflater
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe

import com.rastislavkish.vscan.R

import com.rastislavkish.vscan.agent.AgentSettings
import com.rastislavkish.vscan.agent.CaptureMetadata
import com.rastislavkish.vscan.agent.CameraAnalysisFrame
import com.rastislavkish.vscan.agent.EventCaptureTicket
import com.rastislavkish.vscan.agent.FrameSharpness
import com.rastislavkish.vscan.agent.InspectionController
import com.rastislavkish.vscan.agent.InspectionPhase
import com.rastislavkish.vscan.agent.InspectionSession

import com.rastislavkish.vscan.core.Resources
import com.rastislavkish.vscan.core.Settings

import com.rastislavkish.vscan.ui.settingsactivity.SettingsActivity

/**
* The app: find the expiry date on the package in the user's hand.
*
* Deliberately not built on ScanFragment. That screen is organised around a
* Config - a camera, a resolution, a pair of prompts, a model - because a scan is
* one question asked once. An inspection is a conversation with a backend that
* remembers, and the only thing it wants from the device is a good photograph on
* demand. Sharing a fragment between the two would have meant a Config that is
* never read and a TabAdapter mutex held for nothing.
*
* So there is no Config here, no TabAdapter, and no Conversation. One camera use
* case, one controller, four buttons.
*/
class ExpiryFragment: Fragment(), CoroutineScope, InspectionController.Listener {

    override val coroutineContext: CoroutineContext
    get() = Dispatchers.Main+job

    private lateinit var job: Job

    private lateinit var settings: Settings
    private lateinit var agentSettings: AgentSettings
    private lateinit var resources: Resources
    private lateinit var inspectionController: InspectionController
    private lateinit var orientationEventListener: OrientationEventListener

    private var camera: Camera?=null
    private var cameraProvider: ProcessCameraProvider?=null
    private val imageCapture: ImageCapture
    private val tremorImageCapture: ImageCapture
    private val imageAnalysis: ImageAnalysis
    private val preview: Preview
    private var boundToTremor=false
    private var previewView: PreviewView?=null
    private var analysisExecutor: ExecutorService?=null
    private var lastAnalysisNs=0L
    private var lastSnapshotNs=0L

    private lateinit var statusLabel: TextView
    private lateinit var instructionLabel: TextView
    private lateinit var actionButton: Button
    private lateinit var controlBar: LinearLayout
    private lateinit var settingsButton: Button
    private lateinit var repeatButton: Button
    private lateinit var pauseButton: Button
    private lateinit var newPackageButton: Button
    private lateinit var stopButton: Button

    init {
        // A date code is a few millimetres of dot-matrix print. 1536 is chosen
        // against the backend, which bounds its long edge at 1568: sending more
        // only buys a second resampling on the way in.
        val resolutionSelector=ResolutionSelector.Builder()
        .setResolutionStrategy(ResolutionStrategy(Size(1536, 1536), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
        .build()

        imageCapture=ImageCapture.Builder()
        .setResolutionSelector(resolutionSelector)
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
        .build()

        // The same frame, for a hand that will not hold still.
        //
        // MAXIMIZE_QUALITY is right for a steady hand and wrong here: on most
        // devices it fuses several exposures, and fusing frames taken while the
        // phone is moving is how a crisp dot-matrix code becomes a doubled one.
        // It is also slow enough that a burst of them would cost more of the
        // user's time than the sharper frame is worth.
        //
        // Same resolution, because that was chosen for the size of a date code
        // and a tremor is not a reason to send a smaller picture of one.
        tremorImageCapture=ImageCapture.Builder()
        .setResolutionSelector(resolutionSelector)
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
        .build()

        // Motion is downsampled to ~320x240. Bounded ~640px colour keyframes
        // provide pre-event evidence; only a selected frame is sent to verification.
        preview=Preview.Builder().build()

        imageAnalysis=ImageAnalysis.Builder()
        .setResolutionSelector(ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build())
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
        ): View? {
        return inflater.inflate(R.layout.fragment_expiry, container, false)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        job=Job()
        settings=Settings.getInstance(requireContext())
        agentSettings=AgentSettings.getInstance(requireContext())
        resources=Resources.getInstance(requireContext())
        inspectionController=InspectionController(requireContext(), this, this)

        statusLabel=view.findViewById(R.id.statusLabel)
        instructionLabel=view.findViewById(R.id.instructionLabel)
        actionButton=view.findViewById(R.id.actionButton)
        controlBar=view.findViewById(R.id.controlBar)
        settingsButton=view.findViewById(R.id.settingsButton)
        previewView=view.findViewById(R.id.previewView)
        repeatButton=view.findViewById(R.id.repeatButton)
        pauseButton=view.findViewById(R.id.pauseButton)
        newPackageButton=view.findViewById(R.id.newPackageButton)
        stopButton=view.findViewById(R.id.stopButton)

        actionButton.setOnClickListener { onActionButtonClick() }
        // Long press repeats. The instruction is the one thing a user loses when
        // a trolley rattles past, and hunting for a small Repeat button with a
        // package in the other hand is exactly what the big target avoids.
        actionButton.setOnLongClickListener {
            inspectionController.repeatInstruction()
            true
            }

        settingsButton.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
            }
        repeatButton.setOnClickListener { inspectionController.repeatInstruction() }
        pauseButton.setOnClickListener { inspectionController.togglePause() }
        newPackageButton.setOnClickListener { inspectionController.start() }
        stopButton.setOnClickListener { inspectionController.stop() }

        orientationEventListener=object : OrientationEventListener(requireContext()) {

            override fun onOrientationChanged(orientation: Int) {
                onOrientationChange(orientation)
                }
            }

        val cameraProviderFuture=ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(Runnable {
            cameraProvider=cameraProviderFuture.get()
            bindCamera()
            }, ContextCompat.getMainExecutor(requireContext()))

        try {
            EventBus.getDefault().register(this)
            }
        catch (e: Exception) {

            }

        onStateChanged(null)
        }

    override fun onResume() {
        orientationEventListener.enable()
        super.onResume()
        }
    override fun onPause() {
        orientationEventListener.disable()

        // Pause rather than stop: leaving the foreground is usually a phone call
        // or TalkBack's own menu, not the user giving up on the package.
        inspectionController.onBackgrounded()

        super.onPause()
        }
    private companion object {

        const val ANALYSIS_INTERVAL_NS=100_000_000L
        const val SNAPSHOT_INTERVAL_NS=500_000_000L

        /**
        * Frames taken per capture when the hand has been measured as unsteady.
        *
        * Three, because the spacing matters more than the count: a tremor at
        * four to twelve cycles a second passes through its quiet part several
        * times a second, so three shots a few hundred milliseconds apart are
        * unlikely to all land on the same part of the cycle. A fourth buys
        * little and is another half-second of a blind user's time in a shop.
        */
        const val TREMOR_BURST=3

        /**
        * The auto-exposure frame rate pinned while a trembling hand holds the
        * phone, which is what puts a thirtieth of a second ceiling on the
        * exposure. See applyShutterFloor.
        */
        const val TREMOR_SHUTTER_FPS=30
        }

    override fun onDestroy() {
        try {
            EventBus.getDefault().unregister(this)
            }
        catch (e: Exception) {

            }

        inspectionController.release()
        imageAnalysis.clearAnalyzer()
        analysisExecutor?.shutdownNow()
        analysisExecutor=null
        job.cancel()
        super.onDestroy()
        }

    /**
    * Volume keys and shake do what the big button does.
    *
    * The gesture bindings in Settings address scan Actions, which no longer
    * exist here, so every device input collapses onto the one thing this screen
    * can do. That is a feature: a user holding a jar in one hand and a cane in
    * the other should not have to find a button at all.
    */
    @Subscribe
    fun onDeviceInputEvent(event: DeviceInputEvent) {
        onActionButtonClick()
        }

    fun onActionButtonClick() {
        if (!inspectionController.active) {
            inspectionController.start()
            return
            }

        capture()
        }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    fun capture(reservation: EventCaptureTicket?=null) {
        val ticket=reservation ?: inspectionController.reserveCapture() ?: return
        if (!inspectionController.acceptsCapture(ticket)) return
        if (camera==null) {
            inspectionController.captureFailed(ticket, "The camera is not ready yet.")
            return
            }

        // Taken before anything else in this method: the user's wait starts at
        // the press, not at the point CameraX hands back a frame.
        val pressedAt=SystemClock.elapsedRealtime()

        val useFlash=settings.useFlashlight

        // Off unless the user asked for it: a flash on glossy film or a
        // laminated lid produces exactly the specular blowout that makes a
        // dot-matrix date unreadable, and a ruined frame costs a whole turn.
        imageCapture.setFlashMode(when (useFlash) {
            true -> ImageCapture.FLASH_MODE_ON
            false -> ImageCapture.FLASH_MODE_OFF
            })

        if (settings.useSounds)
        resources.shutterSound.play()

        val still=stillCapture()

        // A burst is bought with the user's time, so it is spent only where it
        // buys something. A steady hand's frames differ by nothing worth
        // choosing between, and the second and third would be pure latency in
        // front of somebody standing in a shop.
        val wanted=if (inspectionController.handUnsteady) TREMOR_BURST else 1

        launch {
            val frames=mutableListOf<Pair<ByteArray, CaptureMetadata>>()
            var problem: String?=null

            for (index in 0 until wanted) {
                // Re-checked every frame, not only at the start: a burst spans a
                // few hundred milliseconds, and a turn that ends inside one must
                // not go on photographing for it.
                if (index>0&&!inspectionController.acceptsCapture(ticket))
                break

                val shot=takeOneFrame(still, useFlash, ticket, pressedAt)

                if (shot.image!=null&&shot.metadata!=null)
                frames.add(Pair(shot.image, shot.metadata))
                else problem=shot.problem ?: problem

                if (shot.dropped)
                return@launch
                }

            if (frames.isEmpty()) {
                inspectionController.captureFailed(ticket,
                    problem ?: "The camera could not take that picture.")
                return@launch
                }

            // Decoding runs off the main thread: three 1536px stills is not
            // somewhere to stall the interface a blind user is waiting on.
            val chosen=if (frames.size>1)
            withContext(Dispatchers.Default) { FrameSharpness.sharpest(frames.map { it.first }) } ?: 0
            else 0

            if (!inspectionController.acceptsCapture(ticket))
            return@launch

            val (image, metadata)=frames[chosen]

            inspectionController.submit(image, metadata.copy(
                // The user's wait ends when a frame is chosen, not when the
                // first one arrived, so a burst has to say what it really cost.
                press_to_image_ms=(SystemClock.elapsedRealtime()-pressedAt).toInt(),
                burst_frames=frames.size,
                burst_chosen=chosen,
                ), ticket)
            }
        }

    /** One frame of a burst: what came back, or why nothing did. */
    private class Shot(
        val image: ByteArray?=null,
        val metadata: CaptureMetadata?=null,
        val problem: String?=null,

        /** The turn moved on underneath this frame; say nothing and stop. */
        val dropped: Boolean=false,
        )

    /**
    * One still, awaited rather than delivered by callback.
    *
    * A burst has to be sequential - CameraX takes one picture at a time - and
    * chaining callbacks by hand is how the second frame ends up being submitted
    * while the third is still in flight. Suspending keeps the whole burst in one
    * place, where it reads as what it is.
    */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private suspend fun takeOneFrame(
        still: ImageCapture,
        useFlash: Boolean,
        ticket: EventCaptureTicket,
        pressedAt: Long,
        ): Shot = suspendCancellableCoroutine { continuation ->
        fun finish(shot: Shot) {
            if (continuation.isActive) continuation.resume(shot)
            }

        try {
            still.takePicture(
                ContextCompat.getMainExecutor(requireContext()),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(imageProxy: ImageProxy) {
                        if (!inspectionController.acceptsCapture(ticket)) {
                            imageProxy.close()
                            finish(Shot(dropped=true))
                            return
                            }

                        // Read the geometry before extractImage() closes the proxy.
                        val capture=CaptureMetadata(
                            width=imageProxy.width,
                            height=imageProxy.height,
                            rotation_degrees=imageProxy.imageInfo.rotationDegrees,
                            flash_fired=useFlash,
                            device_ms=(SystemClock.elapsedRealtime() and 0x7fffffffL).toInt(),
                            press_to_image_ms=(SystemClock.elapsedRealtime()-pressedAt).toInt(),
                            automatic=ticket.automatic,
                            )

                        val image=extractImage(imageProxy)

                        finish(
                        if (image!=null) Shot(image, capture)
                        else Shot(problem="The camera returned an unsupported image type.")
                        )
                        }

                    override fun onError(error: ImageCaptureException) {
                        finish(Shot(problem="The camera could not take that picture."))
                        }
                    },
                )
            }
        catch (e: Exception) {
            finish(Shot(problem="The camera could not start that picture."))
            }
        }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    fun extractImage(imageProxy: ImageProxy): ByteArray? {
        val mediaImage=imageProxy.image

        if (mediaImage!=null && mediaImage.format==ImageFormat.JPEG) {
            val buffer=mediaImage.planes[0].buffer
            val bytes=ByteArray(buffer.remaining())
            buffer.get(bytes)

            imageProxy.close()
            return bytes
            }

        imageProxy.close()
        toast("The camera returned an unsupported image type")
        return null
        }

    fun bindCamera() {
        val provider=cameraProvider ?: return

        provider.unbindAll()

        previewView?.let { preview.setSurfaceProvider(it.surfaceProvider) }

        if (!agentSettings.visualStability) {
            camera=provider.bindToLifecycle(
                viewLifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                stillCapture(),
                )
            applyShutterFloor()
            return
            }

        val executor=analysisExecutor ?: Executors.newSingleThreadExecutor().also { analysisExecutor=it }
        imageAnalysis.setAnalyzer(executor, ::analyse)

        camera=provider.bindToLifecycle(
            viewLifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            stillCapture(),
            imageAnalysis,
            )
        applyShutterFloor()
        }

    /** Which use case this hand should be photographed with. */
    private fun stillCapture(): ImageCapture =
    if (boundToTremor) tremorImageCapture else imageCapture

    /**
    * Swap the still capture between its two modes, and only then.
    *
    * A rebind tears the camera down and brings it back up - including the
    * analysis stream, which the gate then has to re-anchor - so it may only
    * happen when the answer actually changed. It changes at most once a
    * session, because StabilityMonitor.tremulous is latched.
    *
    * Called only while a request is in flight. That is the one moment in a turn
    * with nothing to interrupt: no capture is running, the gate refuses to
    * reserve another while its phase is REQUEST_RUNNING, and the user is
    * already waiting on the network, so the camera's few hundred milliseconds
    * cost them nothing they were not spending anyway.
    */
    private fun rebindForTremor(tremulous: Boolean) {
        if (cameraProvider==null||tremulous==boundToTremor)
        return

        boundToTremor=tremulous
        bindCamera()
        }

    /**
    * Keep the shutter short enough that a tremor cannot smear the exposure.
    *
    * Pinning the auto-exposure frame rate is the portable way to put a floor
    * under shutter speed: at 30fps the exposure cannot exceed a thirtieth of a
    * second, and AE pays for it in sensor gain instead. Grainier and sharper
    * beats clean and smeared when what has to survive is millimetres of
    * dot-matrix print.
    *
    * Only while a trembling hand holds the phone. A steady hand on a dim shelf
    * wants the long exposure it gets today, and taking that away would be this
    * change making a worse photograph for the users it was not for.
    *
    * Best effort by construction: a vendor is free to ignore a range it does
    * not support, and a phone that does is no worse off than before.
    */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyShutterFloor() {
        val control=camera?.cameraControl ?: return

        runCatching {
            val options=CaptureRequestOptions.Builder()

            if (boundToTremor)
            options.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(TREMOR_SHUTTER_FPS, TREMOR_SHUTTER_FPS),
                )

            Camera2CameraControl.from(control).setCaptureRequestOptions(options.build())
            }
        }

    /**
    * The agent decided the view has settled. Identical to a button press, on
    * purpose: it goes through the same guard, so a capture that arrives while a
    * turn is still running is dropped exactly as a stray press would be.
    */
    override fun onCaptureRequested(ticket: EventCaptureTicket) {
        capture(ticket)
        }

    /**
    * Runs on the analysis executor, never the main thread.
    *
    * The ImageProxy is closed in a finally block without exception: CameraX
    * hands out a small fixed pool of buffers, and one leaked proxy stalls the
    * whole stream - including, eventually, the still capture the user is
    * waiting on.
    */
    private fun analyse(proxy: ImageProxy) {
        try {
            if (!inspectionController.wantsAnalysis) return
            val now=SystemClock.elapsedRealtimeNanos()

            // ~10 Hz is plenty to characterise a settling hand, and the camera
            // will happily deliver three times that if asked.
            if (now-lastAnalysisNs<ANALYSIS_INTERVAL_NS)
            return

            lastAnalysisNs=now

            val includeSnapshot=now-lastSnapshotNs>=SNAPSHOT_INTERVAL_NS
            if (includeSnapshot) lastSnapshotNs=now
            val frame=CameraAnalysisFrame.copy(proxy, includeSnapshot)
            inspectionController.offerAnalysisFrame(frame, now)
            }
        catch (e: Exception) {
            // Analysis is advisory. It must never take down a capture path the
            // user is depending on.
            }
        finally {
            proxy.close()
            }
        }

    var lastRotationValue=-1
    fun onOrientationChange(orientation: Int) {
        if (orientation==OrientationEventListener.ORIENTATION_UNKNOWN)
        return

        val rotation=UseCase.snapToSurfaceRotation(orientation)

        if (rotation!=lastRotationValue) {
            imageCapture.setTargetRotation(rotation)
            tremorImageCapture.setTargetRotation(rotation)
            imageAnalysis.setTargetRotation(rotation)
            lastRotationValue=rotation
            }
        }

    // ------------------------------------------------------------------
    // InspectionController.Listener
    // ------------------------------------------------------------------

    override fun onStateChanged(session: InspectionSession?) {
        val running=session!=null && session.active

        controlBar.visibility=if (running) View.VISIBLE else View.GONE

        if (session==null || !running) {
            // The verdict belonged to the session that measured it. Carrying it
            // into the next one would photograph a steady user's first package
            // on settings chosen for somebody else's hands.
            rebindForTremor(false)

            statusLabel.text="Not inspecting"
            actionButton.text="Find expiry date"
            actionButton.contentDescription="Find expiry date"
            actionButton.isEnabled=true
            return
            }

        statusLabel.text=session.statusText()
        pauseButton.text=if (session.phase==InspectionPhase.PAUSED) "Resume" else "Pause"

        when (session.phase) {
            InspectionPhase.SENDING -> {
                // A request is in flight: no capture is running, the gate will
                // not reserve another, and the user is already waiting on the
                // network. The only free moment to change the camera over.
                rebindForTremor(inspectionController.handUnsteady)

                actionButton.text="Checking image"
                actionButton.contentDescription="Checking image, please wait"
                // Nothing to send while a frame is in flight, and a disabled
                // control is honest about that to TalkBack in a way a silently
                // ignored press is not.
                actionButton.isEnabled=false
                }
            InspectionPhase.PAUSED -> {
                actionButton.text="Paused"
                actionButton.contentDescription="Paused. Press Resume to continue"
                actionButton.isEnabled=false
                }
            else -> {
                actionButton.text="Capture now"
                actionButton.contentDescription="Capture now"
                actionButton.isEnabled=inspectionController.acceptingCaptures
                }
            }
        }

    override fun onInstruction(text: String) {
        instructionLabel.text=text
        }

    override fun onOutcome(text: String) {
        instructionLabel.text=text
        toast(text)
        }

    override fun onProblem(text: String) {
        instructionLabel.text=text
        toast(text)
        }

    fun toast(text: String) {
        Toast.makeText(requireActivity(), text, Toast.LENGTH_LONG).show()
        }
    }

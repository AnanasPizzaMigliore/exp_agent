/*
* Copyright (C) 2024 Rastislav Kish
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

import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.graphics.ImageFormat
import androidx.camera.core.ExperimentalGetImage
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import androidx.fragment.app.Fragment
import android.util.Size
import java.io.File
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

import android.content.ClipboardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentValues
import android.content.Intent
import android.content.Context

import android.net.Uri

import android.provider.MediaStore

import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ToggleButton
import android.widget.Toast
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.OrientationEventListener
import com.google.android.material.bottomnavigation.BottomNavigationView

import kotlinx.serialization.*
import kotlinx.serialization.json.Json

import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.UseCase
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.ImageProxy
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import android.hardware.camera2.CaptureRequest
import android.util.Range
import java.util.concurrent.Executors

import kotlin.coroutines.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*

import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe

import com.rastislavkish.rtk.TouchWrapper
import com.rastislavkish.rtk.GestureEventArgs

import com.rastislavkish.vscan.R

import com.rastislavkish.vscan.core.ProvidersManager
import com.rastislavkish.vscan.core.Config
import com.rastislavkish.vscan.core.ConfigManager
import com.rastislavkish.vscan.core.FlashlightMode
import com.rastislavkish.vscan.core.UsedCamera
import com.rastislavkish.vscan.core.STT
import com.rastislavkish.vscan.core.Resources
import com.rastislavkish.vscan.core.Settings
import com.rastislavkish.vscan.core.openai.*

import com.rastislavkish.vscan.agent.CaptureMetadata
import com.rastislavkish.vscan.agent.FrameSharpness
import com.rastislavkish.vscan.agent.InspectionController
import com.rastislavkish.vscan.agent.InspectionPhase
import com.rastislavkish.vscan.agent.InspectionSession

import com.rastislavkish.vscan.core.Action
import com.rastislavkish.vscan.core.ScanWithActiveConfigAction
import com.rastislavkish.vscan.core.ScanWithConfigAction
import com.rastislavkish.vscan.core.ConsultConfigAction
import com.rastislavkish.vscan.core.AskAction
import com.rastislavkish.vscan.core.SetSystemPromptAction
import com.rastislavkish.vscan.core.SetUserPromptAction

class ScanFragment: Fragment(), CoroutineScope, InspectionController.Listener {

    override val coroutineContext: CoroutineContext
    get() = Dispatchers.Main+job

    private lateinit var job: Job

    private lateinit var askSTT: STT
    private lateinit var systemPromptSTT: STT
    private lateinit var userPromptSTT: STT

    private lateinit var adapter: TabAdapter
    private lateinit var resources: Resources
    private lateinit var settings: Settings
    private lateinit var providersManager: ProvidersManager
    private lateinit var configManager: ConfigManager
    private lateinit var orientationEventListener: OrientationEventListener
    private lateinit var touchWrapper: TouchWrapper

    private var camera: Camera?=null
    private var cameraProvider: ProcessCameraProvider?=null
    private val imageCapture: ImageCapture
    private val highResImageCapture: ImageCapture
    private val inspectionImageCapture: ImageCapture
    private val tremorImageCapture: ImageCapture

    private var configUsedByCamera: Config?=null
    private var cameraBoundToInspection=false
    private var cameraBoundToTremor=false

    private lateinit var scanButton: Button
    private lateinit var askButton: Button
    private lateinit var systemPromptButton: Button
    private lateinit var userPromptButton: Button
    private lateinit var multipurposeInputLayout: TextInputLayout
    private lateinit var multipurposeInput: TextInputEditText
    private var multipurposeInputPurpose=MultipurposeInputPurpose.MESSAGE

    private lateinit var inspectionController: InspectionController
    private lateinit var expiryButton: Button
    private lateinit var expiryStatus: TextView
    private lateinit var expiryBar: LinearLayout
    private lateinit var repeatInstructionButton: Button
    private lateinit var pauseInspectionButton: Button
    private lateinit var newPackageButton: Button
    private lateinit var stopInspectionButton: Button

    init {
        val resolutionSelector=ResolutionSelector.Builder()
        .setResolutionStrategy(ResolutionStrategy(Size(512, 512), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
        .build()

        val highResResolutionSelector=ResolutionSelector.Builder()
        .setResolutionStrategy(ResolutionStrategy(Size(1024, 1024), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
        .build()

        imageCapture=ImageCapture.Builder()
        .setResolutionSelector(resolutionSelector)
        .setFlashMode(ImageCapture.FLASH_MODE_ON)
        .build()

        highResImageCapture=ImageCapture.Builder()
        .setResolutionSelector(highResResolutionSelector)
        .setFlashMode(ImageCapture.FLASH_MODE_ON)
        .build()

        // A date code is a few millimetres of dot-matrix print, and the scan
        // path's 512 and 1024 squares throw away exactly the detail that has to
        // survive. 1536 is chosen against the backend, which bounds its long
        // edge at 1568: sending anything larger only buys a second resampling.
        val inspectionResolutionSelector=ResolutionSelector.Builder()
        .setResolutionStrategy(ResolutionStrategy(Size(1536, 1536), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
        .build()

        inspectionImageCapture=ImageCapture.Builder()
        .setResolutionSelector(inspectionResolutionSelector)
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
        .build()

        // The same frame for a hand that will not hold still.
        //
        // MAXIMIZE_QUALITY is the right mode for a steady hand and the wrong one
        // here: on most devices it fuses several exposures, and fusing frames
        // taken while the phone is moving is how a crisp dot-matrix code becomes
        // a doubled one. It also takes long enough that a burst of them would
        // cost more of the user's time than the sharper frame is worth.
        //
        // Same resolution, because the resolution was chosen for the date code
        // and a tremor is not a reason to send a smaller picture of it.
        tremorImageCapture=ImageCapture.Builder()
        .setResolutionSelector(inspectionResolutionSelector)
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
        .build()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
        ): View? {
        return inflater.inflate(R.layout.fragment_scan, container, false)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        job=Job()
        adapter=TabAdapter.getInstance(requireContext())
        resources=Resources.getInstance(requireContext())
        settings=Settings.getInstance(requireContext())
        providersManager=ProvidersManager.getInstance(requireContext())
        configManager=ConfigManager.getInstance(requireContext())
        orientationEventListener=object : OrientationEventListener(requireContext()) {

            override fun onOrientationChanged(orientation: Int) {
                onOrientationChange(orientation)
                }
            }
        touchWrapper=TouchWrapper()
        touchWrapper.addSwipeLeftListener(this::onSwipeLeft)
        touchWrapper.addSwipeRightListener(this::onSwipeRight)
        touchWrapper.addSwipeUpListener(this::onSwipeUp)
        touchWrapper.addTapListener(this::onTap)

        scanButton=view.findViewById(R.id.scanButton)
        scanButton.setOnClickListener(this::scanButtonClick)
        scanButton.setOnTouchListener(this::onTouch)

        askButton=view.findViewById(R.id.askButton)
        askButton.setOnClickListener(this::askButtonClick)
        askButton.setOnLongClickListener(this::askButtonLongClick)
        systemPromptButton=view.findViewById(R.id.systemPromptButton)
        systemPromptButton.setOnClickListener(this::systemPromptButtonClick)
        systemPromptButton.setOnLongClickListener(this::systemPromptButtonLongClick)
        userPromptButton=view.findViewById(R.id.userPromptButton)
        userPromptButton.setOnClickListener(this::userPromptButtonClick)
        userPromptButton.setOnLongClickListener(this::userPromptButtonLongClick)

        askSTT=STT(requireContext())
        systemPromptSTT=STT(requireContext())
        userPromptSTT=STT(requireContext())

        val saveButton: Button=view.findViewById(R.id.saveButton)
        saveButton.setOnClickListener(this::saveButtonClick)
        val resetConfigButton: Button=view.findViewById(R.id.resetConfigButton)
        resetConfigButton.setOnClickListener(this::resetConfigButtonClick)

        multipurposeInputLayout=view.findViewById(R.id.multipurposeInputLayout)
        multipurposeInput=view.findViewById(R.id.multipurposeInput)
        multipurposeInput.setOnEditorActionListener(this::onMultipurposeInputEditorAction)

        inspectionController=InspectionController(requireContext(), this, this)

        expiryButton=view.findViewById(R.id.expiryButton)
        expiryButton.setOnClickListener(this::expiryButtonClick)
        expiryStatus=view.findViewById(R.id.expiryStatus)
        expiryBar=view.findViewById(R.id.expiryBar)
        repeatInstructionButton=view.findViewById(R.id.repeatInstructionButton)
        repeatInstructionButton.setOnClickListener { inspectionController.repeatInstruction() }
        pauseInspectionButton=view.findViewById(R.id.pauseInspectionButton)
        pauseInspectionButton.setOnClickListener { inspectionController.togglePause() }
        newPackageButton=view.findViewById(R.id.newPackageButton)
        newPackageButton.setOnClickListener { inspectionController.start() }
        stopInspectionButton=view.findViewById(R.id.stopInspectionButton)
        stopInspectionButton.setOnClickListener { inspectionController.stop() }

        onStateChanged(null)

        val cameraProviderFuture=ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(Runnable {
            launch { adapter.mutex.withLock {
                cameraProvider=cameraProviderFuture.get()

                bindCamera(adapter)
                }}
            }, ContextCompat.getMainExecutor(requireContext()))

        try {
            EventBus.getDefault().register(this)
            }
        catch (e: Exception) {

            }
        }

    override fun onResume() {
        orientationEventListener.enable()
        super.onResume()

        checkShareBox()
        }
    override fun onPause() {
        orientationEventListener.disable()
        resetMultipurposeInput()

        // Pause rather than stop: leaving the foreground is usually a phone call
        // or TalkBack's own menu, not the user giving up on the package.
        inspectionController.onBackgrounded()

        super.onPause()
        }
    override fun onDestroy() {
        inspectionController.release()
        job.cancel()
        super.onDestroy()
        }

    @Subscribe
    fun onDeviceInputEvent(event: DeviceInputEvent) {
        when (event.kind) {
            DeviceInputEventKind.VOLUME_UP_PRESS -> {
                performAction(settings.volumeUpPressAction ?: return)
                }
            DeviceInputEventKind.VOLUME_DOWN_PRESS -> {
                performAction(settings.volumeDownPressAction ?: return)
                }
            DeviceInputEventKind.SHAKE -> {
                performAction(settings.shakeAction ?: return)
                }
            }
        }

    fun scanButtonClick(v: View) {
        // While an inspection is running the big button is the inspection's
        // shutter. Ordinary scanning is not lost - it comes back the moment the
        // session ends - but one central control means the user never has to
        // find a different target mid-package with one hand occupied.
        if (inspectionController.active) {
            captureForInspection()
            return
            }

        launch { adapter.mutex.withLock {
            scanWithConfig(adapter, adapter.activeConfig)
            }}
        }

    fun askButtonClick(v: View) {
        setMultipurposeInputPurpose(MultipurposeInputPurpose.MESSAGE, true)
        }
    fun askButtonLongClick(v: View): Boolean {
        launch {
            val enquiry=askSTT.recognize() ?: return@launch

            adapter.mutex.withLock {
                sendMessage(adapter, enquiry)
                }
            }

        return true
        }
    fun systemPromptButtonClick(v: View) {
        setMultipurposeInputPurpose(MultipurposeInputPurpose.SYSTEM_PROMPT, true)
        }
    fun systemPromptButtonLongClick(v: View): Boolean {
        launch {
            val enquiry=systemPromptSTT.recognize() ?: return@launch

            if (!enquiry.isEmpty())
            adapter.mutex.withLock {
                setSystemPrompt(adapter, enquiry, true)
                }
            }

        return true
        }
    fun userPromptButtonClick(v: View) {
        setMultipurposeInputPurpose(MultipurposeInputPurpose.USER_PROMPT, true)
        }
    fun userPromptButtonLongClick(v: View): Boolean {
        launch {
            val enquiry=userPromptSTT.recognize() ?: return@launch

            if (!enquiry.isEmpty())
            adapter.mutex.withLock {
                setUserPrompt(adapter, enquiry, true)
                }
            }

        return true
        }
    fun saveButtonClick(v: View) {
        launch { adapter.mutex.withLock {
            val image=adapter.lastTakenImage ?: return@launch
            val timestamp=adapter.lastTakenImageTimestamp ?: return@launch



            if (settings.describeSavedImages) {
                val fileDescriptionConfig=settings.getFileDescriptionConfig(configManager)
                val conversation=Conversation(
                    providersManager,
                    fileDescriptionConfig.model,
                    fileDescriptionConfig.maxCompletionTokens,
                    fileDescriptionConfig.reasoningEffort,
                    fileDescriptionConfig.systemPromptOrNull,
                    )

                val encodedImage=Base64.getEncoder().encodeToString(image)
                conversation.addMessage(ImageMessage(
                    fileDescriptionConfig.userPrompt,
                    LocalImage(encodedImage),
                    ))

                var errorMessage: String?=null

                var fileName=try {
                    val response=conversation.generateResponse().text
                    "$response-${timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))}.jpg"
                    }
                catch (e: Exception) {
                    errorMessage=e.message ?: ""
                    "${timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))}.jpg"
                    }

                if (fileName.isEmpty() && errorMessage==null) {
                    errorMessage="Reasoning exceeded the token limit"
                    fileName="${timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))}.jpg"
                    }

                try {
                    saveToGallery(fileName, image)

                    if (errorMessage!=null)
                    toast("Saved as $fileName")
                    else
                    toast("Error describing the image: $errorMessage Image saved as $fileName")
                    }
                catch (e: Exception) {
                    toast("Saving failed: ${e.message}")
                    }
                }
            else {
                val fileName="${timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))}.jpg"
                try {
                    saveToGallery(fileName, image)
                    toast("Saved as $fileName")
                    }
                catch (e: Exception) {
                    toast("Saving failed: ${e.message}")
                    }
                }
            }}
        }
    fun resetConfigButtonClick(v: View) {
        launch { adapter.mutex.withLock {
            adapter.resetActiveConfig()
            adapter.resetConversation()
            adapter.lastTakenImage=null
            adapter.lastTakenImageTimestamp=null

            toast("Reset to ${adapter.activeConfig.name}")
            }}
        }

    fun onMultipurposeInputEditorAction(v: View, actionId: Int, event: KeyEvent?): Boolean {
        if (actionId==EditorInfo.IME_ACTION_DONE) {
            confirmMultipurposeInput()

            return true
            }

        return false
        }

    fun onSwipeLeft(args: GestureEventArgs) {
        val navBar: BottomNavigationView=requireActivity().findViewById(R.id.bottomNavigationView)
        navBar.setSelectedItemId(R.id.optionsFragment)
        }
    fun onSwipeRight(args: GestureEventArgs) {
        val navBar: BottomNavigationView=requireActivity().findViewById(R.id.bottomNavigationView)
        navBar.setSelectedItemId(R.id.configListFragment)
        }
    fun onSwipeUp(args: GestureEventArgs) {
        val navBar: BottomNavigationView=requireActivity().findViewById(R.id.bottomNavigationView)
        navBar.setSelectedItemId(R.id.conversationFragment)
        }
    fun onTap(args: GestureEventArgs) {
        scanButtonClick(scanButton)
        }

    var lastRotationValue=-1
    fun onOrientationChange(orientation: Int) {
        if (orientation==OrientationEventListener.ORIENTATION_UNKNOWN) {
            return
            }

        val rotation=UseCase.snapToSurfaceRotation(orientation)

        if (rotation!=lastRotationValue) {
            imageCapture.setTargetRotation(rotation)
            highResImageCapture.setTargetRotation(rotation)
            inspectionImageCapture.setTargetRotation(rotation)
            tremorImageCapture.setTargetRotation(rotation)

            lastRotationValue=rotation
            }
        }
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    fun extractImageFromProxy(imageProxy: ImageProxy): ByteArray? {
        val mediaImage=imageProxy.image
        if (mediaImage!=null) {
            if (mediaImage.format==ImageFormat.JPEG) {
                val buffer=mediaImage.planes[0].buffer //ByteBuffer
                val bytes=ByteArray(buffer.remaining())
                buffer.get(bytes)

                imageProxy.close()
                return bytes
                }
            else {
                toast("Error: The camera returned an unsupported image type")
                }
            }
        imageProxy.close()
        return null
        }

    fun onTouch(v: View, event: MotionEvent): Boolean {
        touchWrapper.update(event)

        return true
        }

    fun setMultipurposeInputPurpose(purpose: MultipurposeInputPurpose, focus: Boolean=false) {
        if (!(multipurposeInput.text?.isBlank() ?: false))
        multipurposeInput.text?.clear()
        multipurposeInputPurpose=purpose

        multipurposeInputLayout.hint=when (purpose) {
            MultipurposeInputPurpose.MESSAGE -> "Message"
            MultipurposeInputPurpose.SYSTEM_PROMPT -> "System prompt"
            MultipurposeInputPurpose.USER_PROMPT -> "User prompt"
            }

        if (focus) {
            multipurposeInput.requestFocus()

            val imm=requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(multipurposeInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    fun resetMultipurposeInput() {
        setMultipurposeInputPurpose(MultipurposeInputPurpose.MESSAGE)
        }
    fun confirmMultipurposeInput() {
        val text=multipurposeInput.text?.toString() ?: return
        val purpose=multipurposeInputPurpose

        resetMultipurposeInput()

        if (text.isBlank()) {
            return
            }

        launch { adapter.mutex.withLock() {
            when (purpose) {
                MultipurposeInputPurpose.MESSAGE -> {
                    sendMessage(adapter, text)
                    }
                MultipurposeInputPurpose.SYSTEM_PROMPT -> {
                    setSystemPrompt(adapter, text)
                    }
                MultipurposeInputPurpose.USER_PROMPT -> {
                    setUserPrompt(adapter, text)
                    }
                }
            }}
        }

    fun performAction(action: Action) {
        when (action) {
            is ScanWithActiveConfigAction -> {
                scanButtonClick(scanButton)
                }
            is ScanWithConfigAction -> {
                val config=configManager.getConfig(action.config)
                ?: configManager.getBaseConfig()

                launch { adapter.mutex.withLock {
                    scanWithConfig(adapter, config)
                    }}
                }
            is ConsultConfigAction -> {
                val config=configManager.getConfig(action.config)
                ?: configManager.getBaseConfig()

                launch { adapter.mutex.withLock {
                    consultConfig(adapter, config)
                    }}
                }
            is AskAction -> {
                askButtonClick(askButton)
                }
            is SetSystemPromptAction -> {
                systemPromptButtonClick(systemPromptButton)
                }
            is SetUserPromptAction -> {
                userPromptButtonClick(userPromptButton)
                }
            }
        }

    suspend fun bindCamera(adapter: TabAdapter) {
        cameraProvider?.unbindAll()

        val cameraSelector=when (adapter.activeConfig.camera) {
            UsedCamera.BACK_CAMERA -> CameraSelector.DEFAULT_BACK_CAMERA
            UsedCamera.FRONT_CAMERA -> CameraSelector.DEFAULT_FRONT_CAMERA
            }

        // CameraX allows one ImageCapture at a time, so an inspection takes the
        // camera over for its duration and hands it back when the session ends.
        if (cameraBoundToInspection)
        camera=cameraProvider?.bindToLifecycle(requireActivity(), cameraSelector, inspectionCapture())
        else if (!adapter.activeConfig.highRes)
        camera=cameraProvider?.bindToLifecycle(requireActivity(), cameraSelector, imageCapture)
        else
        camera=cameraProvider?.bindToLifecycle(requireActivity(), cameraSelector, highResImageCapture)

        applyShutterFloor(cameraBoundToInspection&&cameraBoundToTremor)

        configUsedByCamera=adapter.activeConfig
        }

    /** Which inspection use case the current hand should be photographed with. */
    private fun inspectionCapture(): ImageCapture =
    if (cameraBoundToTremor) tremorImageCapture else inspectionImageCapture

    /**
    * Keep the shutter short enough that a tremor cannot smear the exposure.
    *
    * Pinning the auto-exposure frame rate is the portable way to put a floor
    * under shutter speed: at 30fps the exposure cannot exceed a thirtieth of a
    * second, and AE pays for it with sensor gain instead. Grainier and sharper
    * beats clean and smeared when what has to survive is millimetres of
    * dot-matrix print.
    *
    * Only while a trembling hand owns the camera. A steady hand on a dim shelf
    * wants the long exposure it is getting today, and taking it away would be
    * this change making a worse photograph for the users it was not for.
    *
    * Best effort by construction: vendors are free to ignore an unsupported
    * range, and a phone that does is no worse off than before.
    */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyShutterFloor(enabled: Boolean) {
        val control=camera?.cameraControl ?: return

        runCatching {
            val options=CaptureRequestOptions.Builder()

            if (enabled)
            options.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(TREMOR_SHUTTER_FPS, TREMOR_SHUTTER_FPS),
                )

            Camera2CameraControl.from(control).setCaptureRequestOptions(options.build())
            }
        }
    /**
    * Swap the camera between scanning and inspection, and only then.
    *
    * Rebinding tears the camera down and brings it back up, so doing it on every
    * phase change would blink the capture pipeline once per turn. The binding
    * only actually depends on whether an inspection owns the camera.
    */
    fun rebindCameraForInspection(inspecting: Boolean) {
        if (cameraProvider==null || inspecting==cameraBoundToInspection)
        return

        cameraBoundToInspection=inspecting

        // The verdict belongs to the session that measured it. Carrying it into
        // the next one would photograph a steady user's first package on
        // settings chosen for somebody else's hands.
        if (!inspecting)
        cameraBoundToTremor=false

        launch { adapter.mutex.withLock {
            bindCamera(adapter)
            }}
        }

    /**
    * Swap the inspection between its two capture use cases, and only then.
    *
    * Same shape as [rebindCameraForInspection] and for the same reason: a
    * rebind tears the pipeline down and brings it back up, so it may only
    * happen when the answer actually changed. It changes at most once a
    * session, because the verdict is latched - see StabilityMonitor.tremulous -
    * and it is checked between turns rather than at the shutter, so a rebind
    * can never land underneath a capture that is already running.
    */
    fun rebindCameraForTremor(tremulous: Boolean) {
        if (cameraProvider==null || !cameraBoundToInspection || tremulous==cameraBoundToTremor)
        return

        cameraBoundToTremor=tremulous

        launch { adapter.mutex.withLock {
            bindCamera(adapter)
            }}
        }
    fun takePicture(adapter: TabAdapter, callback: (ByteArray) -> Unit) {
        val flashMode=when (shouldUseFlashlight(adapter)) {
            true -> ImageCapture.FLASH_MODE_ON
            false -> ImageCapture.FLASH_MODE_OFF
            }

        imageCapture.setFlashMode(flashMode)
        highResImageCapture.setFlashMode(flashMode)

        if (!adapter.activeConfig.highRes)
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val image=extractImageFromProxy(imageProxy)
                    callback(image ?: return)
                    }

                override fun onError(error: ImageCaptureException) {
                    toast("Error capturing the image")
                    }
                },
            )
        else
        highResImageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val image=extractImageFromProxy(imageProxy)
                    callback(image ?: return)
                    }

                override fun onError(error: ImageCaptureException) {
                    toast("Error capturing the image")
                    }
                },
            )
        }
    fun takePictureToAdapter(adapter: TabAdapter, callback: suspend (TabAdapter) -> Unit) {
        val flashMode=when (shouldUseFlashlight(adapter)) {
            true -> ImageCapture.FLASH_MODE_ON
            false -> ImageCapture.FLASH_MODE_OFF
            }

        imageCapture.setFlashMode(flashMode)
        highResImageCapture.setFlashMode(flashMode)

        if (!adapter.activeConfig.highRes)
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val image=extractImageFromProxy(imageProxy)
                    val timestamp=LocalDateTime.now()

                    launch { adapter.mutex.withLock {
                        adapter.lastTakenImage=image
                        adapter.lastTakenImageTimestamp=timestamp

                        callback(adapter)
                        }}
                    }

                override fun onError(error: ImageCaptureException) {
                    toast("Error capturing the image")
                    }
                },
            )
        else
        highResImageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val image=extractImageFromProxy(imageProxy)
                    val timestamp=LocalDateTime.now()

                    launch { adapter.mutex.withLock {
                        adapter.lastTakenImage=image
                        adapter.lastTakenImageTimestamp=timestamp

                        callback(adapter)
                        }}
                    }

                override fun onError(error: ImageCaptureException) {
                    toast("Error capturing the image")
                    }
                },
            )
        }
    fun scanWithConfig(adapter: TabAdapter, config: Config) {
        takePictureToAdapter(adapter, callback@{adapter ->
            if (settings.useSounds)
            resources.shutterSound.play()

            try {
                val response=adapter.consultConfig(config) ?: return@callback
                toastResponse(response)
                }
            catch (e: Exception) {
                toast(e.message ?: "Error")
                }
            })
        }
    suspend fun consultConfig(adapter: TabAdapter, config: Config) {
        try {
            val response=adapter.consultConfig(config) ?: return
            toastResponse(response)
            }
        catch (e: Exception) {
            toast(e.message ?: "Error")
            }
        }
    suspend fun sendMessage(adapter: TabAdapter, message: String) {
        adapter.conversation.addMessage(TextMessage(message))

        try {
            val response=adapter.conversation.generateResponse()
            toastResponse(response)
            }
        catch (e: Exception) {
            toast(e.message ?: "Error")
            }
        }
    fun setSystemPrompt(adapter: TabAdapter, message: String, includePromptInConfirmation: Boolean=false) {
        adapter.activeConfig=adapter.activeConfig.withSystemPrompt(message)

        if (!includePromptInConfirmation)
        toast("System prompt set")
        else
        toast("${message} set as system prompt")
        }
    fun setUserPrompt(adapter: TabAdapter, message: String, includePromptInConfirmation: Boolean=false) {
        adapter.activeConfig=adapter.activeConfig.withUserPrompt(message)

        if (!includePromptInConfirmation)
        toast("User prompt set")
        else
        toast("${message} set as user prompt")
        }
    fun checkShareBox() {
        val shareBox=ShareBox.getInstance(requireContext())
        val image=shareBox.popImage() ?: return
        val timestamp=LocalDateTime.now()

        launch { adapter.mutex.withLock {
            adapter.lastTakenImage=image
            adapter.lastTakenImageTimestamp=timestamp

            val config=settings.getShareConfig(configManager)
            consultConfig(adapter, config)
            }}
        }
    fun shouldUseFlashlight(adapter: TabAdapter): Boolean {
        return when (adapter.activeConfig.flashlightMode) {
            FlashlightMode.DEFAULT -> settings.useFlashlight
            FlashlightMode.ON -> true
            FlashlightMode.OFF -> false
            }
        }
    // ------------------------------------------------------------------
    // Expiry inspection
    // ------------------------------------------------------------------

    fun expiryButtonClick(v: View) {
        if (inspectionController.active)
        inspectionController.stop()
        else
        inspectionController.start()
        }

    /**
    * Take one still and hand it to the inspection.
    *
    * Separate from takePicture() because an inspection frame is not a scan: it
    * goes to a different capture use case, it never touches adapter.lastTakenImage
    * (Save is for what the user chose to photograph, not for the dozen frames an
    * inspection burns through), and the controller has to be told about a
    * capture that fails as well as one that succeeds.
    */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    fun captureForInspection() {
        if (!inspectionController.acceptingCaptures) {
            // The controller says the same thing out loud with the reason; this
            // is only the guard against a second shutter press.
            inspectionController.repeatInstruction()
            return
            }

        launch {
            val useFlash=adapter.mutex.withLock { shouldUseFlashlight(adapter) }
            val capture=inspectionCapture()

            // Off unless the user asked for it: a flash on glossy film or a
            // laminated lid produces exactly the specular blowout that makes a
            // dot-matrix date unreadable, and the frame it ruins costs a whole
            // turn to recover.
            capture.setFlashMode(when (useFlash) {
                true -> ImageCapture.FLASH_MODE_ON
                false -> ImageCapture.FLASH_MODE_OFF
                })

            // One sound for the turn, however many frames it takes. The shutter
            // is the user's confirmation that their press was heard, not a count
            // of exposures, and three clicks would only say something untrue.
            if (settings.useSounds)
            resources.shutterSound.play()

            // A burst is bought with the user's time, so it is spent only where
            // it buys something. A steady hand's frames differ by nothing worth
            // choosing between, and the second and third shot would be pure
            // latency in front of somebody waiting in a shop.
            val wanted=if (inspectionController.handUnsteady) TREMOR_BURST else 1
            val frames=mutableListOf<Pair<ByteArray, CaptureMetadata>>()

            repeat(wanted) {
                takeOneForInspection(capture, useFlash)?.let { frames.add(it) }
                }

            if (frames.isEmpty()) {
                onProblem("The camera could not take that picture. Try again.")
                return@launch
                }

            // Decoding happens off the main thread: three 1536px stills in front
            // of a blind user is not somewhere to stall the UI.
            val chosen=if (frames.size>1)
            withContext(Dispatchers.Default) { FrameSharpness.sharpest(frames.map { it.first }) } ?: 0
            else 0

            val (image, metadata)=frames[chosen]
            inspectionController.submit(image, metadata)
            }
        }

    /**
    * One still for the inspection, awaited rather than delivered by callback.
    *
    * A burst has to be sequential - CameraX takes one picture at a time - and
    * sequencing callbacks by hand is how a chain of them ends up submitting the
    * second frame while the third is still in flight. Suspending keeps the whole
    * burst in one place, where it reads as what it is.
    *
    * Null for a frame that failed. A burst carries on past one: two good frames
    * out of three is still a choice, and the caller reports the failure only
    * when nothing at all came back.
    */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private suspend fun takeOneForInspection(
        capture: ImageCapture,
        useFlash: Boolean,
        ): Pair<ByteArray, CaptureMetadata>? = suspendCancellableCoroutine { continuation ->
        capture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    // Read the geometry before extractImageFromProxy closes it.
                    val metadata=CaptureMetadata(
                        width=imageProxy.width,
                        height=imageProxy.height,
                        rotation_degrees=imageProxy.imageInfo.rotationDegrees,
                        flash_fired=useFlash,
                        device_ms=(android.os.SystemClock.elapsedRealtime() and 0x7fffffffL).toInt(),
                        )

                    val image=extractImageFromProxy(imageProxy)

                    if (continuation.isActive)
                    continuation.resume(if (image!=null) Pair(image, metadata) else null)
                    }

                override fun onError(error: ImageCaptureException) {
                    if (continuation.isActive)
                    continuation.resume(null)
                    }
                },
            )
        }

    override fun onStateChanged(session: InspectionSession?) {
        val running=session!=null && session.active

        expiryButton.text=if (running) "End" else "Expiry"
        expiryButton.contentDescription=if (running) "End expiry inspection" else "Find expiry date"

        expiryBar.visibility=if (running) View.VISIBLE else View.GONE
        expiryStatus.visibility=if (running) View.VISIBLE else View.GONE

        scanButton.text=if (running) "Capture now" else "Scan"

        if (session!=null) {
            expiryStatus.text=session.statusText()
            pauseInspectionButton.text=if (session.phase==InspectionPhase.PAUSED) "Resume" else "Pause"

            // Nothing to send while a frame is in flight or the session is
            // paused, and a disabled control is honest about that to TalkBack
            // in a way a silently ignored press is not.
            scanButton.isEnabled=session.phase!=InspectionPhase.SENDING
            }
        else {
            expiryStatus.text=""
            scanButton.isEnabled=true
            }

        // The camera has to change hands whenever a session starts or ends.
        rebindCameraForInspection(running)

        // And between turns, once the gate has decided how this hand behaves.
        rebindCameraForTremor(running&&inspectionController.handUnsteady)
        }

    override fun onInstruction(text: String) {
        toast(text)
        }

    override fun onOutcome(text: String) {
        toast(text)
        }

    override fun onProblem(text: String) {
        toast(text)
        }

    fun toast(text: String) {
        Toast.makeText(requireActivity(), text, Toast.LENGTH_LONG).show()
        }
    fun toastResponse(response: AssistantMessage) {
        if (!response.text.isEmpty())
        toast(response.text)
        else if (response.finishReason=="length")
        toast("Error: Reasoning exceeded the token limit")
        else
        toast("Error: Received empty output")
        }
    fun createImageFile(fileName: String): File {
        val storageDir: File = if (Build.VERSION.SDK_INT<Build.VERSION_CODES.Q) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) else requireActivity().getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: throw Exception("Failed to optain the Pictures folder path")
        val imageFile=File("$storageDir/$fileName")
        imageFile.createNewFile()
        return imageFile
        }
    fun saveToGallery(fileName: String, image: ByteArray) {
        val imageFile=createImageFile(fileName)

        if (Build.VERSION.SDK_INT<Build.VERSION_CODES.Q) {
            val intent=Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            intent.setData(Uri.parse("file://${imageFile.absolutePath}"))
            requireActivity().sendBroadcast(intent)
            }
        else {
            val contentValues=ContentValues()
            contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, imageFile.name)
            contentValues.put(MediaStore.Images.Media.MIME_TYPE, "image/*")
            val contentUri: Uri=if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED))
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else
            MediaStore.Images.Media.INTERNAL_CONTENT_URI

            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 1)
            val uri: Uri=requireActivity().getContentResolver().insert(contentUri, contentValues) ?: throw Exception("Unable to obtain uri for writing")

            var os: OutputStream?=null
            try {
                os=requireActivity().getContentResolver().openOutputStream(uri)
                os?.write(image)
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                requireActivity().getContentResolver().update(uri, contentValues, null, null)
                }
            catch (e: Exception) {
                requireActivity().getContentResolver().delete(uri, null, null)
                throw Exception("Unable to write to the output file")
                }
            finally {
                try {
                    os?.close()
                    }
                catch (e: Exception) {
                    throw Exception("Unable to close the output stream")
                    }
                }

            val mediaScanIntent=Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.setData(contentUri)
            requireActivity().sendBroadcast(mediaScanIntent)
            }
        }

    companion object {

        /**
        * Frames taken per inspection capture when the hand is unsteady.
        *
        * Three, because the interval matters more than the count: a tremor at
        * four to twelve cycles a second passes through its quiet part several
        * times a second, so three shots a few hundred milliseconds apart are
        * very unlikely to all land on the same part of the cycle. A fourth buys
        * little and is another half-second of a blind user's time in a shop.
        */
        const val TREMOR_BURST=3

        /**
        * The auto-exposure frame rate pinned while a trembling hand holds the
        * phone, which is what puts a ceiling of a thirtieth of a second on the
        * exposure. See applyShutterFloor.
        */
        const val TREMOR_SHUTTER_FPS=30
        }
    }

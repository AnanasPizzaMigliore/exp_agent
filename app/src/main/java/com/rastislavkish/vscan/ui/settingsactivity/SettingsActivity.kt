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

package com.rastislavkish.vscan.ui.settingsactivity

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.content.Intent

import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult

import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.view.View

import com.rastislavkish.vscan.R
import com.rastislavkish.vscan.ui.fitContentInsideSystemBars

import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.rastislavkish.vscan.agent.AgentSettings
import com.rastislavkish.vscan.agent.ExperimentLogger
import com.rastislavkish.vscan.agent.GateConditions

import com.rastislavkish.vscan.core.ConfigManager
import com.rastislavkish.vscan.core.Settings

import com.rastislavkish.vscan.ui.configselectionactivity.ConfigSelectionActivity
import com.rastislavkish.vscan.ui.configselectionactivity.ConfigSelectionActivityOutput
import com.rastislavkish.vscan.ui.actionselectionactivity.ActionSelectionActivity
import com.rastislavkish.vscan.ui.actionselectionactivity.ActionSelectionActivityOutput
import com.rastislavkish.vscan.ui.providersactivity.ProvidersActivity
import com.rastislavkish.vscan.ui.modelprovidermappingsactivity.ModelProviderMappingsActivity

class SettingsActivity : AppCompatActivity() {

    /** How the empty block label reads in the list. */
    private val NO_BLOCK="Not part of the ablation"


    private lateinit var configManager: ConfigManager
    private lateinit var settings: Settings

    private lateinit var flashlightSwitch: Switch
    private lateinit var soundsSwitch: Switch
    private lateinit var describeSavedImagesSwitch: Switch

    private lateinit var defaultConfigSelector: TextView
    private lateinit var shareConfigSelector: TextView
    private lateinit var fileDescriptionConfigSelector: TextView

    private lateinit var shakeActionSelector: TextView
    private lateinit var volumeUpPressActionSelector: TextView
    private lateinit var volumeDownPressActionSelector: TextView

    private lateinit var agentSettings: AgentSettings
    private lateinit var speakGuidanceSwitch: Switch
    private lateinit var storeInspectionImagesSwitch: Switch
    private lateinit var retrievalMemorySwitch: Switch
    private lateinit var ablationArmsSwitch: Switch
    private lateinit var retrievalNameMatchingSwitch: Switch
    private lateinit var instructionGroundingSwitch: Switch
    private lateinit var autoCaptureSwitch: Switch
    private lateinit var gateConditionSelector: Spinner
    private lateinit var blockLabelSelector: Spinner
    private lateinit var productLabelSelector: Spinner
    private lateinit var participantSelector: Spinner
    private lateinit var logStatusLabel: TextView

    private var lastActivatedConfigSelector: View?=null
    private var lastActivatedActionSelector: View?=null
    private lateinit var configSelectionActivityLauncher: ActivityResultLauncher<Intent>
    private lateinit var actionSelectionActivityLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        fitContentInsideSystemBars()

        configManager=ConfigManager.getInstance(this)
        settings=Settings.getInstance(this)

        flashlightSwitch=findViewById(R.id.flashlightSwitch)
        soundsSwitch=findViewById(R.id.soundsSwitch)
        describeSavedImagesSwitch=findViewById(R.id.describeSavedImagesSwitch)

        defaultConfigSelector=findViewById(R.id.defaultConfigSelector)
        shareConfigSelector=findViewById(R.id.shareConfigSelector)
        fileDescriptionConfigSelector=findViewById(R.id.fileDescriptionConfigSelector)

        shakeActionSelector=findViewById(R.id.shakeActionSelector)
        volumeUpPressActionSelector=findViewById(R.id.volumeUpPressActionSelector)
        volumeDownPressActionSelector=findViewById(R.id.volumeDownPressActionSelector)

        agentSettings=AgentSettings.getInstance(this)
        speakGuidanceSwitch=findViewById(R.id.speakGuidanceSwitch)
        storeInspectionImagesSwitch=findViewById(R.id.storeInspectionImagesSwitch)
        retrievalMemorySwitch=findViewById(R.id.retrievalMemorySwitch)
        ablationArmsSwitch=findViewById(R.id.ablationArmsSwitch)
        retrievalNameMatchingSwitch=findViewById(R.id.retrievalNameMatchingSwitch)
        instructionGroundingSwitch=findViewById(R.id.instructionGroundingSwitch)
        autoCaptureSwitch=findViewById(R.id.autoCaptureSwitch)

        // The ablation conditions, so the study can be run from the phone
        // alone. Spinners rather than free text: a mistyped condition would
        // either refuse to start the session or, worse, label a run as an
        // ablation it was not.
        gateConditionSelector=findViewById(R.id.gateConditionSelector)
        gateConditionSelector.adapter=ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            GateConditions.names.toList())

        // "" is ordinary use. It is shown as a readable phrase rather than an
        // empty row so it can be chosen deliberately, because leaving a stale
        // block label on is how three sessions came to be scored as something
        // nobody had performed.
        blockLabelSelector=findViewById(R.id.blockLabelSelector)
        blockLabelSelector.adapter=ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            GateConditions.blocks.map { if (it.isEmpty()) NO_BLOCK else it })

        participantSelector=findViewById(R.id.participantSelector)
        participantSelector.adapter=ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            GateConditions.participants.map { if (it.isEmpty()) NO_BLOCK else it })

        productLabelSelector=findViewById(R.id.productLabelSelector)
        productLabelSelector.adapter=ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            GateConditions.studyLabels.map { if (it.isEmpty()) NO_BLOCK else it })

        logStatusLabel=findViewById(R.id.logStatusLabel)

        configSelectionActivityLauncher=registerForActivityResult(StartActivityForResult(), this::configSelectionActivityResult)
        actionSelectionActivityLauncher=registerForActivityResult(StartActivityForResult(), this::actionSelectionActivityResult)
        }

    override fun onResume() {
        flashlightSwitch.setChecked(settings.useFlashlight)
        soundsSwitch.setChecked(settings.useSounds)
        describeSavedImagesSwitch.setChecked(settings.describeSavedImages)
        speakGuidanceSwitch.setChecked(agentSettings.speakGuidance)
        storeInspectionImagesSwitch.setChecked(agentSettings.storeImages)
        instructionGroundingSwitch.setChecked(agentSettings.instructionGrounding)
        retrievalMemorySwitch.setChecked(agentSettings.retrievalMemory)
        ablationArmsSwitch.setChecked(agentSettings.ablationArms)
        retrievalNameMatchingSwitch.setChecked(agentSettings.retrievalNameMatching)
        autoCaptureSwitch.setChecked(agentSettings.autoCapture)

        val conditions=GateConditions.names.toList()
        gateConditionSelector.setSelection(
            conditions.indexOf(agentSettings.gateCondition).coerceAtLeast(0))
        blockLabelSelector.setSelection(
            GateConditions.blocks.indexOf(agentSettings.blockLabel).coerceAtLeast(0))
        productLabelSelector.setSelection(
            GateConditions.studyLabels.indexOf(agentSettings.productLabel).coerceAtLeast(0))
        participantSelector.setSelection(
            GateConditions.participants.indexOf(agentSettings.participant).coerceAtLeast(0))

        refreshSelectors()
        refreshLogStatus()

        super.onResume()
        }

    /**
    * Say what has been recorded so far.
    *
    * The one thing on the phone that can confirm the study is being written
    * down. Off the main thread because the file grows for the whole run, and
    * back onto it to speak, so TalkBack reads a line that is already correct
    * rather than one that changes underneath the announcement.
    */
    private fun refreshLogStatus() {
        lifecycleScope.launch {
            val status=withContext(Dispatchers.IO) {
                ExperimentLogger.status(this@SettingsActivity)
                }

            logStatusLabel.text=describe(status)
            }
        }

    private fun describe(status: ExperimentLogger.LogStatus): String {
        if (status.empty)
        return "Log: nothing recorded yet"

        val sessions=if (status.sessions==1) "1 session" else "${status.sessions} sessions"
        val images=if (status.images>0) ", ${status.images} frames" else ""

        return "Log: $sessions, ${size(status.bytes)}$images, last written ${ago(status.lastWriteMs)}"
        }

    private fun size(bytes: Long): String =
    if (bytes<1024L*1024L) "${maxOf(1L, bytes/1024L)} kB"
    else "%.1f MB".format(bytes/1024.0/1024.0)

    private fun ago(whenMs: Long): String {
        if (whenMs<=0L)
        return "never"

        val minutes=(System.currentTimeMillis()-whenMs)/60_000L

        return when {
            minutes<1L -> "just now"
            minutes==1L -> "1 minute ago"
            minutes<60L -> "$minutes minutes ago"
            minutes<120L -> "1 hour ago"
            else -> "${minutes/60L} hours ago"
            }
        }
    override fun onPause() {
        settings.useFlashlight=flashlightSwitch.isChecked()
        settings.useSounds=soundsSwitch.isChecked()
        settings.describeSavedImages=describeSavedImagesSwitch.isChecked()

        settings.save()

        agentSettings.speakGuidance=speakGuidanceSwitch.isChecked()
        agentSettings.storeImages=storeInspectionImagesSwitch.isChecked()
        agentSettings.instructionGrounding=instructionGroundingSwitch.isChecked()
        agentSettings.retrievalMemory=retrievalMemorySwitch.isChecked()
        agentSettings.ablationArms=ablationArmsSwitch.isChecked()
        agentSettings.retrievalNameMatching=retrievalNameMatchingSwitch.isChecked()
        agentSettings.autoCapture=autoCaptureSwitch.isChecked()
        agentSettings.gateCondition=GateConditions.names.toList()
        .getOrElse(gateConditionSelector.selectedItemPosition) { GateConditions.FULL }
        agentSettings.blockLabel=GateConditions.blocks
        .getOrElse(blockLabelSelector.selectedItemPosition) { "" }
        agentSettings.productLabel=GateConditions.studyLabels
        .getOrElse(productLabelSelector.selectedItemPosition) { "" }
        agentSettings.participant=GateConditions.participants
        .getOrElse(participantSelector.selectedItemPosition) { "" }
        agentSettings.save()

        super.onPause()
        }

    fun onDefaultConfigSelectorClick(v: View) {
        lastActivatedConfigSelector=defaultConfigSelector
        startConfigSelectionActivity()
        }
    fun onShareConfigSelectorClick(v: View) {
        lastActivatedConfigSelector=shareConfigSelector
        startConfigSelectionActivity()
        }
    fun onFileDescriptionConfigSelectorClick(v: View) {
        lastActivatedConfigSelector=fileDescriptionConfigSelector
        startConfigSelectionActivity()
        }

    fun onShakeActionSelectorClick(v: View) {
        lastActivatedActionSelector=shakeActionSelector
        startActionSelectionActivity()
        }
    fun onVolumeUpPressActionSelectorClick(v: View) {
        lastActivatedActionSelector=volumeUpPressActionSelector
        startActionSelectionActivity()
        }
    fun onVolumeDownPressActionSelectorClick(v: View) {
        lastActivatedActionSelector=volumeDownPressActionSelector
        startActionSelectionActivity()
        }

    fun onApiProvidersLabelClick(v: View) {
        startProvidersActivity()
        }
    fun onModelProviderMappingsLabelClick(v: View) {
        startModelProviderMappingsActivity()
        }

    fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    private fun refreshSelectors() {
        val defaultConfig=settings.getDefaultConfig(configManager)
        val shareConfig=settings.getShareConfig(configManager)
        val fileDescriptionConfig=settings.getFileDescriptionConfig(configManager)

        defaultConfigSelector.text=defaultConfig.name
        shareConfigSelector.text=shareConfig.name
        fileDescriptionConfigSelector.text=fileDescriptionConfig.name

        shakeActionSelector.text=settings.shakeAction?.toString(configManager) ?: "None"
        volumeUpPressActionSelector.text=settings.volumeUpPressAction?.toString(configManager) ?: "None"
        volumeDownPressActionSelector.text=settings.volumeDownPressAction?.toString(configManager) ?: "None"
        }
    private fun startConfigSelectionActivity() {
        val intent=Intent(this, ConfigSelectionActivity::class.java)
        configSelectionActivityLauncher.launch(intent)
        }
    private fun startActionSelectionActivity() {
        val intent=Intent(this, ActionSelectionActivity::class.java)
        actionSelectionActivityLauncher.launch(intent)
        }
    private fun startProvidersActivity() {
        val intent=Intent(this, ProvidersActivity::class.java)
        startActivity(intent)
        }
    private fun startModelProviderMappingsActivity() {
        val intent=Intent(this, ModelProviderMappingsActivity::class.java)
        startActivity(intent)
        }
    private fun configSelectionActivityResult(result: ActivityResult) {
        if (result.resultCode==RESULT_OK) {
            val output=ConfigSelectionActivityOutput.fromIntent(result.data, "SettingsActivity")

            if (!output.configIds.isEmpty()) {
                val id=output.configIds[0]

                when (lastActivatedConfigSelector) {
                    defaultConfigSelector -> {
                        settings.defaultConfigId=id
                        }
                    shareConfigSelector -> {
                        settings.shareConfigId=id
                        }
                    fileDescriptionConfigSelector -> {
                        settings.fileDescriptionConfigId=id
                        }
                    }

                refreshSelectors()
                }
            }
        }
    private fun actionSelectionActivityResult(result: ActivityResult) {
        if (result.resultCode==RESULT_OK) {
            val output=ActionSelectionActivityOutput.fromIntent(result.data, "SettingsActivity")

            val action=output.action

            when (lastActivatedActionSelector) {
                shakeActionSelector -> {
                    settings.shakeAction=action
                    }
                volumeUpPressActionSelector -> {
                    settings.volumeUpPressAction=action
                    }
                volumeDownPressActionSelector -> {
                    settings.volumeDownPressAction=action
                    }
                }

            refreshSelectors()
            }
        }
    }

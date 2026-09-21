/*
* Copyright (C) 2025 Rastislav Kish
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

package com.expagent.ui.actionselectionactivity

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

import android.content.Intent

import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult

import android.widget.TextView
import android.widget.Toast
import android.view.View

import kotlinx.serialization.*
import kotlinx.serialization.json.Json

import com.expagent.R
import com.expagent.ui.fitContentInsideSystemBars

import com.expagent.core.ConfigManager
import com.expagent.core.Action
import com.expagent.core.ScanWithActiveConfigAction
import com.expagent.core.ScanWithConfigAction
import com.expagent.core.ConsultConfigAction
import com.expagent.core.AskAction
import com.expagent.core.SetSystemPromptAction
import com.expagent.core.SetUserPromptAction

import com.expagent.ui.configselectionactivity.ConfigSelectionActivity
import com.expagent.ui.configselectionactivity.ConfigSelectionActivityOutput

class ActionSelectionActivity : AppCompatActivity() {

    private lateinit var configManager: ConfigManager

    private var scanWithConfigConfig: Int=-1

    private var consultConfigConfig: Int=-1

    private lateinit var scanWithConfigConfigSelector: TextView
    private lateinit var consultConfigConfigSelector: TextView

    private var lastActivatedConfigSelector: View?=null

    private lateinit var configSelectionActivityLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_action_selection)
        fitContentInsideSystemBars()

        configManager=ConfigManager.getInstance(this)

        scanWithConfigConfigSelector=findViewById(R.id.scanWithConfigConfigSelector)
        consultConfigConfigSelector=findViewById(R.id.consultConfigConfigSelector)

        refreshSelectors()

        configSelectionActivityLauncher=registerForActivityResult(StartActivityForResult(), this::configSelectionActivityResult)
        }

    fun onScanWithActiveConfigClick(v: View) {
        returnResult(ScanWithActiveConfigAction())
        }

    fun onScanWithConfigClick(v: View) {
        returnResult(ScanWithConfigAction(scanWithConfigConfig))
        }
    fun onScanWithConfigConfigSelectorClick(v: View) {
        lastActivatedConfigSelector=scanWithConfigConfigSelector
        startConfigSelectionActivity()
        }

    fun onConsultConfigClick(v: View) {
        returnResult(ConsultConfigAction(consultConfigConfig))
        }
    fun onConsultConfigConfigSelectorClick(v: View) {
        lastActivatedConfigSelector=consultConfigConfigSelector
        startConfigSelectionActivity()
        }

    fun onAskClick(v: View) {
        returnResult(AskAction())
        }

    fun onSetSystemPromptClick(v: View) {
        returnResult(SetSystemPromptAction())
        }

    fun onSetUserPromptClick(v: View) {
        returnResult(SetUserPromptAction())
        }

    fun onNoneClick(v: View) {
        returnResult(null)
        }

    fun returnResult(action: Action?) {
        val result=ActionSelectionActivityOutput(action)

        val resultIntent=Intent()
        resultIntent.putExtra("result", Json.encodeToString(result))
        setResult(RESULT_OK, resultIntent)

        finish()
        }

    private fun refreshSelectors() {
        scanWithConfigConfigSelector.text=getConfigName(scanWithConfigConfig)
        consultConfigConfigSelector.text=getConfigName(consultConfigConfig)
        }
    private fun getConfigName(id: Int): String {
        return configManager.getConfig(id)?.name
        ?: configManager.getBaseConfig().name
        }

    private fun startConfigSelectionActivity() {
        val intent=Intent(this, ConfigSelectionActivity::class.java)
        configSelectionActivityLauncher.launch(intent)
        }
    private fun configSelectionActivityResult(result: ActivityResult) {
        if (result.resultCode==RESULT_OK) {
            val output=ConfigSelectionActivityOutput.fromIntent(result.data, "ActionSelectionActivity")

            if (!output.configIds.isEmpty()) {
                val id=output.configIds[0]

                when (lastActivatedConfigSelector) {
                    scanWithConfigConfigSelector -> scanWithConfigConfig=id
                    consultConfigConfigSelector -> consultConfigConfig=id
                    }

                refreshSelectors()
                }
            }
        }

    fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    }

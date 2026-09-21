/*
* Copyright (C) 2024 Rastislav Kish
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

package com.expagent.ui.mainactivity

import java.util.Base64

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText
import androidx.recyclerview.widget.RecyclerView
import androidx.navigation.fragment.NavHostFragment

import kotlin.coroutines.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*

import com.expagent.R

import com.expagent.core.Settings
import com.expagent.core.TextController
import com.expagent.core.Config
import com.expagent.core.openai.Conversation
import com.expagent.core.openai.LocalImage
import com.expagent.core.openai.ImageMessage
import com.expagent.core.openai.AssistantMessage

class ConfigListFragment: Fragment(), CoroutineScope {

    override val coroutineContext: CoroutineContext
    get() = Dispatchers.Main+job

    private lateinit var job: Job

    private lateinit var settings: Settings
    private lateinit var tabAdapter: TabAdapter
    private lateinit var configSorter: ConfigSorter
    private lateinit var configListAdapter: ConfigListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
        ): View? {
        return inflater.inflate(R.layout.fragment_config_list, container, false)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        job=Job()
        settings=Settings.getInstance(requireContext())
        tabAdapter=TabAdapter.getInstance(requireContext())
        configSorter=ConfigSorter.getInstance(requireContext())
        configListAdapter=ConfigListAdapter(requireContext(), configSorter)
        configListAdapter.setItemClickListener(this::configClick)
        configListAdapter.setItemLongClickListener(this::configLongClick)
        val configList: RecyclerView=view.findViewById(R.id.configList)
        configList.adapter=configListAdapter

        val searchInput: TextInputEditText=view.findViewById(R.id.searchInput)
        TextController(searchInput).setTextChangeListener(this::searchInputTextChange)
        }

    override fun onResume() {
        configListAdapter.refresh()
        super.onResume()
        }

    fun configClick(config: Config) {
        launch { tabAdapter.mutex.withLock {
            tabAdapter.activeConfig=config
            configSorter.markSelection(config.id)

            val navHostFragment = requireActivity().supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            val navController = navHostFragment.navController
            navController.navigateUp()
            }}
        }
    fun configLongClick(config: Config) {
        launch { tabAdapter.mutex.withLock {
            try {
                val response=tabAdapter.consultConfig(config) ?: return@launch
                toastResponse(response)
                }
            catch (e: Exception) {
                toast(e.message ?: "Error")
                }
            }}
        }
    fun searchInputTextChange(text: String) {
        configListAdapter.setFilter(text)
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
    }

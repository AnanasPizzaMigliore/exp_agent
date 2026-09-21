/*
* Copyright (C) 2023 Rastislav Kish
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

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Parcelable

import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.FragmentManager

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream

import kotlinx.serialization.*
import kotlinx.serialization.json.Json

import org.greenrobot.eventbus.EventBus

import com.expagent.R
import com.expagent.ui.fitContentInsideSystemBars

import com.expagent.core.PermissionRequester
import com.expagent.core.Settings

import androidx.navigation.Navigation
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI

import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var container: FragmentContainerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        fitContentInsideSystemBars()

        val permissionRequester=PermissionRequester(this)
        if (!permissionRequester.permissionsGranted) {
            permissionRequester.requestPermissions(this)
            }

        // The nav graph has a single destination, so there is nothing to set up
        // a navigation bar against. NavHostFragment shows it on its own.
        }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode==KeyEvent.KEYCODE_VOLUME_UP) {
            EventBus.getDefault().post(DeviceInputEvent(DeviceInputEventKind.VOLUME_UP_PRESS))
            return true
            }
        if (keyCode==KeyEvent.KEYCODE_VOLUME_DOWN) {
            EventBus.getDefault().post(DeviceInputEvent(DeviceInputEventKind.VOLUME_DOWN_PRESS))
            return true
            }

        return super.onKeyDown(keyCode, event)
        }

    fun handleSendImage(intent: Intent) {
        (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let { uri ->
            val image=getByteArrayFromUri(this, uri) ?: return

            val shareBox=ShareBox.getInstance(this)
            shareBox.pushImage(image)
            }
        }

    fun getByteArrayFromUri(context: Context, uri: Uri): ByteArray? {
        val contentResolver: ContentResolver = context.contentResolver
        val inputStream: InputStream? = contentResolver.openInputStream(uri)

        inputStream?.let {
            val buffer = ByteArray(1024)
            val byteArrayOutputStream = ByteArrayOutputStream()
            var bytesRead: Int

            try {
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    byteArrayOutputStream.write(buffer, 0, bytesRead)
                    }
                }
            catch (e: Exception) {
                e.printStackTrace()
                return null
                }
            finally {
                try {
                    inputStream.close()
                    }
                catch (e: Exception) {
                    e.printStackTrace()
                    }
                }

            return byteArrayOutputStream.toByteArray()
            }

        return null
        }

    fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    }

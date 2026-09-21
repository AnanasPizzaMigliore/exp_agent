/*
* Copyright (C) 2023 Rastislav Kish
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

package com.rastislavkish.vscan.ui

import android.app.Activity
import android.view.View

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
* Keeps the activity's content clear of the system bars.
*
* From Android 15 on, an app targeting SDK 35 or above is always laid out
* edge to edge: the window spans the whole display and the status and
* navigation bars are drawn over it. Without this, the first row of every
* screen sits underneath the status bar, which for the top bar here means a
* button that is half covered and, with TalkBack off, hard to hit.
*
* The insets are applied as padding on the content view, so the layouts
* underneath can keep constraining to parent edges. The keyboard is included,
* which reproduces what adjustResize used to do for the text input screen.
*/
fun Activity.fitContentInsideSystemBars() {
    val content=findViewById<View>(android.R.id.content)

    ViewCompat.setOnApplyWindowInsetsListener(content) { view, windowInsets ->
        val insets=windowInsets.getInsets(
        WindowInsetsCompat.Type.systemBars()
        or WindowInsetsCompat.Type.displayCutout()
        or WindowInsetsCompat.Type.ime()
        )

        view.updatePadding(insets.left, insets.top, insets.right, insets.bottom)

        windowInsets
        }
    }

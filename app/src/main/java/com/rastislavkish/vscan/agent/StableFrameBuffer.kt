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

data class StableSnapshot(val id: Long, val atNs: Long, val jpeg: ByteArray)

/** Three recent stable keyframes plus, outside this buffer, one pinned event reference. */
class StableFrameBuffer(private val capacity: Int=3) {
    init { require(capacity>0) }
    private val frames=ArrayDeque<StableSnapshot>()
    val size: Int get() = frames.size

    fun add(frame: StableSnapshot) {
        if (frames.lastOrNull()?.atNs?.let { frame.atNs<=it }==true) return
        frames.addLast(frame)
        while (frames.size>capacity) frames.removeFirst()
        }

    fun get(id: Long?): StableSnapshot? = frames.firstOrNull { it.id==id }
    fun clear() { frames.clear() }
    }

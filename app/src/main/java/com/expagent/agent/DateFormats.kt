/*
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

package com.expagent.agent

/**
* Day-first dates, in the two forms this app needs.
*
* Built from iso_date rather than from the model's verbatim date_string,
* because the verbatim form is whatever was printed on the package - "16-02-25",
* "16.02.2025", "16 FEB 25" - and the reading was being spoken exactly as
* printed. "Best before 16-02-2025" came out of TextToSpeech as "sixteen dash
* zero two dash two thousand and twenty five" and took three and a half seconds
* to say, which is longer than the model took to read it.
*
* Two forms, deliberately different:
*
*   [numeric] 16/02/2025 - for the screen and the log. Day first, the European
*   convention, and unambiguous to a reader.
*
*   [spoken] "16 February 2025" - for the ear. Also day first, but with the
*   month named, because a slash is not a sound and every engine guesses
*   differently at what to do with it. A misheard month is a wrong date, and a
*   blind user has no way to catch it.
*/
object DateFormats {

    private val MONTHS=listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
        )

    /** "2025-02-16" -> "16/02/2025", "2025-02" -> "02/2025", else null. */
    fun numeric(iso: String?): String? {
        val parts=split(iso) ?: return null
        val (year, month, day)=parts

        return if (day!=null)
        "%02d/%02d/%04d".format(day, month, year)
        else
        "%02d/%04d".format(month, year)
        }

    /** "2025-02-16" -> "16 February 2025", "2025-02" -> "February 2025". */
    fun spoken(iso: String?): String? {
        val parts=split(iso) ?: return null
        val (year, month, day)=parts

        val name=MONTHS[month-1]

        return if (day!=null) "$day $name $year" else "$name $year"
        }

    /** Year, month, and day when the date carries one. Null if unparseable. */
    private fun split(iso: String?): Triple<Int, Int, Int?>? {
        val text=iso?.trim() ?: return null
        val fields=text.split("-")

        if (fields.size<2||fields.size>3)
        return null

        val year=fields[0].toIntOrNull() ?: return null
        val month=fields[1].toIntOrNull() ?: return null

        if (year<1900||year>2200||month<1||month>12)
        return null

        if (fields.size==2)
        return Triple(year, month, null)

        val day=fields[2].toIntOrNull() ?: return null

        if (day<1||day>31)
        return null

        return Triple(year, month, day)
        }
    }

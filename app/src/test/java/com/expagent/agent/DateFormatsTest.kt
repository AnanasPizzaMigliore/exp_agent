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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DateFormatsTest {

    @Test
    fun daysComeFirst() {
        assertEquals("16/02/2025", DateFormats.numeric("2025-02-16"))
        assertEquals("16 February 2025", DateFormats.spoken("2025-02-16"))
        }

    @Test
    fun singleDigitsArePaddedOnScreenButNotSpoken() {
        // "01/03/2025" reads correctly; "1 March 2025" says correctly. Speaking
        // a padded day gives "zero one March".
        assertEquals("01/03/2025", DateFormats.numeric("2025-03-01"))
        assertEquals("1 March 2025", DateFormats.spoken("2025-03-01"))
        }

    @Test
    fun monthPrecisionIsKept() {
        // Plenty of packages carry only a month. Inventing a day would be
        // inventing information the package does not have.
        assertEquals("02/2025", DateFormats.numeric("2025-02"))
        assertEquals("February 2025", DateFormats.spoken("2025-02"))
        }

    @Test
    fun everyMonthNamesCorrectly() {
        assertEquals("1 January 2025", DateFormats.spoken("2025-01-01"))
        assertEquals("31 December 2025", DateFormats.spoken("2025-12-31"))
        }

    @Test
    fun rubbishIsRefusedRatherThanGuessed() {
        // Returning null hands the caller back to the verbatim printed string.
        // A silently wrong date is the one outcome a blind user cannot catch.
        assertNull(DateFormats.numeric(null))
        assertNull(DateFormats.numeric(""))
        assertNull(DateFormats.numeric("16-02-2025"))
        assertNull(DateFormats.numeric("2025-13-01"))
        assertNull(DateFormats.numeric("2025-02-32"))
        assertNull(DateFormats.numeric("not a date"))
        assertNull(DateFormats.spoken("2025"))
        }
    }

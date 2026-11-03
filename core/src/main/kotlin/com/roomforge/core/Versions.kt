/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.core

/** smallest complete version compare for update tags like "v1.2.3" or "1.2". */
object AppVersions {
    fun partsOf(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V")
            .split('.')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    /** true when [tag] names a newer version than [current]. */
    fun isNewer(current: String, tag: String): Boolean {
        val a = partsOf(current)
        val b = partsOf(tag)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (y != x) return y > x
        }
        return false
    }
}

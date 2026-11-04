/*
 * RoomForgeDroid — acoustic analyzer.
 * Copyright (C) 2026 RoomForgeDroid contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roomforge.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionCompareTest {
    @Test
    fun newerTagDetected() {
        assertTrue(AppVersions.isNewer("1.0.0", "v1.0.1"))
        assertTrue(AppVersions.isNewer("1.0.0", "1.1"))
        assertTrue(AppVersions.isNewer("1.9", "v1.10"))
    }

    @Test
    fun sameOrOlderIsNotNewer() {
        assertFalse(AppVersions.isNewer("1.0.0", "v1.0.0"))
        assertFalse(AppVersions.isNewer("1.0.1", "v1.0.0"))
        assertFalse(AppVersions.isNewer("2.0", "v1.99.99"))
    }
}

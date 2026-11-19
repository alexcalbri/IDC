package com.ideasdeveloper.idc.server.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionResponseTest {
    @Test
    fun catalogOlderThanTheServerIsNotAnUpdate() {
        assertFalse(VersionResponse(coreVersion = "0.8.0", latestCoreVersion = "0.7.2").coreUpdateAvailable)
    }

    @Test
    fun onlyANewerCatalogVersionIsAnUpdate() {
        assertTrue(VersionResponse(coreVersion = "0.8.0", latestCoreVersion = "0.8.2").coreUpdateAvailable)
        assertFalse(VersionResponse(coreVersion = "0.8.0", latestCoreVersion = "0.8.0").coreUpdateAvailable)
        assertFalse(VersionResponse(coreVersion = "0.8.0", latestCoreVersion = null).coreUpdateAvailable)
    }
}

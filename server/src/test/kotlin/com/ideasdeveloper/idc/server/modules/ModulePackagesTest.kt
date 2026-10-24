package com.ideasdeveloper.idc.server.modules

import com.ideasdeveloper.idc.ModuleVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModulePackagesTest {
    @Test
    fun catalogUrlsResolveToRepositoryBranchAndFolder() {
        assertEquals(
            GitHubLocation("alexcalbri", "IDC", "develop", "modules"),
            GitHubLocation.parse("https://github.com/alexcalbri/IDC/tree/develop/modules"),
        )
        assertEquals(
            GitHubLocation("owner", "repo", "main", "catalog/modules"),
            GitHubLocation.parse("https://api.github.com/repos/owner/repo/contents/catalog/modules?ref=main"),
        )
        assertEquals("HEAD", GitHubLocation.parse("https://api.github.com/repos/owner/repo/contents/modules")?.ref)
        assertNull(GitHubLocation.parse("https://example.com/modules.json"))
    }

    @Test
    fun archiveUrlDownloadsTheConfiguredBranch() {
        assertEquals(
            "https://codeload.github.com/alexcalbri/IDC/zip/master",
            GitHubLocation("alexcalbri", "IDC", "master", "modules").archiveUrl,
        )
    }

    @Test
    fun versionsCompareNumericallyAndNeverDowngrade() {
        assertTrue(ModuleVersion.isNewer("0.10.0", "0.9.0"))
        assertTrue(ModuleVersion.isNewer("0.3.0", "0.2.9"))
        assertTrue(ModuleVersion.isNewer("1.0.0", "1.0.0-beta"))
        assertFalse(ModuleVersion.isNewer("0.1.0", "0.2.0"))
        assertFalse(ModuleVersion.isNewer("0.2.0", "0.2"))
        assertEquals(0, ModuleVersion.compare("0.2.0", "0.2.0"))
    }

    @Test
    fun providerNameFollowsTheModuleLayout() {
        assertEquals("com.ideasdeveloper.idc.modules.hotspot.HotspotServerModuleProvider", ModulePackageBuilder.providerClassName("hotspot"))
        assertEquals("com.ideasdeveloper.idc.modules.ventas_online.VentasOnlineServerModuleProvider", ModulePackageBuilder.providerClassName("ventas_online"))
    }
}

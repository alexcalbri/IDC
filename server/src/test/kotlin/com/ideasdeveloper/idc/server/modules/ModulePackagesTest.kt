package com.ideasdeveloper.idc.server.modules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun providerNameFollowsTheModuleLayout() {
        assertEquals("com.ideasdeveloper.idc.modules.hotspot.HotspotServerModuleProvider", ModulePackageBuilder.providerClassName("hotspot"))
        assertEquals("com.ideasdeveloper.idc.modules.ventas_online.VentasOnlineServerModuleProvider", ModulePackageBuilder.providerClassName("ventas_online"))
    }
}

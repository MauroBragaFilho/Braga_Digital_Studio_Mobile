package com.bragastudio.mobile.common.module

import androidx.compose.ui.graphics.vector.ImageVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BdsmModuleRegistryTest {

    private class Fake(
        override val id: String,
        override val order: Int,
        override val placements: Set<ModulePlacement> = setOf(ModulePlacement.HOME_CARD),
        override val route: String? = id,
        override val featureFlag: String? = null,
        override val selectionRoutes: Set<String> = route?.let(::setOf) ?: emptySet(),
    ) : BdsmModule {
        override val titleRes: Int = 0
        override val icon: ImageVector get() = error("não usado nos testes")
    }

    @Test
    fun duplicateIdsAreRejected() {
        val result = runCatching { BdsmModuleRegistry(listOf(Fake("a", 1), Fake("a", 2))) }
        assertTrue(result.exceptionOrNull() is DuplicateModuleIdException)
    }

    @Test
    fun orderedByOrderThenId() {
        val reg = BdsmModuleRegistry(listOf(Fake("c", 2), Fake("b", 1), Fake("a", 2)))
        assertEquals(listOf("b", "a", "c"), reg.visible.map { it.id })
    }

    @Test
    fun placementsFilterAndKeepOrder() {
        val reg = BdsmModuleRegistry(
            listOf(
                Fake("home", 0, setOf(ModulePlacement.BOTTOM_BAR)),
                Fake("rec", 2, setOf(ModulePlacement.BOTTOM_BAR, ModulePlacement.HOME_CARD)),
                Fake("ndi", 1, setOf(ModulePlacement.HOME_CARD)),
            ),
        )
        assertEquals(listOf("home", "rec"), reg.at(ModulePlacement.BOTTOM_BAR).map { it.id })
        assertEquals(listOf("ndi", "rec"), reg.at(ModulePlacement.HOME_CARD).map { it.id })
        assertTrue(reg.at(ModulePlacement.HOME_PRIMARY).isEmpty())
    }

    @Test
    fun featureFlagHidesModule() {
        val modules = listOf(Fake("a", 1), Fake("beta", 2, featureFlag = "beta"))
        val off = BdsmModuleRegistry(modules) { it != "beta" }
        assertEquals(listOf("a"), off.visible.map { it.id })
        assertNull(off.byId("beta"))
        val on = BdsmModuleRegistry(modules)
        assertEquals(listOf("a", "beta"), on.visible.map { it.id })
    }

    @Test
    fun lookupByRoute() {
        val reg = BdsmModuleRegistry(listOf(Fake("a", 1, route = "r/a"), Fake("b", 2, route = null)))
        assertEquals("a", reg.byRoute("r/a")?.id)
        assertNull(reg.byRoute(null))
        assertNull(reg.byRoute("x"))
    }

    @Test
    fun tabsAreResolvedFromSelectionRoutes() {
        val bar = setOf(ModulePlacement.BOTTOM_BAR)
        val reg = BdsmModuleRegistry(
            listOf(
                Fake("home", 0, bar, route = "home"),
                Fake("ndi", 20, bar, route = "ndi"),
                Fake("media", 30, bar, route = "media", selectionRoutes = setOf("media", "recording", "luts")),
                Fake("settings", 90, setOf(ModulePlacement.MORE), route = "settings"),
                Fake("monitor", 10, setOf(ModulePlacement.HOME_PRIMARY), route = "preview"),
            ),
        )
        assertEquals(0, reg.tabIndexFor("home"))
        assertEquals(2, reg.tabIndexFor("luts"))
        assertEquals(-1, reg.tabIndexFor("preview"))
        assertEquals(-1, reg.tabIndexFor("settings"))
        assertEquals(-1, reg.tabIndexFor(null))
        assertTrue(reg.showsNavigation("recording"))
        assertTrue(!reg.showsNavigation("preview"))
    }

    @Test
    fun hiddenModuleLeavesNavigationAndMore() {
        val more = setOf(ModulePlacement.MORE)
        val modules = listOf(Fake("settings", 1, more), Fake("bsp", 2, more, featureFlag = "bsp"))
        assertEquals(listOf("settings"), BdsmModuleRegistry(modules) { it != "bsp" }.at(ModulePlacement.MORE).map { it.id })
        assertEquals(listOf("settings", "bsp"), BdsmModuleRegistry(modules).at(ModulePlacement.MORE).map { it.id })
    }

    @Test
    fun settingsCategoryRoutes() {
        assertEquals("settings/camera", settingsCategoryRoute("camera"))
    }
}

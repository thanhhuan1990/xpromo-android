package com.huanhuynh.xpromo

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class XPromoPickerTest {

    private fun app(id: String, groups: List<String> = emptyList(), priority: Int = 0, enabled: Boolean = true, countries: List<String> = emptyList()) =
        PromoApp(id = id, name = id, groups = groups, priority = priority, enabled = enabled, countries = countries)

    private val feed = PromoFeed(
        apps = listOf(
            app("self", listOf("household")),
            app("lich", listOf("spiritual"), priority = 90),
            app("water", listOf("health", "daily"), priority = 50),
            app("chitieu", listOf("household", "finance"), priority = 60),
            app("off", listOf("household"), enabled = false),
            app("us_only", listOf("household"), countries = listOf("US")),
        )
    )

    @Test
    fun rank_filtersAndSortsByGroupThenPriority() {
        val r = XPromoPicker.rank(feed, "self", listOf("household", "health", "daily"), "vn", isInstalled = { false })
        assertEquals(listOf("water", "chitieu", "lich"), r.map { it.id })
    }

    @Test
    fun rank_excludesInstalledAndHidden() {
        val r = XPromoPicker.rank(feed, "self", listOf("household"), "VN", isInstalled = { it == "chitieu" }, hidden = setOf("water"))
        assertEquals(listOf("lich"), r.map { it.id })
    }

    @Test
    fun rank_includeInstalled_keepsInstalledLastEvenIfDisabled() {
        val r = XPromoPicker.rank(
            feed, "self", listOf("household"), "VN",
            isInstalled = { it == "chitieu" || it == "off" }, includeInstalled = true,
        )
        assertEquals(listOf("lich", "water", "chitieu", "off"), r.map { it.id })
    }

    @Test
    fun rank_countryFilter() {
        val us = XPromoPicker.rank(feed, "self", listOf("household"), "US", isInstalled = { false })
        val vn = XPromoPicker.rank(feed, "self", listOf("household"), "VN", isInstalled = { false })
        assertTrue(us.any { it.id == "us_only" })
        assertTrue(vn.none { it.id == "us_only" })
    }

    @Test
    fun pickForCard_rotatesTop3() {
        val ranked = listOf(app("a"), app("b"), app("c"), app("d"))
        assertEquals(listOf("a", "b", "c", "a"), (0..3).map { XPromoPicker.pickForCard(ranked, it)!!.id })
        assertNull(XPromoPicker.pickForCard(emptyList(), 0))
    }

    @Test
    fun marketUri_hasUtmReferrer() {
        val u = XPromoPicker.marketUri("com.x", "com.self", "settings")
        assertEquals("market://details?id=com.x&referrer=utm_source%3Dcom.self%26utm_medium%3Dxpromo%26utm_campaign%3Dsettings", u)
    }

    private fun file(vararg paths: String) = paths.map(::File).first { it.exists() }

    /** Mọi app trong feed dự phòng phải có <package> trong manifest thư viện (để lọc app đã cài trên Android 11+). */
    @Test
    fun manifestQueries_coverEveryFeedApp() {
        val feed = Json { ignoreUnknownKeys = true }.decodeFromString(
            PromoFeed.serializer(), file("src/main/assets/xpromo/apps.json", "xpromo/src/main/assets/xpromo/apps.json").readText(),
        )
        val manifest = file("src/main/AndroidManifest.xml", "xpromo/src/main/AndroidManifest.xml").readText()
        val declared = Regex("""<package android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        val missing = feed.apps.map { it.id }.filter { it !in declared }
        assertTrue("Thiếu <package> trong AndroidManifest.xml: $missing", missing.isEmpty())
    }

    @Test
    fun bundledFeed_parsesAndIsValid() {
        val f = listOf("src/main/assets/xpromo/apps.json", "xpromo/src/main/assets/xpromo/apps.json").map(::File).first { it.exists() }
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString(PromoFeed.serializer(), f.readText())
        assertTrue(parsed.apps.isNotEmpty())
        assertEquals(parsed.apps.size, parsed.apps.map { it.id }.toSet().size)
        val r = XPromoPicker.rank(parsed, "com.huanhuynh.freshnest", listOf("household", "health", "daily"), "VN", isInstalled = { false })
        assertTrue(r.none { it.id == "com.huanhuynh.freshnest" || !it.enabled })
        assertTrue(r.isNotEmpty())
    }
}

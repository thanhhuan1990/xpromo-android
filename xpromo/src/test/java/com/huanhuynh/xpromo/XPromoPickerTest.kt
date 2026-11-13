package com.huanhuynh.xpromo

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class XPromoPickerTest {

    private fun app(
        id: String,
        groups: List<String> = emptyList(),
        priority: Int = 0,
        enabled: Boolean = true,
        countries: List<String> = emptyList(),
        languages: List<String> = emptyList(),
    ) = PromoApp(id = id, name = id, groups = groups, priority = priority, enabled = enabled, countries = countries, languages = languages)

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
        // water (health, daily: liên quan household → 2 điểm) trước lich (spiritual liên quan household → 1 điểm).
        assertEquals(listOf("water", "lich", "chitieu", "off"), r.map { it.id })
    }

    @Test
    fun rank_countryFilter() {
        val us = XPromoPicker.rank(feed, "self", listOf("household"), "US", isInstalled = { false })
        val vn = XPromoPicker.rank(feed, "self", listOf("household"), "VN", isInstalled = { false })
        assertTrue(us.any { it.id == "us_only" })
        assertTrue(vn.none { it.id == "us_only" })
    }

    @Test
    fun rank_relatedGroupsBeatUnrelated() {
        val f = PromoFeed(apps = listOf(
            app("game", listOf("games", "fun"), priority = 99),
            app("lich", listOf("spiritual", "daily"), priority = 10),
            app("dict", listOf("education", "daily"), priority = 50),
        ))
        // App tâm linh: lich chung nhóm, dict có "daily" liên quan, game không liên quan dù priority cao nhất.
        val r = XPromoPicker.rank(f, "self", listOf("spiritual"), "VN", isInstalled = { false })
        assertEquals(listOf("lich", "dict", "game"), r.map { it.id })
        // Game: game khác lên đầu, từ điển (education liên quan games) kế tiếp.
        val g = XPromoPicker.rank(f, "self", listOf("games", "fun"), "VN", isInstalled = { false })
        assertEquals(listOf("game", "dict", "lich"), g.map { it.id })
    }

    @Test
    fun rank_languageFilter_onlyForNotInstalled() {
        val f = PromoFeed(apps = listOf(
            app("vankhan", listOf("spiritual"), languages = listOf("vi")),
            app("covay", listOf("games"), languages = listOf("vi", "en", "zh", "ja")),
            app("wheel", listOf("fun")),
        ))
        val en = XPromoPicker.rank(f, "self", listOf("games"), "US", isInstalled = { false }, lang = "en")
        assertEquals(listOf("covay", "wheel"), en.map { it.id })
        val vi = XPromoPicker.rank(f, "self", listOf("games"), "VN", isInstalled = { false }, lang = "vi")
        assertEquals(3, vi.size)
        // Đã cài thì vẫn hiện nút "Mở" dù khác ngôn ngữ.
        val installed = XPromoPicker.rank(f, "self", listOf("games"), "US", isInstalled = { it == "vankhan" }, includeInstalled = true, lang = "en")
        assertTrue(installed.any { it.id == "vankhan" })
    }

    @Test
    fun rank_fatiguedAppsGoLast() {
        val stats = mapOf("water" to PromoStats(views = XPromoPicker.FATIGUE_VIEWS, clicks = 0))
        val r = XPromoPicker.rank(feed, "self", listOf("household", "health", "daily"), "VN", isInstalled = { false }, stats = stats)
        assertEquals(listOf("chitieu", "lich", "water"), r.map { it.id })
        // Đã từng bấm thì không bị coi là nhàm.
        val clicked = mapOf("water" to PromoStats(views = 20, clicks = 1))
        val r2 = XPromoPicker.rank(feed, "self", listOf("household", "health", "daily"), "VN", isInstalled = { false }, stats = clicked)
        assertEquals("water", r2.first().id)
    }

    @Test
    fun splitForList_sameGroupFirstThenRest() {
        val ranked = listOf(
            app("a", listOf("games")), app("b", listOf("games")), app("c", listOf("education")),
            app("d", listOf("games")), app("e", listOf("spiritual")),
        )
        val (first, rest) = XPromoPicker.splitForList(ranked, listOf("games", "fun"), max = 2)
        assertEquals(listOf("a", "b"), first.map { it.id })
        assertEquals(listOf("c", "d", "e"), rest.map { it.id })
        // Không app nào chung nhóm → nhóm 1 là max app đầu.
        val (f2, r2) = XPromoPicker.splitForList(ranked, listOf("health"), max = 3)
        assertEquals(listOf("a", "b", "c"), f2.map { it.id })
        assertEquals(listOf("d", "e"), r2.map { it.id })
        assertTrue(XPromoPicker.splitForList(emptyList(), listOf("games"), 4).let { it.first.isEmpty() && it.second.isEmpty() })
    }

    @Test
    fun pickForCard_rotatesTop3() {
        val ranked = listOf(app("a"), app("b"), app("c"), app("d"))
        assertEquals(listOf("a", "b", "c", "a"), (0..3).map { XPromoPicker.pickForCard(ranked, it)!!.id })
        assertNull(XPromoPicker.pickForCard(emptyList(), 0))
    }

    @Test
    fun pickForCard_skipsLastShown() {
        val ranked = listOf(app("a"), app("b"), app("c"))
        assertTrue((0..5).none { XPromoPicker.pickForCard(ranked, it, lastId = "b")!!.id == "b" })
        // Chỉ còn đúng app vừa hiện thì vẫn hiện lại.
        assertEquals("a", XPromoPicker.pickForCard(listOf(app("a")), 3, lastId = "a")!!.id)
    }

    @Test
    fun marketUri_hasUtmReferrer() {
        val u = XPromoPicker.marketUri("com.x", "com.self", "settings")
        assertEquals("market://details?id=com.x&referrer=utm_source%3Dcom.self%26utm_medium%3Dxpromo%26utm_campaign%3Dsettings", u)
    }

    private fun file(vararg paths: String) = paths.map(::File).first { it.exists() }

    /** Mọi app trong feed dự phòng phải có <package> trong manifest thư viện; manifest phải có intent MEMBER. */
    @Test
    fun manifestQueries_coverEveryFeedApp() {
        val feed = Json { ignoreUnknownKeys = true }.decodeFromString(
            PromoFeed.serializer(), file("src/main/assets/xpromo/apps.json", "xpromo/src/main/assets/xpromo/apps.json").readText(),
        )
        val manifest = file("src/main/AndroidManifest.xml", "xpromo/src/main/AndroidManifest.xml").readText()
        val declared = Regex("""<package android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        val missing = feed.apps.map { it.id }.filter { it !in declared }
        assertTrue("Thiếu <package> trong AndroidManifest.xml: $missing", missing.isEmpty())
        assertEquals(2, Regex(Regex.escape(XPromo.MEMBER_ACTION)).findAll(manifest).count())
    }

    @Test
    fun bundledFeed_parsesAndIsValid() {
        val f = listOf("src/main/assets/xpromo/apps.json", "xpromo/src/main/assets/xpromo/apps.json").map(::File).first { it.exists() }
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString(PromoFeed.serializer(), f.readText())
        assertTrue(parsed.apps.isNotEmpty())
        assertEquals(parsed.apps.size, parsed.apps.map { it.id }.toSet().size)
        assertTrue("Mọi app phải khai báo languages", parsed.apps.all { it.languages.isNotEmpty() || it.id.endsWith("spin_the_wheel") })
        val r = XPromoPicker.rank(parsed, "com.huanhuynh.freshnest", listOf("household", "health", "daily"), "VN", isInstalled = { false }, lang = "vi")
        assertTrue(r.none { it.id == "com.huanhuynh.freshnest" || !it.enabled })
        assertTrue(r.isNotEmpty())
    }
}

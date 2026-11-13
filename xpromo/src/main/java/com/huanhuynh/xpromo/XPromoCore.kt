package com.huanhuynh.xpromo

import kotlinx.serialization.Serializable

/*
 * XPromo – quảng bá chéo các app của HuanHuynh. Thư viện: https://github.com/thanhhuan1990/xpromo-android
 * Dùng: implementation("com.github.thanhhuan1990:xpromo-android:<version>") (repo https://jitpack.io),
 * gọi XPromo.init(...) trong Application, đặt MoreAppsSection / XPromoCard ở chỗ phù hợp.
 * <queries> và bản dự phòng assets/xpromo/apps.json nằm sẵn trong thư viện (tự gộp vào app).
 * Danh sách app trực tuyến: https://thanhhuan1990.github.io/xpromo/apps.json
 */

@Serializable
data class PromoFeed(
    val schemaVersion: Int = 1,
    val version: String = "",
    val developer: String = "",
    val apps: List<PromoApp> = emptyList(),
)

@Serializable
data class PromoApp(
    /** Package name trên Google Play. */
    val id: String,
    val name: String,
    val tagline: String = "",
    /** Hiện khi chưa tải được icon. */
    val emoji: String = "📱",
    /** URL PNG vuông (khuyến nghị 192 px). */
    val icon: String? = null,
    /** Nhóm chủ đề để ghép app liên quan: spiritual, household, health, finance, daily, education, games, fun… */
    val groups: List<String> = emptyList(),
    /** Mã quốc gia ISO (VN, US…). Rỗng = mọi nơi. */
    val countries: List<String> = emptyList(),
    val priority: Int = 0,
    val enabled: Boolean = true,
    /** Bản dịch tên/mô tả theo mã ngôn ngữ (en, zh…). Thiếu thì dùng name/tagline gốc (tiếng Việt). */
    val i18n: Map<String, PromoText> = emptyMap(),
    /**
     * Ngôn ngữ giao diện của app đích (vi, en, zh, ja). App chưa cài chỉ được gợi ý khi người dùng đang dùng
     * app nguồn bằng một trong các ngôn ngữ này – không gợi ý app chỉ có tiếng Việt cho người dùng tiếng Anh.
     * Rỗng = phù hợp mọi ngôn ngữ.
     */
    val languages: List<String> = emptyList(),
) {
    fun nameIn(lang: String): String = i18n[lang]?.name?.takeIf { it.isNotBlank() } ?: name
    fun taglineIn(lang: String): String = i18n[lang]?.tagline?.takeIf { it.isNotBlank() } ?: tagline
    fun supports(lang: String?): Boolean = lang == null || languages.isEmpty() || lang in languages
}

@Serializable
data class PromoText(val name: String? = null, val tagline: String? = null)

/** Số lần một app đã được hiện / được bấm trên máy này (để không hiện mãi một app người dùng không quan tâm). */
data class PromoStats(val views: Int = 0, val clicks: Int = 0)

/** Chữ cố định của giao diện XPromo. */
data class XPromoTexts(
    val moreApps: String,
    val sameDeveloper: String,
    val open: String,
    val install: String,
    val view: String,
    val hide: String,
) {
    companion object {
        val VI = XPromoTexts("Ứng dụng khác của chúng tôi", "Ứng dụng cùng nhà phát triển", "Mở", "Cài", "Xem", "Ẩn gợi ý")
        val EN = XPromoTexts("More apps from us", "From the same developer", "Open", "Get", "View", "Hide")
        val ZH = XPromoTexts("我们的其他应用", "同一开发者的应用", "打开", "安装", "查看", "隐藏")
        val JA = XPromoTexts("ほかのアプリ", "同じ開発者のアプリ", "開く", "入手", "見る", "非表示")
        fun of(lang: String): XPromoTexts = when (lang) { "en" -> EN; "zh" -> ZH; "ja" -> JA; else -> VI }

        /** Chọn ngôn ngữ: ngôn ngữ hiện tại nếu app hỗ trợ, ngược lại ngôn ngữ đầu tiên của app. */
        fun pickLang(current: String, supported: List<String>): String =
            if (current in supported) current else supported.firstOrNull() ?: "vi"
    }
}

/** Chọn & sắp xếp app để quảng bá (thuần Kotlin → unit test được). */
object XPromoPicker {

    /** Đã hiện từng ấy lần mà chưa lần nào được bấm → coi như người dùng không quan tâm, xếp sau. */
    const val FATIGUE_VIEWS = 5

    /**
     * Nhóm gần nhau: app chung nhóm với app nguồn +2 điểm mỗi nhóm, app thuộc nhóm "liên quan" +1.
     * Ví dụ đang ở app tâm linh (spiritual) thì app hằng ngày/gia đình vẫn được ưu tiên hơn game.
     */
    val RELATED: Map<String, Set<String>> = mapOf(
        "spiritual" to setOf("daily", "household"),
        "household" to setOf("finance", "health", "daily", "spiritual"),
        "finance" to setOf("household", "daily"),
        "health" to setOf("household", "daily"),
        "daily" to setOf("spiritual", "household", "finance", "health", "education"),
        "education" to setOf("daily", "games"),
        "games" to setOf("fun", "education"),
        "fun" to setOf("games"),
    )

    fun groupScore(app: PromoApp, hostGroups: List<String>): Int = app.groups.sumOf { g: String ->
        val points: Int = when {
            g in hostGroups -> 2
            hostGroups.any { h -> RELATED[h]?.contains(g) == true } -> 1
            else -> 0
        }
        points
    }

    private fun fatigued(s: PromoStats?): Boolean = s != null && s.clicks == 0 && s.views >= FATIGUE_VIEWS

    /**
     * Loại: chính app đang chạy, app user đã ẩn; app chưa cài thì loại thêm app tắt / không dành cho quốc gia này /
     * không có ngôn ngữ [lang]. App đã cài: bỏ qua nếu `includeInstalled = false` (mặc định), ngược lại giữ
     * (kể cả app đang tắt) và xếp sau app chưa cài – để hiện nút "Mở".
     * Sắp: chưa cài trước → app chưa "nhàm" (xem [FATIGUE_VIEWS]) → điểm nhóm → priority → tên.
     */
    fun rank(
        feed: PromoFeed,
        selfPackage: String,
        hostGroups: List<String>,
        country: String?,
        isInstalled: (String) -> Boolean,
        hidden: Set<String> = emptySet(),
        includeInstalled: Boolean = false,
        lang: String? = null,
        stats: Map<String, PromoStats> = emptyMap(),
    ): List<PromoApp> {
        val c = country?.uppercase()
        return feed.apps
            .asSequence()
            .filter { it.id != selfPackage && it.id !in hidden }
            .map { it to isInstalled(it.id) }
            .filter { (app, installed) ->
                if (installed) includeInstalled
                else app.enabled && app.supports(lang) &&
                    (app.countries.isEmpty() || (c != null && c in app.countries.map(String::uppercase)))
            }
            .sortedWith(
                compareBy<Pair<PromoApp, Boolean>> { it.second }
                    .thenBy { (app, _) -> fatigued(stats[app.id]) }
                    .thenByDescending { (app, _) -> groupScore(app, hostGroups) }
                    .thenByDescending { it.first.priority }
                    .thenBy { it.first.name }
            )
            .map { it.first }
            .toList()
    }

    /**
     * App cho thẻ gợi ý: xoay vòng trong nhóm đầu (tối đa 3 app) theo số lần đã hiện,
     * bỏ app vừa hiện lần trước ([lastId]) nếu còn lựa chọn khác, để user không thấy mãi một app.
     */
    fun pickForCard(ranked: List<PromoApp>, shownCount: Int, lastId: String? = null): PromoApp? {
        if (ranked.isEmpty()) return null
        val top = ranked.take(3)
        val pool = top.filter { it.id != lastId }.ifEmpty { top }
        return pool[shownCount.mod(pool.size)]
    }

    /** Link Google Play kèm UTM để Play Console thống kê lượt cài theo app nguồn & vị trí. */
    fun playReferrer(selfPackage: String, placement: String): String =
        "utm_source%3D$selfPackage%26utm_medium%3Dxpromo%26utm_campaign%3D$placement"

    fun marketUri(target: String, selfPackage: String, placement: String): String =
        "market://details?id=$target&referrer=${playReferrer(selfPackage, placement)}"

    fun webUri(target: String, selfPackage: String, placement: String): String =
        "https://play.google.com/store/apps/details?id=$target&referrer=${playReferrer(selfPackage, placement)}"
}

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
    /** Nhóm chủ đề để ghép app liên quan: spiritual, household, health, finance, daily, fun… */
    val groups: List<String> = emptyList(),
    /** Mã quốc gia ISO (VN, US…). Rỗng = mọi nơi. */
    val countries: List<String> = emptyList(),
    val priority: Int = 0,
    val enabled: Boolean = true,
)

/** Chọn & sắp xếp app để quảng bá (thuần Kotlin → unit test được). */
object XPromoPicker {

    /**
     * Loại: chính app đang chạy, app tắt, app đã cài, app user đã ẩn, app không dành cho quốc gia này.
     * Sắp: càng chung nhóm chủ đề với app hiện tại càng lên trước → priority → tên.
     */
    fun rank(
        feed: PromoFeed,
        selfPackage: String,
        hostGroups: List<String>,
        country: String?,
        isInstalled: (String) -> Boolean,
        hidden: Set<String> = emptySet(),
    ): List<PromoApp> {
        val c = country?.uppercase()
        return feed.apps
            .asSequence()
            .filter { it.enabled && it.id != selfPackage && it.id !in hidden }
            .filter { it.countries.isEmpty() || (c != null && c in it.countries.map(String::uppercase)) }
            .filter { !isInstalled(it.id) }
            .sortedWith(
                compareByDescending<PromoApp> { app -> app.groups.count { it in hostGroups } }
                    .thenByDescending { it.priority }
                    .thenBy { it.name }
            )
            .toList()
    }

    /**
     * App cho thẻ gợi ý: xoay vòng trong nhóm đầu (tối đa 3 app) theo số lần đã hiện,
     * để user không thấy mãi một app.
     */
    fun pickForCard(ranked: List<PromoApp>, shownCount: Int): PromoApp? {
        if (ranked.isEmpty()) return null
        val top = ranked.take(3)
        return top[shownCount.mod(top.size)]
    }

    /** Link Google Play kèm UTM để Play Console thống kê lượt cài theo app nguồn & vị trí. */
    fun playReferrer(selfPackage: String, placement: String): String =
        "utm_source%3D$selfPackage%26utm_medium%3Dxpromo%26utm_campaign%3D$placement"

    fun marketUri(target: String, selfPackage: String, placement: String): String =
        "market://details?id=$target&referrer=${playReferrer(selfPackage, placement)}"

    fun webUri(target: String, selfPackage: String, placement: String): String =
        "https://play.google.com/store/apps/details?id=$target&referrer=${playReferrer(selfPackage, placement)}"
}

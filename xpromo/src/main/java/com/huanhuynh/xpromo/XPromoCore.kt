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
     * Loại: chính app đang chạy, app user đã ẩn; app chưa cài thì loại thêm app tắt / không dành cho quốc gia này.
     * App đã cài: bỏ qua nếu `includeInstalled = false` (mặc định), ngược lại giữ (kể cả app đang tắt) và xếp sau
     * app chưa cài – để hiện nút "Mở".
     * Sắp: chưa cài trước → càng chung nhóm chủ đề càng lên trước → priority → tên.
     */
    fun rank(
        feed: PromoFeed,
        selfPackage: String,
        hostGroups: List<String>,
        country: String?,
        isInstalled: (String) -> Boolean,
        hidden: Set<String> = emptySet(),
        includeInstalled: Boolean = false,
    ): List<PromoApp> {
        val c = country?.uppercase()
        return feed.apps
            .asSequence()
            .filter { it.id != selfPackage && it.id !in hidden }
            .map { it to isInstalled(it.id) }
            .filter { (app, installed) ->
                if (installed) includeInstalled
                else app.enabled && (app.countries.isEmpty() || (c != null && c in app.countries.map(String::uppercase)))
            }
            .sortedWith(
                compareBy<Pair<PromoApp, Boolean>> { it.second }
                    .thenByDescending { (app, _) -> app.groups.count { it in hostGroups } }
                    .thenByDescending { it.first.priority }
                    .thenBy { it.first.name }
            )
            .map { it.first }
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

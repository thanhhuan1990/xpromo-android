package com.huanhuynh.xpromo

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Cấu hình cho từng app chủ. */
data class XPromoConfig(
    /** Package của app đang chạy (để không tự quảng bá chính mình + làm utm_source). */
    val selfPackage: String,
    /** Nhóm chủ đề của app đang chạy – app cùng nhóm được ưu tiên. */
    val hostGroups: List<String>,
    val feedUrl: String = DEFAULT_FEED_URL,
    val bundledAsset: String = "xpromo/apps.json",
    val refreshHours: Long = 24,
    /** Thẻ gợi ý: tối thiểu N ngày giữa 2 lần hiện. */
    val cardMinIntervalDays: Long = 3,
    /** User bấm ✕ → ẩn app đó N ngày. */
    val hideDays: Long = 30,
    /** Bật/tắt từ xa (Remote Config). */
    val enabled: () -> Boolean = { true },
    /** Ghi analytics: xpromo_view / xpromo_click / xpromo_dismiss. */
    val analytics: (String, Map<String, Any?>) -> Unit = { _, _ -> },
) {
    companion object {
        const val DEFAULT_FEED_URL = "https://thanhhuan1990.github.io/xpromo/apps.json"
    }
}

/**
 * Quảng bá chéo: tải danh sách app (cache 24 giờ, dự phòng assets), lọc app đã cài,
 * giới hạn tần suất thẻ gợi ý, mở Google Play kèm UTM, tải & cache icon.
 */
class XPromo private constructor(private val context: Context, val config: XPromoConfig) {

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("xpromo", Context.MODE_PRIVATE)
    private val feedFile = File(context.filesDir, "xpromo_feed.json")

    private val _feed = MutableStateFlow(loadLocal())
    val feed: StateFlow<PromoFeed> = _feed.asStateFlow()

    val enabled: Boolean get() = runCatching { config.enabled() }.getOrDefault(true)

    private fun parse(raw: String?): PromoFeed? =
        raw?.let { runCatching { json.decodeFromString(PromoFeed.serializer(), it) }.getOrNull() }
            ?.takeIf { it.apps.isNotEmpty() }

    private fun loadLocal(): PromoFeed {
        val cached = runCatching { feedFile.takeIf { it.exists() }?.readText() }.getOrNull()
        parse(cached)?.let { return it }
        val bundled = runCatching { context.assets.open(config.bundledAsset).bufferedReader().use { it.readText() } }.getOrNull()
        return parse(bundled) ?: PromoFeed()
    }

    /** Tải lại danh sách nếu đã quá `refreshHours`. Gọi lúc mở app. */
    fun refreshIfStale(scope: CoroutineScope, force: Boolean = false) {
        val last = prefs.getLong(KEY_FETCHED_AT, 0L)
        if (!force && System.currentTimeMillis() - last < config.refreshHours * HOUR_MS) return
        scope.launch(Dispatchers.IO) {
            val raw = httpGet(config.feedUrl) ?: return@launch
            val feed = parse(raw) ?: return@launch
            runCatching { feedFile.writeText(raw) }
            prefs.edit().putLong(KEY_FETCHED_AT, System.currentTimeMillis()).apply()
            _feed.value = feed
        }
    }

    /** App có cài trên máy không (cần khai báo package trong <queries> của manifest, Android 11+). */
    fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun hiddenNow(): Set<String> {
        val now = System.currentTimeMillis()
        return prefs.all.filterKeys { it.startsWith(KEY_HIDE) }
            .filterValues { (it as? Long ?: 0L) > now }
            .keys.map { it.removePrefix(KEY_HIDE) }.toSet()
    }

    /** Danh sách app để hiện trong mục "Ứng dụng khác" (đã lọc app đã cài). */
    fun candidates(feed: PromoFeed = this.feed.value): List<PromoApp> {
        if (!enabled) return emptyList()
        return XPromoPicker.rank(
            feed, config.selfPackage, config.hostGroups, Locale.getDefault().country,
            isInstalled = ::isInstalled, hidden = hiddenNow(),
        )
    }

    /**
     * Các app được chỉ định (theo thứ tự `ids`) mà còn đủ điều kiện quảng bá:
     * đang bật, chưa cài, user chưa ẩn, đúng quốc gia. Dùng cho gợi ý đúng ngữ cảnh.
     */
    fun targets(ids: List<String>, feed: PromoFeed = this.feed.value): List<PromoApp> {
        val ok = candidates(feed).associateBy { it.id }
        return ids.mapNotNull { ok[it] }
    }

    /** App đầu tiên còn đủ điều kiện trong `ids`, hoặc null. */
    fun target(ids: List<String>, feed: PromoFeed = this.feed.value): PromoApp? = targets(ids, feed).firstOrNull()

    /** Ghi nhận thẻ gợi ý theo ngữ cảnh đã hiện (không tính vào giới hạn tần suất của XPromoCard). */
    fun onTargetShown(app: PromoApp, placement: String) = log("xpromo_view", app, placement)

    /** App cho thẻ gợi ý theo ngữ cảnh, hoặc null nếu chưa tới lượt (giới hạn tần suất). */
    fun nextCard(feed: PromoFeed = this.feed.value): PromoApp? {
        if (!enabled) return null
        val last = prefs.getLong(KEY_CARD_AT, 0L)
        if (System.currentTimeMillis() - last < config.cardMinIntervalDays * DAY_MS) {
            // Trong cùng "lượt" vẫn trả lại app đã hiện để thẻ không biến mất khi recompose/quay lại màn.
            val current = prefs.getString(KEY_CARD_APP, null) ?: return null
            return candidates(feed).firstOrNull { it.id == current }
        }
        return XPromoPicker.pickForCard(candidates(feed), prefs.getInt(KEY_CARD_COUNT, 0))
    }

    /** Ghi nhận thẻ đã hiện (bắt đầu tính khoảng cách tần suất). */
    fun onCardShown(app: PromoApp, placement: String) {
        if (prefs.getString(KEY_CARD_APP, null) == app.id &&
            System.currentTimeMillis() - prefs.getLong(KEY_CARD_AT, 0L) < config.cardMinIntervalDays * DAY_MS
        ) return
        prefs.edit()
            .putLong(KEY_CARD_AT, System.currentTimeMillis())
            .putString(KEY_CARD_APP, app.id)
            .putInt(KEY_CARD_COUNT, prefs.getInt(KEY_CARD_COUNT, 0) + 1)
            .apply()
        log("xpromo_view", app, placement)
    }

    fun onListShown(apps: List<PromoApp>, placement: String) {
        if (apps.isEmpty()) return
        config.analytics("xpromo_list_view", mapOf("placement" to placement, "count" to apps.size, "source" to config.selfPackage))
    }

    /** User bấm ✕ trên thẻ → ẩn app đó `hideDays` ngày. */
    fun dismiss(app: PromoApp, placement: String) {
        prefs.edit()
            .putLong(KEY_HIDE + app.id, System.currentTimeMillis() + config.hideDays * DAY_MS)
            .remove(KEY_CARD_APP)
            .apply()
        log("xpromo_dismiss", app, placement)
    }

    /** Mở trang Google Play của app (kèm UTM). */
    fun open(activityContext: Context, app: PromoApp, placement: String) {
        log("xpromo_click", app, placement)
        val market = Intent(Intent.ACTION_VIEW, Uri.parse(XPromoPicker.marketUri(app.id, config.selfPackage, placement)))
            .setPackage("com.android.vending")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activityContext.startActivity(market)
        } catch (e: ActivityNotFoundException) {
            runCatching {
                activityContext.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(XPromoPicker.webUri(app.id, config.selfPackage, placement)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private fun log(event: String, app: PromoApp, placement: String) = runCatching {
        config.analytics(event, mapOf("target" to app.id, "placement" to placement, "source" to config.selfPackage))
    }

    // ---------------- Icon ----------------

    private val memIcons = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun icon(url: String): ImageBitmap? {
        memIcons[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "xpromo_icons").apply { mkdirs() }
            val file = File(dir, url.hashCode().toUInt().toString() + ".png")
            if (!file.exists() || System.currentTimeMillis() - file.lastModified() > 7 * DAY_MS) {
                httpGetBytes(url)?.let { bytes -> runCatching { file.writeBytes(bytes) } }
            }
            runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
                ?.also { memIcons[url] = it }
        }
    }

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 24 * HOUR_MS
        private const val KEY_FETCHED_AT = "fetched_at"
        private const val KEY_CARD_AT = "card_at"
        private const val KEY_CARD_APP = "card_app"
        private const val KEY_CARD_COUNT = "card_count"
        private const val KEY_HIDE = "hide_"

        @Volatile
        private var instance: XPromo? = null

        /** Gọi một lần trong Application.onCreate. */
        fun init(context: Context, config: XPromoConfig): XPromo =
            instance ?: synchronized(this) {
                instance ?: XPromo(context.applicationContext, config).also { instance = it }
            }

        fun get(): XPromo? = instance

        private fun httpGetBytes(url: String): ByteArray? = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.instanceFollowRedirects = true
            try {
                if (c.responseCode !in 200..299) null else c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        }.getOrNull()

        private fun httpGet(url: String): String? = httpGetBytes(url)?.toString(Charsets.UTF_8)
    }
}

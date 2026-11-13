package com.huanhuynh.xpromo

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
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
    /** Nhóm chủ đề của app đang chạy – app cùng nhóm được ưu tiên, nhóm liên quan xếp kế tiếp. */
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
    /**
     * Ngôn ngữ app hỗ trợ (mã ISO: vi, en, zh). Phần tử đầu là mặc định khi ngôn ngữ máy không có trong danh sách.
     * Mặc định chỉ tiếng Việt → giao diện XPromo luôn tiếng Việt dù máy cài ngôn ngữ khác.
     * Cũng dùng để chỉ gợi ý app đích có cùng ngôn ngữ (xem `PromoApp.languages`).
     */
    val languages: List<String> = listOf("vi"),
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

    // Phải khai báo trước _feed: thuộc tính được khởi tạo theo thứ tự khai báo, _feed gọi loadLocal() ngay.
    private val bundledFeed: PromoFeed? by lazy {
        parse(runCatching { context.assets.open(config.bundledAsset).bufferedReader().use { it.readText() } }.getOrNull())
    }

    private val _feed = MutableStateFlow(loadLocal())
    val feed: StateFlow<PromoFeed> = _feed.asStateFlow()

    /**
     * Tăng mỗi khi app quay lại màn hình (onResume) – UI dùng làm khoá để đọc lại trạng thái "đã cài".
     * Trước 1.0.8 trạng thái chỉ đọc một lần: user bấm "Cài", cài xong quay lại vẫn thấy "Cài".
     */
    private val _installTick = MutableStateFlow(0)
    val installTick: StateFlow<Int> = _installTick.asStateFlow()

    val enabled: Boolean get() = runCatching { config.enabled() }.getOrDefault(true)

    init {
        (context as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { refreshInstalled() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Đọc lại trạng thái cài của các app (tự gọi khi app quay lại màn hình). */
    fun refreshInstalled() {
        _installTick.value = _installTick.value + 1
    }

    private fun parse(raw: String?): PromoFeed? =
        raw?.let { runCatching { json.decodeFromString(PromoFeed.serializer(), it) }.getOrNull() }
            ?.takeIf { it.apps.isNotEmpty() }

    /** Lấy bản mới hơn (theo `version`) giữa bản tải về và bản đóng gói sẵn trong thư viện. */
    private fun newest(a: PromoFeed?, b: PromoFeed?): PromoFeed? = when {
        a == null -> b
        b == null -> a
        b.version > a.version -> b
        else -> a
    }

    private fun loadLocal(): PromoFeed {
        val cached = parse(runCatching { feedFile.takeIf { it.exists() }?.readText() }.getOrNull())
        return newest(cached, bundledFeed) ?: PromoFeed()
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
            _feed.value = newest(feed, bundledFeed) ?: feed
        }
    }

    /**
     * App có cài trên máy không. Android 11+ chỉ thấy được app khai báo trong <queries>: thư viện khai báo sẵn
     * package các app hiện có, và (từ 1.0.8) mọi app dùng thư viện đều có activity XPromoMemberActivity với
     * action [MEMBER_ACTION] → app phát hành sau này cũng tự thấy nhau, không cần cập nhật danh sách.
     */
    fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Ngôn ngữ giao diện hiện tại của app (trong `config.languages`). */
    fun currentLang(): String = XPromoTexts.pickLang(Locale.getDefault().language, config.languages)

    private fun hiddenNow(): Set<String> {
        val now = System.currentTimeMillis()
        return prefs.all.filterKeys { it.startsWith(KEY_HIDE) }
            .filterValues { (it as? Long ?: 0L) > now }
            .keys.map { it.removePrefix(KEY_HIDE) }.toSet()
    }

    private fun stats(): Map<String, PromoStats> {
        val all = prefs.all
        val ids = all.keys.filter { it.startsWith(KEY_VIEWS) || it.startsWith(KEY_CLICKS) }
            .map { it.removePrefix(KEY_VIEWS).removePrefix(KEY_CLICKS) }.toSet()
        return ids.associateWith { id ->
            PromoStats(views = (all[KEY_VIEWS + id] as? Int) ?: 0, clicks = (all[KEY_CLICKS + id] as? Int) ?: 0)
        }
    }

    /** Đếm lượt hiện, mỗi app tối đa 1 lần / 12 giờ (recompose, quay lại màn không bị tính thêm). */
    private fun countView(id: String) {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_VIEW_AT + id, 0L) < 12 * HOUR_MS) return
        prefs.edit()
            .putLong(KEY_VIEW_AT + id, now)
            .putInt(KEY_VIEWS + id, prefs.getInt(KEY_VIEWS + id, 0) + 1)
            .apply()
    }

    private fun countClick(id: String) {
        prefs.edit().putInt(KEY_CLICKS + id, prefs.getInt(KEY_CLICKS + id, 0) + 1).apply()
    }

    /**
     * Danh sách app để hiện trong mục "Ứng dụng khác". Mặc định bỏ app đã cài; app chưa cài phải có ngôn ngữ [lang]
     * (null = không lọc theo ngôn ngữ).
     */
    fun candidates(
        feed: PromoFeed = this.feed.value,
        includeInstalled: Boolean = false,
        lang: String? = currentLang(),
    ): List<PromoApp> {
        if (!enabled) return emptyList()
        return XPromoPicker.rank(
            feed, config.selfPackage, config.hostGroups, Locale.getDefault().country,
            isInstalled = ::isInstalled, hidden = hiddenNow(), includeInstalled = includeInstalled,
            lang = lang, stats = stats(),
        )
    }

    /**
     * Các app được chỉ định (theo thứ tự `ids`) mà còn đủ điều kiện quảng bá:
     * đang bật, chưa cài, user chưa ẩn, đúng quốc gia & ngôn ngữ. Dùng cho gợi ý đúng ngữ cảnh.
     */
    fun targets(
        ids: List<String>,
        feed: PromoFeed = this.feed.value,
        includeInstalled: Boolean = true,
        lang: String? = currentLang(),
    ): List<PromoApp> {
        val ok = candidates(feed, includeInstalled, lang).associateBy { it.id }
        val list = ids.mapNotNull { ok[it] }
        // Ưu tiên app chưa cài (để giới thiệu), app đã cài xếp sau (nút "Mở").
        return list.sortedBy { isInstalled(it.id) }
    }

    /** App đầu tiên còn đủ điều kiện trong `ids` (ưu tiên app chưa cài), hoặc null. */
    fun target(
        ids: List<String>,
        feed: PromoFeed = this.feed.value,
        includeInstalled: Boolean = true,
        lang: String? = currentLang(),
    ): PromoApp? = targets(ids, feed, includeInstalled, lang).firstOrNull()

    /** Ghi nhận thẻ gợi ý theo ngữ cảnh đã hiện (không tính vào giới hạn tần suất của XPromoCard). */
    fun onTargetShown(app: PromoApp, placement: String) {
        countView(app.id)
        log("xpromo_view", app, placement)
    }

    /** App cho thẻ gợi ý theo ngữ cảnh, hoặc null nếu chưa tới lượt (giới hạn tần suất). */
    fun nextCard(feed: PromoFeed = this.feed.value, lang: String? = currentLang()): PromoApp? {
        if (!enabled) return null
        val list = candidates(feed, lang = lang)
        val last = prefs.getLong(KEY_CARD_AT, 0L)
        val current = prefs.getString(KEY_CARD_APP, null)
        if (System.currentTimeMillis() - last < config.cardMinIntervalDays * DAY_MS) {
            // Trong cùng "lượt" vẫn trả lại app đã hiện để thẻ không biến mất khi recompose/quay lại màn.
            // App đó vừa được cài → không còn trong danh sách → thẻ tự ẩn tới lượt sau.
            return current?.let { id -> list.firstOrNull { it.id == id } }
        }
        return XPromoPicker.pickForCard(list, prefs.getInt(KEY_CARD_COUNT, 0), prefs.getString(KEY_CARD_LAST, null))
    }

    /** Ghi nhận thẻ đã hiện (bắt đầu tính khoảng cách tần suất). */
    fun onCardShown(app: PromoApp, placement: String) {
        if (prefs.getString(KEY_CARD_APP, null) == app.id &&
            System.currentTimeMillis() - prefs.getLong(KEY_CARD_AT, 0L) < config.cardMinIntervalDays * DAY_MS
        ) return
        prefs.edit()
            .putLong(KEY_CARD_AT, System.currentTimeMillis())
            .putString(KEY_CARD_APP, app.id)
            .putString(KEY_CARD_LAST, app.id)
            .putInt(KEY_CARD_COUNT, prefs.getInt(KEY_CARD_COUNT, 0) + 1)
            .apply()
        countView(app.id)
        log("xpromo_view", app, placement)
    }

    fun onListShown(apps: List<PromoApp>, placement: String) {
        if (apps.isEmpty()) return
        apps.filterNot { isInstalled(it.id) }.forEach { countView(it.id) }
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

    /**
     * App đã cài → mở app (nếu có [deepLink] thì mở thẳng màn đó, app đích chưa hỗ trợ link thì mở màn chính);
     * chưa cài → mở trang Google Play (kèm UTM). [deepLink] chỉ là đường dẫn cố định, không kèm dữ liệu người dùng.
     */
    fun open(activityContext: Context, app: PromoApp, placement: String, deepLink: String? = null) {
        countClick(app.id)
        if (deepLink != null && isInstalled(app.id)) {
            val link = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).setPackage(app.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching { activityContext.startActivity(link) }.isSuccess
            if (opened) {
                log("xpromo_open_deeplink", app, placement)
                return
            }
        }
        val launch = if (isInstalled(app.id)) activityContext.packageManager.getLaunchIntentForPackage(app.id) else null
        if (launch != null) {
            log("xpromo_open_app", app, placement)
            runCatching { activityContext.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onSuccess { return }
        }
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

    suspend fun icon(url: String): ImageBitmap? = icon(url, "")

    /**
     * Icon app: bộ nhớ → file cache (7 ngày) → tải [url] → icon đóng gói sẵn `assets/xpromo/icons/<id>.png`.
     * Không mạng / URL lỗi vẫn có icon thật thay vì emoji.
     */
    suspend fun icon(url: String?, id: String): ImageBitmap? {
        val key = url ?: "asset:$id"
        memIcons[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            val fromNet = url?.let { u ->
                val dir = File(context.cacheDir, "xpromo_icons").apply { mkdirs() }
                val file = File(dir, u.hashCode().toUInt().toString() + ".png")
                if (!file.exists() || System.currentTimeMillis() - file.lastModified() > 7 * DAY_MS) {
                    val bytes = httpGetBytes(u)
                    if (bytes == null) log("icon tải lỗi: $u")
                    bytes?.let { b -> runCatching { file.writeBytes(b) } }
                }
                runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
                    ?: run { file.delete(); null }
            }
            val bmp = fromNet ?: id.takeIf { it.isNotEmpty() }?.let { bundledIcon(it) }
            bmp?.also { if (fromNet != null) memIcons[key] = it }
        }
    }

    private fun bundledIcon(id: String): ImageBitmap? = runCatching {
        context.assets.open("xpromo/icons/$id.png").use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
    }.getOrNull()

    private fun log(msg: String) {
        if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            android.util.Log.d("XPromo", msg)
        }
    }

    companion object {
        /** Action chung mà mọi app dùng XPromo khai báo (XPromoMemberActivity) để các app thấy nhau trên Android 11+. */
        const val MEMBER_ACTION = "com.huanhuynh.xpromo.MEMBER"

        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 24 * HOUR_MS
        private const val KEY_FETCHED_AT = "fetched_at"
        private const val KEY_CARD_AT = "card_at"
        private const val KEY_CARD_APP = "card_app"
        private const val KEY_CARD_LAST = "card_last"
        private const val KEY_CARD_COUNT = "card_count"
        private const val KEY_HIDE = "hide_"
        private const val KEY_VIEWS = "views_"
        private const val KEY_VIEW_AT = "viewat_"
        private const val KEY_CLICKS = "clicks_"

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

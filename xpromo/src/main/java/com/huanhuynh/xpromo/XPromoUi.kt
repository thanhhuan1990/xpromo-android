package com.huanhuynh.xpromo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Icon app: tải từ URL (có cache), chưa có thì hiện emoji. */
@Composable
fun XPromoIcon(xpromo: XPromo, app: PromoApp, size: Dp = 44.dp) {
    val bmp by produceState<ImageBitmap?>(initialValue = null, app.icon) {
        value = app.icon?.let { xpromo.icon(it) }
    }
    val shape = RoundedCornerShape(size * 0.24f)
    Box(
        Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        else Text(app.emoji, fontSize = (size.value * 0.5f).sp)
    }
}

/**
 * Mục "Ứng dụng khác của chúng tôi" (đặt trong Cài đặt / Giới thiệu). Chỉ hiện app chưa cài.
 * Không hiện gì nếu danh sách rỗng hoặc bị tắt từ xa.
 */
@Composable
fun MoreAppsSection(
    xpromo: XPromo,
    modifier: Modifier = Modifier,
    placement: String = "settings",
    title: String = "Ứng dụng khác của chúng tôi",
    max: Int = 4,
    /** Chỉ hiện các app này (theo thứ tự). null = tất cả, xếp theo nhóm chủ đề. */
    ids: List<String>? = null,
) {
    val feed by xpromo.feed.collectAsState()
    val apps = remember(feed, ids) { (if (ids != null) xpromo.targets(ids, feed) else xpromo.candidates(feed)).take(max) }
    if (apps.isEmpty()) return
    val context = LocalContext.current
    LaunchedEffect(apps.map { it.id }) { xpromo.onListShown(apps, placement) }
    Column(modifier) {
        Text(
            title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
                apps.forEachIndexed { i, app ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().clickable { xpromo.open(context, app, placement) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        XPromoIcon(xpromo, app, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                app.tagline, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { xpromo.open(context, app, placement) }) { Text("Cài") }
                    }
                }
            }
        }
    }
}

/**
 * Thẻ gợi ý theo ngữ cảnh (đặt ở "điểm nghỉ" như màn trống, sau khi hoàn thành việc).
 * Tự giới hạn tần suất (mặc định ≥ 3 ngày/lần), có nút ✕ để ẩn app đó 30 ngày. Ghi rõ là app cùng nhà phát triển.
 */
@Composable
fun XPromoCard(
    xpromo: XPromo,
    placement: String,
    modifier: Modifier = Modifier,
    label: String = "Ứng dụng cùng nhà phát triển",
) {
    val feed by xpromo.feed.collectAsState()
    var hidden by remember { mutableStateOf(false) }
    val app = remember(feed) { xpromo.nextCard(feed) }
    if (app == null || hidden) return
    val context = LocalContext.current
    LaunchedEffect(app.id) { xpromo.onCardShown(app, placement) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { hidden = true; xpromo.dismiss(app, placement) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Ẩn gợi ý", modifier = Modifier.size(18.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 10.dp)) {
                XPromoIcon(xpromo, app, 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        app.tagline, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { xpromo.open(context, app, placement) }) { Text("Xem") }
            }
        }
    }
}

/**
 * Thẻ gợi ý một app cụ thể theo ngữ cảnh, kèm câu dẫn riêng.
 * `targets`: danh sách (package, câu dẫn) theo thứ tự ưu tiên – hiện app đầu tiên còn đủ điều kiện
 * (đang bật, chưa cài, chưa bị ẩn). Không giới hạn tần suất; nút ✕ ẩn app đó `hideDays` ngày.
 */
@Composable
fun XPromoTargetCard(
    xpromo: XPromo,
    targets: List<Pair<String, String>>,
    placement: String,
    modifier: Modifier = Modifier,
    label: String = "Ứng dụng cùng nhà phát triển",
    button: String = "Xem",
) {
    val feed by xpromo.feed.collectAsState()
    var dismissed by remember { mutableStateOf(setOf<String>()) }
    val ids = targets.map { it.first }.filter { it !in dismissed }
    val app = remember(feed, ids) { xpromo.target(ids, feed) } ?: return
    val headline = targets.firstOrNull { it.first == app.id }?.second.orEmpty()
    val context = LocalContext.current
    LaunchedEffect(app.id, placement) { xpromo.onTargetShown(app, placement) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { dismissed = dismissed + app.id; xpromo.dismiss(app, placement) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Ẩn gợi ý", modifier = Modifier.size(18.dp))
                }
            }
            if (headline.isNotBlank()) {
                Text(
                    headline, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 10.dp, bottom = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 10.dp)) {
                XPromoIcon(xpromo, app, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(app.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        app.tagline, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { xpromo.open(context, app, placement) }) { Text(button) }
            }
        }
    }
}

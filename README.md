# xpromo-android

Thư viện quảng bá chéo các app Android của HuanHuynh (Kotlin + Jetpack Compose).

- Mục **"Ứng dụng khác của chúng tôi"** (`MoreAppsSection`): tối đa 4 app user chưa cài.
- **Thẻ gợi ý** (`XPromoCard`): tự giới hạn tần suất (mặc định 3 ngày/lần), có nút ✕ để ẩn app đó 30 ngày.
- Danh sách app tải từ `https://thanhhuan1990.github.io/xpromo/apps.json`, lưu đệm 24 giờ. Không có mạng thì dùng bản dự phòng nằm trong thư viện.
- Mở Google Play kèm UTM (`utm_source=<app nguồn>&utm_medium=xpromo&utm_campaign=<vị trí>`).
- Thư viện có sẵn `<queries>` cho mọi app trong danh sách. Phần này tự gộp vào manifest của app khi build.

## Dùng trong app

`settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") { content { includeGroup("com.github.thanhhuan1990") } }
    }
}
```

`app/build.gradle.kts`:
```kotlin
implementation("com.github.thanhhuan1990:xpromo-android:1.0.6")
```

`Application.onCreate` hoặc DI container:
```kotlin
val xpromo = XPromo.init(
    app,
    XPromoConfig(
        selfPackage = app.packageName,
        hostGroups = listOf("household", "health"),   // nhóm chủ đề của app này → ưu tiên app cùng nhóm
        enabled = { remoteConfigBool("xpromo_enabled") }, // tuỳ chọn: bật/tắt từ xa
        analytics = { name, params -> analyticsLog(name, params) }, // tuỳ chọn: xpromo_view/click/dismiss/list_view
    ),
)
xpromo.refreshIfStale(appScope)
```

UI:
```kotlin
XPromo.get()?.let { MoreAppsSection(it, placement = "settings") }        // trong Cài đặt
XPromo.get()?.let { XPromoCard(it, placement = "alerts_all_fresh") }     // ở một "điểm nghỉ", không chen vào luồng chính

// 1.0.2 – gợi ý đúng app theo ngữ cảnh (app đầu tiên còn bật & chưa cài; câu dẫn riêng):
XPromo.get()?.let {
    XPromoTargetCard(it, listOf(
        "com.huanhuynh.lichvannien" to "Xem ngày tốt để bắt đầu việc này",
        "com.huanhuynh.vankhan" to "Văn khấn đầy đủ cho mọi lễ",
    ), placement = "lot_result")
}
XPromo.get()?.let { MoreAppsSection(it, placement = "tet", title = "Chuẩn bị Tết", ids = listOf("com.huanhuynh.lichvannien", "com.huanhuynh.vankhan")) }
```

Changelog: 1.0.6 – thêm Cờ Tướng Có Thầy, Cờ Vây Có Thầy (`<queries>`, icon, danh sách đóng gói sẵn); tiếng Nhật: `languages` có thể gồm `"ja"`, feed có `i18n.ja`; nhóm chủ đề mới `games`. 1.0.5 – đa ngôn ngữ: `XPromoConfig(languages = listOf("vi", "en", "zh"))` (mặc định chỉ "vi" → app tiếng Việt không đổi), chữ cố định (Mở/Cài/Xem…) theo ngôn ngữ đang hiển thị của app; feed có `i18n.{en,zh}.name/tagline` (thiếu thì dùng bản tiếng Việt). 1.0.4 – icon đóng gói sẵn trong thư viện (dùng khi tải URL lỗi/không mạng), nền icon trung tính thay vì màu theme; log debug khi tải icon lỗi. 1.0.3 – app đã cài hiện nút "Mở" (mở thẳng app) trong `XPromoTargetCard` và `MoreAppsSection` (`showInstalled`), `XPromoCard` vẫn chỉ gợi ý app chưa cài; dùng bản danh sách mới hơn (theo `version`) giữa bản tải về và bản đóng gói sẵn. 1.0.2 – `XPromoTargetCard`, `MoreAppsSection(ids=…)`, `XPromo.targets()/target()`; thêm `<queries>` vankhan, xinxam. 1.0.1 – minSdk 24.

Nhóm chủ đề đang dùng: `spiritual`, `household`, `health`, `finance`, `daily`, `fun`, `games`.

## Việc thường gặp

| Việc | Làm ở đâu | Cần ra bản app mới? |
|---|---|---|
| Đổi tên, mô tả, icon, `priority`; bật/tắt một app | `apps.json` trong repo `thanhhuan1990.github.io` | Không. App tự cập nhật trong ≤ 24 giờ |
| Thêm app mới | 1) `apps.json` trên github.io và icon 192 px `xpromo/icons/<package>.png`<br>2) Repo này: thêm `<package>` vào `AndroidManifest.xml`, chép `apps.json` vào `assets/xpromo/`, gắn tag phiên bản mới | Không bắt buộc. App mới vẫn hiện ngay; chỉ phần lọc "đã cài" cần bản app dùng thư viện mới |
| Sửa lỗi hoặc đổi giao diện module | Repo này: sửa, chạy test, gắn tag mới | Có: mỗi app đổi số phiên bản thư viện ở lần phát hành kế tiếp |

Nên khai báo trước `<package>` cho các app sắp làm, kể cả khi chưa phát hành.

## Phát hành phiên bản

```bash
./gradlew :xpromo:testDebugUnitTest
git tag 1.0.5 && git push origin main --tags
```
JitPack build tự động ở lần đầu có người tải tag đó. Xem trạng thái tại https://jitpack.io/#thanhhuan1990/xpromo-android. Repo cần để **public** để dùng JitPack miễn phí.

Nếu Gradle không tìm thấy `com.github.thanhhuan1990:xpromo-android`, dùng toạ độ đa module của JitPack: `com.github.thanhhuan1990.xpromo-android:xpromo:<tag>`.

## Phát triển cùng lúc với app (không cần tag)

Trong app, chạy với `-PxpromoLocal=true` (hoặc thêm `xpromoLocal=true` vào `gradle.properties` trên máy). `settings.gradle.kts` của app sẽ dùng thẳng source `../xpromo-android` thay cho bản JitPack.

## App Flutter

Thư viện này chỉ cho Kotlin/Compose. Các app Flutter cần một package Dart riêng đọc cùng `apps.json`.

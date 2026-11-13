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
implementation("com.github.thanhhuan1990:xpromo-android:1.0.9")
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
// 1.0.7 – app đích đã cài thì mở thẳng một màn bằng deep link (chỉ đường dẫn cố định, không gửi dữ liệu người dùng):
XPromo.get()?.let {
    XPromoTargetCard(it, listOf("com.huanhuynh.numerology" to "Đang nghĩ tên cho bé? Thử tên theo thần số học"),
        placement = "pregnancy_name", deepLinks = mapOf("com.huanhuynh.numerology" to "thansohoc://app/dat-ten-con"))
}
XPromo.get()?.let { MoreAppsSection(it, placement = "tet", title = "Chuẩn bị Tết", ids = listOf("com.huanhuynh.lichvannien", "com.huanhuynh.vankhan")) }
```

Changelog: 1.0.9 – `MoreAppsSection` có nút **"Xem thêm N ứng dụng"**: hiện sẵn nhóm 1 (app chung nhóm chủ đề, tối đa `max`), bấm để mở rộng ra nhóm 2 (app nhóm liên quan / nhóm khác / đã cài, tổng tối đa `maxExpanded` = 12), bấm lại "Thu gọn"; tắt bằng `expandable = false`; event `xpromo_list_expand`; `XPromoPicker.splitForList()`. 1.0.8 – **sửa lỗi hiện "Cài" cho app đã cài**: (1) trạng thái đã cài được đọc lại mỗi khi app quay lại màn hình (trước đây chỉ đọc một lần, cài xong quay lại vẫn thấy "Cài"); (2) mọi app dùng thư viện có `XPromoMemberActivity` (action `com.huanhuynh.xpromo.MEMBER`) và thư viện khai báo `<queries>` theo action đó → các app luôn thấy nhau trên Android 11+, kể cả app phát hành sau (không còn phụ thuộc danh sách `<package>`); thêm `<package>` Từ Điển. **Chọn app theo nhóm tốt hơn**: chung nhóm +2 điểm, nhóm liên quan +1 (`XPromoPicker.RELATED`); app chưa cài chỉ gợi ý khi có ngôn ngữ đang dùng (`languages` trong feed – không gợi ý app chỉ có tiếng Việt cho người dùng tiếng Anh); app đã hiện ≥ 5 lần mà chưa được bấm xếp sau; thẻ gợi ý không lặp lại app vừa hiện. Nhóm mới `education`. 1.0.7 – `XPromoTargetCard(deepLinks = …)` / `XPromo.open(…, deepLink)`: app đích đã cài thì mở thẳng màn được chỉ định (app đích chưa hỗ trợ link → mở màn chính), event `xpromo_open_deeplink`; khai báo trước `<queries>` Cờ Vua Có Thầy, Cờ Tỷ Phú, Xếp Gạch 9999. 1.0.6 – thêm Cờ Tướng Có Thầy, Cờ Vây Có Thầy (`<queries>`, icon, danh sách đóng gói sẵn); tiếng Nhật: `languages` có thể gồm `"ja"`, feed có `i18n.ja`; nhóm chủ đề mới `games`. 1.0.5 – đa ngôn ngữ: `XPromoConfig(languages = listOf("vi", "en", "zh"))` (mặc định chỉ "vi" → app tiếng Việt không đổi), chữ cố định (Mở/Cài/Xem…) theo ngôn ngữ đang hiển thị của app; feed có `i18n.{en,zh}.name/tagline` (thiếu thì dùng bản tiếng Việt). 1.0.4 – icon đóng gói sẵn trong thư viện (dùng khi tải URL lỗi/không mạng), nền icon trung tính thay vì màu theme; log debug khi tải icon lỗi. 1.0.3 – app đã cài hiện nút "Mở" (mở thẳng app) trong `XPromoTargetCard` và `MoreAppsSection` (`showInstalled`), `XPromoCard` vẫn chỉ gợi ý app chưa cài; dùng bản danh sách mới hơn (theo `version`) giữa bản tải về và bản đóng gói sẵn. 1.0.2 – `XPromoTargetCard`, `MoreAppsSection(ids=…)`, `XPromo.targets()/target()`; thêm `<queries>` vankhan, xinxam. 1.0.1 – minSdk 24.

Nhóm chủ đề đang dùng: `spiritual`, `household`, `health`, `finance`, `daily`, `education`, `fun`, `games`. Nhóm liên quan nhau xem `XPromoPicker.RELATED`.

Trường `languages` của mỗi app trong `apps.json`: ngôn ngữ giao diện app đó có (`["vi"]`, `["vi","en","zh"]`…; bỏ trống = mọi ngôn ngữ). App nguồn đang hiển thị ngôn ngữ nào thì chỉ gợi ý app chưa cài có ngôn ngữ đó (app đã cài vẫn hiện nút "Mở").

## Việc thường gặp

| Việc | Làm ở đâu | Cần ra bản app mới? |
|---|---|---|
| Đổi tên, mô tả, icon, `priority`; bật/tắt một app | `apps.json` trong repo `thanhhuan1990.github.io` | Không. App tự cập nhật trong ≤ 24 giờ |
| Thêm app mới | 1) `apps.json` trên github.io (nhớ `groups`, `languages`) và icon 192 px `xpromo/icons/<package>.png`<br>2) App mới dùng XPromo ≥ 1.0.8 → các app khác (≥ 1.0.8) tự nhận ra khi đã cài, không cần sửa thư viện<br>3) Nên vẫn thêm `<package>` vào `AndroidManifest.xml` + chép `apps.json` vào `assets/xpromo/` ở lần ra tag kế tiếp (cho app dùng thư viện cũ) | Không |
| Sửa lỗi hoặc đổi giao diện module | Repo này: sửa, chạy test, gắn tag mới | Có: mỗi app đổi số phiên bản thư viện ở lần phát hành kế tiếp |

Nên khai báo trước `<package>` cho các app sắp làm, kể cả khi chưa phát hành.

## Phát hành phiên bản

```bash
./gradlew :xpromo:testDebugUnitTest
git tag 1.0.9 && git push origin main --tags
```
JitPack build tự động ở lần đầu có người tải tag đó. Xem trạng thái tại https://jitpack.io/#thanhhuan1990/xpromo-android. Repo cần để **public** để dùng JitPack miễn phí.

Nếu Gradle không tìm thấy `com.github.thanhhuan1990:xpromo-android`, dùng toạ độ đa module của JitPack: `com.github.thanhhuan1990.xpromo-android:xpromo:<tag>`.

## Phát triển cùng lúc với app (không cần tag)

Trong app, chạy với `-PxpromoLocal=true` (hoặc thêm `xpromoLocal=true` vào `gradle.properties` trên máy). `settings.gradle.kts` của app sẽ dùng thẳng source `../xpromo-android` thay cho bản JitPack.

## App Flutter

Thư viện này chỉ cho Kotlin/Compose. Các app Flutter cần một package Dart riêng đọc cùng `apps.json`.

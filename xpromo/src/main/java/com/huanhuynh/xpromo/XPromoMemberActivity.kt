package com.huanhuynh.xpromo

import android.app.Activity
import android.os.Bundle

/**
 * Activity rỗng, không giao diện, tự đóng ngay. Chỉ để khai báo intent-filter [XPromo.MEMBER_ACTION]:
 * mọi app dùng XPromo có activity này, và thư viện khai báo <queries> theo action đó → trên Android 11+
 * các app của nhà phát triển luôn "thấy" nhau (biết app nào đã cài để hiện nút "Mở"), kể cả app phát hành
 * sau phiên bản thư viện. Không đọc dữ liệu gì, mở từ bên ngoài cũng chỉ đóng lại.
 */
class XPromoMemberActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}

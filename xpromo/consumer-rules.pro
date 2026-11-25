# kotlinx.serialization: giữ serializer của model feed (thư viện serialization đã có rule chung, đây là dự phòng).
-keepclassmembers class com.huanhuynh.xpromo.** {
    *** Companion;
}
-keepclasseswithmembers class com.huanhuynh.xpromo.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.huanhuynh.xpromo.**$$serializer { *; }

# Icon: giữ phần tải mạng và phần decode ở hai lớp riêng (không gộp/inline vào cùng một hàm).
-keep,allowobfuscation class com.huanhuynh.xpromo.IconDownloader { *; }
-keep,allowobfuscation class com.huanhuynh.xpromo.IconDecoder { *; }

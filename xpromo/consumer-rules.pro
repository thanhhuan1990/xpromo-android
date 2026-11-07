# kotlinx.serialization: giữ serializer của model feed (thư viện serialization đã có rule chung, đây là dự phòng).
-keepclassmembers class com.huanhuynh.xpromo.** {
    *** Companion;
}
-keepclasseswithmembers class com.huanhuynh.xpromo.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.huanhuynh.xpromo.**$$serializer { *; }

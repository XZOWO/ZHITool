# Compose / Kotlin 元数据
-keep class kotlin.Metadata { *; }

# 应用入口
-keep class com.zhitool.rearlyric.ZhiApplication { *; }
-keep class com.zhitool.rearlyric.ui.MainActivity { *; }
-keep class com.zhitool.rearlyric.rear.RearLyricActivity { *; }
-keep class com.zhitool.rearlyric.lyric.LyricService { *; }

# libxposed-api / libxposed-service
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class io.github.libxposed.service.XposedProvider { *; }

# Xposed hook 类（被 ModuleEntry 引用；保留以便崩溃日志可读、反射访问安全）
-keep class com.zhitool.rearlyric.xposed.** { *; }

# DexKit：native 方法绑定，整体保留（叠加其自带 consumer proguard）
-keep class org.luckypray.dexkit.** { *; }
-keepclasseswithmembers,includedescriptorclasses class org.luckypray.dexkit.** {
    native <methods>;
}
-dontwarn org.luckypray.dexkit.**

# Lyricon（词幕生态）模型/订阅：经 AIDL 与反射/序列化使用，整体保留
-keep class io.github.proify.** { *; }
-keepclassmembers class io.github.proify.** { *; }

# miuix / kyant backdrop
-dontwarn top.yukonga.miuix.**
-dontwarn com.kyant.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class io.github.proify.** {
    kotlinx.serialization.KSerializer serializer(...);
}

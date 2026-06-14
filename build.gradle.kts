plugins {
    // AGP 9.0+ 自带 Kotlin，无需再单独应用 kotlin.android 插件
    id("com.android.application") version "9.1.1" apply false
    id("com.android.library") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

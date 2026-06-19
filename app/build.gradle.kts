import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    // AGP 9 内置 Kotlin；仅需 compose 编译器插件
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.zhitool.rearlyric"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zhitool.rearlyric"
        minSdk = 27
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"

        // 设备为 arm64（Xiaomi 17 系列 / HyperOS 全 64 位）；只打包 arm64 的 DexKit native，
        // 既精简体积，hook 进程（system_server / subscreencenter 均 64 位）也能正确加载。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // DexKit 的 libdexkit.so 需在 hook 进程里通过模块 classloader 的 native 路径
        // 被 System.loadLibrary 找到，强制提取到真实 lib 目录最稳。
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.05.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // REAREye 同款 UI 栈：miuix(MIUI 设计系统) + kyant backdrop/capsule(液态玻璃)
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.0")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.0")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.0")
    implementation("io.github.kyant0:backdrop:1.0.6")
    implementation("io.github.kyant0:capsule:2.1.3")
    implementation("com.composables:icons-material-symbols-rounded-cmp:2.2.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("io.github.libxposed:service:101.0.0")
    compileOnly("io.github.libxposed:api:101.0.1")

    // DexKit：在 com.xiaomi.subscreencenter（混淆）中按字符串特征定位
    // 「息屏返回桌面」方法/前台包字段，用于背屏保活 hook。
    implementation("org.luckypray:dexkit:2.2.0")

    // 歌词数据源：Lyricon（词幕生态）订阅端
    implementation("io.github.proify.lyricon:subscriber:0.1.70")
    implementation("io.github.proify.lyricon:central:0.1.70")

    // 歌词模型（Song/RichLyricLine/LyricWord）：渲染走自绘 FullLyricView/RearLyricRenderView。
    implementation("io.github.proify.lyricon.lyric:model:0.1.70")
}

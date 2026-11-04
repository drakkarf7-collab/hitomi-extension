import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- Extension metadata -------------------------------------------------
val extName = "Hitomi & nhentai"
val pkgName = "eu.kanade.tachiyomi.extension.all.hitomi"
val extVersionCode = 4
val libVersion = "1.4" // most compatible extension API (Mihon, Tachimanga, forks)
val extVersionName = "$libVersion.$extVersionCode"
// -------------------------------------------------------------------------

android {
    namespace = pkgName
    compileSdk = 34

    defaultConfig {
        applicationId = pkgName
        minSdk = 21
        targetSdk = 34
        versionCode = extVersionCode
        versionName = extVersionName
        base.archivesName.set("tachiyomi-all.hitomi-v$extVersionName")
        manifestPlaceholders["appName"] = "Tachiyomi: $extName"
        manifestPlaceholders["extClass"] = ".HitomiFactory"
        manifestPlaceholders["nsfw"] = 1
    }

    signingConfigs {
        create("release") {
            // CI can override these with GitHub secrets; see README.
            storeFile = file(System.getenv("KEY_STORE_FILE") ?: "../signingkey.jks")
            storePassword = System.getenv("KEY_STORE_PASSWORD") ?: "hitomi-ext"
            keyAlias = System.getenv("KEY_ALIAS") ?: "hitomi"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "hitomi-ext"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")

    buildFeatures {
        buildConfig = false
        resValues = false
        shaders = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        // Only use stdlib APIs that every host app ships.
        apiVersion.set(KotlinVersion.KOTLIN_1_8)
        freeCompilerArgs.add("-Xno-param-assertions")
        freeCompilerArgs.add("-Xno-call-assertions")
        freeCompilerArgs.add("-Xno-receiver-assertions")
    }
}

dependencies {
    // Everything is provided by the host app at runtime.
    compileOnly("com.github.keiyoushi:extensions-lib:v1.4.2.1")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:1.8.22")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    compileOnly("com.squareup.okhttp3:okhttp:5.0.0-alpha.11")
    compileOnly("io.reactivex:rxjava:1.3.8")
    compileOnly("org.jsoup:jsoup:1.15.3")
    compileOnly("com.github.null2264.injekt:injekt-core:4135455a2a")
}

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.batareya16.miWearBridge"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.batareya16.miWearBridge"
        minSdk = 26
        targetSdk = 33
        versionCode = 3
        versionName = "2.1"
    }

    signingConfigs {
        create("release") {
            // The module signature is irrelevant to LSPosed, so by default we sign the
            // release with the standard Android debug keystore (so the APK is installable).
            // Override in ~/.gradle/gradle.properties with:
            //   RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD
            val home = System.getProperty("user.home")
            storeFile = file((findProperty("RELEASE_STORE_FILE") as String?) ?: "$home/.android/debug.keystore")
            storePassword = (findProperty("RELEASE_STORE_PASSWORD") as String?) ?: "android"
            keyAlias = (findProperty("RELEASE_KEY_ALIAS") as String?) ?: "androiddebugkey"
            keyPassword = (findProperty("RELEASE_KEY_PASSWORD") as String?) ?: "android"
        }
    }

    buildTypes {
        named("release") {
            // Keep minify off: this is a small Xposed module and R8 can break the hooks
            // (reflective class/method lookups). Re-enable only with proper keep rules.
            isMinifyEnabled = false
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        named("debug") {
            versionNameSuffix = "-debug-" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now())
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_1_8.toString()
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("com.github.kyuubiran:EzXHelper:2.2.1")
    implementation("org.luckypray:dexkit:2.0.7")
}
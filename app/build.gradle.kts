plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.garan.tesnav"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.garan.tesnav"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        manifestPlaceholders["AMAP_API_KEY"] =
            providers.gradleProperty("AMAP_API_KEY").orNull ?: ""
        buildConfigField("boolean", "EXPORT_ENABLED", providers.gradleProperty("EXPORT_ENABLED").orNull ?: "true")
        buildConfigField("String", "WEBSOCKET_URL", "\"${providers.gradleProperty("WEBSOCKET_URL").orNull ?: "ws://192.168.53.232:7766/amap-navigation"}\"")
        buildConfigField("String", "API_TOKEN", "\"${providers.gradleProperty("API_TOKEN").orNull ?: ""}\"")
        buildConfigField("long", "EXPORT_INTERVAL_MS", "${providers.gradleProperty("EXPORT_INTERVAL_MS").orNull ?: "200"}L")
        buildConfigField("String", "HOME_ASSISTANT_URL", "\"${providers.gradleProperty("HOME_ASSISTANT_URL").orNull ?: ""}\"")
        buildConfigField("String", "HOME_ASSISTANT_TOKEN", "\"${providers.gradleProperty("HOME_ASSISTANT_TOKEN").orNull ?: ""}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.amap.navi)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation(libs.okhttp)
}

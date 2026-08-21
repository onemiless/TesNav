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
}

dependencies {
    implementation(libs.amap.navi)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val teamPreview = providers.gradleProperty("teamPreview").orNull == "true"

android {
    namespace = "com.example.lightrhythm"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.lightrhythm"
        manifestPlaceholders["launcherLabel"] = "@string/app_name"
        minSdk = 26
        targetSdk = 37
        // CI builds increase monotonically so phones can install them as updates.
        versionCode = providers.gradleProperty("ciVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("ciVersionName").orNull ?: "1.0"
        val defaultUpdateUrl = if (teamPreview) {
            "https://raw.githubusercontent.com/boxintan/LightRhythm/team-preview-download/update.json"
        } else {
            "https://github.com/boxintan/LightRhythm/releases/latest/download/update.json"
        }
        val updateUrl = providers.gradleProperty("updateMetadataUrl").orNull ?: defaultUpdateUrl
        require(updateUrl.startsWith("https://") && updateUrl.none { it == '"' || it == '\\' || it.isWhitespace() })
        buildConfigField("String", "UPDATE_METADATA_URL", "\"$updateUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            providers.environmentVariable("LIGHTRHYTHM_DEBUG_KEYSTORE_PATH").orNull?.let {
                storeFile = file(it)
            }
        }
    }

    buildTypes {
        debug {
            when {
                teamPreview -> {
                    applicationIdSuffix = ".preview"
                    manifestPlaceholders["launcherLabel"] = "光律预览版"
                    // The preview publishing job signs the APK after checking its package name.
                    signingConfig = null
                }
                providers.gradleProperty("detectionVerification").orNull == "true" -> {
                    applicationIdSuffix = ".detectiontest"
                    manifestPlaceholders["launcherLabel"] = "光律联调版"
                }
            }
        }
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.danielphingston.metro"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.danielphingston.metro"
        // Android 12: covers ~90% of active devices while keeping the API surface modern.
        // Newer APIs (themed icons on 13+) are used behind runtime checks.
        minSdk = 31
        targetSdk = 36
        // CI passes the tag (e.g. 0.2.0) and the run number; local builds use the defaults.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "0.2.0"
        // Lets tapping the version text in settings 7 times unlock paid packs without Play Billing,
        // for testing before the in-app products exist. MUST be false for any release sold with
        // real billing, or buyers' friends can skip the purchase. Debug builds are always unlocked.
        buildConfigField("boolean", "TEST_UNLOCK", "true")
    }

    signingConfigs {
        // Release signing comes from the environment (CI secrets). Without it, release builds
        // are signed with the debug key: installable for testing, not accepted by Google Play.
        System.getenv("METRO_KEYSTORE_PATH")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("METRO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("METRO_KEY_ALIAS")
                keyPassword = System.getenv("METRO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Screenshot tests write PNGs to app/build/outputs/roborazzi.
                it.systemProperty("roborazzi.test.record", "true")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.billing)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

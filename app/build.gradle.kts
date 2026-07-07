import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is driven by a gitignored `keystore.properties` at the repo root (see
// keystore.properties.example). When it is absent — CI, a fresh clone, debug-only work — the release
// signingConfig is simply not wired, so `assembleDebug` and unit tests still run without any keystore.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists()

android {
    namespace = "com.hletrd.findx9tele"
    // Compile against the newest SDK (API 37) required by the latest AndroidX libraries.
    // Runtime target stays Android 16 (API 36) — compileSdk and targetSdk are decoupled.
    compileSdk = 37

    defaultConfig {
        // Public app id (Play URL / Settings). The internal Kotlin/namespace package stays
        // com.hletrd.findx9tele — not user-visible, so it is left to avoid a repo-wide package move.
        applicationId = "com.hletrd.telecampro"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Upload key for Google Play App Signing. Populated only when keystore.properties exists;
        // secrets live there (gitignored), never in this file or git history.
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8/minify intentionally OFF for v1 (Play does not require it; keeps the Camera2/HAL
            // capture paths risk-free). Enabling it later needs keep-rules for the name-persisted
            // enums in SettingsStore + a full on-device re-verification pass.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Only sign when the keystore is present. The release packaging task below fails fast
            // without it, so a Play-ineligible unsigned AAB is never produced as a "successful" build.
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        // Intentional for this single-device camera: runtime support targets Android 16 / API 36
        // even though compileSdk is newer for AndroidX. Play's current target requirement is satisfied.
        disable += "OldTargetApi"
        // minSdk is 36; keeping adaptive icons in the conventional v26 folder is harmless and clearer
        // than moving resources for a packaging-only lint warning.
        disable += "ObsoleteSdkInt"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.heifwriter)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

if (!hasReleaseKeystore) {
    tasks.matching { it.name == "packageReleaseBundle" || it.name == "bundleRelease" || it.name == "assembleRelease" }
        .configureEach {
            doFirst {
                throw GradleException(
                    "Release signing is required for Play upload. Create gitignored keystore.properties " +
                        "from keystore.properties.example, then rerun this task.",
                )
            }
        }
}

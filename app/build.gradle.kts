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

fun signingValue(propertyName: String, envName: String): String? =
    keystoreProps.getProperty(propertyName)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "CHANGE_ME" }
        ?: System.getenv(envName)?.trim()?.takeIf { it.isNotEmpty() }

val releaseStoreFile = signingValue("storeFile", "TELECAMPRO_STORE_FILE")
val releaseKeyAlias = signingValue("keyAlias", "TELECAMPRO_KEY_ALIAS")
val releaseStorePassword = signingValue("storePassword", "TELECAMPRO_STORE_PASSWORD")
val releaseKeyPassword = signingValue("keyPassword", "TELECAMPRO_KEY_PASSWORD") ?: releaseStorePassword
val hasReleaseSigning =
    keystorePropsFile.exists() &&
        releaseStoreFile != null &&
        releaseKeyAlias != null &&
        releaseStorePassword != null &&
        releaseKeyPassword != null

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
        // Upload key for Google Play App Signing. The gitignored keystore.properties names the key;
        // passwords can live there or, preferably, in TELECAMPRO_* environment variables for the build.
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Keep debug installs distinguishable from the Play identity. Without this, a debug APK
            // occupies com.hletrd.telecampro and is indistinguishable from a release install by
            // package name (QA gate 2026-07-07 caught exactly that: a DEBUGGABLE binary emitting the
            // debug-only X9TeleVendor dump under the release id).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
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
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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

    // OPPO CameraUnit / OCS SDK — bridge to the privileged system-camera path (super-steady
    // stabilization for the teleconverter, etc.) that raw Camera2 can't reach. POC for 300 mm OIS.
    implementation("com.oplus.ocs:camera:1.1.0")
    implementation("com.oplus.ocs:base:1.0.16")

    testImplementation(libs.junit)
}

if (!hasReleaseSigning) {
    tasks.matching { it.name == "packageReleaseBundle" || it.name == "bundleRelease" || it.name == "assembleRelease" }
        .configureEach {
            doFirst {
                throw GradleException(
                    "Release signing is required for Play upload. Create gitignored keystore.properties " +
                        "from keystore.properties.example and provide store/key passwords either there " +
                        "or via TELECAMPRO_STORE_PASSWORD and TELECAMPRO_KEY_PASSWORD.",
                )
            }
        }
}

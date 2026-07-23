import java.util.Properties
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Applied explicitly (AGP would apply it lazily for enableUnitTestCoverage) so the
    // JacocoTaskExtension exists on Test tasks BEFORE the configureEach below runs — AGP's own
    // deferred apply registers the extension after script-body configureEach actions, which fails
    // task creation with "Extension of type 'JacocoTaskExtension' does not exist".
    jacoco
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
        // Public app id (Play URL / Settings / OPPO CameraUnit auth-code binding). The internal
        // Kotlin/namespace package stays com.hletrd.findx9tele — not user-visible, so it is left to
        // avoid a repo-wide package move.
        applicationId = "me.hletrd.telecampro"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // On-device instrumented smoke tier (app/src/androidTest). The external device-tests/
        // harness owns functional depth; the instrumented suite exists to exercise real code
        // paths for line coverage (docs/TESTING.md).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // occupies me.hletrd.telecampro and is indistinguishable from a release install by
            // package name (QA gate 2026-07-07 caught exactly that: a DEBUGGABLE binary emitting
            // debug-only camera capability logs under the release id).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Host-JVM unit-test line coverage (JaCoCo via AGP). Debug-only: the flag adds a
            // coverage-instrumented unit-test task, never touches the APK bytecode we ship.
            enableUnitTestCoverage = true
            // Instrumented (connected) coverage is PROPERTY-GATED, never default-on: this flag
            // makes AGP JaCoCo-instrument the debug APK's BYTECODE, and the default debug build
            // must stay uninstrumented so device-tests/ perf checks and APK-sha attestation always
            // run against clean bytecode. Enable per coverage run:
            //   ./gradlew :app:createDebugAndroidTestCoverageReport -PandroidTestCoverage=true
            enableAndroidTestCoverage =
                providers.gradleProperty("androidTestCoverage").orNull == "true"
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

    testOptions {
        unitTests {
            // Required for Robolectric: merges Android resources/assets/manifest into the host
            // unit-test classpath (also what compose ui-test's ComponentActivity manifest rides on).
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

// Robolectric loads app classes through its sandbox classloader WITHOUT a code-source location, and
// the JaCoCo agent skips location-less classes by default — so Robolectric-driven line coverage
// silently reads 0% unless the agent is told to include them (robolectric#2230/#5575). The
// jdk.internal exclusion is required on JDK 9+ or the agent trips over JDK internals. Existing
// pure-JVM tests are unaffected by either flag.
tasks.withType<Test>().configureEach {
    configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

// --- Robolectric android-all under dependency verification -------------------------------------
// At first test run Robolectric's own MavenArtifactFetcher (NOT Gradle: it ignores Gradle repos,
// caches, and verification-metadata.xml) downloads the ~40 MB pre-instrumented framework jar for
// each simulated SDK straight from Maven Central into ~/.m2 — a side channel outside this repo's
// dependency-verification perimeter. Instead, declare the exact jar Robolectric 4.16.1 pins for
// simulated SDK 36 as a REAL Gradle dependency, copy it into the build dir, and run the tests
// offline against that dir — so its sha256 lives in gradle/verification-metadata.xml like any
// other dependency. The pinned version must move in lockstep with Robolectric upgrades; on drift
// the test task fails with the expected coordinate printed in the message.
val robolectricJars: Configuration by configurations.creating
val robolectricJarsDir = layout.buildDirectory.dir("robolectric-jars")
val fetchRobolectricJars by tasks.registering(Copy::class) {
    from(robolectricJars)
    into(robolectricJarsDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(fetchRobolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricJarsDir.get().asFile.path)
}

composeCompiler {
    // PERF4-1: framework types carried by CameraUiState (Size/Range/Uri + our CameraCaps holder)
    // are UNSTABLE to the Compose compiler, so every child receiving the whole state recomposed
    // on every ~10-25 Hz telemetry tick regardless of strong skipping. The config marks the
    // effectively-immutable ones stable; CameraUiState itself is @Immutable in source.
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose_stability.conf"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.heifwriter)
    implementation(libs.androidx.exifinterface)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // OPPO CameraUnit / OCS SDK — official OEM extension SDK for stabilization modes and related
    // camera capabilities. debugImplementation on purpose: its ONLY consumer is the debug-source-set
    // OcsProbe (release ships a no-op stub), so the closed-source OEM AAR must not ride in the
    // release AAB it is never invoked from (supply-chain surface + Data-Safety accuracy).
    debugImplementation(libs.oplus.ocs.camera)
    debugImplementation(libs.oplus.ocs.base)

    testImplementation(libs.junit)
    // Robolectric host tests (CameraViewModel and friends). The BOM must be re-applied to the test
    // configuration — the implementation(platform(...)) above does not flow into testImplementation.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Supplies the ComponentActivity createComposeRule launches; the debug variant's merged
    // manifest is what Robolectric unit tests see.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // The verified offline android-all framework jar (see the robolectricJars block above).
    robolectricJars(libs.robolectric.android.all.instrumented)

    // On-device instrumented smoke tier (app/src/androidTest). Deliberately LEAN — no compose
    // BOM/assertions here: the suite drives MainActivity via ActivityScenario and observes the
    // ViewModel's StateFlow directly; device-tests/ owns functional depth (docs/TESTING.md).
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
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

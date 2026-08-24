import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Release source identity must inspect the exact Git checkout every invocation")
abstract class VerifyCleanReleaseGitTask : DefaultTask() {
    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:Input
    abstract val protectedSourceRoots: ListProperty<String>

    @get:Input
    abstract val unsupportedImmutableClaims: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun verifyAndGenerate() {
        val provenanceNamespace = "telecam-release-provenance"
        val provenanceName = "source.properties"
        val outputRoot = outputDirectory.get().asFile
        val namespaceDirectory = outputRoot.resolve(provenanceNamespace)
        val output = namespaceDirectory.resolve(provenanceName)

        fun requireExactOutputNamespace(allowMissingProvenance: Boolean) {
            if (!outputRoot.exists()) return
            val members = outputRoot.walkTopDown()
                .filter { it != outputRoot }
                .map { it.relativeTo(outputRoot).invariantSeparatorsPath }
                .toSet()
            val expected = setOf(provenanceNamespace, "$provenanceNamespace/$provenanceName")
            val unexpected = members - expected
            if (unexpected.isNotEmpty() || (!allowMissingProvenance && members != expected)) {
                throw GradleException(
                    "Generated release provenance namespace is not exact: " +
                        "expected=$expected actual=$members",
                )
            }
            if (
                namespaceDirectory.exists() &&
                (!namespaceDirectory.isDirectory || Files.isSymbolicLink(namespaceDirectory.toPath()))
            ) {
                throw GradleException(
                    "Release provenance namespace is not a regular directory: $namespaceDirectory",
                )
            }
            if (output.exists() && (!output.isFile || Files.isSymbolicLink(output.toPath()))) {
                throw GradleException("Release provenance member is not a regular file: $output")
            }
        }

        fun gitBytes(vararg arguments: String): ByteArray {
            val command = listOf("git", *arguments)
            val process = ProcessBuilder(command)
                .directory(repositoryDirectory.get().asFile)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.use { it.readBytes() }
            if (process.waitFor() != 0) {
                throw GradleException(
                    "${command.joinToString(" ")} failed: ${output.toString(Charsets.UTF_8).trim()}",
                )
            }
            return output
        }

        fun gitValue(vararg arguments: String): String =
            gitBytes(*arguments).toString(Charsets.US_ASCII).trim()

        fun displayNulRecords(raw: ByteArray): String = raw
            .toString(Charsets.UTF_8)
            .split('\u0000')
            .filter(String::isNotEmpty)
            .joinToString("\n")

        fun worktreeChanges(): ByteArray = gitBytes(
            "status",
            "--porcelain=v1",
            "-z",
            "--untracked-files=all",
            "--ignore-submodules=none",
        )

        fun ignoredSourceInputs(): ByteArray = gitBytes(
            "ls-files",
            "-z",
            "--others",
            "--ignored",
            "--exclude-standard",
            "--",
            *protectedSourceRoots.get().toTypedArray(),
        )

        fun requireRegularTrackedInputs() {
            val records = gitBytes("ls-files", "--stage", "-z")
                .toString(Charsets.UTF_8)
                .split('\u0000')
                .filter(String::isNotEmpty)
            val root = repositoryDirectory.get().asFile.toPath()
            records.forEach { record ->
                val separator = record.indexOf('\t')
                val metadata = if (separator >= 0) record.substring(0, separator) else ""
                val relative = if (separator >= 0) record.substring(separator + 1) else ""
                val fields = metadata.split(' ').filter(String::isNotEmpty)
                if (fields.size != 3 || relative.isEmpty()) {
                    throw GradleException("Git returned a malformed tracked release-input record")
                }
                val mode = fields[0]
                if (mode != "100644" && mode != "100755") {
                    throw GradleException(
                        "Release input is not a regular tracked file: $relative (mode $mode)",
                    )
                }
                var current = root
                val parts = Paths.get(relative).normalize()
                if (parts.isAbsolute || parts.any { it.toString() == ".." }) {
                    throw GradleException("Tracked release-input path is unsafe: $relative")
                }
                parts.forEachIndexed { index, component ->
                    current = current.resolve(component.toString())
                    val attributes = try {
                        Files.readAttributes(
                            current,
                            BasicFileAttributes::class.java,
                            LinkOption.NOFOLLOW_LINKS,
                        )
                    } catch (error: Exception) {
                        throw GradleException(
                            "Could not inspect tracked release input without following links: $relative",
                            error,
                        )
                    }
                    val final = index == parts.nameCount - 1
                    val valid = if (final) attributes.isRegularFile else attributes.isDirectory
                    if (!valid || Files.isSymbolicLink(current)) {
                        throw GradleException(
                            "Release input is not a no-follow regular path: $relative",
                        )
                    }
                }
            }
        }

        fun requireClean() {
            requireRegularTrackedInputs()
            val changes = worktreeChanges()
            if (changes.isNotEmpty()) {
                throw GradleException(
                    "Release source must be a clean immutable commit; worktree changes:\n" +
                        displayNulRecords(changes),
                )
            }
            val ignoredInputs = ignoredSourceInputs()
            if (ignoredInputs.isNotEmpty()) {
                throw GradleException(
                    "Release source roots contain ignored packageable inputs:\n" +
                        displayNulRecords(ignoredInputs),
                )
            }
        }

        val legacyClaims = unsupportedImmutableClaims.get()
        if (legacyClaims.isNotEmpty()) {
            throw GradleException(
                "Caller-supplied immutable release claims are unsupported: " +
                    legacyClaims.joinToString() + ". Direct Gradle release outputs are " +
                    "developer-only; immutable evidence exists only in the output namespace " +
                    "published by tools/build_immutable_release.py.",
            )
        }
        requireExactOutputNamespace(allowMissingProvenance = true)

        requireClean()
        val head = gitValue("rev-parse", "HEAD")
        val tree = gitValue("rev-parse", "HEAD^{tree}")
        if (!head.matches(Regex("[0-9a-f]{40}")) || !tree.matches(Regex("[0-9a-f]{40}"))) {
            throw GradleException("Git release identity is not canonical: head=$head tree=$tree")
        }
        // Recheck both filesystem state and identity after capture. This catches a concurrent branch,
        // index, or worktree mutation during the verification action instead of signing provenance
        // for a state different from the one that was inspected.
        requireClean()
        val finalHead = gitValue("rev-parse", "HEAD")
        val finalTree = gitValue("rev-parse", "HEAD^{tree}")
        if (head != finalHead || tree != finalTree) {
            throw GradleException(
                "Git release identity changed during verification: " +
                    "head=$head->$finalHead tree=$tree->$finalTree",
            )
        }

        output.parentFile.mkdirs()
        output.writeText(
            "schema=2\nevidence=external-wrapper-required\ncommit=$head\ntree=$tree\n",
            Charsets.US_ASCII,
        )
        requireExactOutputNamespace(allowMissingProvenance = false)
    }
}

@DisableCachingByDefault(because = "Debug provenance must capture the checkout contents every invocation")
abstract class GenerateDebugSourceProvenanceTask : DefaultTask() {
    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packagedSourceInputs: ConfigurableFileCollection

    @get:Input
    abstract val sourceScopes: ListProperty<String>

    @get:Input
    abstract val sourceOwner: org.gradle.api.provider.Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = repositoryDirectory.get().asFile.canonicalFile
        val outputRoot = outputDirectory.get().asFile
        val namespace = outputRoot.resolve("telecam-debug-provenance")
        val output = namespace.resolve("source.manifest")
        val owner = sourceOwner.get()
        if (owner !in setOf("immutable-debug-worktree-v1", "mutable-development-worktree")) {
            throw GradleException("Unsupported debug source owner: $owner")
        }

        fun gitBytes(vararg arguments: String): ByteArray {
            val command = listOf("git", *arguments)
            val process = ProcessBuilder(command)
                .directory(root)
                .redirectErrorStream(true)
                .start()
            val bytes = process.inputStream.use { it.readBytes() }
            if (process.waitFor() != 0) {
                throw GradleException(
                    "${command.joinToString(" ")} failed: " +
                        bytes.toString(Charsets.UTF_8).trim(),
                )
            }
            return bytes
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

        fun scopedGitBytes(vararg arguments: String): ByteArray = gitBytes(
            *arguments,
            "--",
            *sourceScopes.get().toTypedArray(),
        )

        val head = gitBytes("rev-parse", "HEAD").toString(Charsets.US_ASCII).trim()
        if (!head.matches(Regex("[0-9a-f]{40}"))) {
            throw GradleException("Git debug-source identity is not canonical: $head")
        }
        val changed = scopedGitBytes(
            "status",
            "--porcelain=v1",
            "-z",
            "--untracked-files=all",
            "--ignore-submodules=none",
        )
        val ignored = scopedGitBytes(
            "ls-files",
            "-z",
            "--others",
            "--ignored",
            "--exclude-standard",
        )

        val entries = packagedSourceInputs.files
            .map { it.canonicalFile }
            .filter { it.isFile }
            .map { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (relative.startsWith("../") || '\n' in relative || '\r' in relative) {
                    throw GradleException("Unsafe debug source input path: $relative")
                }
                val bytes = file.readBytes()
                Triple(relative, bytes.size.toLong(), sha256(bytes))
            }
            .sortedBy { it.first }
        if (entries.isEmpty()) {
            throw GradleException("No debug source inputs were found")
        }
        val duplicatePaths = entries.groupBy { it.first }.filterValues { it.size != 1 }.keys
        if (duplicatePaths.isNotEmpty()) {
            throw GradleException("Duplicate debug source inputs: $duplicatePaths")
        }
        val canonicalEntries = entries.joinToString("") { (path, size, digest) ->
            "$digest  $size  $path\n"
        }
        val contentSha256 = sha256(canonicalEntries.toByteArray(Charsets.UTF_8))
        val dirty = changed.isNotEmpty() || ignored.isNotEmpty()

        if (outputRoot.exists()) {
            val members = outputRoot.walkTopDown()
                .filter { it != outputRoot }
                .map { it.relativeTo(outputRoot).invariantSeparatorsPath }
                .toSet()
            val expected = setOf(
                "telecam-debug-provenance",
                "telecam-debug-provenance/source.manifest",
            )
            if ((members - expected).isNotEmpty()) {
                throw GradleException(
                    "Generated debug provenance namespace contains unexpected members: $members",
                )
            }
        }
        namespace.mkdirs()
        output.writeText(
            buildString {
                append("schema=2\n")
                append("source_owner=$owner\n")
                append("commit=$head\n")
                append("dirty=$dirty\n")
                append("content_sha256=$contentSha256\n")
                append("file_count=${entries.size}\n")
                append(canonicalEntries)
            },
            Charsets.UTF_8,
        )
    }
}

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

// File ownership is deliberately NOT environment- or Gradle-property-configurable. The immutable
// wrapper descriptor-copies keystore.properties and its repository-relative JKS into the private
// checkout, so this ordinary local path resolves to the sealed copy there. A direct Gradle build may
// use the local file for developer output, but no caller-supplied path is accepted as evidence.
// Password and alias values remain safe environment inputs because they are values, not paths.
fun normalizedRepositoryStoreFile(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() && it != "CHANGE_ME" } ?: return null
    val parts = value.split('/')
    if (
        value.startsWith('/') ||
        value.contains('\\') ||
        Regex("^[A-Za-z]:").containsMatchIn(value) ||
        parts.any { it.isEmpty() || it == "." || it == ".." }
    ) {
        throw GradleException("Release storeFile must be one normalized repository-relative path")
    }
    return value
}
val configuredReleaseStoreFile = normalizedRepositoryStoreFile(
    keystoreProps.getProperty("storeFile"),
)
// This path configures signing only. It does not authenticate wrapper origin or make the mutable
// app/build/outputs tree release evidence.
val releaseStoreFile = configuredReleaseStoreFile
val releaseKeyAlias = signingValue("keyAlias", "TELECAMPRO_KEY_ALIAS")
val releaseStorePassword = signingValue("storePassword", "TELECAMPRO_STORE_PASSWORD")
val releaseKeyPassword = signingValue("keyPassword", "TELECAMPRO_KEY_PASSWORD") ?: releaseStorePassword
val hasReleaseSigning =
    keystorePropsFile.exists() &&
        releaseStoreFile != null &&
        releaseKeyAlias != null &&
        releaseStorePassword != null &&
        releaseKeyPassword != null

val releaseSourceRoots = listOf("app/src/main", "app/src/release")
// Version the generated root when its exact namespace/schema changes. An existing developer build
// may still contain the retired schema-1 flat file; pointing the schema-2 task at a fresh owned root
// preserves fail-closed sibling checks without requiring a destructive clean.
val releaseSourceProvenanceDir = layout.buildDirectory.dir("generated/release-source-provenance-v2")
val legacyImmutableClaimNames = listOf(
    "immutableReleaseCommit",
    "immutableReleaseTree",
    "immutableReleaseAuthorityPath",
    "immutableReleaseAuthorityNonce",
    "immutableReleaseStoreFile",
)
val suppliedLegacyImmutableClaims = legacyImmutableClaimNames.filter {
    providers.gradleProperty(it).isPresent
}
val verifyCleanReleaseGit = tasks.register<VerifyCleanReleaseGitTask>("verifyCleanReleaseGit") {
    group = "verification"
    description =
        "Verify clean developer release inputs; immutable evidence is published only by the outer wrapper."
    repositoryDirectory.set(rootProject.layout.projectDirectory)
    protectedSourceRoots.set(releaseSourceRoots)
    unsupportedImmutableClaims.set(suppliedLegacyImmutableClaims)
    outputDirectory.set(releaseSourceProvenanceDir)
    outputs.upToDateWhen { false }
}

// The external device harness exercises an already-built debug APK. Package an exact content
// identity for every checked-in or local source/configuration input that can change those APK
// bytes, so the harness can refuse a stale artifact before touching a device. Tests and device
// harness files are deliberately outside this identity: they do not enter the application APK and
// have their own attested source manifest.
val debugSourceScopes = listOf(
    "app/src/main",
    "app/src/debug",
    "app/build.gradle.kts",
    "app/compose_stability.conf",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "gradle/libs.versions.toml",
    "gradle/wrapper/gradle-wrapper.properties",
)
val debugSourceProvenanceDir = layout.buildDirectory.dir("generated/debug-source-provenance")
val debugSourceOwner = providers.gradleProperty("immutableDebugSourceOwner")
    .orElse("mutable-development-worktree")
val generateDebugSourceProvenance =
    tasks.register<GenerateDebugSourceProvenanceTask>("generateDebugSourceProvenance") {
        repositoryDirectory.set(rootProject.layout.projectDirectory)
        sourceScopes.set(debugSourceScopes)
        sourceOwner.set(debugSourceOwner)
        packagedSourceInputs.from(
            fileTree(rootProject.file("app/src/main")),
            fileTree(rootProject.file("app/src/debug")),
            debugSourceScopes.drop(2).map(rootProject::file),
        )
        outputDirectory.set(debugSourceProvenanceDir)
        outputs.upToDateWhen { false }
    }

// Establish the source snapshot before any debug compile/resource task can consume it. The
// generated-assets edge below guarantees packaging; this pre-build edge guarantees ordering.
tasks.matching { it.name == "preDebugBuild" }.configureEach {
    dependsOn(generateDebugSourceProvenance)
}

// Executable tools-suite seam. It exists only when the test supplies a disposable repository;
// release tasks always depend on the fixed, non-overridable task above.
providers.gradleProperty("releaseGateFixtureRepo").orNull?.let { fixtureRepository ->
    tasks.register<VerifyCleanReleaseGitTask>("verifyCleanReleaseGitFixture") {
        repositoryDirectory.set(file(fixtureRepository))
        protectedSourceRoots.set(releaseSourceRoots)
        unsupportedImmutableClaims.set(suppliedLegacyImmutableClaims)
        outputDirectory.set(
            file(
                providers.gradleProperty("releaseGateFixtureOutput").orNull
                    ?: error("releaseGateFixtureOutput is required with releaseGateFixtureRepo"),
            ),
        )
        outputs.upToDateWhen { false }
    }
}

android {
    namespace = "me.hletrd.telecampro"
    // Compile against the newest SDK (API 37) required by the latest AndroidX libraries.
    // Runtime target stays Android 16 (API 36) — compileSdk and targetSdk are decoupled.
    compileSdk = 37

    defaultConfig {
        // Public app id (Play URL / Settings). The Kotlin
        // namespace shares the me.hletrd vendor prefix (repo-wide move 2026-07-25) while keeping the
        // package now matches the applicationId; nothing here names a handset.
        applicationId = "me.hletrd.telecampro"
        // Multi-device support (2026-08-01, user decision): floor at Android 13 — the lint NewApi
        // audit found ZERO unguarded APIs below 35 after the two API-35 fixes, and every plausible
        // teleconverter host (vivo X100/X200/X300 Ultra, Find X9 line, 2022+ flagships) ships 13+.
        // PMA110 behavior is byte-identical; other devices run spec paths via DeviceProfile.
        minSdk = 33
        targetSdk = 36
        // versionCode 1 (v1.0, published 2026-08-04) and 3 (v1.0.1) are both SPENT — both reached
        // Google Play. Play rejects a re-used versionCode outright, so every upload must be strictly
        // greater, including a re-upload of otherwise identical bytes. 4 is the candidate.
        //
        // 1.0.1 SHIPPED, which is what makes 1.0.2 a real release rather than a re-cut: its notes
        // told tablet owners they were getting "a landscape layout with a side control rail", and
        // 1.0.2 REMOVES that rail — orientation now moves no control on any device. That is a
        // visible change to something users already have, so it takes its own version and its own
        // release notes, and those notes must not re-announce the Korean UI or the smaller download
        // that 1.0.1 already delivered.
        versionCode = 4
        versionName = "1.0.2"

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
            // R8 ON since 2026-08-04 (Play Console flagged the app as unoptimized). Both historical
            // blockers are retired: the reflection-sensitive OEM SDK path left with the com.oplus.ocs
            // removal on 2026-07-25, and the name-persisted enum keep rule in proguard-rules.pro is
            // now live rather than staged. R8 runs in FULL mode — android.enableR8.fullMode defaults
            // true on AGP 8+ and gradle.properties does not override it.
            isMinifyEnabled = true
            // Resource shrinking shares R8's reachability graph. The dynamic-resource audit remains
            // clean (no Resources.getIdentifier in production), so release can safely remove both
            // unreachable bytecode and the resources reachable only from it.
            isShrinkResources = true
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
        // Intentional: runtime support targets Android 16 / API 36 even though compileSdk is newer
        // for AndroidX. Play's current target requirement is satisfied.
        disable += "OldTargetApi"
        // Keeping adaptive icons in the conventional v26 folder is harmless and clearer than moving
        // resources for a packaging-only lint warning.
        disable += "ObsoleteSdkInt"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }


    androidResources {
        // Generates res/xml/locales_config and wires android:localeConfig from the values-* folders
        // that actually exist. Without a localeConfig the platform does not treat the app as
        // locale-aware: the per-app language entry is absent from Settings, and even an explicit
        // `cmd locale set-app-locales ko-KR` leaves the UI in English with the Korean resources
        // sitting unused in the APK — measured on device, which is how this was found. Generated
        // rather than hand-written so adding a locale cannot silently forget to list it.
        generateLocaleConfig = true
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

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateDebugSourceProvenance,
            GenerateDebugSourceProvenanceTask::outputDirectory,
        )
    }
    onVariants(selector().withBuildType("release")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            verifyCleanReleaseGit,
            VerifyCleanReleaseGitTask::outputDirectory,
        )
    }
}

// AGP makes every release compile/lint/resource/package entry point depend on preReleaseBuild.
// Put the one live Git inspection there, before any release source can be read. The generated asset
// dependency above remains as a second structural edge for provenance packaging itself.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyCleanReleaseGit)
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

// The ordinary Android test/lint tasks can stay green while the repository's explicit
// host-executable coverage partition falls below its 99.5% contract. Keep the threshold as a named
// Gradle quality gate and attach it to `check`, so CI and release workflows cannot accidentally run
// only the raw JaCoCo task and omit the partition semantics.
val verifyPartitionACoverage = tasks.register<Exec>("verifyPartitionACoverage") {
    dependsOn("createDebugUnitTestCoverageReport")
    workingDir(rootProject.projectDir)
    commandLine(
        "python3",
        "tools/coverage/partition_report.py",
        "app/build/reports/coverage/test/debug/report.xml",
        "--fail-under-a",
        "99.5",
    )
}
tasks.named("check").configure { dependsOn(verifyPartitionACoverage) }

// --- Robolectric android-all under dependency verification -------------------------------------
// At first test run Robolectric's own MavenArtifactFetcher (NOT Gradle: it ignores Gradle repos,
// caches, and verification-metadata.xml) downloads the ~40 MB pre-instrumented framework jar for
// each simulated SDK straight from Maven Central into ~/.m2 — a side channel outside this repo's
// dependency-verification perimeter. Instead, declare the exact jar Robolectric 4.16.1 pins for
// simulated SDK 36 as a REAL Gradle dependency, copy it into the build dir, and run the tests
// offline against that dir — so its sha256 lives in gradle/verification-metadata.xml like any
// other dependency. The pinned version must move in lockstep with Robolectric upgrades; on drift
// the test task fails with the expected coordinate printed in the message.
val robolectricJars = configurations.create("robolectricJars")
val robolectricJarsDir = layout.buildDirectory.dir("robolectric-jars")
val fetchRobolectricJars = tasks.register<Copy>("fetchRobolectricJars") {
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

    // Opt-in compiler reports for recomposition work: `-PcomposeReports=true` writes per-module
    // stability/skippability CSV+txt under build/compose_*. Off by default because it slows the
    // Kotlin task and writes into the build dir on every compile; it is the tool for answering
    // "which composable is unskippable and why" instead of guessing at traversal cost.
    if (providers.gradleProperty("composeReports").orNull == "true") {
        reportsDestination = layout.buildDirectory.dir("compose_reports")
        metricsDestination = layout.buildDirectory.dir("compose_metrics")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // Installs src/main/baseline-prof.txt at first run so ART compiles the startup and
    // menu paths ahead of time. Without it the release APK sits at `status=verify` and
    // every method is interpreted until JIT warms — measured as a 61 ms worst frame on
    // the first settings open, 26 ms once AOT-compiled.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.heifwriter)
    implementation(libs.androidx.exifinterface)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    // debug-only: the only @Preview lives in src/debug (release minification is enabled, but a preview
    // entry point on the main source set would ship inside the AAB).
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

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

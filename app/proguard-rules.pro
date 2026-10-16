# Release is MINIFIED since 2026-08-04 (R8, full mode). The rules here are LIVE, not staged.

# SettingsStore round-trips ~40 enums BY NAME — Enum.name on write, enumValueOf on read. R8 renames
# enum constants unless they are kept, and the resulting failure is SILENT: enumValueOf throws,
# SettingsStore's runCatching swallows it, and every persisted setting falls back to its default. A
# user would relaunch into a reset app with no crash to report and nothing in logcat.
#
# Scoped to the whole app package rather than to camera.**, which is where every persisted enum
# lives TODAY (CameraState.kt, ManualControls.kt, Teleconverter.kt — audited 2026-08-04). Because
# the failure mode is silent rather than loud, a persisted enum added later under video/ or ui/
# must not be able to reintroduce it just by sitting outside a narrow rule.
#
# <fields> is the member that actually pins the constant names; values()/valueOf() are named
# explicitly rather than leaning on the default proguard-android-optimize.txt enum rule, which
# keeps those two methods but NOT the constant fields.
-keepclassmembers enum me.hletrd.telecampro.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# Audited 2026-08-04 and deliberately NOT kept — recorded so the audit is not repeated:
#  - Camera2 vendor keys (CaptureRequest.Key("com.oplus.…", …)) are string literals naming FRAMEWORK
#    types. R8 rewrites neither the literal nor android.* classes.
#  - Log tags are string literals. The logcat lines device-tests/ parses (e.g. "CameraEngine:
#    RecordingFinalized: …") are BuildConfig.DEBUG-gated and isMinifyEnabled is release-only, so
#    that harness only ever reads an unminified debug build.
#  - javaClass.simpleName appears only on caught Throwables, which are framework/JDK types.
#  - No reflection, Class.forName, @Keep, JNI, or Resources.getIdentifier in app/src/main/kotlin.
#  - Compose, heifwriter and exifinterface ship their own consumer rules via their AARs.

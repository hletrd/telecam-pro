package me.hletrd.telecampro.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.net.toUri
import java.util.Locale
import me.hletrd.telecampro.R

internal enum class ExternalNavigationTarget {
    APP_SETTINGS,
    PRIVACY_POLICY,
}

internal enum class ExternalLaunchOutcome {
    LAUNCHED,
    UNRESOLVED,
    SECURITY_BLOCKED,
}

internal enum class ExternalNavigationFailureReason {
    UNRESOLVED,
    SECURITY_BLOCKED,
}

internal data class ExternalNavigationFailure(
    val target: ExternalNavigationTarget,
    val reason: ExternalNavigationFailureReason,
)

/**
 * One classification boundary for framework external-activity launch failures.
 *
 * Keep the attempt itself in this tiny seam: callers must present [ExternalLaunchOutcome] rather
 * than silently swallowing the two normal managed-device failure modes. Unexpected runtime
 * failures still escape instead of being mislabeled as device policy.
 */
internal fun attemptExternalLaunch(start: () -> Unit): ExternalLaunchOutcome = try {
    start()
    ExternalLaunchOutcome.LAUNCHED
} catch (_: ActivityNotFoundException) {
    ExternalLaunchOutcome.UNRESOLVED
} catch (_: SecurityException) {
    ExternalLaunchOutcome.SECURITY_BLOCKED
}

/**
 * Hands one of this app's own captures to the phone's viewer, with read access for that one URI.
 * The type is the family ("image/" or "video/"), not a query: the provider round trip for the exact
 * type would run on the main thread at the tap, and every gallery resolves the wildcard.
 */
internal fun launchMediaViewer(context: Context, uri: Uri, video: Boolean): ExternalLaunchOutcome {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, if (video) "video/*" else "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return attemptExternalLaunch { context.startActivity(intent) }
}

internal fun launchExternal(
    context: Context,
    target: ExternalNavigationTarget,
): ExternalLaunchOutcome {
    val intent = when (target) {
        ExternalNavigationTarget.APP_SETTINGS -> Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
        ExternalNavigationTarget.PRIVACY_POLICY -> Intent(
            Intent.ACTION_VIEW,
            privacyPolicyUrl(context.resources.configuration.locales[0]).toUri(),
        )
    }
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return attemptExternalLaunch { context.startActivity(intent) }
}

internal fun externalNavigationFailure(
    target: ExternalNavigationTarget,
    outcome: ExternalLaunchOutcome,
): ExternalNavigationFailure? = when (outcome) {
    ExternalLaunchOutcome.LAUNCHED -> null
    ExternalLaunchOutcome.UNRESOLVED -> ExternalNavigationFailure(
        target,
        ExternalNavigationFailureReason.UNRESOLVED,
    )
    ExternalLaunchOutcome.SECURITY_BLOCKED -> ExternalNavigationFailure(
        target,
        ExternalNavigationFailureReason.SECURITY_BLOCKED,
    )
}

@StringRes
internal fun ExternalNavigationFailure.messageRes(): Int = when (target) {
    ExternalNavigationTarget.APP_SETTINGS -> when (reason) {
        ExternalNavigationFailureReason.UNRESOLVED -> R.string.external_settings_unavailable
        ExternalNavigationFailureReason.SECURITY_BLOCKED -> R.string.external_settings_blocked
    }
    ExternalNavigationTarget.PRIVACY_POLICY -> when (reason) {
        ExternalNavigationFailureReason.UNRESOLVED -> R.string.external_privacy_unavailable
        ExternalNavigationFailureReason.SECURITY_BLOCKED -> R.string.external_privacy_blocked
    }
}

internal fun privacyPolicyUrl(locale: Locale): String =
    if (locale.language == Locale.KOREAN.language) PRIVACY_POLICY_KO_URL else PRIVACY_POLICY_URL

internal const val PRIVACY_POLICY_URL = "https://hletrd.github.io/telecam-pro/privacy-policy/"
internal const val PRIVACY_POLICY_KO_URL = "${PRIVACY_POLICY_URL}#ko"

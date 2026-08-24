package me.hletrd.telecampro.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.net.toUri
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

internal data class ExternalNavigationFailure(
    val target: ExternalNavigationTarget,
    val outcome: ExternalLaunchOutcome,
) {
    init {
        require(outcome != ExternalLaunchOutcome.LAUNCHED)
    }
}

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
            PRIVACY_POLICY_URL.toUri(),
        )
    }
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return attemptExternalLaunch { context.startActivity(intent) }
}

internal fun externalNavigationFailure(
    target: ExternalNavigationTarget,
    outcome: ExternalLaunchOutcome,
): ExternalNavigationFailure? = if (outcome == ExternalLaunchOutcome.LAUNCHED) {
    null
} else {
    ExternalNavigationFailure(target, outcome)
}

@StringRes
internal fun ExternalNavigationFailure.messageRes(): Int = when (target) {
    ExternalNavigationTarget.APP_SETTINGS -> when (outcome) {
        ExternalLaunchOutcome.UNRESOLVED -> R.string.external_settings_unavailable
        ExternalLaunchOutcome.SECURITY_BLOCKED -> R.string.external_settings_blocked
        ExternalLaunchOutcome.LAUNCHED -> error("A launched outcome is not a failure")
    }
    ExternalNavigationTarget.PRIVACY_POLICY -> when (outcome) {
        ExternalLaunchOutcome.UNRESOLVED -> R.string.external_privacy_unavailable
        ExternalLaunchOutcome.SECURITY_BLOCKED -> R.string.external_privacy_blocked
        ExternalLaunchOutcome.LAUNCHED -> error("A launched outcome is not a failure")
    }
}

internal const val PRIVACY_POLICY_URL = "https://hletrd.github.io/telecam-pro/privacy-policy/"

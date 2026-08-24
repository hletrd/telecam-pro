package me.hletrd.telecampro.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.hletrd.telecampro.R
import me.hletrd.telecampro.ui.theme.CameraColors

/** Assertive, non-focus-stealing acknowledgement plus the privacy-specific local recovery route. */
@Composable
internal fun ExternalNavigationRecovery(
    failure: ExternalNavigationFailure?,
    onOpenPrivacyInApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (failure == null) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(failure.messageRes()),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            // This is a real failed action, not a route capability caption: recording/alarm red is
            // the intentional error signal here (see CameraColors.Record's restricted contract).
            color = CameraColors.Record,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (failure.target == ExternalNavigationTarget.PRIVACY_POLICY) {
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onOpenPrivacyInApp,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.action_view_in_app),
                    color = CameraColors.Accent,
                )
            }
        }
    }
}

/** Bundled policy copy for devices whose browser route is absent or prohibited. */
@Composable
internal fun PrivacyPolicyFallbackDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.privacy_fallback_title)) },
        text = {
            Text(
                text = stringResource(R.string.privacy_fallback_body),
                modifier = Modifier.verticalScroll(rememberScrollState()),
                color = CameraColors.TextPrimary,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

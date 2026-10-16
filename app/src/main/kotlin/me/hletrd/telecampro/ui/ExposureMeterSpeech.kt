package me.hletrd.telecampro.ui

import androidx.annotation.StringRes
import java.util.Locale
import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.ui.controls.formatEvComp

/**
 * The exposure meter's spoken form (AGG4-71): a name, and a state built from [stateText] with the
 * signed EV [value]. The meter was a bare Text plus an unlabelled Canvas, so TalkBack read raw
 * glyphs ("M", "M plus zero point three") with no name, unit or meaning — on the ONE exposure
 * instrument in M mode, which stays on screen even in compact DISP. In M it is a light meter
 * ("Metered +0.3 EV", or "Metering…" until the first analysis); in P/S/ISO the same column shows
 * the compensation dial, so it speaks with that dial's existing name. No live region: the meter
 * updates at analysis rate.
 */
internal data class ExposureMeterSpeech(
    @StringRes val name: Int,
    @StringRes val stateText: Int,
    val value: String?,
)

internal fun exposureMeterSpeech(
    mode: ExposureMode,
    manualEv: Float?,
    compensationEv: Float,
): ExposureMeterSpeech = when {
    mode == ExposureMode.MANUAL && manualEv != null -> ExposureMeterSpeech(
        R.string.a11y_exposure_meter,
        R.string.a11y_exposure_meter_metered,
        // Same digits the visual "M +0.3" readout shows.
        "%+.1f".format(Locale.US, manualEv),
    )
    mode == ExposureMode.MANUAL -> ExposureMeterSpeech(
        R.string.a11y_exposure_meter,
        R.string.a11y_exposure_meter_metering,
        null,
    )
    else -> ExposureMeterSpeech(
        R.string.a11y_exposure_compensation,
        R.string.a11y_ev_value,
        formatEvComp(compensationEv),
    )
}

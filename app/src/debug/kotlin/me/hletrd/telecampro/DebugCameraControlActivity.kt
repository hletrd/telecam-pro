package me.hletrd.telecampro

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Shell-only ingress for camera measurement controls.
 *
 * The debug manifest protects this exported component with signature-level `android.permission.DUMP`.
 * It publishes into a process-local mailbox and launches MainActivity with NO command extras, so the
 * ordinary exported launcher remains inert even when an untrusted app supplies lookalike extras.
 * Shell usage:
 * `adb shell am start -n me.hletrd.telecampro.debug/me.hletrd.telecampro.DebugCameraControlActivity
 * --ez zsl_spike true` or the same command with `--ef debug_zoom 3.0`.
 */
class DebugCameraControlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = debugCameraControlCommand(intent)
        if (DebugCameraControlMailbox.publish(command)) {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }
        finish()
    }
}

internal fun debugCameraControlCommand(intent: Intent?): DebugCameraControlCommand {
    if (intent == null) return DebugCameraControlCommand()
    val zslSpike = intent.takeIf { it.hasExtra(EXTRA_ZSL_SPIKE) }
        ?.getBooleanExtra(EXTRA_ZSL_SPIKE, false)
    val zoom = intent.takeIf { it.hasExtra(EXTRA_DEBUG_ZOOM) }
        ?.getFloatExtra(EXTRA_DEBUG_ZOOM, -1f)
        ?.takeIf { it.isFinite() && it > 0f }
    return DebugCameraControlCommand(zslSpike = zslSpike, zoomRatio = zoom)
}

internal const val EXTRA_ZSL_SPIKE = "zsl_spike"
internal const val EXTRA_DEBUG_ZOOM = "debug_zoom"

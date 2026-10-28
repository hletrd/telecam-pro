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
 *
 * `--ez debug_record true|false` starts or stops a Video take: soak harnesses need REC on devices
 * whose shell cannot inject input (MIUI refuses `input` without its "USB debugging (Security
 * settings)" switch, measured on the POCO X3 GT 2026-10-27), and a wanted STATE rather than a toggle
 * keeps a repeated or late command from inverting the take. `--ez debug_shutter true` presses the
 * Photo shutter once, for the same devices' still profiling.
 * `--ez debug_standalone_maker_video true|false` explicitly enters or leaves the standalone TC
 * 0x80b5 maker-video measurement probe; it has no release or GENERIC-profile effect.
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
    val record = intent.takeIf { it.hasExtra(EXTRA_DEBUG_RECORD) }
        ?.getBooleanExtra(EXTRA_DEBUG_RECORD, false)
    val shutterPresses = if (intent.getBooleanExtra(EXTRA_DEBUG_SHUTTER, false)) 1 else 0
    val standaloneMakerVideo = intent.takeIf { it.hasExtra(EXTRA_DEBUG_STANDALONE_MAKER_VIDEO) }
        ?.getBooleanExtra(EXTRA_DEBUG_STANDALONE_MAKER_VIDEO, false)
    return DebugCameraControlCommand(
        zslSpike = zslSpike,
        zoomRatio = zoom,
        record = record,
        shutterPresses = shutterPresses,
        standaloneMakerVideo = standaloneMakerVideo,
    )
}

internal const val EXTRA_ZSL_SPIKE = "zsl_spike"
internal const val EXTRA_DEBUG_ZOOM = "debug_zoom"
internal const val EXTRA_DEBUG_RECORD = "debug_record"
internal const val EXTRA_DEBUG_SHUTTER = "debug_shutter"
internal const val EXTRA_DEBUG_STANDALONE_MAKER_VIDEO = "debug_standalone_maker_video"

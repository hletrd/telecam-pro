package me.hletrd.telecampro.ui.controls

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Exposure
import androidx.compose.material.icons.outlined.FilterCenterFocus
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Iso
import androidx.compose.material.icons.outlined.Lens
import androidx.compose.material.icons.outlined.Loupe
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.ShutterSpeed
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.SurroundSound
import androidx.compose.material.icons.outlined.Texture
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoStable
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.ui.graphics.vector.ImageVector
import me.hletrd.telecampro.camera.FnSlot

/**
 * The one Fn-slot -> icon mapping (user-requested 2026-07-31: the text-only tiles read as a wall of
 * words). Outlined set throughout — the filled set reads heavier than the thin HUD typography this
 * UI keys on, and mixing weights inside one 4-wide grid is exactly the visual noise the tiles had.
 * Icons are RECOGNITION AIDS next to the existing label+value, never a replacement: every tile
 * keeps its text, so nothing regresses for TalkBack (semantics carry the label already) and no
 * icon has to carry meaning alone.
 */
internal fun fnSlotIcon(slot: FnSlot): ImageVector = when (slot) {
    FnSlot.EXPOSURE_MODE -> Icons.Outlined.Tune
    FnSlot.FOCUS -> Icons.Outlined.CenterFocusStrong
    FnSlot.SHUTTER -> Icons.Outlined.ShutterSpeed
    FnSlot.ISO -> Icons.Outlined.Iso
    FnSlot.WB -> Icons.Outlined.WbSunny
    FnSlot.EV -> Icons.Outlined.Exposure
    FnSlot.ZOOM -> Icons.Outlined.ZoomIn
    FnSlot.STABILIZATION -> Icons.Outlined.VideoStable
    FnSlot.DRIVE -> Icons.Outlined.BurstMode
    FnSlot.METERING -> Icons.Outlined.FilterCenterFocus
    FnSlot.PEAKING -> Icons.Outlined.BlurOn
    FnSlot.ZEBRA -> Icons.Outlined.Texture
    FnSlot.TRANSFER -> Icons.Outlined.ShowChart
    FnSlot.AUDIO_SCENE -> Icons.Outlined.SurroundSound
    FnSlot.GRID -> Icons.Outlined.GridOn
    FnSlot.LEVEL -> Icons.Outlined.Straighten
    FnSlot.PUNCH_IN -> Icons.Outlined.Loupe
    FnSlot.TELECONVERTER -> Icons.Outlined.Lens
    FnSlot.OPEN_GATE -> Icons.Outlined.Fullscreen
    FnSlot.FRAME_LINES -> Icons.Outlined.CropFree
    FnSlot.FLASH -> Icons.Outlined.FlashOn
    FnSlot.TIMER -> Icons.Outlined.Timer
    FnSlot.ASPECT -> Icons.Outlined.AspectRatio
    FnSlot.AUDIO_INPUT -> Icons.Outlined.Mic
}

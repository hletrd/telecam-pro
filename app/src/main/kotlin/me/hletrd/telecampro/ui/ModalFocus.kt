package me.hletrd.telecampro.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties

/** Excludes every finder descendant from keyboard/D-pad focus while a sibling modal owns the UI. */
internal fun Modifier.finderFocusEnabled(enabled: Boolean): Modifier =
    focusProperties { canFocus = enabled }

/** Keeps spatial traversal coherent inside a modal without blocking focusable Popup/Dialog windows. */
internal fun Modifier.modalFocusBoundary(): Modifier = focusGroup()

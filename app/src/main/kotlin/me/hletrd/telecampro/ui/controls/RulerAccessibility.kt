package me.hletrd.telecampro.ui.controls

/**
 * The accessibility step count a [RulerSlider] publishes through `progressSemantics` (AGG4-64).
 *
 * With `steps = 0` TalkBack's swipe (and volume keys in adjust mode) asks for `value ± 5 %`, and a
 * SNAPPED ruler rounds that back onto its own detent grid: whenever one detent is wider than 5 %
 * (`totalUnits < 10`), the request rounds back to the same detent and the value never moves. The
 * full-stop ISO ruler over 50–6400 (7 units) was stuck in both directions; denser snapped rulers
 * skipped detents they could not address. Publishing `totalUnits - 1` inner steps makes one swipe
 * exactly one detent. Continuous rulers keep `0` (their own domain has no detent grid).
 */
internal fun rulerSemanticSteps(snap: Boolean, totalUnits: Int): Int =
    if (snap) (totalUnits - 1).coerceAtLeast(0) else 0

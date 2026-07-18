package com.hletrd.findx9tele.camera

/**
 * Clamps only against a finite, ordered vendor range. Camera metadata should already satisfy this,
 * but failing open with the requested value is safer than throwing on a transient malformed HAL
 * range and killing the camera thread.
 */
internal fun clampToOrderedBounds(value: Float, lower: Float?, upper: Float?): Float =
    if (lower != null && upper != null && lower.isFinite() && upper.isFinite() && lower <= upper) {
        value.coerceIn(lower, upper)
    } else {
        value
    }

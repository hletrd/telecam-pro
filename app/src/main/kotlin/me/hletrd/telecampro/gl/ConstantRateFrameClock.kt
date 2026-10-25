package me.hletrd.telecampro.gl

/**
 * Places every recorded camera frame on the EXACT constant-rate grid of the selected frame rate, so a
 * 29.97 take is 30000/1001 frames per second of file time and stays in step with its audio in any
 * editor that conforms to the tagged rate (owner decision 2026-10-26: "29.97, 59.94 and 23.976 are
 * the standard").
 *
 * The sensor cannot be asked for those rates. PMA110 ignores SENSOR_FRAME_DURATION in a video session
 * and runs 1/upper of the INTEGER AE target range, about 0.04 % fast — 30.012 fps with AE off, 29.898
 * with HAL AE at "30" (device-measured 2026-10-26) — so encoding raw sensor timestamps wrote files
 * whose real rate was off the tag by up to 0.24 % (a 1.4 s audio drift over ten minutes in a
 * constant-rate editor), and the encoder's own FrameDropper left a 67 ms timestamp hole each time it
 * trimmed a fast sensor.
 *
 * Each frame goes to the grid slot nearest its capture time (slot 0 = the first frame, whose sensor
 * timestamp stays the video PTS 0 the A/V anchor and the gyro sidecar are built on), so a frame is never
 * more than half a slot from when it was captured. A frame whose slot is already filled is DROPPED (a
 * sensor faster than the grid: one frame every ~24 s at 29.97 over a 30.012 fps sensor); a gap the camera
 * left is FILLED by repeating the current frame into the empty slots, up to [maxFillSlots]
 * ([encoderMaxFillSlots]), so the file stays constant-rate across a camera hiccup instead of shifting
 * everything after it. A longer gap is left as a hole — at that point the camera itself stopped.
 *
 * GL-thread confined, one instance per encoder output. Integer-only, so it is exact over a whole take.
 */
internal class ConstantRateFrameClock(
    private val rateNumerator: Long,
    private val rateDenominator: Long,
    private val maxFillSlots: Int,
) {
    init {
        require(rateNumerator > 0 && rateDenominator > 0) { "rate must be positive" }
        require(maxFillSlots >= 1) { "maxFillSlots must be at least one" }
    }

    private var baseNs = 0L
    private var started = false
    private var lastSlot = -1L
    private var firstSlot = 0L

    /** Frames placed, dropped, and grid slots filled by repetition, for the per-take diagnostic. */
    var placedFrames = 0L
        private set
    var droppedFrames = 0L
        private set
    var filledSlots = 0L
        private set

    /**
     * Places the real camera frame captured at [timestampNs] and returns how many grid slots it fills:
     * 0 = dropped, 1 = its own slot, more = it also fills the slots a camera gap left empty. The
     * presentation time of each fill is [presentationNs] for indices `0 until` that count.
     */
    fun place(timestampNs: Long): Int {
        placedFrames++
        if (!started) {
            started = true
            baseNs = timestampNs
            lastSlot = 0L
            firstSlot = 0L
            return 1
        }
        val target = slotFor(timestampNs - baseNs)
        if (target <= lastSlot) {
            droppedFrames++
            return 0
        }
        val gap = target - lastSlot
        firstSlot = if (gap > maxFillSlots) target else lastSlot + 1
        lastSlot = target
        val count = (target - firstSlot + 1).toInt()
        filledSlots += count - 1
        return count
    }

    /** Presentation time (ns after the first frame) of fill [index] of the frame [place] just placed. */
    fun presentationNs(index: Int): Long = slotStartNs(firstSlot + index)

    /** The grid slot nearest [elapsedNs] after the first frame (rounded half up; negative stays before 0). */
    internal fun slotFor(elapsedNs: Long): Long {
        val slotLengthUnits = rateDenominator * NANOS_PER_SECOND
        return Math.floorDiv(elapsedNs * rateNumerator + slotLengthUnits / 2, slotLengthUnits)
    }

    /** Start of grid slot [slot] in ns after the first frame: slot × den / num seconds, rounded to the ns. */
    internal fun slotStartNs(slot: Long): Long {
        val units = slot * rateDenominator
        val wholeSeconds = units / rateNumerator
        val remainder = units % rateNumerator
        return wholeSeconds * NANOS_PER_SECOND + (remainder * NANOS_PER_SECOND + rateNumerator / 2) / rateNumerator
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

/**
 * The fill bound the encoder output uses: a third of a second of slots, rounded up (10 at 29.97, 20 at
 * 59.94, 8 at 23.976). Fills run as one burst of draws + swaps on the GL thread that also draws the
 * viewfinder, so a whole second (up to 60 swaps) was too long a hold; a third still covers a mid-REC
 * repeating-request swap, the longest ordinary stall measured (~235 + 67 ms, 2026-10-26).
 */
internal fun encoderMaxFillSlots(rateNumerator: Int, rateDenominator: Int): Int {
    val thirdSecondDenominator = rateDenominator.toLong() * 3
    return ((rateNumerator + thirdSecondDenominator - 1) / thirdSecondDenominator).toInt().coerceAtLeast(1)
}

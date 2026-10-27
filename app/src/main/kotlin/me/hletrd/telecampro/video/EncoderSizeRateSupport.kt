package me.hletrd.telecampro.video

fun interface EncoderSizeRateSupport {
    fun supports(width: Int, height: Int, frameRate: Double): Boolean
}

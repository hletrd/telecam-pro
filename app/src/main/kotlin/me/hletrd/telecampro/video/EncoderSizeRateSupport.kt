package me.hletrd.telecampro.video

import android.media.MediaFormat

/** Exact-component codec support for the complete format the recorder would configure. */
fun interface EncoderFormatSupport {
    fun supports(format: MediaFormat): Boolean
}

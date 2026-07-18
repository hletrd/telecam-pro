package com.hletrd.findx9tele.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [videoResolutionLabel] (TEST4-11 / carried P5.9): the 4:3 branch classifies by REAL HEIGHT
 * (the vertical resolution the label's K-class implies for a 4:3 frame), not by width buckets, and
 * off-class sizes fall back to the honest WxH string. 16:9 keeps exact-height names.
 */
class VideoResolutionLabelTest {

    @Test
    fun `device 4-3 sizes label by their height class`() {
        // Open Gate on PMA110 (CLAUDE.md): 2560x1920.
        assertEquals("2.5K 4:3", videoResolutionLabelFor(2560, 1920))
        // Full-sensor-class 4:3 (3840x2880 = the 4K-wide 4:3 frame).
        assertEquals("4K 4:3", videoResolutionLabelFor(3840, 2880))
        assertEquals("8K 4:3", videoResolutionLabelFor(7680, 5760))
        assertEquals("1080 4:3", videoResolutionLabelFor(1920, 1440))
    }

    @Test
    fun `nonstandard 4-3 size classifies by height not by a wider width bucket`() {
        // 2880x2160: the old width>=2560 bucket called it 2.5K while its 2160 height sits between
        // classes; by height it is the 2.5K-class 4:3 (>=1920), never promoted by width alone.
        assertEquals("2.5K 4:3", videoResolutionLabelFor(2880, 2160))
        // Below every class: honest raw size.
        assertEquals("1440×1080", videoResolutionLabelFor(1440, 1080))
    }

    @Test
    fun `16-9 sizes keep their exact-height names`() {
        assertEquals("4K", videoResolutionLabelFor(3840, 2160))
        assertEquals("1080p", videoResolutionLabelFor(1920, 1080))
        assertEquals("720p", videoResolutionLabelFor(1280, 720))
        assertEquals("1440p", videoResolutionLabelFor(2560, 1440))
    }
}

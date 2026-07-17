package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererConfigStoreTest {
    @Test
    fun `pre-start renderer changes survive as one complete snapshot`() {
        val store = RendererConfigStore()

        store.update {
            it.copy(
                peaking = true,
                peakingLevel = PeakingLevel.HIGH,
                peakingColor = PeakingColor.YELLOW,
                zebra = true,
                zebraLevel = ZebraLevel.IRE70,
                falseColor = true,
                histogram = true,
                waveform = true,
                punchIn = true,
                teleFinder = true,
            )
        }

        assertEquals(
            RendererConfig(
                peaking = true,
                peakingLevel = PeakingLevel.HIGH,
                peakingColor = PeakingColor.YELLOW,
                zebra = true,
                zebraLevel = ZebraLevel.IRE70,
                falseColor = true,
                histogram = true,
                waveform = true,
                punchIn = true,
                teleFinder = true,
            ),
            store.snapshot(),
        )
    }

    @Test
    fun `later updates preserve unrelated desired renderer fields`() {
        val store = RendererConfigStore(RendererConfig(peaking = true, histogram = true))

        store.update { it.copy(zebra = true) }
        store.update { it.copy(punchIn = true) }
        store.update { it.copy(teleFinder = true) }

        assertEquals(
            RendererConfig(peaking = true, zebra = true, histogram = true, punchIn = true, teleFinder = true),
            store.snapshot(),
        )
    }
}

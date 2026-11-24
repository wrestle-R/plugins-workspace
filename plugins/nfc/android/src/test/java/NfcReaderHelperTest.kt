// Copyright 2019-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

package app.tauri.nfc

import android.nfc.NfcAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcReaderHelperTest {
    @Test
    fun readerModeFlags_includesAllTechsAndOptionalPlatformSoundsFlag() {
        val withSounds = NfcReaderHelper.readerModeFlags(noPlatformSounds = false)
        assertEquals(0, withSounds and NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS)
        assertTrue(withSounds and NfcAdapter.FLAG_READER_NFC_A != 0)
        assertTrue(withSounds and NfcAdapter.FLAG_READER_NFC_B != 0)
        assertTrue(withSounds and NfcAdapter.FLAG_READER_NFC_F != 0)
        assertTrue(withSounds and NfcAdapter.FLAG_READER_NFC_V != 0)
        assertTrue(withSounds and NfcAdapter.FLAG_READER_NFC_BARCODE != 0)

        val silent = NfcReaderHelper.readerModeFlags(noPlatformSounds = true)
        assertTrue(silent and NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS != 0)
    }

    @Test
    fun matchesTechLists_handlesAndOrSemantics() {
        val tagTechs = arrayOf(
            "android.nfc.tech.NfcA",
            "android.nfc.tech.IsoDep"
        )
        val techLists = arrayOf(
            arrayOf("android.nfc.tech.NfcF"),
            arrayOf("android.nfc.tech.NfcA", "android.nfc.tech.IsoDep")
        )
        assertTrue(NfcReaderHelper.matchesTechLists(tagTechs, techLists))
        assertFalse(
            NfcReaderHelper.matchesTechLists(
                arrayOf("android.nfc.tech.NfcA"),
                techLists
            )
        )
    }

    @Test
    fun matchesUriParts_andMimeTypePattern_validateFilters() {
        val emptyUri = UriFilter()
        assertFalse(NfcReaderHelper.hasUriFilter(emptyUri))

        val filter = UriFilter().apply {
            scheme = "https"
            host = "tauri.app"
            pathPrefix = "/docs"
        }
        assertTrue(NfcReaderHelper.hasUriFilter(filter))
        assertTrue(NfcReaderHelper.matchesUriParts("HTTPS", "TAURI.APP", "/docs/nfc", filter))
        assertFalse(NfcReaderHelper.matchesUriParts("http", "tauri.app", "/docs/nfc", filter))
        assertFalse(NfcReaderHelper.matchesUriParts("https", "tauri.app", "/other", filter))

        assertTrue(NfcReaderHelper.matchesMimeTypePattern("text/plain", "TEXT/PLAIN"))
        assertTrue(NfcReaderHelper.matchesMimeTypePattern("text/plain", "text/*"))
        assertFalse(NfcReaderHelper.matchesMimeTypePattern("application/json", "text/*"))
    }
}

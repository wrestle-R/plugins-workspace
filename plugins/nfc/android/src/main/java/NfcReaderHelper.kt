// Copyright 2019-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

package app.tauri.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef

internal object NfcReaderHelper {
    private const val BASE_READER_FLAGS =
        NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NFC_BARCODE

    fun readerModeFlags(noPlatformSounds: Boolean): Int {
        return if (noPlatformSounds) {
            BASE_READER_FLAGS or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        } else {
            BASE_READER_FLAGS
        }
    }

    fun hasUriFilter(uri: UriFilter?): Boolean {
        return uri != null && (uri.scheme != null || uri.host != null || uri.pathPrefix != null)
    }

    fun matchesTechLists(tagTechs: Array<String>, techLists: Array<Array<String>>?): Boolean {
        if (techLists == null) {
            return true
        }
        val available = tagTechs.toSet()
        return techLists.any { required -> required.all { it in available } }
    }

    fun matchesMimeTypePattern(recordMime: String?, filterMime: String): Boolean {
        val actual = recordMime?.trim()?.lowercase() ?: return false
        val expected = filterMime.trim().lowercase()
        if (expected == "*/*") {
            return true
        }
        if (expected.endsWith("/*")) {
            val prefix = expected.removeSuffix("*")
            return actual.startsWith(prefix)
        }
        return actual == expected
    }

    fun matchesUriParts(
        scheme: String?,
        host: String?,
        path: String?,
        filter: UriFilter
    ): Boolean {
        filter.scheme?.let {
            if (!it.equals(scheme, ignoreCase = true)) {
                return false
            }
        }
        filter.host?.let {
            if (!it.equals(host, ignoreCase = true)) {
                return false
            }
        }
        filter.pathPrefix?.let {
            if (path == null || !path.startsWith(it)) {
                return false
            }
        }
        return true
    }

    private fun matchesAnyRecord(
        records: Array<NdefRecord>,
        mimeType: String?,
        uri: UriFilter?
    ): Boolean {
        if (mimeType != null && records.none { matchesMimeTypePattern(it.toMimeType(), mimeType) }) {
            return false
        }
        return uri == null || !hasUriFilter(uri) || records.any { record ->
            record.toUri()?.let { matchesUriParts(it.scheme, it.host, it.path, uri) } == true
        }
    }

    fun matchesTag(
        tag: Tag,
        kind: ScanKind,
        ndef: Ndef?,
        ndefMessage: NdefMessage?
    ): Boolean {
        return when (kind) {
            is ScanKind.Ndef -> {
                val techLists = kind.techLists()
                if (techLists != null) {
                    if (!matchesTechLists(tag.techList, techLists)) {
                        return false
                    }
                } else if (ndef == null) {
                    return false
                }

                if (kind.mimeType != null || hasUriFilter(kind.uri)) {
                    val records = ndefMessage?.records ?: return false
                    return matchesAnyRecord(records, kind.mimeType, kind.uri)
                }
                true
            }
            is ScanKind.Tag -> {
                if (kind.mimeType != null || hasUriFilter(kind.uri)) {
                    val records = ndefMessage?.records ?: return false
                    return matchesAnyRecord(records, kind.mimeType, kind.uri)
                }
                true
            }
        }
    }
}

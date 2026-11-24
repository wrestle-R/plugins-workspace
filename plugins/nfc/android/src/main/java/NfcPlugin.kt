// Copyright 2019-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

package app.tauri.nfc

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.webkit.WebView
import app.tauri.Logger
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSArray
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import org.json.JSONArray
import java.io.IOException
import kotlin.concurrent.thread

sealed class NfcAction {
    object Read : NfcAction()
    data class Write(val message: NdefMessage) : NfcAction()
}

@InvokeArg
class UriFilter {
    var scheme: String? = null
    var host: String? = null
    var pathPrefix: String? = null
}

@InvokeArg
enum class TechKind(@JsonValue val value: String) {
    IsoDep("IsoDep"),
    MifareClassic("MifareClassic"),
    MifareUltralight("MifareUltralight"),
    Ndef("Ndef"),
    NdefFormatable("NdefFormatable"),
    NfcA("NfcA"),
    NfcB("NfcB"),
    NfcBarcode("NfcBarcode"),
    NfcF("NfcF"),
    NfcV("NfcV");

    fun className(): String {
        return when (this) {
            IsoDep -> {
                android.nfc.tech.IsoDep::class.java.name
            }
            MifareClassic -> {
                android.nfc.tech.MifareClassic::class.java.name
            }
            MifareUltralight -> {
                android.nfc.tech.MifareUltralight::class.java.name
            }
            Ndef -> {
                android.nfc.tech.Ndef::class.java.name
            }
            NdefFormatable -> {
                android.nfc.tech.NdefFormatable::class.java.name
            }
            NfcA -> {
                android.nfc.tech.NfcA::class.java.name
            }
            NfcB -> {
                android.nfc.tech.NfcB::class.java.name
            }
            NfcBarcode -> {
                android.nfc.tech.NfcBarcode::class.java.name
            }
            NfcF -> {
                android.nfc.tech.NfcF::class.java.name
            }
            NfcV -> {
                android.nfc.tech.NfcV::class.java.name
            }
        }
    }
}

@InvokeArg
@JsonDeserialize(using = ScanKindDeserializer::class)
sealed class ScanKind {
    @JsonDeserialize
    class Tag: ScanKind() {
        var mimeType: String? = null
        var uri: UriFilter? = null
    }
    @JsonDeserialize
    class Ndef: ScanKind() {
        var mimeType: String? = null
        var uri: UriFilter? = null
        var techLists: Array<Array<TechKind>>? = null
    }

    fun techLists(): Array<Array<String>>? {
        return when (this) {
            is Tag -> null
            is Ndef -> {
                techLists?.let {
                    val techs = mutableListOf<Array<String>>()
                    for (techList in it) {
                        val list = mutableListOf<String>()
                        for (tech in techList) {
                            list.add(tech.className())
                        }
                        techs.add(list.toTypedArray())
                    }
                    techs.toTypedArray()
                }
            }
        }
    }
}

internal class ScanKindDeserializer: JsonDeserializer<ScanKind>() {
    override fun deserialize(
        jsonParser: JsonParser,
        deserializationContext: DeserializationContext
    ): ScanKind {
        val node: JsonNode = jsonParser.codec.readTree(jsonParser)
        node.get("tag")?.let {
            return jsonParser.codec.treeToValue(it, ScanKind.Tag::class.java)
        } ?: node.get("ndef")?.let {
            return jsonParser.codec.treeToValue(it, ScanKind.Ndef::class.java)
        } ?: run {
            throw Error("unknown scan kind $node")
        }
    }
}

@InvokeArg
class ScanOptions {
    lateinit var kind: ScanKind
    var keepSessionAlive: Boolean = false
    var noPlatformSounds: Boolean = false
}

@InvokeArg
class NDEFRecordData {
    var format: Short = 0
    var kind: ByteArray = ByteArray(0)
    var id: ByteArray = ByteArray(0)
    var payload: ByteArray = ByteArray(0)
}

@InvokeArg
class WriteOptions {
    var kind: ScanKind? = null
    lateinit var records: Array<NDEFRecordData>
    var noPlatformSounds: Boolean = false
}

class Session(
    val action: NfcAction,
    var invoke: Invoke?,
    val keepAlive: Boolean,
    var tag: Tag? = null,
    val kind: ScanKind? = null,
    val readerModeFlags: Int = NfcReaderHelper.readerModeFlags(false),
    var writing: Boolean = false
)

@TauriPlugin
class NfcPlugin(private val activity: Activity) : Plugin(activity) {
    private var nfcAdapter: NfcAdapter? = null
    private var session: Session? = null
    private val sessionLock = Any()

    // Accessed only on the activity thread.
    private var readerSession: Session? = null
    private var paused = false

    private fun onTagDiscovered(currentSession: Session, tag: Tag) {
        synchronized(sessionLock) {
            if (session !== currentSession || currentSession.invoke == null) return
        }
        when (val action = currentSession.action) {
            is NfcAction.Read -> handleDiscoveredRead(currentSession, tag)
            is NfcAction.Write -> handleDiscoveredWrite(currentSession, tag, action.message)
        }
    }

    override fun load(webView: WebView) {
        super.load(webView)
        this.nfcAdapter = NfcAdapter.getDefaultAdapter(activity.applicationContext)
    }

    override fun onPause() {
        paused = true
        updateReaderMode()
        super.onPause()
        Logger.info("NFC", "onPause")
    }

    override fun onResume() {
        super.onResume()
        Logger.info("NFC", "onResume")
        paused = false
        updateReaderMode()
    }

    private fun isAvailable(): Pair<Boolean, String?> {
        val available: Boolean
        var errorReason: String? = null

        if (this.nfcAdapter === null) {
            available = false
            errorReason = "Device does not have NFC capabilities"
        } else if (this.nfcAdapter?.isEnabled == false) {
            available = false
            errorReason = "NFC is disabled in device settings"
        } else {
            available = true
        }

        return Pair(available, errorReason)
    }

    @Command
    fun isAvailable(invoke: Invoke) {
        val ret = JSObject()
        ret.put("available", isAvailable().first)
        invoke.resolve(ret)
    }

    @Command
    fun scan(invoke: Invoke) {
        val status = isAvailable()
        if (!status.first) {
            invoke.reject("NFC unavailable: " + status.second)
            return
        }

        val args = invoke.parseArgs(ScanOptions::class.java)
        val flags = NfcReaderHelper.readerModeFlags(args.noPlatformSounds)

        val previousInvoke = synchronized(sessionLock) {
            if (session?.writing == true) {
                invoke.reject("An NFC write is already in progress")
                return
            }
            val prev = session?.invoke
            session = Session(
                NfcAction.Read,
                invoke,
                args.keepSessionAlive,
                null,
                args.kind,
                flags
            )
            prev
        }
        previousInvoke?.reject("Scan superseded by a new scan")

        updateReaderMode()
    }

    @Command
    fun write(invoke: Invoke) {
        val status = isAvailable()
        if (!status.first) {
            invoke.reject("NFC unavailable: " + status.second)
            return
        }

        val args = invoke.parseArgs(WriteOptions::class.java)

        val ndefRecords: MutableList<NdefRecord> = ArrayList()
        for (record in args.records) {
            ndefRecords.add(NdefRecord(record.format, record.kind, record.id, record.payload))
        }

        val message = NdefMessage(ndefRecords.toTypedArray())

        val retainedTag = synchronized(sessionLock) {
            val current = session
            if (current != null) {
                val tag = current.tag
                if (tag == null || current.writing) {
                    invoke.reject("NFC session is busy or has no connected tag")
                    return
                }
                current.writing = true
                Pair(current, tag)
            } else {
                val kind = args.kind
                if (kind == null) {
                    invoke.reject("Missing `kind` for write")
                    return
                }
                session = Session(
                    NfcAction.Write(message), invoke, false, null, kind,
                    NfcReaderHelper.readerModeFlags(args.noPlatformSounds)
                )
                null
            }
        }
        if (retainedTag != null) {
            val (current, tag) = retainedTag
            thread {
                val result = runCatching { writeTag(tag, message) }
                synchronized(sessionLock) { current.writing = false }
                result.fold(
                    onSuccess = { invoke.resolve() },
                    onFailure = { invoke.reject(it.toString()) }
                )
            }
            return
        }

        updateReaderMode()
        Logger.warn("NFC", "Write Mode Enabled")
    }

    private fun readNdefMessage(tag: Tag): Pair<Ndef?, NdefMessage?> {
        val ndef = Ndef.get(tag) ?: return Pair(null, null)
        var message = ndef.cachedNdefMessage
        if (message == null) {
            try {
                ndef.connect()
                message = ndef.ndefMessage
            } catch (_: Exception) {
                // Tag may be unformatted or unreadable as NDEF
            } finally {
                try {
                    if (ndef.isConnected) {
                        ndef.close()
                    }
                } catch (_: IOException) {
                }
            }
        }
        return Pair(ndef, message)
    }

    private fun handleDiscoveredRead(currentSession: Session, tag: Tag) {
        val invokeToResolve: Invoke
        try {
            val (ndef, ndefMessage) = readNdefMessage(tag)
            val kind = currentSession.kind
            if (kind != null && !NfcReaderHelper.matchesTag(tag, kind, ndef, ndefMessage)) {
                return
            }

            val records = ndefMessage?.records ?: arrayOf()
            val jsonRecords = Array(records.size) { i -> recordToJson(records[i]) }

            val ret = JSObject()
            ret.put("id", fromU8Array(tag.id))
            ret.put("kind", JSArray.from(tag.techList))
            ret.put("records", JSArray.from(jsonRecords))

            synchronized(sessionLock) {
                if (session !== currentSession || currentSession.invoke == null) {
                    return
                }
                invokeToResolve = currentSession.invoke!!
                currentSession.invoke = null
                if (currentSession.keepAlive) {
                    currentSession.tag = tag
                } else {
                    session = null
                }
            }

            if (!currentSession.keepAlive) {
                updateReaderMode()
            }
            invokeToResolve.resolve(ret)
        } catch (e: Exception) {
            val invokeToReject = synchronized(sessionLock) {
                if (session === currentSession) {
                    val inv = currentSession.invoke
                    currentSession.invoke = null
                    session = null
                    inv
                } else {
                    null
                }
            }
            updateReaderMode()
            invokeToReject?.reject("failed to read tag", e)
        }
    }

    private fun handleDiscoveredWrite(currentSession: Session, tag: Tag, message: NdefMessage) {
        val kind = currentSession.kind
        if (kind != null) {
            val (ndef, ndefMessage) = readNdefMessage(tag)
            if (!NfcReaderHelper.matchesTag(tag, kind, ndef, ndefMessage)) {
                return
            }
        }

        val invokeToSettle = synchronized(sessionLock) {
            if (session !== currentSession || currentSession.writing) return
            val pending = currentSession.invoke ?: return
            currentSession.writing = true
            currentSession.invoke = null
            pending
        }

        val result = runCatching { writeTag(tag, message) }
        synchronized(sessionLock) {
            if (session === currentSession) session = null
        }
        updateReaderMode()
        result.fold(
            onSuccess = { invokeToSettle.resolve() },
            onFailure = { invokeToSettle.reject(it.toString()) }
        )
    }

    private fun writeTag(tag: Tag, message: NdefMessage) {
        // This should return tags that are already in ndef format
        val ndefTag = Ndef.get(tag)
        if (ndefTag !== null) {
            // We have to connect first to check maxSize.
            try {
                ndefTag.connect()
            } catch (e: IOException) {
                throw Exception("Couldn't connect to NFC tag", e)
            }

            try {
                if (ndefTag.maxSize < message.toByteArray().size) {
                    throw Exception("The message is too large for the provided NFC tag")
                } else if (!ndefTag.isWritable) {
                    throw Exception("NFC tag is read-only")
                } else {
                    try {
                        ndefTag.writeNdefMessage(message)
                    } catch (e: Exception) {
                        throw Exception("Couldn't write message to NFC tag", e)
                    }
                }
            } finally {
                try {
                    ndefTag.close()
                } catch (e: IOException) {
                    Logger.error("failed to close tag", e)
                }
            }

            return
        }

        // This should cover tags that are not yet in ndef format but can be converted
        val ndefFormatableTag = NdefFormatable.get(tag)
        if (ndefFormatableTag !== null) {
            try {
                ndefFormatableTag.connect()
                ndefFormatableTag.format(message)
            } catch (e: Exception) {
                throw Exception("Couldn't format tag as Ndef", e)
            } finally {
                try {
                    ndefFormatableTag.close()
                } catch (e: IOException) {
                    Logger.error("failed to close tag", e)
                }
            }

            return
        }

        // if we get to this line, the tag was neither Ndef nor NdefFormatable compatible
        throw Exception("Tag doesn't support Ndef format")
    }

    private fun updateReaderMode() {
        activity.runOnUiThread {
            if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
            val desired = if (paused) null else synchronized(sessionLock) { session }
            if (readerSession === desired) return@runOnUiThread
            if (desired == null) {
                nfcAdapter?.disableReaderMode(activity)
            } else {
                nfcAdapter?.enableReaderMode(
                    activity, { tag -> onTagDiscovered(desired, tag) }, desired.readerModeFlags, null
                )
            }
            readerSession = desired
        }
    }
}

private fun fromU8Array(byteArray: ByteArray): JSONArray {
    val json = JSONArray()
    for (byte in byteArray) {
        json.put(byte.toInt() and 0xFF)
    }
    return json
}

private fun recordToJson(record: NdefRecord): JSObject {
    val json = JSObject()
    json.put("tnf", record.tnf)
    json.put("kind", fromU8Array(record.type))
    json.put("id", fromU8Array(record.id))
    json.put("payload", fromU8Array(record.payload))
    return json
}

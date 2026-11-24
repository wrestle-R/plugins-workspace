---
nfc: minor
nfc-js: minor
---

Use Android `NfcAdapter.enableReaderMode` and `disableReaderMode` instead of foreground dispatch when scanning and writing NFC tags so system tag handling does not run alongside the app. Added the Android-only `noPlatformSounds` option to `ScanOptions` and `WriteOptions`.

// Copyright 2019-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

use crate::{NfcExt, TransceiveRequest, TransceiveResponse};
use tauri::{AppHandle, Runtime};

#[tauri::command]
pub async fn transceive<R: Runtime>(
    app: AppHandle<R>,
    request: TransceiveRequest,
) -> crate::Result<TransceiveResponse> {
    app.nfc().transceive(request).await
}

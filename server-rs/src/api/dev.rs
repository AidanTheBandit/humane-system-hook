use std::process::Stdio;
use std::time::{SystemTime, UNIX_EPOCH};

use axum::extract::multipart::Field;
use axum::extract::{DefaultBodyLimit, Multipart, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::post;
use axum::{Json, Router};
use tokio::io::AsyncWriteExt;
use tokio::process::Command;

use crate::api::ApiState;

const SYSTEM_INJECTOR_STAGING_URI: &str = "content://com.penumbraos.systeminjector.staging";

pub fn router() -> Router<ApiState> {
    Router::new()
        .route("/install", post(install_apks))
        .layer(DefaultBodyLimit::max(512 * 1024 * 1024))
}

async fn install_apks(State(state): State<ApiState>, mut multipart: Multipart) -> Response {
    if let Err(response) = check_dev_apk_install_permission(&state).await {
        return response;
    }

    let mut staged_apks = Vec::new();
    let mut index = 0usize;
    loop {
        let field = match multipart.next_field().await {
            Ok(Some(field)) => field,
            Ok(None) => break,
            Err(e) => {
                return (
                    StatusCode::BAD_REQUEST,
                    format!("failed to read multipart body: {e}"),
                )
                    .into_response();
            }
        };

        if field.name() != Some("apk") {
            continue;
        }

        let filename = match staging_filename(index, field.file_name()) {
            Ok(filename) => filename,
            Err(message) => return (StatusCode::BAD_REQUEST, message).into_response(),
        };
        index += 1;

        let bytes = match stream_apk_to_staging(&filename, field).await {
            Ok(bytes) => bytes,
            Err(response) => return response,
        };
        staged_apks.push(serde_json::json!({
            "filename": filename,
            "bytes": bytes,
        }));
    }

    if staged_apks.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            "multipart body must contain at least one 'apk' file part",
        )
            .into_response();
    }

    let staged_filenames = staged_apks
        .iter()
        .filter_map(|apk| apk.get("filename").and_then(|filename| filename.as_str()))
        .collect::<Vec<_>>();
    let install_arg = staged_filenames.join(",");
    let output = match execute_content_install(&install_arg).await {
        Ok(output) => output,
        Err(response) => return response,
    };

    if !output.contains("OK") {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({
                "error": "system-injector install failed",
                "output": output,
            })),
        )
            .into_response();
    }

    Json(serde_json::json!({
        "accepted": true,
        "restart_expected": true,
        "apks": staged_apks,
    }))
    .into_response()
}

async fn stream_apk_to_staging(filename: &str, mut field: Field<'_>) -> Result<usize, Response> {
    let uri = format!("{SYSTEM_INJECTOR_STAGING_URI}/{filename}");
    let mut child = Command::new("/system/bin/content")
        .args(["write", "--uri", &uri])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| {
            tracing::error!(error = %e, "failed to spawn content write");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("failed to spawn content write: {e}"),
            )
                .into_response()
        })?;

    let mut stdin = child.stdin.take().ok_or_else(|| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            "content write stdin unavailable",
        )
            .into_response()
    })?;

    let mut bytes_written: usize = 0;
    while let Some(chunk) = field.chunk().await.map_err(|e| {
        let _ = child.start_kill();
        (
            StatusCode::BAD_REQUEST,
            format!("failed to read APK part: {e}"),
        )
            .into_response()
    })? {
        bytes_written += chunk.len();
        if let Err(e) = stdin.write_all(&chunk).await {
            let _ = child.start_kill();
            return Err((
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("failed to stream APK to content provider: {e}"),
            )
                .into_response());
        }
    }
    drop(stdin);

    let output = child.wait_with_output().await.map_err(|e| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("failed waiting for content write: {e}"),
        )
            .into_response()
    })?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        tracing::warn!(status = ?output.status, %stderr, "content write failed");
        return Err((
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({
                "error": "content write failed",
                "filename": filename,
                "stderr": stderr.trim(),
            })),
        )
            .into_response());
    }

    Ok(bytes_written)
}

async fn execute_content_install(install_arg: &str) -> Result<String, Response> {
    let output = Command::new("/system/bin/content")
        .args([
            "call",
            "--uri",
            SYSTEM_INJECTOR_STAGING_URI,
            "--method",
            "install",
            "--arg",
            install_arg,
        ])
        .output()
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("failed to spawn content call: {e}"),
            )
                .into_response()
        })?;

    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
    if !output.status.success() {
        return Err((
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({
                "error": "content call failed",
                "stdout": stdout,
                "stderr": stderr,
            })),
        )
            .into_response());
    }

    Ok(if stderr.is_empty() {
        stdout
    } else {
        format!("{stdout}\n{stderr}")
    })
}

fn staging_filename(index: usize, original_filename: Option<&str>) -> Result<String, String> {
    let source = original_filename.unwrap_or("app.apk");
    let basename = source
        .rsplit(['/', '\\'])
        .next()
        .filter(|name| !name.is_empty())
        .unwrap_or("app.apk");

    let sanitized = basename
        .bytes()
        .map(|b| {
            if b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-') {
                b as char
            } else {
                '_'
            }
        })
        .collect::<String>();
    let safe_name = if sanitized.is_empty() || sanitized == "." || sanitized == ".." {
        "app.apk".to_owned()
    } else {
        sanitized
    };
    let timestamp_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|e| format!("system clock error: {e}"))?
        .as_millis();
    let filename = format!("{timestamp_ms}-{index}-{safe_name}");
    validate_apk_filename(&filename)?;
    Ok(filename)
}

fn validate_apk_filename(filename: &str) -> Result<(), String> {
    if filename.is_empty() {
        return Err("filename must not be empty".into());
    }

    if filename.contains('/') || filename.contains("..") {
        return Err("filename must not contain '/' or '..'".into());
    }

    if !filename
        .bytes()
        .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-'))
    {
        return Err("filename contains unsupported characters".into());
    }

    Ok(())
}

async fn check_dev_apk_install_permission(state: &ApiState) -> Result<(), Response> {
    let config = state.shared_config.read().await;

    if config.dev.apk_install_enabled {
        Ok(())
    } else {
        Err((
            StatusCode::FORBIDDEN,
            Json(serde_json::json!({
                "error": "dev APK install is disabled",
            })),
        )
            .into_response())
    }
}

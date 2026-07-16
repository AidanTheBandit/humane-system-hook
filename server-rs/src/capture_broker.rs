//! Coordinate live camera captures for external agents (Hermes/Starlight).
//!
//! Flow:
//! 1. LLM returns ChatResult::DeferredVision → pin action UnderstandScene
//! 2. Pin camera fires → AnalyzeImage uploads JPEG bytes
//! 3. CaptureBroker publishes the bytes
//! 4. HTTP GET /api/photos/wait returns base64 to the agent

use std::sync::Arc;
use std::time::{Duration, Instant};

use tokio::sync::{Mutex, Notify};
use uuid::Uuid;

#[derive(Clone)]
pub struct CaptureBroker {
    inner: Arc<Mutex<State>>,
    notify: Arc<Notify>,
}

struct State {
    /// Next capture is expected (optional intent tracking).
    pending: Option<PendingCapture>,
    /// Most recent capture, if any.
    latest: Option<CapturedImage>,
    /// Generation counter so waiters can ignore stale images.
    generation: u64,
}

#[derive(Clone)]
struct PendingCapture {
    request_id: String,
    question: String,
    created_at: Instant,
}

#[derive(Clone)]
pub struct CapturedImage {
    pub request_id: String,
    pub question: String,
    pub bytes: Vec<u8>,
    pub captured_at: Instant,
    pub generation: u64,
}

impl CaptureBroker {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(Mutex::new(State {
                pending: None,
                latest: None,
                generation: 0,
            })),
            notify: Arc::new(Notify::new()),
        }
    }

    /// Record that we asked the pin to take a photo (optional bookkeeping).
    pub async fn request_capture(&self, question: impl Into<String>) -> String {
        let request_id = Uuid::new_v4().to_string();
        let mut state = self.inner.lock().await;
        state.pending = Some(PendingCapture {
            request_id: request_id.clone(),
            question: question.into(),
            created_at: Instant::now(),
        });
        request_id
    }

    /// Called from AnalyzeImage when the pin uploads a frame.
    pub async fn publish(&self, bytes: Vec<u8>) {
        let mut state = self.inner.lock().await;
        state.generation = state.generation.saturating_add(1);
        let (request_id, question) = match state.pending.take() {
            Some(p) => (p.request_id, p.question),
            None => (Uuid::new_v4().to_string(), String::new()),
        };
        state.latest = Some(CapturedImage {
            request_id,
            question,
            bytes,
            captured_at: Instant::now(),
            generation: state.generation,
        });
        self.notify.notify_waiters();
    }

    pub async fn latest(&self) -> Option<CapturedImage> {
        self.inner.lock().await.latest.clone()
    }

    /// Wait until a capture with generation > `after_generation` arrives, or timeout.
    pub async fn wait_for_capture(
        &self,
        after_generation: u64,
        timeout: Duration,
    ) -> Option<CapturedImage> {
        let deadline = Instant::now() + timeout;
        loop {
            {
                let state = self.inner.lock().await;
                if let Some(img) = &state.latest {
                    if img.generation > after_generation {
                        return Some(img.clone());
                    }
                }
            }

            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return None;
            }

            tokio::select! {
                _ = self.notify.notified() => {}
                _ = tokio::time::sleep(remaining) => {
                    // Final check
                    let state = self.inner.lock().await;
                    if let Some(img) = &state.latest {
                        if img.generation > after_generation {
                            return Some(img.clone());
                        }
                    }
                    return None;
                }
            }
        }
    }

    pub async fn current_generation(&self) -> u64 {
        self.inner.lock().await.generation
    }
}

impl Default for CaptureBroker {
    fn default() -> Self {
        Self::new()
    }
}

//! Transparent gRPC request deduplication.
//!
//! [`DedupRouter`] wraps [`tonic::service::Routes`] and adds a dedup middleware
//! layer automatically.
//!
//! When duplicate requests (same gRPC path + identical body bytes) arrive in a
//! short burst, the first executes normally while concurrent duplicates coalesce
//! onto it and share the response. Completed responses are cached for a
//! configurable per-method TTL.

use std::collections::HashMap;
use std::convert::Infallible;
use std::sync::Arc;
use std::time::{Duration, Instant};

use axum::body::Body;
use http::HeaderMap;
use http_body::Frame;
use http_body_util::BodyExt;
use prost::bytes::Bytes;
use tokio::sync::{broadcast, Mutex};
use tonic::server::NamedService;
use tower::Service;
use tracing::info;

/// A drop-in replacement for [`tonic::service::Routes`] that adds transparent
/// per-method request deduplication.
///
/// Methods registered with [`.dedup()`](DedupRouter::dedup) will have their
/// responses cached for the given TTL. Concurrent identical requests coalesce
/// onto a single handler invocation.
pub struct DedupRouter {
    routes: tonic::service::Routes,
    dedup: GrpcDedup,
}

impl DedupRouter {
    /// Start a new router with an initial service.
    pub fn new<S>(svc: S) -> Self
    where
        S: Service<http::Request<tonic::body::Body>, Error = Infallible>
            + NamedService
            + Clone
            + Send
            + Sync
            + 'static,
        S::Response: axum::response::IntoResponse,
        S::Future: Send + 'static,
    {
        Self {
            routes: tonic::service::Routes::new(svc),
            dedup: GrpcDedup::new(),
        }
    }

    /// Add a service without dedup.
    pub fn add_service<S>(mut self, svc: S) -> Self
    where
        S: Service<http::Request<tonic::body::Body>, Error = Infallible>
            + NamedService
            + Clone
            + Send
            + Sync
            + 'static,
        S::Response: axum::response::IntoResponse,
        S::Future: Send + 'static,
    {
        self.routes = self.routes.add_service(svc);
        self
    }

    /// Register a method on a specific service for dedup with the given cache
    /// TTL.
    ///
    /// `S` is the gRPC service server type (must implement [`NamedService`]).
    /// `method` is the bare method name (e.g. `"EncryptedWeather"`).  The full
    /// gRPC path is derived from `S::NAME`.
    ///
    /// ```ignore
    /// type AiBus = AiBusServiceServer<AiBusServiceImpl>;
    ///
    /// DedupRouter::new(AiBusServiceServer::new(impl_))
    ///     .dedup::<AiBus>("EncryptedWeather", Duration::from_secs(300))
    ///     .dedup::<AiBus>("Understand", Duration::from_millis(200))
    ///     .add_service(PushRelayServiceServer::new(impl_))
    /// ```
    pub fn dedup<S: NamedService>(mut self, method: &str, ttl: Duration) -> Self {
        let path = format!("/{}/{}", S::NAME, method);
        self.dedup.routes.insert(path, ttl);
        self
    }

    /// Finalize into an [`axum::Router`] with the dedup middleware applied.
    pub fn into_axum_router(self) -> axum::Router {
        self.routes
            .into_axum_router()
            .layer(axum::middleware::from_fn_with_state(
                self.dedup,
                dedup_middleware,
            ))
    }
}

/// Everything needed to reconstruct an HTTP response from cache.
#[derive(Clone)]
struct CachedResponse {
    status: http::StatusCode,
    headers: HeaderMap,
    body: Bytes,
    trailers: Option<HeaderMap>,
    created: Instant,
}

type DedupKey = (String, Bytes); // (gRPC path, raw request body)

struct Inner {
    /// In-flight requests: the first caller registers a broadcast sender;
    /// subsequent callers subscribe and wait for the result.
    inflight: HashMap<DedupKey, broadcast::Sender<CachedResponse>>,

    /// Recently completed responses, kept for the method's configured TTL.
    cache: HashMap<DedupKey, CachedResponse>,
}

/// Shared dedup state.  Constructed internally by [`DedupRouter`].
#[derive(Clone)]
struct GrpcDedup {
    routes: HashMap<String, Duration>,
    inner: Arc<Mutex<Inner>>,
}

impl GrpcDedup {
    fn new() -> Self {
        Self {
            routes: HashMap::new(),
            inner: Arc::new(Mutex::new(Inner {
                inflight: HashMap::new(),
                cache: HashMap::new(),
            })),
        }
    }
}

/// axum middleware that performs the actual dedup logic.
async fn dedup_middleware(
    axum::extract::State(dedup): axum::extract::State<GrpcDedup>,
    request: axum::extract::Request,
    next: axum::middleware::Next,
) -> axum::response::Response {
    let path = request.uri().path().to_owned();

    // If this method isn't configured for dedup, pass through
    let ttl = match dedup.routes.get(&path) {
        Some(ttl) => *ttl,
        None => return next.run(request).await,
    };

    // Read the request body so we can use it as a cache key, then
    // reconstruct the request for the downstream handler
    let (parts, body) = request.into_parts();
    let body_bytes = match axum::body::to_bytes(body, 4 * 1024 * 1024).await {
        Ok(b) => b,
        Err(_) => {
            // Can't read body
            let request = http::Request::from_parts(parts, Body::empty());
            return next.run(request).await;
        }
    };

    let key: DedupKey = (path.clone(), body_bytes.clone());

    let receiver = {
        let mut inner = dedup.inner.lock().await;

        if let Some(entry) = inner.cache.get(&key) {
            if entry.created.elapsed() < ttl {
                info!(path = %path, "dedup: cache hit");
                return rebuild_response(entry);
            } else {
                inner.cache.remove(&key);
            }
        }

        if let Some(tx) = inner.inflight.get(&key) {
            Some(tx.subscribe())
        } else {
            let (tx, _) = broadcast::channel(4);
            inner.inflight.insert(key.clone(), tx);
            None
        }
    };

    // Coalesce. Wait for original request
    if let Some(mut rx) = receiver {
        info!(path = %path, "dedup: coalescing with in-flight request");
        if let Ok(cached) = rx.recv().await {
            info!(path = %path, "dedup: received coalesced result");
            return rebuild_response(&cached);
        }

        // Original request dropped without sending. Take over as leader.
        let mut inner = dedup.inner.lock().await;
        if !inner.inflight.contains_key(&key) {
            let (tx, _) = broadcast::channel(4);
            inner.inflight.insert(key.clone(), tx);
        }
    }

    // Execute the real handler as leader
    let request = http::Request::from_parts(parts, Body::from(body_bytes));
    let response = next.run(request).await;

    // Collect the response body frame-by-frame so we capture both DATA frames
    // and the trailing HEADERS frame (gRPC trailers). gRPC over HTTP/2
    // requires trailers (containing `grpc-status`)
    let (resp_parts, resp_body) = response.into_parts();

    let mut data_buf = Vec::new();
    let mut trailers: Option<HeaderMap> = None;

    let mut body = resp_body;
    while let Some(frame) = body.frame().await {
        match frame {
            Ok(frame) => {
                if let Some(data) = frame.data_ref() {
                    data_buf.extend_from_slice(data);
                } else if frame.is_trailers() {
                    trailers = frame.into_trailers().ok();
                }
            }
            Err(_) => break,
        }
    }

    // If the body stream didn't yield explicit trailers, fall back to
    // synthesizing a grpc-status: 0 trailer so the client doesn't see an
    // unexpected EOS
    if trailers.is_none() {
        let mut t = HeaderMap::new();
        t.insert("grpc-status", http::HeaderValue::from_static("0"));
        trailers = Some(t);
    }

    let cached = CachedResponse {
        status: resp_parts.status,
        headers: resp_parts.headers.clone(),
        body: Bytes::from(data_buf),
        trailers,
        created: Instant::now(),
    };

    {
        let mut inner = dedup.inner.lock().await;
        if let Some(tx) = inner.inflight.remove(&key) {
            let _ = tx.send(cached.clone());
        }
        inner.cache.insert(key, cached.clone());
    }

    rebuild_response(&cached)
}

/// Reconstruct an HTTP response from a cached entry, preserving gRPC trailers.
fn rebuild_response(cached: &CachedResponse) -> axum::response::Response {
    let mut builder = http::Response::builder().status(cached.status);
    *builder.headers_mut().unwrap() = cached.headers.clone();

    // Build a body that yields the DATA frame(s) then the TRAILERS frame.
    let data = cached.body.clone();
    let trailers = cached.trailers.clone();

    let body = Body::new(TraileredBody {
        data: Some(data),
        trailers,
    });

    builder.body(body).unwrap()
}

/// A minimal [`http_body::Body`] implementation that yields a single DATA frame
/// followed by an optional TRAILERS frame.  This is necessary because
/// `Body::from(Bytes)` does not support trailers, which gRPC requires.
struct TraileredBody {
    data: Option<Bytes>,
    trailers: Option<HeaderMap>,
}

impl http_body::Body for TraileredBody {
    type Data = Bytes;
    type Error = axum::Error;

    fn poll_frame(
        mut self: std::pin::Pin<&mut Self>,
        _cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Option<Result<Frame<Self::Data>, Self::Error>>> {
        // Yield the DATA frame first, then the TRAILERS frame.
        if let Some(data) = self.data.take() {
            if !data.is_empty() {
                return std::task::Poll::Ready(Some(Ok(Frame::data(data))));
            }
        }
        if let Some(trailers) = self.trailers.take() {
            return std::task::Poll::Ready(Some(Ok(Frame::trailers(trailers))));
        }
        std::task::Poll::Ready(None)
    }
}

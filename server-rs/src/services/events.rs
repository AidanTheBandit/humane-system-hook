use tonic::{Request, Response, Status};
use tracing::info;

use crate::proto::events::*;
use crate::proto::events::events_ingest_service_server::EventsIngestService;

pub struct EventsIngestServiceImpl;

#[tonic::async_trait]
impl EventsIngestService for EventsIngestServiceImpl {
    async fn ingest(
        &self,
        request: Request<NotableEvent>,
    ) -> Result<Response<IngestResponse>, Status> {
        let event = request.into_inner();
        let event_type = event.event_type;
        let id = event.event_identifier.clone();
        info!(event_type = event_type, ">>> Events.Ingest");
        Ok(Response::new(IngestResponse {
            event_identifier: id,
        }))
    }

    async fn ingest_batch(
        &self,
        request: Request<IngestBatchRequest>,
    ) -> Result<Response<IngestBatchResponse>, Status> {
        let batch = request.into_inner();
        let count = batch.events.len();
        info!(count = count, ">>> Events.IngestBatch");
        let ids: Vec<_> = batch
            .events
            .into_iter()
            .map(|e| e.event_identifier.unwrap_or_default())
            .collect();
        Ok(Response::new(IngestBatchResponse {
            event_identifier: ids,
        }))
    }
}

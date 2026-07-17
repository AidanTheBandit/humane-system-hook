use prost::Message as _;
use tonic::{Request, Response, Status};
use tracing::info;

use super::envelope::unwrap_plaintext_data;
use crate::nearby::NearbyClient;
use crate::proto::aibus::*;
use crate::proto::common::encryption::EncryptedData;

pub struct NearbySearchHandler {
    nearby_client: NearbyClient,
}

impl NearbySearchHandler {
    pub fn new(nearby_client: NearbyClient) -> Self {
        Self { nearby_client }
    }

    pub async fn encrypted_nearby_search(
        &self,
        request: Request<EncryptedNearbySearchRequest>,
    ) -> Result<Response<EncryptedNearbySearchResponse>, Status> {
        let req = request.into_inner();
        let request_bytes = unwrap_plaintext_data(&req.request)?;
        let nearby_req = NearbySearchRequest::decode(request_bytes)
            .map_err(|e| Status::invalid_argument(format!("bad NearbySearchRequest: {e}")))?;

        let location = nearby_req
            .location
            .ok_or_else(|| Status::invalid_argument("NearbySearchRequest missing location"))?;
        let lat = location.latitude;
        let lon = location.longitude;
        let base_radius = if nearby_req.radius_accuracy > 0.0 {
            nearby_req.radius_accuracy.max(3000.0)
        } else {
            3000.0
        };

        info!(
            lat = lat,
            lon = lon,
            radius = base_radius,
            query = %nearby_req.text_query,
            ">>> EncryptedNearbySearch"
        );

        // Try progressively wider radii until we get results (3km, 5km, 10km, 20km, 50km).
        // Rural areas can be very sparse in OSM; returning 0 places makes the native
        // UI say "can't locate you".
        let mut nearby_places = Vec::new();
        for radius in [base_radius, 5000.0, 10_000.0, 20_000.0, 50_000.0] {
            if radius < base_radius {
                continue;
            }
            match self.nearby_client.search(lat, lon, radius, &nearby_req.text_query).await {
                Ok(places) if !places.is_empty() => {
                    nearby_places = places;
                    info!(results = nearby_places.len(), radius, "<<< EncryptedNearbySearch (expanded)");
                    break;
                }
                Ok(_) => {
                    info!(radius, "0 results, expanding search radius");
                }
                Err(e) => {
                    tracing::warn!(error = %e, radius, "Overpass nearby search failed");
                    // If it's an actual server error (not just empty), return unavailable
                    // after the last attempt
                    if radius >= 50_000.0 {
                        return Err(Status::unavailable(format!("nearby search failed: {e}")));
                    }
                }
            }
        }

        let result_count = nearby_places.len();
        let nearby_response = NearbySearchResponse {
            nearby_places,
            status: Some(NearbySearchResultStatus::Success as i32),
        };

        info!(results = result_count, "<<< EncryptedNearbySearch");
        Ok(Response::new(EncryptedNearbySearchResponse {
            response: Some(EncryptedData::new(
                "humane.aibus.NearbySearchResponse",
                nearby_response.encode_to_vec(),
            )),
        }))
    }
}

use prost::Message as _;
use std::sync::Mutex;
use tonic::{Request, Response, Status};
use tracing::{info, warn};

use crate::proto::{aibus::*, common::encryption::EncryptedData};

/// Last known device location, pushed via PUT /api/location by the hook.
static LAST_LOCATION: Mutex<Option<(f64, f64, f64)>> = Mutex::new(None);

/// Update the cached device location (called by the API handler).
pub fn set_location(lat: f64, lon: f64, accuracy: f64) {
    info!(lat, lon, accuracy, "device location updated");
    *LAST_LOCATION.lock().unwrap() = Some((lat, lon, accuracy));
}

pub struct GeoLocateHandler;

impl GeoLocateHandler {
    pub async fn encrypted_geo_locate(
        &self,
        _request: Request<EncryptedGeoLocateRequest>,
    ) -> Result<Response<EncryptedGeoLocateResponse>, Status> {
        let cached = LAST_LOCATION.lock().unwrap().clone();
        match cached {
            Some((lat, lon, accuracy)) => {
                info!(lat, lon, accuracy, ">>> EncryptedGeoLocate (cached)");
                let geo_response = GeoLocateResponse {
                    location: Some(Location { latitude: lat, longitude: lon }),
                    radius_accuracy: accuracy,
                    status: GeoLocateResponseStatus::GeolocateResponseStatusSuccess as i32,
                };
                Ok(Response::new(EncryptedGeoLocateResponse {
                    response: Some(EncryptedData::new(
                        "humane.aibus.GeoLocateResponse",
                        geo_response.encode_to_vec(),
                    )),
                }))
            }
            None => {
                warn!(">>> EncryptedGeoLocate (no location cached)");
                let geo_response = GeoLocateResponse {
                    location: None,
                    radius_accuracy: 0.0,
                    status: GeoLocateResponseStatus::GeolocateResponseStatusNotFound as i32,
                };
                Ok(Response::new(EncryptedGeoLocateResponse {
                    response: Some(EncryptedData::new(
                        "humane.aibus.GeoLocateResponse",
                        geo_response.encode_to_vec(),
                    )),
                }))
            }
        }
    }
}

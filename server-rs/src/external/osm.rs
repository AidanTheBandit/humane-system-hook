//! Shared OpenStreetMap service client helpers.

use reqwest::RequestBuilder;
use serde::Serialize;

const OSM_USER_AGENT: &str = "PenumbraOS/0.1";
const NOMINATIM_REVERSE_URL: &str = "https://nominatim.openstreetmap.org/reverse";

/// Overpass API mirrors, tried in order. The main instance (overpass-api.de)
/// is frequently overloaded with "too busy" errors.
const OVERPASS_MIRRORS: &[&str] = &[
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
];

/// OpenStreetMap API client.
#[derive(Clone)]
pub struct OsmClient {
    http: reqwest::Client,
}

#[derive(Debug)]
pub enum OsmError {
    HttpRequest(reqwest::Error),
    ParseResponse(reqwest::Error),
    AllMirrorsFailed(String),
}

impl std::fmt::Display for OsmError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::HttpRequest(e) => write!(f, "OSM HTTP request failed: {e}"),
            Self::ParseResponse(e) => write!(f, "OSM response parse failed: {e}"),
            Self::AllMirrorsFailed(msg) => write!(f, "All Overpass mirrors failed: {msg}"),
        }
    }
}

impl std::error::Error for OsmError {}

impl OsmClient {
    pub fn new(http: reqwest::Client) -> Self {
        Self { http }
    }

    pub async fn reverse_geocode(
        &self,
        lat: f64,
        lon: f64,
    ) -> Result<ReverseGeocodeResult, OsmError> {
        let url = format!("{NOMINATIM_REVERSE_URL}?format=jsonv2&lat={lat}&lon={lon}");

        let json = execute_osm_request(self.http.get(&url)).await?;

        let address = json.get("address").unwrap_or(&serde_json::Value::Null);

        Ok(ReverseGeocodeResult {
            display_name: optional_string(&json, &["display_name", "name"]),
            street_number: optional_string(address, &["house_number"]),
            street_name: optional_string(address, &["road", "pedestrian", "footway", "path"]),
            municipality: optional_string(
                address,
                &["city", "town", "village", "hamlet", "municipality"],
            ),
            country_subdivision: optional_string(address, &["state", "region", "county"]),
            country: optional_string(address, &["country"]),
            postal_code: optional_string(address, &["postcode"]),
        })
    }

    pub async fn overpass(&self, query: &str) -> Result<serde_json::Value, OsmError> {
        let mut last_err = None;
        for mirror in OVERPASS_MIRRORS {
            match execute_osm_request(self.http.post(*mirror).form(&[("data", query)])).await {
                Ok(json) => return Ok(json),
                Err(e) => {
                    tracing::warn!("Overpass mirror {} failed: {}", mirror, e);
                    last_err = Some(e);
                }
            }
        }
        Err(last_err.unwrap_or_else(|| OsmError::AllMirrorsFailed(
            "no overpass mirrors configured".to_string()
        )))
    }
}

async fn execute_osm_request(builder: RequestBuilder) -> Result<serde_json::Value, OsmError> {
    builder
        .header(reqwest::header::USER_AGENT, OSM_USER_AGENT)
        .send()
        .await
        .map_err(OsmError::HttpRequest)?
        .error_for_status()
        .map_err(OsmError::HttpRequest)?
        .json()
        .await
        .map_err(OsmError::ParseResponse)
}

fn optional_string(value: &serde_json::Value, keys: &[&str]) -> Option<String> {
    keys.iter()
        .find_map(|key| value.get(*key).and_then(|value| value.as_str()))
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToString::to_string)
}

#[derive(Debug, Clone, Serialize)]
pub struct ReverseGeocodeResult {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub display_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub street_number: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub street_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub municipality: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub country_subdivision: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub country: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub postal_code: Option<String>,
}

//! Nearby place search backed by the OpenStreetMap Overpass API.

use crate::proto::aibus::{Location, NearbyPlace};
use tracing::warn;

const OVERPASS_API_URL: &str = "https://overpass-api.de/api/interpreter";

#[derive(Debug)]
pub enum NearbyError {
    /// The HTTP request to the Overpass API failed.
    HttpRequest(reqwest::Error),
    /// The Overpass response could not be parsed as JSON.
    ParseResponse(reqwest::Error),
}

impl std::fmt::Display for NearbyError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::HttpRequest(e) => write!(f, "Overpass HTTP request failed: {e}"),
            Self::ParseResponse(e) => write!(f, "Overpass response parse failed: {e}"),
        }
    }
}

impl From<NearbyError> for tonic::Status {
    fn from(err: NearbyError) -> Self {
        match &err {
            NearbyError::HttpRequest(e) => {
                warn!(error = %e, "Overpass HTTP request failed");
                tonic::Status::unavailable(err.to_string())
            }
            NearbyError::ParseResponse(e) => {
                warn!(error = %e, "Overpass response parse failed");
                tonic::Status::internal(err.to_string())
            }
        }
    }
}

pub struct NearbyClient {
    http: reqwest::Client,
}

impl NearbyClient {
    pub fn new(http: reqwest::Client) -> Self {
        Self { http }
    }

    /// Search for nearby places using the Overpass API.
    ///
    /// `query` is an optional free-text filter applied as a case-insensitive regex to place names. Pass `""` to return all places in the area.
    pub async fn search(
        &self,
        lat: f64,
        lon: f64,
        radius: f64,
        query: &str,
    ) -> Result<Vec<NearbyPlace>, NearbyError> {
        let overpass_ql = build_overpass_query(lat, lon, radius, query);

        let json: serde_json::Value = self
            .http
            .post(OVERPASS_API_URL)
            .form(&[("data", &overpass_ql)])
            .send()
            .await
            .map_err(NearbyError::HttpRequest)?
            .json()
            .await
            .map_err(NearbyError::ParseResponse)?;

        let elements = json
            .get("elements")
            .and_then(|v| v.as_array())
            .cloned()
            .unwrap_or_default();

        Ok(elements.iter().filter_map(osm_element_to_place).collect())
    }
}

fn build_overpass_query(lat: f64, lon: f64, radius: f64, query: &str) -> String {
    let name_filter = if query.is_empty() {
        String::new()
    } else {
        let escaped = escape_overpass_regex(query);
        format!(r#"["name"~"{}",i]"#, escaped)
    };

    format!(
        r#"[out:json][timeout:10];
(
  node["amenity"]{name}(around:{radius},{lat},{lon});
  node["shop"]{name}(around:{radius},{lat},{lon});
  node["tourism"]{name}(around:{radius},{lat},{lon});
  node["leisure"]{name}(around:{radius},{lat},{lon});
  way["amenity"]{name}(around:{radius},{lat},{lon});
  way["shop"]{name}(around:{radius},{lat},{lon});
);
out center body qt 20;"#,
        name = name_filter,
        radius = radius,
        lat = lat,
        lon = lon,
    )
}

/// Escape characters that are special in Overpass regex syntax.
fn escape_overpass_regex(input: &str) -> String {
    input
        .replace('\\', "\\\\")
        .replace('"', "\\\"")
        .replace('[', "\\[")
        .replace(']', "\\]")
        .replace('(', "\\(")
        .replace(')', "\\)")
        .replace('{', "\\{")
        .replace('}', "\\}")
        .replace('.', "\\.")
        .replace('*', "\\*")
        .replace('+', "\\+")
        .replace('?', "\\?")
        .replace('|', "\\|")
        .replace('^', "\\^")
        .replace('$', "\\$")
}

/// Convert a single Overpass JSON element to a `NearbyPlace`.
///
/// Returns `None` for elements that lack a name or usable coordinates.
fn osm_element_to_place(el: &serde_json::Value) -> Option<NearbyPlace> {
    let tags = el.get("tags")?;
    let name = tags.get("name")?.as_str()?.to_string();

    // Nodes carry lat/lon directly; ways carry center.lat / center.lon
    // (from the `out center` directive).
    let (place_lat, place_lon) = resolve_coordinates(el)?;

    let formatted_address = build_address(tags);
    let place_types = collect_place_types(tags);
    let description = pick_description(tags, &place_types);

    let osm_type = el.get("type").and_then(|v| v.as_str()).unwrap_or("node");
    let osm_id = el.get("id").and_then(|v| v.as_u64()).unwrap_or(0);

    Some(NearbyPlace {
        name,
        formatted_address,
        place_types,
        place_id: format!("osm:{osm_type}/{osm_id}"),
        phone_number: tag_or(tags, &["phone", "contact:phone"]),
        place_description: description,
        description_language: "en".into(),
        website_url: tag_or(tags, &["website", "contact:website"]),
        rating: 0.0,
        user_ratings_total: 0,
        location: Some(Location {
            latitude: place_lat,
            longitude: place_lon,
        }),
        open_now: false,
    })
}

fn resolve_coordinates(el: &serde_json::Value) -> Option<(f64, f64)> {
    if let (Some(lat), Some(lon)) = (
        el.get("lat").and_then(|v| v.as_f64()),
        el.get("lon").and_then(|v| v.as_f64()),
    ) {
        return Some((lat, lon));
    }
    let center = el.get("center")?;
    Some((
        center.get("lat").and_then(|v| v.as_f64())?,
        center.get("lon").and_then(|v| v.as_f64())?,
    ))
}

fn build_address(tags: &serde_json::Value) -> String {
    let parts: Vec<&str> = [
        "addr:housenumber",
        "addr:street",
        "addr:city",
        "addr:state",
        "addr:postcode",
    ]
    .iter()
    .filter_map(|key| tags.get(*key).and_then(|v| v.as_str()))
    .collect();

    if parts.is_empty() {
        String::new()
    } else {
        parts.join(", ")
    }
}

fn collect_place_types(tags: &serde_json::Value) -> Vec<String> {
    ["amenity", "shop", "tourism", "leisure"]
        .iter()
        .filter_map(|key| tags.get(*key).and_then(|v| v.as_str()).map(String::from))
        .collect()
}

fn pick_description(tags: &serde_json::Value, place_types: &[String]) -> String {
    tags.get("cuisine")
        .and_then(|v| v.as_str())
        .or_else(|| tags.get("description").and_then(|v| v.as_str()))
        .or_else(|| place_types.first().map(|s| s.as_str()))
        .unwrap_or("")
        .to_string()
}

/// Return the first non-empty string value found for the given tag keys.
fn tag_or(tags: &serde_json::Value, keys: &[&str]) -> String {
    keys.iter()
        .find_map(|k| tags.get(*k).and_then(|v| v.as_str()))
        .unwrap_or("")
        .to_string()
}

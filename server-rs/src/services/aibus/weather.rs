use prost::Message as _;
use tonic::{Request, Response, Status};
use tracing::{info, warn};

use super::envelope::unwrap_plaintext_data;
use crate::external::weather::{WeatherClient, WeatherError};
use crate::proto::aibus::*;
use crate::proto::common::encryption::{self, EncryptedData};

pub struct WeatherHandler {
    weather: WeatherClient,
}

impl WeatherHandler {
    pub fn new(weather: WeatherClient) -> Self {
        Self { weather }
    }

    pub async fn encrypted_weather(
        &self,
        request: Request<EncryptedWeatherRequest>,
    ) -> Result<Response<EncryptedWeatherResponse>, Status> {
        let req = request.into_inner();
        let location_bytes = unwrap_plaintext_data(&req.location)?;
        let location = encryption::LocationEnvelope::decode(location_bytes)
            .map_err(|e| Status::invalid_argument(format!("bad LocationEnvelope: {e}")))?;

        info!(
            lat = location.latitude,
            lon = location.longitude,
            ">>> EncryptedWeather"
        );

        let current = self
            .weather
            .current(location.latitude.into(), location.longitude.into())
            .await
            .map_err(|e| match e {
                WeatherError::NotConfigured => {
                    info!(">>> EncryptedWeather (no API key configured)");
                    Status::unavailable(
                        "weather not configured — set PIRATE_WEATHER_API_KEY in the environment or .env, or set pirate_weather_api_key in config.toml",
                    )
                }
                other => {
                    warn!(error = %other, "PirateWeather current weather request failed");
                    Status::unavailable(format!("weather API request failed: {other}"))
                }
            })?;

        let weather = WeatherResponse {
            has_precipitation: current.has_precipitation,
            precipitation_type: current.precipitation_type.clone().unwrap_or_default(),
            temperature_fahrenheit: current.temperature_fahrenheit,
            temperature_celsius: current.temperature_celsius,
            weather_text: current.summary.clone(),
            weather_icon: pirate_weather_icon_to_device(&current.icon),
            u_v_index: current.uv_index,
        };

        info!(
            temp_f = format!("{:.0}", current.temperature_fahrenheit),
            temp_c = format!("{:.0}", current.temperature_celsius),
            summary = %current.summary,
            icon = %current.icon,
            "<<< EncryptedWeather"
        );

        Ok(Response::new(EncryptedWeatherResponse {
            response: Some(EncryptedData::new(
                "humane.aibus.WeatherResponse",
                weather.encode_to_vec(),
            )),
        }))
    }
}

/// Map PirateWeather icon string to the device's integer weather icon code.
fn pirate_weather_icon_to_device(icon: &str) -> i32 {
    match icon {
        "clear-day" => 1,
        "clear-night" => 33,
        "partly-cloudy-day" => 3,
        "partly-cloudy-night" => 35,
        "cloudy" => 7,
        "rain" => 12,
        "snow" => 19,
        "sleet" => 24,
        "wind" => 32,
        "fog" => 11,
        "thunderstorm" => 15,
        _ => 3,
    }
}

//! Standalone gRPC server for Humane AI Pin.

mod config;
mod llm;
mod services;

/// Generated protobuf/gRPC modules.
mod proto {
    pub mod aibus {
        tonic::include_proto!("humane.aibus");
    }
    pub mod pushrelay {
        tonic::include_proto!("humane.pushrelay");
    }
    pub mod featureflags {
        tonic::include_proto!("humane.featureflags");
    }
    pub mod account {
        tonic::include_proto!("humane.account");
    }
    pub mod contacts {
        tonic::include_proto!("humane.contacts");
    }
    pub mod events {
        tonic::include_proto!("humane.events");
    }
    pub mod provisioning {
        tonic::include_proto!("humane.provisioning");
    }
}

use std::path::PathBuf;
use std::sync::Arc;

use proto::account::user_information_service_server::UserInformationServiceServer;
use proto::account::wifi_config_service_server::WifiConfigServiceServer;
use proto::aibus::ai_bus_service_server::AiBusServiceServer;
use proto::contacts::contacts_rpc_service_server::ContactsRpcServiceServer;
use proto::events::events_ingest_service_server::EventsIngestServiceServer;
use proto::featureflags::feature_flags_service_server::FeatureFlagsServiceServer;
use proto::provisioning::device_onboarding_dac_service_server::DeviceOnboardingDacServiceServer;
use proto::pushrelay::push_relay_service_server::PushRelayServiceServer;

use services::aibus::AiBusServiceImpl;
use services::contacts::ContactsRpcServiceImpl;
use services::events::EventsIngestServiceImpl;
use services::featureflags::FeatureFlagsServiceImpl;
use services::provisioning::{OnboardingCa, ProvisioningServiceImpl};
use services::pushrelay::PushRelayServiceImpl;
use services::user_info::UserInformationServiceImpl;
use services::wifi_config::WifiConfigServiceImpl;

use config::Config;
use llm::LlmAgent;
use tonic::transport::Server;
use tower_http::classify::GrpcFailureClass;
use tower_http::trace::TraceLayer;
use tracing::info;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .init();

    // Locate config file: check --config <path>, then ./config.toml, then next to binary
    let config_path = std::env::args()
        .position(|a| a == "--config")
        .and_then(|i| std::env::args().nth(i + 1))
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("config.toml"));

    let config = Config::load(&config_path)?;

    // CLI port arg overrides config (for backward compat: `humane-server 9090`)
    let port: u16 = std::env::args()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .unwrap_or(config.server.port);

    // Build LLM agent
    let agent = Arc::new(LlmAgent::from_config(
        &config.llm,
        &config.server.system_prompt,
    )?);

    // Generate ephemeral CA for signing DUC certificates during onboarding
    let onboarding_ca = Arc::new(OnboardingCa::generate()?);
    let user_id = uuid::Uuid::new_v4().to_string();
    let display_name = config
        .server
        .display_name
        .clone()
        .unwrap_or_else(|| "Penumbra".into());

    let addr = format!("0.0.0.0:{}", port).parse()?;

    let provider_label = config.llm.provider.to_uppercase();
    info!("============================================================");
    info!("Humane gRPC server listening on {} (plaintext)", addr);
    info!(
        "LLM provider: {} (model: {})",
        provider_label, config.llm.model
    );
    info!(
        "Onboarding: display_name={}, user_id={}",
        display_name, user_id
    );
    info!("Services:");
    info!(
        "  - humane.aibus.AIBusService/Understand       ({})",
        config.llm.provider
    );
    info!(
        "  - humane.aibus.AIBusService/AnalyzeImage     ({}, vision)",
        config.llm.provider
    );
    info!("  - humane.pushrelay.PushRelayService/Subscribe (no-op hold)");
    info!("  - humane.pushrelay.PushRelayService/GetPushTokens (empty)");
    info!("  - humane.featureflags.FeatureFlagsService/GetFlags (empty)");
    info!("  - humane.account.WifiConfigService/ListSecureWifiConfigs (empty)");
    info!("  - humane.account.UserInformationService/GetUserPersonalDetails (stub)");
    info!("  - humane.contacts.ContactsRPCService/GetContacts (empty)");
    info!("  - humane.events.EventsIngestService/Ingest (discard)");
    info!("  - humane.events.EventsIngestService/IngestBatch (discard)");
    info!("  - humane.provisioning.DeviceOnboardingDACService/* (onboarding)");
    info!("  - All other RPCs: UNIMPLEMENTED");
    info!("============================================================");

    Server::builder()
        .layer(
            TraceLayer::new_for_grpc()
                .make_span_with(|request: &http::Request<_>| {
                    tracing::info_span!(
                        "grpc",
                        path = %request.uri().path(),
                    )
                })
                .on_request(|_request: &http::Request<_>, _span: &tracing::Span| {
                    info!("request");
                })
                .on_response(
                    |response: &http::Response<_>,
                     latency: std::time::Duration,
                     _span: &tracing::Span| {
                        info!(latency = ?latency, status = %response.status(), "response");
                    },
                )
                .on_failure(
                    |error: GrpcFailureClass,
                     latency: std::time::Duration,
                     _span: &tracing::Span| {
                        tracing::error!(latency = ?latency, error = %error, "failed");
                    },
                ),
        )
        .add_service(AiBusServiceServer::new(AiBusServiceImpl { agent }))
        .add_service(PushRelayServiceServer::new(PushRelayServiceImpl))
        .add_service(FeatureFlagsServiceServer::new(FeatureFlagsServiceImpl))
        .add_service(WifiConfigServiceServer::new(WifiConfigServiceImpl))
        .add_service(UserInformationServiceServer::new(
            UserInformationServiceImpl,
        ))
        .add_service(ContactsRpcServiceServer::new(ContactsRpcServiceImpl))
        .add_service(EventsIngestServiceServer::new(EventsIngestServiceImpl))
        .add_service(DeviceOnboardingDacServiceServer::new(
            ProvisioningServiceImpl {
                ca: onboarding_ca,
                display_name,
                user_id,
            },
        ))
        .serve(addr)
        .await?;

    Ok(())
}

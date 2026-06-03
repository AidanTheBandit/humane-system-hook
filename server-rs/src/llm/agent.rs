use std::sync::Arc;

use reqwest::Client as HttpClient;
use tracing::info;

use crate::config::ResolvedConfig;

use super::backend::LlmBackend;
use super::providers;
use super::request::LlmChatRequest;
use super::request_log::LlmRequestLogger;

/// Hot-swappable LLM agent facade backed by an object-safe provider adapter.
pub struct LlmAgent {
    backend: Arc<dyn LlmBackend>,
}

impl LlmAgent {
    /// Build an `LlmAgent` from the loaded config.
    pub async fn from_config(
        config: &ResolvedConfig,
        http_client: HttpClient,
        request_logger: LlmRequestLogger,
    ) -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let provider = config.config.llm.provider;
        info!(provider = %provider, model = %config.config.llm.model, "constructing LLM agent");

        let backend = providers::build_backend(config, http_client, request_logger).await?;

        Ok(Self { backend })
    }

    /// Send a prompt with request-scoped conversation and system prompt context to the LLM.
    pub async fn chat(&self, request: LlmChatRequest) -> Result<String, String> {
        self.backend.chat(request).await
    }

    /// Send a vision prompt with an image (base64-encoded JPEG) and a question.
    pub async fn vision_prompt(
        &self,
        question: &str,
        image_base64: &str,
    ) -> Result<String, String> {
        self.backend.vision_prompt(question, image_base64).await
    }
}

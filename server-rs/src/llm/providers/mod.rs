mod anthropic;
mod echo;
mod gemini;
mod openai;

use reqwest::Client as HttpClient;
use rig::completion::message::{ImageMediaType, Message, UserContent};
use rig::OneOrMany;
use std::sync::Arc;

use crate::config::{LlmProvider, ResolvedConfig};
use crate::llm::backend::LlmBackend;
use crate::llm::providers::anthropic::AnthropicProvider;
use crate::llm::providers::echo::EchoProvider;
use crate::llm::providers::gemini::GeminiProvider;
use crate::llm::providers::openai::OpenAiProvider;
use crate::llm::request_log::LlmRequestLogger;

pub async fn build_backend(
    config: &ResolvedConfig,
    http_client: HttpClient,
    request_logger: LlmRequestLogger,
) -> Result<Arc<dyn LlmBackend>, Box<dyn std::error::Error + Send + Sync>> {
    match config.config.llm.provider {
        LlmProvider::Echo => Ok(EchoProvider::build()),
        LlmProvider::Gemini => GeminiProvider::build(config, http_client, request_logger).await,
        LlmProvider::Anthropic => AnthropicProvider::build(config, http_client, request_logger).await,
        LlmProvider::OpenAi | LlmProvider::OpenAiCompatible => {
            OpenAiProvider::build(config, http_client, request_logger).await
        }
    }
}

pub fn vision_message(question: &str, image_base64: &str) -> Message {
    Message::User {
        content: OneOrMany::many(vec![
            UserContent::text(question),
            UserContent::image_base64(
                image_base64,
                Some(ImageMediaType::JPEG),
                None, // detail: auto
            ),
        ])
        .expect("non-empty content vec"),
    }
}

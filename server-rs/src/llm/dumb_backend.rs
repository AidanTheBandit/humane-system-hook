use std::sync::Arc;
use std::time::Instant;

use base64::Engine as _;
use reqwest::Client as HttpClient;
use rig::completion::message::{AssistantContent, Message, UserContent};
use serde::{Deserialize, Serialize};
use tracing::{error, info};

use crate::config::ResolvedConfig;
use crate::llm::ChatResult;

use super::backend::{LlmBackend, LlmFuture};
use super::error::friendly_error_message;
use super::prompt::PromptBuilder;
use super::request::LlmChatRequest;
use super::request_log::LlmRequestLogger;

/// Dumb OpenAI-compatible completion backend.
///
/// Single-shot chat completion only — no local tool loop, no multi-turn agent.
/// Hermes (via Starlight) owns agent behavior; Penumbra just asks for text.
pub struct DumbOpenAiBackend {
    http: HttpClient,
    api_key: String,
    base_url: String,
    model: String,
    request_logger: LlmRequestLogger,
}

#[derive(Serialize)]
struct ChatCompletionRequest {
    model: String,
    messages: Vec<WireMessage>,
    #[serde(skip_serializing_if = "Option::is_none")]
    temperature: Option<f32>,
}

#[derive(Serialize)]
struct WireMessage {
    role: String,
    content: WireContent,
}

#[derive(Serialize)]
#[serde(untagged)]
enum WireContent {
    Text(String),
    Parts(Vec<WirePart>),
}

#[derive(Serialize)]
#[serde(tag = "type")]
enum WirePart {
    #[serde(rename = "text")]
    Text { text: String },
    #[serde(rename = "image_url")]
    ImageUrl { image_url: ImageUrl },
}

#[derive(Serialize)]
struct ImageUrl {
    url: String,
}

#[derive(Deserialize)]
struct ChatCompletionResponse {
    choices: Vec<Choice>,
}

#[derive(Deserialize)]
struct Choice {
    message: ChoiceMessage,
}

#[derive(Deserialize)]
struct ChoiceMessage {
    content: Option<String>,
}

impl DumbOpenAiBackend {
    pub fn new(
        config: &ResolvedConfig,
        http: HttpClient,
        request_logger: LlmRequestLogger,
    ) -> Result<Arc<dyn LlmBackend>, Box<dyn std::error::Error + Send + Sync>> {
        let llm = &config.config.llm;
        let api_key = llm.resolve_api_key().ok_or(
            "OpenAI api_key not set; configure OPENAI_API_KEY or llm.api_key for dumb mode",
        )?;
        let base_url = llm
            .base_url
            .clone()
            .unwrap_or_else(|| "https://api.openai.com/v1".to_string())
            .trim_end_matches('/')
            .to_string();

        info!(
            model = %llm.model,
            base_url = %base_url,
            "OpenAI dumb completion backend ready (no local agent loop)"
        );

        Ok(Arc::new(Self {
            http,
            api_key,
            base_url,
            model: llm.model.clone(),
            request_logger,
        }))
    }

    fn build_messages(request: &LlmChatRequest) -> Vec<WireMessage> {
        let mut messages = Vec::new();

        for msg in PromptBuilder::build_chat_history(request) {
            if let Some(wire) = rig_message_to_wire(&msg) {
                messages.push(wire);
            }
        }

        // Current user utterance (+ optional image)
        if let Some(image_bytes) = &request.image {
            let b64 = base64::engine::general_purpose::STANDARD.encode(image_bytes);
            messages.push(WireMessage {
                role: "user".to_string(),
                content: WireContent::Parts(vec![
                    WirePart::Text {
                        text: request.utterance.clone(),
                    },
                    WirePart::ImageUrl {
                        image_url: ImageUrl {
                            url: format!("data:image/jpeg;base64,{b64}"),
                        },
                    },
                ]),
            });
        } else {
            messages.push(WireMessage {
                role: "user".to_string(),
                content: WireContent::Text(request.utterance.clone()),
            });
        }

        messages
    }
}

fn rig_message_to_wire(msg: &Message) -> Option<WireMessage> {
    match msg {
        Message::System { content } => {
            if content.is_empty() {
                None
            } else {
                Some(WireMessage {
                    role: "system".to_string(),
                    content: WireContent::Text(content.clone()),
                })
            }
        }
        Message::User { content } => {
            let text = content
                .iter()
                .filter_map(|part| match part {
                    UserContent::Text(t) => Some(t.text.as_str()),
                    _ => None,
                })
                .collect::<Vec<_>>()
                .join("\n");
            if text.is_empty() {
                None
            } else {
                Some(WireMessage {
                    role: "user".to_string(),
                    content: WireContent::Text(text),
                })
            }
        }
        Message::Assistant { content, .. } => {
            let text = content
                .iter()
                .filter_map(|part| match part {
                    AssistantContent::Text(t) => Some(t.text.as_str()),
                    _ => None,
                })
                .collect::<Vec<_>>()
                .join("\n");
            if text.is_empty() {
                None
            } else {
                Some(WireMessage {
                    role: "assistant".to_string(),
                    content: WireContent::Text(text),
                })
            }
        }
    }
}

impl LlmBackend for DumbOpenAiBackend {
    fn chat<'a>(&'a self, request: LlmChatRequest) -> LlmFuture<'a> {
        Box::pin(async move {
            let utterance = request.utterance.clone();
            let run_id = request.template_context.run_id.clone();
            let history = PromptBuilder::build_chat_history(&request);
            let started = Instant::now();

            let body = ChatCompletionRequest {
                model: self.model.clone(),
                messages: Self::build_messages(&request),
                temperature: Some(0.7),
            };

            let url = format!("{}/chat/completions", self.base_url);
            let raw_result = self
                .http
                .post(&url)
                .bearer_auth(&self.api_key)
                .json(&body)
                .send()
                .await;

            let latency_ms = started.elapsed().as_millis();

            let result = match raw_result {
                Ok(resp) => {
                    let status = resp.status();
                    let text = resp.text().await.unwrap_or_default();
                    if !status.is_success() {
                        error!(
                            provider = "OpenAI-dumb",
                            status = %status,
                            body = %text.chars().take(500).collect::<String>(),
                            "LLM chat failed"
                        );
                        Err(friendly_error_message(&format!("HTTP {status}: {text}")))
                    } else {
                        match serde_json::from_str::<ChatCompletionResponse>(&text) {
                            Ok(parsed) => {
                                let content = parsed
                                    .choices
                                    .first()
                                    .and_then(|c| c.message.content.clone())
                                    .unwrap_or_default();
                                Ok(ChatResult::Text(content))
                            }
                            Err(e) => {
                                error!(
                                    provider = "OpenAI-dumb",
                                    error = %e,
                                    "failed to parse completion"
                                );
                                Err(friendly_error_message(&e))
                            }
                        }
                    }
                }
                Err(e) => {
                    error!(provider = "OpenAI-dumb", error = %e, "LLM chat failed");
                    Err(friendly_error_message(&e))
                }
            };

            self.request_logger
                .log_chat(
                    "OpenAI-dumb",
                    &run_id,
                    &history,
                    &utterance,
                    match &result {
                        Ok(ChatResult::Text(text)) => Some(text.as_str()),
                        Ok(ChatResult::DeferredVision) => None,
                        Err(_) => None,
                    },
                    result.clone().err().as_deref(),
                    latency_ms,
                )
                .await;

            result
        })
    }
}

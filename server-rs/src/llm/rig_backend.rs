use std::time::Instant;

use std::sync::Arc;

use reqwest::Client as HttpClient;
use rig::agent::{Agent, AgentBuilder, PromptHook};
use rig::client::CompletionClient;
use rig::completion::CompletionModel;
use rig::completion::{Message, Prompt};
use tracing::error;

use crate::config::ResolvedConfig;

use super::backend::{LlmBackend, LlmFuture};
use super::error::friendly_error_message;
use super::prompt::PromptBuilder;
use super::providers::vision_message;
use super::request::LlmChatRequest;
use super::request_log::LlmRequestLogger;
use super::tools::registry::LlmToolContext;

/// Shared LLM backend for providers
pub struct RigBackend<M>
where
    M: CompletionModel + 'static,
    (): PromptHook<M> + 'static,
{
    provider_label: &'static str,
    agent: Agent<M>,
    request_logger: LlmRequestLogger,
    max_tool_turns: usize,
    tool_concurrency: usize,
}

impl<M> RigBackend<M>
where
    M: CompletionModel + 'static,
    (): PromptHook<M> + 'static,
{
    pub async fn from_client<C, F>(
        provider_label: &'static str,
        client: C,
        request_logger: LlmRequestLogger,
        config: &ResolvedConfig,
        http_client: HttpClient,
        customize_builder: F,
    ) -> Result<Arc<dyn LlmBackend>, Box<dyn std::error::Error + Send + Sync>>
    where
        C: CompletionClient<CompletionModel = M>,
        F: FnOnce(AgentBuilder<M>) -> AgentBuilder<M>,
    {
        let llm_config = &config.config.llm;
        let builder = customize_builder(client.agent(&llm_config.model));

        let tool_resources = if llm_config.tools.enabled {
            let tool_context = LlmToolContext::new(http_client, config);
            tool_context
                .build_tool_resources(llm_config)
                .await
                .map_err(|err| -> Box<dyn std::error::Error + Send + Sync> {
                    std::io::Error::new(std::io::ErrorKind::Other, err).into()
                })?
        } else {
            None
        };

        let agent = match tool_resources {
            Some(resources) => resources.apply(builder).build(),
            None => builder.build(),
        };

        Ok(Arc::new(Self {
            provider_label,
            agent,
            request_logger,
            max_tool_turns: llm_config.tools.max_tool_turns,
            tool_concurrency: llm_config.tools.tool_concurrency,
        }))
    }
}

impl<M> LlmBackend for RigBackend<M>
where
    M: CompletionModel + 'static,
    (): PromptHook<M> + 'static,
{
    fn chat<'a>(&'a self, request: LlmChatRequest) -> LlmFuture<'a> {
        Box::pin(async move {
            let utterance = request.utterance.clone();
            let run_id = request.template_context.run_id.clone();
            let history = PromptBuilder::build_chat_history(&request);
            let started = Instant::now();

            let result = self
                .agent
                .prompt(Message::user(utterance.clone()))
                .with_history(history.clone())
                .max_turns(self.max_tool_turns)
                .with_tool_concurrency(self.tool_concurrency.max(1))
                .await;
            let latency_ms = started.elapsed().as_millis();

            let result = result.map_err(|e| {
                error!(provider = self.provider_label, error = %e, "LLM chat failed");
                friendly_error_message(&e)
            });

            self.request_logger
                .log_chat(
                    self.provider_label,
                    &run_id,
                    &history,
                    &utterance,
                    result.clone().ok().as_deref(),
                    result.clone().err().as_deref(),
                    latency_ms,
                )
                .await;

            result
        })
    }

    fn vision_prompt<'a>(&'a self, question: &'a str, image_base64: &'a str) -> LlmFuture<'a> {
        Box::pin(async move {
            let started = Instant::now();
            let result = self
                .agent
                .prompt(vision_message(question, image_base64))
                .await;
            let latency_ms = started.elapsed().as_millis();

            let result = result.map_err(|e| {
                error!(provider = self.provider_label, error = %e, "LLM vision prompt failed");
                friendly_error_message(&e)
            });

            self.request_logger
                .log_vision(
                    self.provider_label,
                    question,
                    image_base64.len(),
                    result.clone().ok().as_deref(),
                    result.clone().err().as_deref(),
                    latency_ms,
                )
                .await;

            result
        })
    }
}

package com.walletledger.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Claude via the official Java SDK. Structured outputs constrain the reply to the JSON schema
 * derived from {@code shape}; the caller still validates every field.
 * Server-side refusal fallbacks are enabled so a declined request is retried on a fallback model.
 */
public class AnthropicLlmClient implements LlmClient {

    private final AnthropicClient client;
    private final String model;
    private final ObjectMapper mapper;

    public AnthropicLlmClient(AnthropicClient client, String model, ObjectMapper mapper) {
        this.client = client;
        this.model = model;
        this.mapper = mapper;
    }

    @Override
    public String completeJson(String systemPrompt, String userContent, Class<?> shape) {
        return call(systemPrompt, userContent, shape);
    }

    @Override
    public String model() {
        return model;
    }

    private <T> String call(String systemPrompt, String userContent, Class<T> shape) {
        StructuredMessageCreateParams<T> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(systemPrompt)
                .outputConfig(shape)
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .addUserMessage(userContent)
                .build();
        StructuredMessage<T> response;
        try {
            response = client.messages().create(params);
        } catch (AnthropicException e) {
            throw new LlmException("LLM request failed: " + e.getMessage(), e);
        }
        if (response.stopReason().map(StopReason.REFUSAL::equals).orElse(false)) {
            throw new LlmException("LLM declined the request");
        }
        T value = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .findFirst()
                .orElseThrow(() -> new LlmException("LLM returned no structured output"));
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new LlmException("Could not serialise LLM output", e);
        }
    }
}

package com.walletledger.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration
public class LlmConfig {

    /** wallet.llm.provider = anthropic (needs ANTHROPIC_API_KEY) or mock (default; offline, fixed answers). */
    @Bean
    public LlmClient llmClient(@Value("${wallet.llm.provider}") String provider,
                               @Value("${wallet.llm.model}") String model,
                               @Value("${wallet.llm.mock-fixtures}") Resource fixtures,
                               ObjectMapper mapper) {
        if ("anthropic".equalsIgnoreCase(provider)) {
            return new AnthropicLlmClient(AnthropicOkHttpClient.fromEnv(), model, mapper);
        }
        return new MockLlmClient(mapper, fixtures);
    }
}

package com.walletledger.llm;

/**
 * The only way the application talks to a language model.
 *
 * <p>The model never decides anything about money and never writes SQL. It only turns text into a
 * small JSON object, which the caller parses and validates like any other untrusted input.
 */
public interface LlmClient {

    /**
     * Asks for a JSON object shaped like {@code shape} and returns the raw JSON text.
     *
     * @throws LlmException if the provider fails or declines to answer
     */
    String completeJson(String systemPrompt, String userContent, Class<?> shape);

    /** Model identifier, stored next to every saved explanation. */
    String model();
}

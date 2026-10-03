package com.walletledger.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline stand-in for a real model: returns fixed JSON, so tests and demos run without a network or
 * an API key. Questions are looked up in a fixture file; anything unknown gets {"type": 0}.
 * Tests can queue exact replies (including malicious ones) with {@link #enqueue}.
 */
public class MockLlmClient implements LlmClient {

    public static final String UNKNOWN_QUESTION = "{\"type\":0,\"counterparty\":null,\"from\":null,\"to\":null}";
    public static final String DEFAULT_EXPLANATION =
            "{\"explanation\":\"The transfer matched a fraud rule. A reviewer should confirm it with the sender.\",\"label\":\"review\"}";

    private static final Pattern QUESTION = Pattern.compile("<question>\\s*(.*?)\\s*</question>", Pattern.DOTALL);

    private final Map<String, String> questions = new HashMap<>();
    private final Deque<String> queued = new ArrayDeque<>();
    private volatile String lastSystemPrompt;
    private volatile String lastUserContent;

    public MockLlmClient(ObjectMapper mapper, Resource fixtures) {
        if (fixtures != null && fixtures.exists()) {
            try (InputStream in = fixtures.getInputStream()) {
                JsonNode root = mapper.readTree(in);
                root.path("questions").fields().forEachRemaining(e -> questions.put(normalize(e.getKey()), e.getValue().toString()));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read mock LLM fixtures", e);
            }
        }
    }

    @Override
    public synchronized String completeJson(String systemPrompt, String userContent, Class<?> shape) {
        lastSystemPrompt = systemPrompt;
        lastUserContent = userContent;
        if (!queued.isEmpty()) {
            return queued.poll();
        }
        if (shape.getSimpleName().equals("QuestionIntent")) {
            Matcher m = QUESTION.matcher(userContent);
            String question = m.find() ? m.group(1) : userContent;
            return questions.getOrDefault(normalize(question), UNKNOWN_QUESTION);
        }
        return DEFAULT_EXPLANATION;
    }

    @Override
    public String model() {
        return "mock";
    }

    /** The next call returns exactly this text, whatever was asked. */
    public synchronized void enqueue(String rawReply) {
        queued.add(rawReply);
    }

    public synchronized void reset() {
        queued.clear();
        lastSystemPrompt = null;
        lastUserContent = null;
    }

    public String lastSystemPrompt() {
        return lastSystemPrompt;
    }

    public String lastUserContent() {
        return lastUserContent;
    }

    private static String normalize(String question) {
        return question.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}

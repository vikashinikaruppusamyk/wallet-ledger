package com.walletledger.explain;

/** What the LLM must return for a risk flag: a short explanation and one of three labels. */
public record FlagExplanation(String explanation, String label) {
}

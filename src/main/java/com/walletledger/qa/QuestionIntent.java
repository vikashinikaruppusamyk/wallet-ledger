package com.walletledger.qa;

/**
 * What the LLM must return for a statement question. Field names are single words so the JSON
 * schema the model sees and the JSON our code parses are identical.
 *
 * @param type         1 sent to a person, 2 received, 3 largest transfer, 4 number of transfers, 0 unsupported
 * @param counterparty a person's name as the user wrote it, or null
 * @param from         inclusive start date, YYYY-MM-DD
 * @param to           inclusive end date, YYYY-MM-DD
 */
public record QuestionIntent(Integer type, String counterparty, String from, String to) {
}

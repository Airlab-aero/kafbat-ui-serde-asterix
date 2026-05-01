package io.airlab.kafbat.serde.asterix.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;

/**
 * Parses a single ASTERIX data item from a {@link ByteBuffer}.
 *
 * <p>Implementations advance the buffer position past the bytes they consume.
 * Implementations must be thread-safe once constructed.
 */
@FunctionalInterface
public interface ItemParser {

    /**
     * Parse the next data item from {@code buffer}.
     *
     * @param buffer  source buffer positioned at the first byte of this item
     * @param mapper  Jackson mapper used to create result nodes
     * @return an {@link ObjectNode} containing the decoded fields
     */
    ObjectNode parse(ByteBuffer buffer, ObjectMapper mapper);
}

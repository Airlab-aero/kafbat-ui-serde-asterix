package io.airlab.kafbat.serde.asterix.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;

/**
 * Parser for ASTERIX <em>Variable</em> data items.
 *
 * <p>In a variable item every octet carries 7 data bits plus a Field eXtension
 * (FX) bit in the LSB position.  FX = 1 means another octet follows;
 * FX = 0 means this is the last octet.
 *
 * <p>The constructor takes an array of {@link FieldDef} arrays – one inner
 * array per expected octet.  Each inner array must sum to exactly 7 bits.
 * Extra extension octets (beyond the defined parts) are silently consumed.
 */
public class VariableItemParser implements ItemParser {

    /** Field definitions per octet (7 bits each, excluding the FX bit). */
    private final FieldDef[][] parts;

    public VariableItemParser(FieldDef[]... parts) {
        this.parts = parts;
        for (int i = 0; i < parts.length; i++) {
            int sum = 0;
            for (FieldDef f : parts[i]) sum += f.bits;
            if (sum != 7) {
                throw new IllegalArgumentException(
                    "Variable item octet " + i + " sums to " + sum + " bits; must be 7");
            }
        }
    }

    @Override
    public ObjectNode parse(ByteBuffer buffer, ObjectMapper mapper) {
        ObjectNode result = mapper.createObjectNode();
        int octetIdx = 0;
        boolean more = true;
        while (more) {
            int octet = buffer.get() & 0xFF;
            more = (octet & 0x01) == 1;          // FX is the LSB
            int data = (octet >>> 1) & 0x7F;     // 7 data bits, now aligned to bits 6..0

            if (octetIdx < parts.length) {
                // extractFields expects the MSB of 'data' to be bit 6
                ObjectNode part = FixedItemParser.extractFields(data, 7, parts[octetIdx], mapper);
                part.fields().forEachRemaining(e -> result.set(e.getKey(), e.getValue()));
            }
            // Extension octets beyond 'parts' are consumed but ignored.
            octetIdx++;
        }
        return result;
    }
}

package io.airlab.kafbat.serde.asterix.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;

/**
 * Parser for ASTERIX <em>Repetitive</em> data items.
 *
 * <p>Structure: {@code REP} (1 octet, unsigned count) followed by
 * {@code REP × recordBytes} octets.  Each record is decoded using the
 * provided {@link FieldDef} array.
 *
 * <p>The result is an {@link ObjectNode} with a single {@code "list"} key
 * whose value is a JSON array of decoded record objects.
 */
public class RepetitiveItemParser implements ItemParser {

    private final int       recordBytes;
    private final FieldDef[] fields;

    public RepetitiveItemParser(int recordBytes, FieldDef... fields) {
        this.recordBytes = recordBytes;
        this.fields      = fields;
        int totalBits = 0;
        for (FieldDef f : fields) totalBits += f.bits;
        if (totalBits != recordBytes * 8) {
            throw new IllegalArgumentException(
                "Fields sum to " + totalBits + " bits but record is " + (recordBytes * 8) + " bits");
        }
    }

    @Override
    public ObjectNode parse(ByteBuffer buffer, ObjectMapper mapper) {
        int rep = buffer.get() & 0xFF;
        ArrayNode list = mapper.createArrayNode();
        for (int i = 0; i < rep; i++) {
            long raw = 0;
            for (int j = 0; j < recordBytes; j++) {
                raw = (raw << 8) | (buffer.get() & 0xFF);
            }
            list.add(FixedItemParser.extractFields(raw, recordBytes * 8, fields, mapper));
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("list", list);
        return result;
    }
}

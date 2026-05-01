package io.airlab.kafbat.serde.asterix.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.airlab.kafbat.serde.asterix.category.CategoryDefinition;
import io.airlab.kafbat.serde.asterix.category.CategoryRegistry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Main ASTERIX binary-to-JSON parser.
 *
 * <h2>ASTERIX frame structure</h2>
 * <p>A Kafka message may contain one or more contiguous <em>data blocks</em>:
 * <pre>
 *   DataBlock := CAT (1 byte) | LEN (2 bytes, big-endian, includes header) | RECORD+
 *   RECORD    := FSPEC | ITEM+
 *   FSPEC     := (octet with FX=1)* octet_with_FX=0
 * </pre>
 *
 * <p>Within each FSPEC octet bits 7-1 (MSB→LSB, ignoring the FX LSB) indicate
 * the presence of data items in User Application Profile (UAP) order.
 *
 * <h2>Thread safety</h2>
 * <p>Instances are thread-safe; the shared {@link ObjectMapper} is stateless.
 */
public class AsterixParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final CategoryRegistry registry;

    public AsterixParser(CategoryRegistry registry) {
        this.registry = registry;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Parse raw ASTERIX bytes into a JSON array of data blocks.
     *
     * @param data raw ASTERIX bytes (may contain multiple concatenated data blocks)
     * @return JSON array, one element per data block
     */
    public ArrayNode parse(byte[] data) {
        ArrayNode result = MAPPER.createArrayNode();
        if (data == null || data.length < 3) return result;

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        while (buf.remaining() >= 3) {
            int startPos = buf.position();
            ObjectNode block = parseDataBlock(buf, startPos);
            if (block == null) break;
            result.add(block);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Data block parsing
    // -----------------------------------------------------------------------

    private ObjectNode parseDataBlock(ByteBuffer buf, int startPos) {
        if (buf.remaining() < 3) return null;

        int cat = buf.get() & 0xFF;
        int len = ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);

        ObjectNode block = MAPPER.createObjectNode();
        block.put("category", cat);

        int endPos = startPos + len;
        if (len < 3 || endPos > buf.capacity()) {
            block.put("error", "Invalid data block length: " + len);
            return block;
        }

        CategoryDefinition catDef = registry.get(cat);
        if (catDef == null) {
            // Unknown category – return a raw hex dump
            int remaining = endPos - buf.position();
            byte[] raw = new byte[remaining];
            buf.get(raw);
            block.put("unknown", true);
            block.put("hex", ExplicitItemParser.bytesToHex(raw));
            return block;
        }

        block.put("name", catDef.name);
        ArrayNode records = MAPPER.createArrayNode();

        while (buf.position() < endPos) {
            int before = buf.position();
            ObjectNode record = parseRecord(buf, catDef, endPos);
            if (record == null || buf.position() == before) {
                // No progress – avoid infinite loop
                break;
            }
            records.add(record);
        }

        block.set("records", records);
        return block;
    }

    // -----------------------------------------------------------------------
    // Record parsing
    // -----------------------------------------------------------------------

    private ObjectNode parseRecord(ByteBuffer buf, CategoryDefinition catDef, int endPos) {
        if (buf.position() >= endPos || !buf.hasRemaining()) return null;

        // --- Read FSPEC ---------------------------------------------------
        List<Integer> fspec = new ArrayList<>();
        int b;
        do {
            if (buf.position() >= endPos) break;
            b = buf.get() & 0xFF;
            fspec.add(b);
        } while ((b & 0x01) == 1);   // FX=1 → more FSPEC octets follow

        ObjectNode record = MAPPER.createObjectNode();
        int uapIdx = 0;

        // --- Parse items indicated by FSPEC --------------------------------
        for (int fspecByte : fspec) {
            for (int bit = 7; bit >= 1; bit--) {       // bits 7..1 (FX is bit 0)
                boolean present = (fspecByte & (1 << bit)) != 0;
                if (present) {
                    if (uapIdx >= catDef.uap.length) {
                        // FSPEC extends beyond the known UAP – skip unknown items.
                        // We cannot determine item length, so stop parsing.
                        return record;
                    }
                    String itemId = catDef.uap[uapIdx];
                    if (itemId != null) {
                        ItemParser parser = catDef.items.get(itemId);
                        if (parser != null) {
                            try {
                                record.set(itemId, parser.parse(buf, MAPPER));
                            } catch (Exception ex) {
                                ObjectNode err = MAPPER.createObjectNode();
                                err.put("error", ex.getMessage());
                                record.set(itemId, err);
                                return record; // can't reliably continue after a parse error
                            }
                        } else {
                            // Item ID in UAP but no parser – note it and stop
                            ObjectNode err = MAPPER.createObjectNode();
                            err.put("error", "No parser registered for " + itemId);
                            record.set(itemId, err);
                            return record;
                        }
                    }
                    // null UAP entry = reserved/not used position → nothing to parse
                }
                uapIdx++;
            }
        }
        return record;
    }
}

package io.airlab.kafbat.serde.asterix.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;

/**
 * Parser for ASTERIX <em>Fixed</em> data items.
 *
 * <p>Reads exactly {@code byteCount} octets from the buffer and distributes
 * their bits MSB-first across the provided {@link FieldDef} array.
 * The total number of bits in all fields must equal {@code byteCount * 8}.
 *
 * <p>Supports items up to 7 bytes (56 bits). For 8-byte items use two 4-byte
 * Fixed parsers or a custom {@link ItemParser} lambda.
 */
public class FixedItemParser implements ItemParser {

    private final int byteCount;
    private final FieldDef[] fields;

    public FixedItemParser(int byteCount, FieldDef... fields) {
        this.byteCount = byteCount;
        this.fields    = fields;
        int totalBits = 0;
        for (FieldDef f : fields) totalBits += f.bits;
        if (totalBits != byteCount * 8) {
            throw new IllegalArgumentException(
                "Fields sum to " + totalBits + " bits but item is " + (byteCount * 8) + " bits");
        }
    }

    @Override
    public ObjectNode parse(ByteBuffer buffer, ObjectMapper mapper) {
        // Read all bytes into a long (big-endian, MSB first).
        // Supports up to 8 bytes; the 8-byte case handles the full long range.
        long raw = 0;
        for (int i = 0; i < byteCount; i++) {
            raw = (raw << 8) | (buffer.get() & 0xFF);
        }
        return extractFields(raw, byteCount * 8, fields, mapper);
    }

    /**
     * Extract named fields from a pre-read {@code raw} long value.
     *
     * @param raw       raw bits, packed MSB-first
     * @param totalBits total number of bits in {@code raw}
     * @param fields    ordered field definitions
     * @param mapper    Jackson mapper
     * @return ObjectNode with decoded field values
     */
    public static ObjectNode extractFields(long raw, int totalBits,
                                           FieldDef[] fields, ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        int bitsRead = 0;
        for (FieldDef f : fields) {
            int shift = totalBits - bitsRead - f.bits;
            long mask = (1L << f.bits) - 1L;
            long val  = (raw >> shift) & mask;
            bitsRead += f.bits;

            if (f.spare) continue;

            if (f.lsb == 1.0) {
                // Integer output
                if (f.signed) {
                    node.put(f.name, signExtend(val, f.bits));
                } else {
                    node.put(f.name, val);
                }
            } else {
                // Scaled floating-point output
                double dval = (f.signed ? signExtend(val, f.bits) : (double) val) * f.lsb;
                node.put(f.name, dval);
            }
        }
        return node;
    }

    /** Sign-extend a {@code bits}-bit two's-complement value stored in a long. */
    static long signExtend(long val, int bits) {
        long signBit = 1L << (bits - 1);
        return (val & signBit) != 0 ? val - (signBit << 1) : val;
    }
}

package io.airlab.kafbat.serde.asterix.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;

/**
 * Parser for ASTERIX <em>Explicit</em> data items (used for SP and RE fields).
 *
 * <p>Structure: {@code LEN} (1 octet, value includes itself) followed by
 * {@code LEN - 1} octets of data.  The data bytes are returned as a hex string.
 */
public class ExplicitItemParser implements ItemParser {

    @Override
    public ObjectNode parse(ByteBuffer buffer, ObjectMapper mapper) {
        int len = buffer.get() & 0xFF;
        int dataLen = Math.max(0, len - 1);
        byte[] data = new byte[dataLen];
        if (dataLen > 0) buffer.get(data);

        ObjectNode node = mapper.createObjectNode();
        node.put("len", len);
        node.put("hex", bytesToHex(data));
        return node;
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b & 0xFF));
        return sb.toString();
    }
}

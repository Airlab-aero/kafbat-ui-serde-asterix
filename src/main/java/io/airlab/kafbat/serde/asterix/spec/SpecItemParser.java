package io.airlab.kafbat.serde.asterix.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.airlab.kafbat.serde.asterix.parser.ExplicitItemParser;
import io.airlab.kafbat.serde.asterix.parser.ItemParser;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime ASTERIX item parser driven by a JSON spec rule.
 *
 * <p>Supports all ASTERIX item types:
 * <ul>
 *   <li><b>Group</b> – fixed-size collection of named fields</li>
 *   <li><b>Extended</b> – FX-terminated variable, 7 data bits + 1 FX bit per octet</li>
 *   <li><b>Element</b> – single field with a specific bit width and content encoding</li>
 *   <li><b>Repetitive</b> – REP count followed by N sub-records</li>
 *   <li><b>Compound</b> – PSF-selected sub-items</li>
 *   <li><b>Explicit</b> – length-prefixed raw bytes</li>
 * </ul>
 */
public class SpecItemParser implements ItemParser {

    private static final char[] ICAO6 = {
        ' ','A','B','C','D','E','F','G','H','I','J','K','L','M',
        'N','O','P','Q','R','S','T','U','V','W','X','Y','Z',' ',
        ' ',' ',' ',' ','0','1','2','3','4','5','6','7','8','9',
        ' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',
        ' ',' ',' ',' ',' ',' ',' ',' ',' ',' '
    };

    private final JsonNode rule;

    public SpecItemParser(JsonNode rule) {
        this.rule = rule;
    }

    @Override
    public ObjectNode parse(ByteBuffer buffer, ObjectMapper mapper) {
        JsonNode result = parseRule(rule, buffer, mapper);
        if (result instanceof ObjectNode on) return on;
        // Wrap primitives (single-element items) in an object
        ObjectNode wrap = mapper.createObjectNode();
        wrap.set("val", result);
        return wrap;
    }

    // ── Rule dispatcher ────────────────────────────────────────────────────

    private JsonNode parseRule(JsonNode rule, ByteBuffer buf, ObjectMapper mapper) {
        if (rule == null) return mapper.createObjectNode();
        String type = rule.path("type").asText("Unknown");
        return switch (type) {
            case "Group"      -> parseGroup(rule, buf, mapper);
            case "Extended"   -> parseExtended(rule, buf, mapper);
            case "Element"    -> parseElement(rule, buf, mapper);
            case "Repetitive" -> parseRepetitive(rule, buf, mapper);
            case "Compound"   -> parseCompound(rule, buf, mapper);
            case "Explicit"   -> parseExplicit(buf, mapper);
            default           -> parseRawHex(buf, 0, mapper);
        };
    }

    // ── Group ──────────────────────────────────────────────────────────────

    private ObjectNode parseGroup(JsonNode rule, ByteBuffer buf, ObjectMapper mapper) {
        JsonNode parts = rule.path("items");
        int totalBits = totalBitsOfParts(parts);
        int totalBytes = (totalBits + 7) / 8;

        // Read into a byte array — works for groups wider than 64 bits
        byte[] data = new byte[totalBytes];
        for (int i = 0; i < totalBytes && buf.hasRemaining(); i++) {
            data[i] = buf.get();
        }

        ObjectNode result = mapper.createObjectNode();
        int bitPos = 0;
        for (JsonNode part : parts) {
            String ptype = part.path("type").asText();
            int size = partSize(part);
            if ("Spare".equals(ptype) || "Fx".equals(ptype)) {
                bitPos += size;
                continue;
            }
            if ("Item".equals(ptype)) {
                String name = part.path("name").asText("?");
                JsonNode subRule = part.path("rule");
                // All sub-items in Groups are Elements (verified against all bundled specs)
                long val = extractBitsLong(data, bitPos, size);
                bitPos += size;
                result.set(name, applyContent(subRule.path("content"), val, size, mapper));
            }
        }
        return result;
    }

    // ── Extended (FX-terminated) ───────────────────────────────────────────

    private ObjectNode parseExtended(JsonNode rule, ByteBuffer buf, ObjectMapper mapper) {
        JsonNode groups = rule.path("groups");
        ObjectNode result = mapper.createObjectNode();

        for (int gi = 0; gi < groups.size(); gi++) {
            JsonNode group = groups.get(gi);
            // Each group is exactly 7 data bits + 1 FX bit = 1 byte
            if (!buf.hasRemaining()) break;
            int byteVal = buf.get() & 0xFF;
            int fx = byteVal & 0x01;

            // Extract 7 data bits (bits 7..1)
            int remaining = 7;
            for (JsonNode part : group) {
                String ptype = part.path("type").asText();
                int size = partSize(part);
                remaining -= size;
                if ("Spare".equals(ptype)) continue;
                if ("Item".equals(ptype)) {
                    String name = part.path("name").asText("?");
                    JsonNode subRule = part.path("rule");
                    long val = ((long) byteVal >> (remaining + 1)) & mask(size);
                    result.set(name, applyContent(subRule.path("content"), val, size, mapper));
                }
            }

            if (fx == 0) break;
        }
        return result;
    }

    // ── Element ───────────────────────────────────────────────────────────

    private JsonNode parseElement(JsonNode rule, ByteBuffer buf, ObjectMapper mapper) {
        int size = rule.path("size").asInt(8);
        int bytes = (size + 7) / 8;
        if (size > 64) {
            // Oversized raw element (e.g. CAT240 radar video blocks) — emit as hex
            byte[] data = new byte[bytes];
            int toRead = Math.min(bytes, buf.remaining());
            buf.get(data, 0, toRead);
            return mapper.getNodeFactory().textNode(ExplicitItemParser.bytesToHex(data));
        }
        byte[] data = new byte[bytes];
        for (int i = 0; i < bytes && buf.hasRemaining(); i++) {
            data[i] = buf.get();
        }
        long raw = extractBitsLong(data, 0, size);
        return applyContent(rule.path("content"), raw, size, mapper);
    }

    // ── Repetitive ────────────────────────────────────────────────────────

    private JsonNode parseRepetitive(JsonNode rule, ByteBuffer buf, ObjectMapper mapper) {
        int repSize = rule.path("repSize").asInt(1);
        int count = 0;
        for (int i = 0; i < repSize; i++) count = (count << 8) | (buf.get() & 0xFF);

        JsonNode subRule = rule.path("rule");
        var array = mapper.createArrayNode();
        for (int i = 0; i < count; i++) {
            JsonNode item = parseRule(subRule, buf, mapper);
            array.add(item);
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("records", array);
        return result;
    }

    // ── Compound (PSF-selected sub-items) ─────────────────────────────────

    private ObjectNode parseCompound(JsonNode rule, ByteBuffer buf, ObjectMapper mapper) {
        JsonNode parts = rule.path("items");
        // Collect non-Spare, non-Fx sub-items in order
        List<JsonNode> subItems = new ArrayList<>();
        for (JsonNode p : parts) {
            String pt = p.path("type").asText();
            if ("Item".equals(pt)) subItems.add(p);
        }

        // Read PSF (primary sub-field): one byte at a time while FX=1
        List<Boolean> presence = new ArrayList<>();
        int b;
        do {
            if (!buf.hasRemaining()) break;
            b = buf.get() & 0xFF;
            for (int bit = 7; bit >= 1; bit--) {
                presence.add(((b >> bit) & 1) == 1);
            }
        } while ((b & 1) == 1);

        ObjectNode result = mapper.createObjectNode();
        for (int i = 0; i < Math.min(presence.size(), subItems.size()); i++) {
            if (presence.get(i)) {
                JsonNode part = subItems.get(i);
                String name = part.path("name").asText("?");
                JsonNode subRule = part.path("rule");
                result.set(name, parseRule(subRule, buf, mapper));
            }
        }
        return result;
    }

    // ── Explicit ──────────────────────────────────────────────────────────

    private ObjectNode parseExplicit(ByteBuffer buf, ObjectMapper mapper) {
        if (!buf.hasRemaining()) {
            ObjectNode n = mapper.createObjectNode();
            n.put("hex", "");
            return n;
        }
        int len = buf.get() & 0xFF;
        int dataLen = Math.max(0, len - 1);
        byte[] data = new byte[dataLen];
        if (dataLen > 0) buf.get(data);
        ObjectNode n = mapper.createObjectNode();
        n.put("len", len);
        n.put("hex", ExplicitItemParser.bytesToHex(data));
        return n;
    }

    // ── Content encoding ──────────────────────────────────────────────────

    private JsonNode applyContent(JsonNode content, long raw, int bits, ObjectMapper mapper) {
        String ctype = content.path("type").asText("Raw");
        var factory = mapper.getNodeFactory();

        return switch (ctype) {
            case "Raw", "Integer" -> factory.numberNode(raw);

            case "Quantity" -> {
                boolean signed = content.path("signed").asBoolean(false);
                double lsb = content.path("lsb").asDouble(1.0);
                double val = signed ? signExtend(raw, bits) * lsb : raw * lsb;
                yield factory.numberNode(val);
            }

            case "Table" -> {
                JsonNode values = content.path("values");
                String key = String.valueOf(raw);
                String label = values.has(key) ? values.get(key).asText() : key;
                yield factory.objectNode()
                    .<ObjectNode>set("val", factory.numberNode(raw))
                    .set("label", factory.textNode(label));
            }

            case "String" -> {
                String variant = content.path("variant").asText("Ascii");
                if ("Icao".equals(variant)) {
                    yield factory.textNode(decodeIcao(raw, bits));
                } else {
                    yield factory.textNode(decodeAscii(raw, bits));
                }
            }

            case "Bds", "Cf" -> factory.textNode(
                String.format("%0" + ((bits + 3) / 4) + "X", raw));

            default -> factory.numberNode(raw);
        };
    }

    // ── Bit arithmetic helpers ─────────────────────────────────────────────

    /**
     * Extract {@code numBits} bits (MSB-first) starting at bit offset {@code bitPos}
     * from {@code data}.  Works for any {@code numBits} in [1, 64].
     */
    private static long extractBitsLong(byte[] data, int bitPos, int numBits) {
        long result = 0;
        for (int i = 0; i < numBits; i++) {
            int byteIdx = (bitPos + i) >>> 3;
            int bitIdx  = 7 - ((bitPos + i) & 7);   // MSB of byte = bit 7
            if (byteIdx < data.length) {
                result = (result << 1) | ((data[byteIdx] >>> bitIdx) & 1);
            } else {
                result <<= 1;
            }
        }
        return result;
    }

    private static long mask(int bits) {
        return bits >= 64 ? -1L : (1L << bits) - 1;
    }

    private static double signExtend(long val, int bits) {
        if (bits >= 64) return val;
        long sign = 1L << (bits - 1);
        return (val & sign) != 0 ? (val - (sign << 1)) : val;
    }

    private static int totalBitsOfParts(JsonNode parts) {
        int total = 0;
        for (JsonNode part : parts) {
            total += partSize(part);
        }
        return total;
    }

    private static int partSize(JsonNode part) {
        String ptype = part.path("type").asText();
        if ("Spare".equals(ptype)) return part.path("size").asInt(0);
        if ("Fx".equals(ptype))    return 1;
        // For "Item", look into its rule
        JsonNode rule = part.path("rule");
        String rtype = rule.path("type").asText();
        if ("Element".equals(rtype)) return rule.path("size").asInt(0);
        // Nested groups: compute recursively
        if ("Group".equals(rtype)) return totalBitsOfParts(rule.path("items"));
        return 0;
    }

    private static String decodeIcao(long raw, int bits) {
        int chars = bits / 6;
        StringBuilder sb = new StringBuilder(chars);
        for (int i = chars - 1; i >= 0; i--) {
            int c6 = (int) ((raw >> (i * 6)) & 0x3F);
            sb.append(c6 < ICAO6.length ? ICAO6[c6] : '?');
        }
        // Reverse so MSB chars come first
        return new StringBuilder(sb).reverse().toString().stripTrailing();
    }

    private static String decodeAscii(long raw, int bits) {
        int bytes = bits / 8;
        byte[] b = new byte[bytes];
        for (int i = bytes - 1; i >= 0; i--) {
            b[i] = (byte) (raw & 0xFF);
            raw >>= 8;
        }
        return new String(b).stripTrailing();
    }

    @SuppressWarnings("unused")
    private static ObjectNode parseRawHex(ByteBuffer buf, int bytes, ObjectMapper mapper) {
        byte[] data = new byte[bytes];
        if (bytes > 0 && buf.remaining() >= bytes) buf.get(data);
        ObjectNode n = mapper.createObjectNode();
        n.put("hex", ExplicitItemParser.bytesToHex(data));
        return n;
    }
}

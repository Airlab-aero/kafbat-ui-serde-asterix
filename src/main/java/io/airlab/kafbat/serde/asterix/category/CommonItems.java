package io.airlab.kafbat.serde.asterix.category;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.airlab.kafbat.serde.asterix.parser.*;

import java.nio.ByteBuffer;

/**
 * Reusable {@link ItemParser} instances shared across multiple ASTERIX categories.
 *
 * <p>All parsers are stateless and thread-safe.
 */
final class CommonItems {

    private CommonItems() {}

    // -----------------------------------------------------------------------
    // ICAO 6-bit character set (Mode S / ASTERIX callsign encoding)
    // Index 0 = space, 1-26 = A-Z, 32-41 = 0-9
    // -----------------------------------------------------------------------
    private static final char[] ICAO6 = {
        ' ','A','B','C','D','E','F','G','H','I','J','K','L','M',
        'N','O','P','Q','R','S','T','U','V','W','X','Y','Z',' ',
        ' ',' ',' ',' ','0','1','2','3','4','5','6','7','8','9',
        ' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',
        ' ',' ',' ',' ',' ',' ',' ',' ',' ',' '
    };

    // -----------------------------------------------------------------------
    // Data Source Identifier – I010 (fixed 2 bytes: SAC + SIC)
    // -----------------------------------------------------------------------
    static final ItemParser DSI = new FixedItemParser(2,
        FieldDef.uint("SAC", 8),
        FieldDef.uint("SIC", 8)
    );

    // -----------------------------------------------------------------------
    // Time of Day – I030 / I073 / I077 (fixed 3 bytes, LSB = 1/128 s)
    // -----------------------------------------------------------------------
    static final ItemParser TIME_OF_DAY = new FixedItemParser(3,
        FieldDef.uscaled("ToD", 24, 1.0 / 128.0, "s")
    );

    // -----------------------------------------------------------------------
    // Mode-3/A Code with squawk formatting (fixed 2 bytes)
    // Bits: V(1), G(1), L(1), spare(1), Mode3A(12)
    // -----------------------------------------------------------------------
    static final ItemParser MODE3A = (buf, mapper) -> {
        int hi = buf.get() & 0xFF;
        int lo = buf.get() & 0xFF;
        int raw = (hi << 8) | lo;
        int v    = (raw >> 15) & 1;
        int g    = (raw >> 14) & 1;
        int l    = (raw >> 13) & 1;
        int code = raw & 0x0FFF;
        ObjectNode n = mapper.createObjectNode();
        n.put("V",      v);
        n.put("G",      g);
        n.put("L",      l);
        n.put("Mode3A", code);
        n.put("squawk", String.format("%04o", code));
        return n;
    };

    // -----------------------------------------------------------------------
    // 24-bit Mode S aircraft address (fixed 3 bytes → hex string)
    // -----------------------------------------------------------------------
    static final ItemParser MODES_ADDRESS = (buf, mapper) -> {
        int b0 = buf.get() & 0xFF, b1 = buf.get() & 0xFF, b2 = buf.get() & 0xFF;
        ObjectNode n = mapper.createObjectNode();
        n.put("Addr", String.format("%02X%02X%02X", b0, b1, b2));
        return n;
    };

    // -----------------------------------------------------------------------
    // 8-character ICAO callsign encoded in 6 bits/char × 8 = 6 bytes
    // -----------------------------------------------------------------------
    static final ItemParser CALLSIGN = (buf, mapper) -> {
        long raw = 0;
        for (int i = 0; i < 6; i++) raw = (raw << 8) | (buf.get() & 0xFF);
        StringBuilder id = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            int c6 = (int) ((raw >>> ((7 - i) * 6)) & 0x3F);
            id.append(c6 < ICAO6.length ? ICAO6[c6] : '?');
        }
        ObjectNode n = mapper.createObjectNode();
        n.put("TId", id.toString().stripTrailing());
        return n;
    };

    // -----------------------------------------------------------------------
    // SP / RE – Explicit items (length-prefixed hex dump)
    // -----------------------------------------------------------------------
    static final ItemParser EXPLICIT = new ExplicitItemParser();

    // -----------------------------------------------------------------------
    // Raw-hex fallback parsers (for items not decoded in detail)
    // -----------------------------------------------------------------------

    /** Read exactly {@code bytes} octets and return them as a hex string. */
    static ItemParser rawFixed(int bytes) {
        return (buf, mapper) -> {
            byte[] data = new byte[bytes];
            buf.get(data);
            ObjectNode n = mapper.createObjectNode();
            n.put("hex", ExplicitItemParser.bytesToHex(data));
            return n;
        };
    }

    /**
     * Consume a variable-length item (reads until FX = 0) and return raw hex.
     * Useful for variable items whose field layout is not yet decoded.
     */
    static ItemParser rawVariable() {
        return (buf, mapper) -> {
            StringBuilder hex = new StringBuilder();
            int b;
            do {
                b = buf.get() & 0xFF;
                hex.append(String.format("%02X", b));
            } while ((b & 0x01) == 1);
            ObjectNode n = mapper.createObjectNode();
            n.put("hex", hex.toString());
            return n;
        };
    }

    // -----------------------------------------------------------------------
    // WGS-84 position helpers
    // -----------------------------------------------------------------------

    /**
     * Parse a 24-bit signed latitude (MSB first) using
     * LSB = 180 / 2^23 degrees.
     */
    static double latFrom24(ByteBuffer buf) {
        int raw = (buf.get() & 0xFF) << 16 | (buf.get() & 0xFF) << 8 | (buf.get() & 0xFF);
        if ((raw & 0x800000) != 0) raw -= 0x1000000;   // sign-extend
        return raw * (180.0 / (1 << 23));
    }

    /**
     * Parse a 24-bit signed longitude (MSB first) using
     * LSB = 360 / 2^23 degrees.
     */
    static double lonFrom24(ByteBuffer buf) {
        int raw = (buf.get() & 0xFF) << 16 | (buf.get() & 0xFF) << 8 | (buf.get() & 0xFF);
        if ((raw & 0x800000) != 0) raw -= 0x1000000;
        return raw * (360.0 / (1 << 23));
    }

    /**
     * Parse a 32-bit signed latitude (MSB first) using
     * LSB = 180 / 2^31 degrees.
     */
    static double latFrom32(ByteBuffer buf) {
        long raw = readUint32(buf);
        if ((raw & 0x80000000L) != 0) raw -= 0x100000000L;
        return raw * (180.0 / (1L << 31));
    }

    /**
     * Parse a 32-bit signed longitude (MSB first) using
     * LSB = 360 / 2^31 degrees.
     */
    static double lonFrom32(ByteBuffer buf) {
        long raw = readUint32(buf);
        if ((raw & 0x80000000L) != 0) raw -= 0x100000000L;
        return raw * (360.0 / (1L << 31));
    }

    private static long readUint32(ByteBuffer buf) {
        return ((long)(buf.get() & 0xFF) << 24)
             | ((buf.get() & 0xFF) << 16)
             | ((buf.get() & 0xFF) << 8)
             |  (buf.get() & 0xFF);
    }
}

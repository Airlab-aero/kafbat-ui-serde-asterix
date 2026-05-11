package io.airlab.kafbat.serde.asterix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.airlab.kafbat.serde.asterix.category.CategoryRegistry;
import io.airlab.kafbat.serde.asterix.parser.AsterixParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the ASTERIX binary parser.
 *
 * <p>All test messages are hand-crafted bytes whose expected decoded values
 * are computed from first principles using the EUROCONTROL specification.
 */
class AsterixParserTest {

    private AsterixParser parser;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        parser = new AsterixParser(CategoryRegistry.withBuiltins());
        mapper = new ObjectMapper();
    }

    // -----------------------------------------------------------------------
    // CAT048 – Monoradar Target Reports
    // -----------------------------------------------------------------------

    /**
     * Minimal CAT048 message with I010 (DSI), I140 (ToD), and I040 (position).
     *
     * <pre>
     * Header:  30 00 0D             CAT=48 (0x30), LEN=13
     * FSPEC:   D0                   I010, I140, I040 present (bits 7,6,4 set; FX=0)
     * I010:    00 01                SAC=0, SIC=1
     * I140:    46 50 00             ToD = 0x465000 / 128 = 36000.0 s (10:00:00 UTC)
     * I040:    64 00 40 00          RHO=25600/256=100.0 NM, THETA=16384*360/65536=90.0°
     * </pre>
     */
    @Test
    void cat048_basicPosition() {
        byte[] msg = {
            0x30, 0x00, 0x0D,         // CAT=48, LEN=13
            (byte) 0xD0,              // FSPEC: I010 (bit7), I140 (bit6), I040 (bit4)
            0x00, 0x01,               // I010: SAC=0, SIC=1
            0x46, 0x50, 0x00,         // I140: ToD=36000.0 s
            0x64, 0x00, 0x40, 0x00    // I040: RHO=100.0 NM, THETA=90.0 deg
        };

        ArrayNode result = parser.parse(msg);

        assertThat(result).hasSize(1);
        JsonNode block = result.get(0);
        assertThat(block.get("category").asInt()).isEqualTo(48);
        assertThat(block.get("name").asText()).isEqualTo("Monoradar Target Reports");

        JsonNode record = block.get("records").get(0);

        // I010
        assertThat(record.get("I010").get("SAC").asInt()).isEqualTo(0);
        assertThat(record.get("I010").get("SIC").asInt()).isEqualTo(1);

        // I140 – time of day; spec encodes as Element Quantity, wrapped to {"val": <seconds>}
        assertThat(record.get("I140").get("val").asDouble()).isCloseTo(36000.0, within(0.01));

        // I040 – polar position
        assertThat(record.get("I040").get("RHO").asDouble()).isCloseTo(100.0, within(0.01));
        assertThat(record.get("I040").get("THETA").asDouble()).isCloseTo(90.0, within(0.01));
    }

    /**
     * CAT048 message with Mode-3/A squawk 7700 (emergency) and flight level 350.
     *
     * <pre>
     * Squawk 7700:  bits = 0b111_111_000_000 = 0xFC0 → %04o = "7700"
     * FL 350:       350 * 4 = 1400 = 0x0578 → 2 bytes: V=0, G=0, FL=1400
     *               Encoded: 0x0578 (14 bits), padded to 2 bytes with V/G bits = 0x0578
     * </pre>
     */
    @Test
    void cat048_squawkAndFlightLevel() {
        // FSPEC: I010(bit7), I070(bit3), I090(bit2) → 0x80 | 0x08 | 0x04 = 0x8C
        // I010: SAC=0, SIC=2
        // I070: V=0, G=0, L=0, spare=0, Mode3A=0xFC0 → encoded as 0x0FC0
        // I090: V=0, G=0, FL=1400 (0x0578 in 14 bits) → 0x05 0x78
        byte[] msg = {
            0x30, 0x00, 0x0A,        // CAT=48, LEN=10
            (byte) 0x8C,             // FSPEC: I010, I070, I090
            0x00, 0x02,              // I010: SAC=0, SIC=2
            0x0F, (byte) 0xC0,       // I070: Mode3A = 0xFC0 (= 7700 octal)
            0x05, 0x78               // I090: V=0, G=0, FL=1400 (=350.0 FL, unit=25ft)
        };

        ArrayNode result = parser.parse(msg);
        JsonNode record = result.get(0).get("records").get(0);

        // Mode-3/A; V/G/L are Table-encoded → {"val":n,"label":"..."}; MODE3A is Octal-encoded
        assertThat(record.get("I070").get("MODE3A").asText()).isEqualTo("7700");
        assertThat(record.get("I070").get("V").get("val").asInt()).isEqualTo(0);

        // Flight level: FL raw = 1400, physical = 1400 * 0.25 = 350.0 FL
        assertThat(record.get("I090").get("FL").asDouble()).isCloseTo(350.0, within(0.01));
    }

    /**
     * CAT048 with Mode S aircraft address and ICAO callsign "BAW123".
     *
     * <pre>
     * I220 (Mode S address 0x3C6CF2):  3C 6C F2
     * I240 (callsign "BAW123  "):
     *   B=2, A=1, W=23, 1=33, 2=34, 3=35, ' '=0, ' '=0
     *   Packed: 000010 000001 010111 100001 100010 100011 000000 000000
     *   Bytes:  0x08   0x15   0xE1   0x8A   0x30   0x00
     * </pre>
     */
    @Test
    void cat048_modeSAddressAndCallsign() {
        // FSPEC octet 1: only I010 present (bit7) → 0x80; FX=0
        // FSPEC octet 2: I220 (bit7), I240 (bit6) present → 0xC0; FX=0
        // Need 2 FSPEC bytes → set FX in first byte: 0x81
        byte[] msg = {
            0x30, 0x00, 0x10,         // CAT=48, LEN=16
            (byte) 0x81, (byte) 0xC0, // FSPEC: I010 in byte1, I220+I240 in byte2
            0x00, 0x03,               // I010: SAC=0, SIC=3
            0x3C, 0x6C, (byte) 0xF2, // I220: Mode S address = 3C6CF2
            0x08, 0x15, (byte) 0xE1, (byte) 0x8A, 0x30, 0x00  // I240: "BAW123  "
        };

        ArrayNode result = parser.parse(msg);
        JsonNode record = result.get(0).get("records").get(0);

        // I220 is a 24-bit Raw Element, wrapped to {"val": <long>}
        assertThat(record.get("I220").get("val").asLong()).isEqualTo(0x3C6CF2L);
        // I240 is a 48-bit ICAO6 string Element, wrapped to {"val": "<callsign>"}
        assertThat(record.get("I240").get("val").asText()).isEqualTo("BAW123");
    }

    // -----------------------------------------------------------------------
    // CAT002 – Monoradar Service Messages
    // -----------------------------------------------------------------------

    /**
     * CAT002 north marker message.
     *
     * <pre>
     * Header: 02 00 0A    CAT=2, LEN=10
     * FSPEC:  C0          I010 (bit7), I000 (bit6) present; FX=0
     * I010:   00 01       SAC=0, SIC=1
     * I000:   01          Message Type = 1 (North Marker)
     * I030:   …           absent
     * Wait – with FSPEC=0xC0 only I010 and I000 are present: total = 3+1+2+1=7, LEN=7
     * </pre>
     */
    @Test
    void cat002_northMarker() {
        byte[] msg = {
            0x02, 0x00, 0x07,         // CAT=2, LEN=7
            (byte) 0xC0,              // FSPEC: I010, I000
            0x00, 0x01,               // I010: SAC=0, SIC=1
            0x01                      // I000: MT=1 (North Marker)
        };

        ArrayNode result = parser.parse(msg);

        assertThat(result).hasSize(1);
        JsonNode block = result.get(0);
        assertThat(block.get("category").asInt()).isEqualTo(2);

        JsonNode record = block.get("records").get(0);
        assertThat(record.get("I010").get("SAC").asInt()).isEqualTo(0);
        assertThat(record.get("I010").get("SIC").asInt()).isEqualTo(1);
        // I000 is a Table-encoded Element, wrapped to {"val": 1, "label": "North marker message"}
        assertThat(record.get("I000").get("val").asInt()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Multiple data blocks in one Kafka message
    // -----------------------------------------------------------------------

    @Test
    void multipleDataBlocks_parsedAsArray() {
        // Two identical CAT002 north-marker messages concatenated
        byte[] single = { 0x02, 0x00, 0x07, (byte) 0xC0, 0x00, 0x01, 0x01 };
        byte[] msg = new byte[single.length * 2];
        System.arraycopy(single, 0, msg, 0, single.length);
        System.arraycopy(single, 0, msg, single.length, single.length);

        ArrayNode result = parser.parse(msg);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("category").asInt()).isEqualTo(2);
        assertThat(result.get(1).get("category").asInt()).isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // Unknown category – hex fallback
    // -----------------------------------------------------------------------

    @Test
    void unknownCategory_returnedAsHex() {
        byte[] msg = {
            0x63, 0x00, 0x06,        // CAT=99 (unknown), LEN=6
            0x11, 0x22, 0x33         // 3 bytes payload
        };

        ArrayNode result = parser.parse(msg);
        assertThat(result).hasSize(1);
        JsonNode block = result.get(0);
        assertThat(block.get("category").asInt()).isEqualTo(99);
        assertThat(block.get("unknown").asBoolean()).isTrue();
        assertThat(block.get("hex").asText()).isEqualToIgnoringCase("112233");
    }

    // -----------------------------------------------------------------------
    // CAT021 – ADS-B Target Reports
    // -----------------------------------------------------------------------

    /**
     * Minimal CAT021 message with I010 (DSI) and I130 (WGS-84 position).
     *
     * <p>CAT021 I130 LSB is 180/2^23 degrees for <em>both</em> LAT and LON (signed 24-bit).
     *
     * <pre>
     * Latitude  = 48.0° → raw = round(48.0 × 2^23 / 180) = 2236450 ≈ 0x222522
     * Longitude = 2.35° → raw = round(2.35 × 2^23 / 180) = 109398  ≈ 0x01AB56
     * </pre>
     */
    @Test
    void cat021_wgs84Position() {
        // CAT021 I130 uses lsb = 180/2^23 for both LAT and LON (signed 24-bit)
        // LAT: 48.0 deg, raw = round(48 * 2^23 / 180)
        // LON: 2.35 deg, raw = round(2.35 * 2^23 / 180)
        int latRaw = (int) Math.round(48.0 * (1 << 23) / 180.0);
        int lonRaw = (int) Math.round(2.35 * (1 << 23) / 180.0);

        // CAT021 UAP (ed 2.4): [I010, I040, I161, I015, I071, I130, ...]
        // FSPEC for I010 (bit7) + I130 (bit2) = 0x84
        byte[] msg = {
            0x15, 0x00, 0x0C,              // CAT=21, LEN=12
            (byte) 0x84,                   // FSPEC: I010 (bit7), I130 (bit2)
            0x00, 0x04,                    // I010: SAC=0, SIC=4
            // I130: LAT(3 bytes), LON(3 bytes)
            (byte)((latRaw >> 16) & 0xFF),
            (byte)((latRaw >> 8)  & 0xFF),
            (byte)(latRaw         & 0xFF),
            (byte)((lonRaw >> 16) & 0xFF),
            (byte)((lonRaw >> 8)  & 0xFF),
            (byte)(lonRaw         & 0xFF)
        };

        ArrayNode result = parser.parse(msg);
        JsonNode record = result.get(0).get("records").get(0);

        assertThat(record.get("I010").get("SIC").asInt()).isEqualTo(4);

        double lat = record.get("I130").get("LAT").asDouble();
        double lon = record.get("I130").get("LON").asDouble();
        assertThat(lat).isCloseTo(48.0, within(0.001));
        assertThat(lon).isCloseTo(2.35, within(0.001));
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    void emptyInput_returnsEmptyArray() {
        assertThat(parser.parse(new byte[0])).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    void tooShortInput_returnsEmptyArray() {
        assertThat(parser.parse(new byte[]{0x30, 0x00})).isEmpty();
    }

    @Test
    void cat048_signedCartesianPosition() {
        // I042: X = -100 NM (raw = -100 * 128 = -12800 = 0xCE00 in signed 16-bit)
        //        Y =  200 NM (raw =  200 * 128 =  25600 = 0x6400)
        // FSPEC: I010 (bit7), I042 in octet2 (bit4 of second FSPEC byte)
        // UAP octet 2 positions (bit7..1): I220, I240, I250, I161, I042, I200, I170
        // I042 is at position 4 (0-indexed) in octet 2 → bit3 of second FSPEC byte = 0x08
        // First FSPEC byte: just I010 with FX=1 → 0x81
        // Second FSPEC byte: I042 only → 0x08, FX=0
        byte[] msg = {
            0x30, 0x00, 0x0A,          // CAT=48, LEN=10
            (byte) 0x81, 0x08,         // FSPEC: I010 (byte1), I042 (byte2)
            0x00, 0x05,                // I010: SAC=0, SIC=5
            (byte) 0xCE, 0x00,         // I042 X = -12800 * (1/128) = -100.0 NM
            0x64, 0x00                 // I042 Y =  25600 * (1/128) = 200.0 NM
        };

        ArrayNode result = parser.parse(msg);
        JsonNode record = result.get(0).get("records").get(0);

        assertThat(record.get("I042").get("X").asDouble()).isCloseTo(-100.0, within(0.01));
        assertThat(record.get("I042").get("Y").asDouble()).isCloseTo(200.0, within(0.01));
    }
}

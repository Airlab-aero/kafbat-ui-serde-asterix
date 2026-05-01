package io.airlab.kafbat.serde.asterix.category;

import io.airlab.kafbat.serde.asterix.parser.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ASTERIX Category 048 – Monoradar Target Reports (Edition 1.32).
 *
 * <p>The primary category for conventional PSR/SSR monoradar target data.
 * Carries measured and calculated position, track, Mode S, and ACAS data.
 *
 * <p>Reference: EUROCONTROL-SPEC-0048 Edition 1.32, 2024-07-01.
 */
final class Cat048 {

    private Cat048() {}

    // -----------------------------------------------------------------------
    // UAP
    // -----------------------------------------------------------------------
    private static final String[] UAP = {
        // FSPEC octet 1
        "I010", "I020", "I140", "I040", "I070", "I090", "I130",
        // FSPEC octet 2
        "I220", "I240", "I250", "I161", "I042", "I200", "I170",
        // FSPEC octet 3
        "I210", "I030", "I080", "I100", "I110", "I120", "I230",
        // FSPEC octet 4
        "SP",   "RE",   "I260", null,   null,   null,   null
    };

    // -----------------------------------------------------------------------
    // Item parsers
    // -----------------------------------------------------------------------

    static CategoryDefinition definition() {
        Map<String, ItemParser> items = new LinkedHashMap<>();

        // ------------------------------------------------------------------
        // I010 – Data Source Identifier (2 bytes)
        // ------------------------------------------------------------------
        items.put("I010", CommonItems.DSI);

        // ------------------------------------------------------------------
        // I020 – Target Report Descriptor (Variable, up to 3 octets)
        //   Octet 1: TYP(3), SIM(1), RDPC(1), RDPS(1), ART(1)
        //     TYP: 0=no det, 1=PSR, 2=SSR-only, 3=SSR+PSR, 4=ModeS+PSR,
        //          5=ModeS+SSR+PSR, 6=Enhanced Sur., 7=Dual radar
        //   Octet 2: TST(1), ERR(1), XPP(1), ME(1), MI(1), FOEFRI(2)
        //   Octet 3: ADSB(2), SCN(1), PAI(1), spare(3)
        // ------------------------------------------------------------------
        items.put("I020", new VariableItemParser(
            new FieldDef[]{
                FieldDef.uint("TYP",  3), FieldDef.flag("SIM"),
                FieldDef.flag("RDPC"), FieldDef.flag("RDPS"), FieldDef.flag("ART")
            },
            new FieldDef[]{
                FieldDef.flag("TST"), FieldDef.flag("ERR"),
                FieldDef.flag("XPP"), FieldDef.flag("ME"),
                FieldDef.flag("MI"),  FieldDef.uint("FOEFRI", 2)
            },
            new FieldDef[]{
                FieldDef.uint("ADSB", 2), FieldDef.flag("SCN"),
                FieldDef.flag("PAI"),     FieldDef.spare(3)
            }
        ));

        // ------------------------------------------------------------------
        // I140 – Time of Day (3 bytes, LSB = 1/128 s)
        // ------------------------------------------------------------------
        items.put("I140", CommonItems.TIME_OF_DAY);

        // ------------------------------------------------------------------
        // I040 – Measured Position in Slant Polar Coordinates (4 bytes)
        //   RHO:   unsigned 16-bit, LSB = 1/256 NM
        //   THETA: unsigned 16-bit, LSB = 360/65536 degrees
        // ------------------------------------------------------------------
        items.put("I040", new FixedItemParser(4,
            FieldDef.uscaled("RHO",   16, 1.0 / 256.0,     "NM"),
            FieldDef.uscaled("THETA", 16, 360.0 / 65536.0, "deg")
        ));

        // ------------------------------------------------------------------
        // I070 – Mode-3/A Code with squawk formatting (2 bytes)
        // ------------------------------------------------------------------
        items.put("I070", CommonItems.MODE3A);

        // ------------------------------------------------------------------
        // I090 – Flight Level in Binary Representation (2 bytes)
        //   V(1), G(1), FL(14 signed, LSB = 1/4 FL = 25 ft)
        // ------------------------------------------------------------------
        items.put("I090", new FixedItemParser(2,
            FieldDef.flag("V"),
            FieldDef.flag("G"),
            FieldDef.sscaled("FL", 14, 0.25, "FL")
        ));

        // ------------------------------------------------------------------
        // I130 – Radar Plot Characteristics (Compound)
        //   Sub-items (all 1-byte fixed):
        //     SRL: spare(1) + SRL(7, LSB = 360/2^13 deg)
        //     SRR: spare(4) + SRR(4)
        //     SAM: SAM(8, signed, LSB = 1 dBm)
        //     PRL: spare(1) + PRL(7, LSB = 360/2^13 deg)
        //     PAM: PAM(8, signed, LSB = 1 dBm)
        //     RPD: RPD(8, signed, LSB = 1/256 NM)
        //     APD: APD(8, signed, LSB = 360/2^13 deg)
        // ------------------------------------------------------------------
        items.put("I130", new CompoundItemParser(
            new String[]{"SRL", "SRR", "SAM", "PRL", "PAM", "RPD", "APD"},
            new ItemParser[]{
                new FixedItemParser(1, FieldDef.spare(1),
                    FieldDef.uscaled("SRL", 7, 360.0 / 8192.0, "deg")),
                new FixedItemParser(1, FieldDef.spare(4),
                    FieldDef.uint("SRR", 4)),
                new FixedItemParser(1,
                    FieldDef.sscaled("SAM", 8, 1.0, "dBm")),
                new FixedItemParser(1, FieldDef.spare(1),
                    FieldDef.uscaled("PRL", 7, 360.0 / 8192.0, "deg")),
                new FixedItemParser(1,
                    FieldDef.sscaled("PAM", 8, 1.0, "dBm")),
                new FixedItemParser(1,
                    FieldDef.sscaled("RPD", 8, 1.0 / 256.0, "NM")),
                new FixedItemParser(1,
                    FieldDef.sscaled("APD", 8, 360.0 / 8192.0, "deg"))
            }
        ));

        // ------------------------------------------------------------------
        // I220 – Aircraft Address (Mode S, 3 bytes → hex string)
        // ------------------------------------------------------------------
        items.put("I220", CommonItems.MODES_ADDRESS);

        // ------------------------------------------------------------------
        // I240 – Aircraft Identification (6-byte ICAO 6-bit callsign)
        // ------------------------------------------------------------------
        items.put("I240", CommonItems.CALLSIGN);

        // ------------------------------------------------------------------
        // I250 – Mode S MB Data (Repetitive)
        //   REP + n × [MB(7 bytes) + BDS1(4 bits) + BDS2(4 bits)]
        // ------------------------------------------------------------------
        items.put("I250", (buf, mapper) -> {
            int rep = buf.get() & 0xFF;
            var list = mapper.createArrayNode();
            for (int i = 0; i < rep; i++) {
                byte[] mb = new byte[7];
                buf.get(mb);
                int bds = buf.get() & 0xFF;
                var rec = mapper.createObjectNode();
                rec.put("MB",   ExplicitItemParser.bytesToHex(mb));
                rec.put("BDS1", (bds >> 4) & 0x0F);
                rec.put("BDS2", bds & 0x0F);
                list.add(rec);
            }
            var n = mapper.createObjectNode();
            n.set("list", list);
            return n;
        });

        // ------------------------------------------------------------------
        // I161 – Track Number (2 bytes): spare(3) + TRK(13)
        // ------------------------------------------------------------------
        items.put("I161", new FixedItemParser(2,
            FieldDef.spare(3),
            FieldDef.uint("TRK", 13)
        ));

        // ------------------------------------------------------------------
        // I042 – Calculated Position in Cartesian Coordinates (4 bytes)
        //   X, Y: signed 16-bit, LSB = 1/128 NM
        // ------------------------------------------------------------------
        items.put("I042", new FixedItemParser(4,
            FieldDef.sscaled("X", 16, 1.0 / 128.0, "NM"),
            FieldDef.sscaled("Y", 16, 1.0 / 128.0, "NM")
        ));

        // ------------------------------------------------------------------
        // I200 – Calculated Track Velocity in Polar Representation (4 bytes)
        //   GSPD: unsigned 16-bit, LSB = 2^-14 NM/s (≈ 6.1×10^-5 NM/s)
        //   HDNG: unsigned 16-bit, LSB = 360/65536 degrees
        // ------------------------------------------------------------------
        items.put("I200", new FixedItemParser(4,
            FieldDef.uscaled("GSPD", 16, Math.pow(2, -14),  "NM/s"),
            FieldDef.uscaled("HDNG", 16, 360.0 / 65536.0,   "deg")
        ));

        // ------------------------------------------------------------------
        // I170 – Track Status (Variable, up to 2 octets)
        //   Octet 1: CNF(1), RAD(2), DOU(1), MAH(1), CDM(2)
        //   Octet 2: TRE(1), GHO(1), SUP(1), TCC(1), spare(3)
        // ------------------------------------------------------------------
        items.put("I170", new VariableItemParser(
            new FieldDef[]{
                FieldDef.flag("CNF"), FieldDef.uint("RAD", 2),
                FieldDef.flag("DOU"), FieldDef.flag("MAH"), FieldDef.uint("CDM", 2)
            },
            new FieldDef[]{
                FieldDef.flag("TRE"), FieldDef.flag("GHO"),
                FieldDef.flag("SUP"), FieldDef.flag("TCC"),
                FieldDef.spare(3)
            }
        ));

        // ------------------------------------------------------------------
        // I210 – Track Quality (4 bytes)
        //   σx(8, unsigned, LSB=1/128 NM), σy(8, unsigned, LSB=1/128 NM),
        //   σv(8, unsigned, LSB=2^-14 NM/s), σH(8, unsigned, LSB=360/65536 deg)
        // ------------------------------------------------------------------
        items.put("I210", new FixedItemParser(4,
            FieldDef.uscaled("sigmaX", 8, 1.0 / 128.0,        "NM"),
            FieldDef.uscaled("sigmaY", 8, 1.0 / 128.0,        "NM"),
            FieldDef.uscaled("sigmaV", 8, Math.pow(2, -14),   "NM/s"),
            FieldDef.uscaled("sigmaH", 8, 360.0 / 65536.0,    "deg")
        ));

        // ------------------------------------------------------------------
        // I030 – Warning/Error Conditions (Variable – raw hex)
        // ------------------------------------------------------------------
        items.put("I030", CommonItems.rawVariable());

        // ------------------------------------------------------------------
        // I080 – Mode-3/A Code Confidence Indicator (2 bytes – raw hex)
        // ------------------------------------------------------------------
        items.put("I080", CommonItems.rawFixed(2));

        // ------------------------------------------------------------------
        // I100 – Mode-C Code and Code Confidence Indicator (4 bytes)
        //   V(1), G(1), spare(2), GrayCode(12) | ConfBits(12), spare(4)
        // ------------------------------------------------------------------
        items.put("I100", (buf, mapper) -> {
            int hi  = buf.get() & 0xFF, lo  = buf.get() & 0xFF;
            int chi = buf.get() & 0xFF, clo = buf.get() & 0xFF;
            int code = ((hi & 0x0F) << 8) | lo;
            int conf = ((chi & 0xFF) << 4) | ((clo >> 4) & 0x0F);
            var n = mapper.createObjectNode();
            n.put("V",    (hi >> 7) & 1);
            n.put("G",    (hi >> 6) & 1);
            n.put("ModeC", code);         // Gray-coded Mode C altitude bits
            n.put("Conf",  conf);
            return n;
        });

        // ------------------------------------------------------------------
        // I110 – Height Measured by a 3D Radar (2 bytes)
        //   spare(2), 3DH(14 signed, LSB = 25 ft)
        // ------------------------------------------------------------------
        items.put("I110", new FixedItemParser(2,
            FieldDef.spare(2),
            FieldDef.sscaled("3DH", 14, 25.0, "ft")
        ));

        // ------------------------------------------------------------------
        // I120 – Radial Doppler Speed (Compound)
        //   Sub-item CAL (2 bytes): D(1), spare(5), CAL(10 signed, LSB=1 m/s)
        //   Sub-item RDS (Repetitive, 6 bytes): DOP(16s)+AMB(16u)+FRQ(16u)
        // ------------------------------------------------------------------
        items.put("I120", new CompoundItemParser(
            new String[]{"CAL", "RDS"},
            new ItemParser[]{
                new FixedItemParser(2,
                    FieldDef.flag("D"),
                    FieldDef.spare(5),
                    FieldDef.sscaled("CAL", 10, 1.0, "m/s")),
                new RepetitiveItemParser(6,
                    FieldDef.sscaled("DOP", 16, 1.0, "m/s"),
                    FieldDef.uscaled("AMB", 16, 1.0, "m/s"),
                    FieldDef.uscaled("FRQ", 16, 1.0, "MHz"))
            }
        ));

        // ------------------------------------------------------------------
        // I230 – Communications/ACAS Capability and Flight Status (2 bytes)
        //   COM(3), STAT(3), SI(1), spare(1), MSSC(1), ARC(1), AIC(1),
        //   B1A(1), B1B(4)
        // ------------------------------------------------------------------
        items.put("I230", new FixedItemParser(2,
            FieldDef.uint("COM",  3),
            FieldDef.uint("STAT", 3),
            FieldDef.flag("SI"),
            FieldDef.spare(1),
            FieldDef.flag("MSSC"),
            FieldDef.flag("ARC"),
            FieldDef.flag("AIC"),
            FieldDef.flag("B1A"),
            FieldDef.uint("B1B",  4)
        ));

        // ------------------------------------------------------------------
        // I260 – ACAS Resolution Advisory Report (7 bytes – MB hex dump)
        // ------------------------------------------------------------------
        items.put("I260", (buf, mapper) -> {
            byte[] mb = new byte[7];
            buf.get(mb);
            var n = mapper.createObjectNode();
            n.put("MB", ExplicitItemParser.bytesToHex(mb));
            return n;
        });

        // ------------------------------------------------------------------
        // SP / RE – Explicit (length-prefixed) fields
        // ------------------------------------------------------------------
        items.put("SP", CommonItems.EXPLICIT);
        items.put("RE", CommonItems.EXPLICIT);

        return new CategoryDefinition(48, "Monoradar Target Reports", UAP, items);
    }
}

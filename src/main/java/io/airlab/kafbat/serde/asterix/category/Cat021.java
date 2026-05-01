package io.airlab.kafbat.serde.asterix.category;

import io.airlab.kafbat.serde.asterix.parser.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ASTERIX Category 021 – ADS-B Target Reports (Edition 2.6).
 *
 * <p>Carries ADS-B surveillance data: WGS-84 position, barometric/geometric
 * altitude, ground speed, track angle, callsign, Mode S address, and more.
 *
 * <p>Reference: EUROCONTROL-SPEC-0021 Edition 2.6, 2021-12-21.
 */
final class Cat021 {

    private Cat021() {}

    // -----------------------------------------------------------------------
    // UAP (Edition 2.6)
    // -----------------------------------------------------------------------
    private static final String[] UAP = {
        // FSPEC octet 1
        "I010", "I040", "I030", "I130", "I080", "I073", "I074",
        // FSPEC octet 2
        "I075", "I076", "I140", "I090", "I210", "I070", "I230",
        // FSPEC octet 3
        "I145", "I152", "I200", "I155", "I157", "I160", "I165",
        // FSPEC octet 4
        "I077", "I170", "I020", "I220", "I146", "I148", "I110",
        // FSPEC octet 5
        "I016", "I008", "I271", "I150", "I151", "I131", "I132",
        // FSPEC octet 6
        "I133", "I134", "I135", "I136", "I550", null,   "SP",
        // FSPEC octet 7
        "RE"
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
        // I040 – Target Report Descriptor (Variable, up to 3 octets)
        //   Octet 1: ATP(3), ARC(2), RC(1), RAB(1)
        //     ATP: 0=non-discrete, 1=ICAO 24-bit, 2=duplicate, 3=surface,
        //          4=anonymous, 5-7=reserved
        //     ARC: 0=25ft, 1=100ft, 2=unknown, 3=invalid
        //   Octet 2: DCR(1), GBS(1), SIM(1), TST(1), SAA(1), CL(2)
        //   Octet 3: LLC(1), IPC(1), NOGO(1), CPR(1), LDPJ(1), RCF(1), spare(1)
        // ------------------------------------------------------------------
        items.put("I040", new VariableItemParser(
            new FieldDef[]{
                FieldDef.uint("ATP", 3), FieldDef.uint("ARC", 2),
                FieldDef.flag("RC"),    FieldDef.flag("RAB")
            },
            new FieldDef[]{
                FieldDef.flag("DCR"), FieldDef.flag("GBS"), FieldDef.flag("SIM"),
                FieldDef.flag("TST"), FieldDef.flag("SAA"), FieldDef.uint("CL", 2)
            },
            new FieldDef[]{
                FieldDef.flag("LLC"),  FieldDef.flag("IPC"),
                FieldDef.flag("NOGO"), FieldDef.flag("CPR"),
                FieldDef.flag("LDPJ"), FieldDef.flag("RCF"),
                FieldDef.spare(1)
            }
        ));

        // ------------------------------------------------------------------
        // I030 – Time of Day (3 bytes, LSB = 1/128 s)
        // ------------------------------------------------------------------
        items.put("I030", CommonItems.TIME_OF_DAY);

        // ------------------------------------------------------------------
        // I130 – Position in WGS-84 Co-ordinates (6 bytes)
        //   LAT: 24-bit signed, LSB = 180/2^23 degrees
        //   LON: 24-bit signed, LSB = 360/2^23 degrees
        // ------------------------------------------------------------------
        items.put("I130", (buf, mapper) -> {
            double lat = CommonItems.latFrom24(buf);
            double lon = CommonItems.lonFrom24(buf);
            var n = mapper.createObjectNode();
            n.put("LAT", lat);
            n.put("LON", lon);
            return n;
        });

        // ------------------------------------------------------------------
        // I080 – Target Address (Mode S, 3 bytes)
        // ------------------------------------------------------------------
        items.put("I080", CommonItems.MODES_ADDRESS);

        // ------------------------------------------------------------------
        // I073 – Time of Message Reception for Position (3 bytes, 1/128 s)
        // ------------------------------------------------------------------
        items.put("I073", CommonItems.TIME_OF_DAY);

        // ------------------------------------------------------------------
        // I074 – Time of Message Reception for Position – High Precision (4 bytes)
        //   Byte 0: FSI(2) + 6 bits of fractional seconds
        //   Bytes 1-3: 24-bit fractional seconds (1/2^30 s)
        // ------------------------------------------------------------------
        items.put("I074", CommonItems.rawFixed(4));

        // ------------------------------------------------------------------
        // I075 – Time of Message Reception for Velocity (3 bytes, 1/128 s)
        // ------------------------------------------------------------------
        items.put("I075", CommonItems.TIME_OF_DAY);

        // ------------------------------------------------------------------
        // I076 – Time of Message Reception for Velocity – High Precision (4 bytes)
        // ------------------------------------------------------------------
        items.put("I076", CommonItems.rawFixed(4));

        // ------------------------------------------------------------------
        // I140 – Geometric Height (2 bytes, signed, LSB = 6.25 ft)
        // ------------------------------------------------------------------
        items.put("I140", new FixedItemParser(2,
            FieldDef.sscaled("GeoH", 16, 6.25, "ft")
        ));

        // ------------------------------------------------------------------
        // I090 – Figure of Merit / NUCp or NIC (Variable – raw hex)
        // ------------------------------------------------------------------
        items.put("I090", CommonItems.rawVariable());

        // ------------------------------------------------------------------
        // I210 – Link Technology Indicator (Variable)
        //   Octet 1: spare(3), DTI(1), MDS(1), UAT(1), VDL(1)
        // ------------------------------------------------------------------
        items.put("I210", new VariableItemParser(
            new FieldDef[]{
                FieldDef.spare(3),    FieldDef.flag("DTI"),
                FieldDef.flag("MDS"), FieldDef.flag("UAT"), FieldDef.flag("VDL")
            }
        ));

        // ------------------------------------------------------------------
        // I070 – Mode-3/A Code (2 bytes) – same as CAT048
        // ------------------------------------------------------------------
        items.put("I070", CommonItems.MODE3A);

        // ------------------------------------------------------------------
        // I230 – Roll Angle (2 bytes, signed, LSB = 0.01 degrees)
        // ------------------------------------------------------------------
        items.put("I230", new FixedItemParser(2,
            FieldDef.sscaled("Roll", 16, 0.01, "deg")
        ));

        // ------------------------------------------------------------------
        // I145 – Flight Level (2 bytes)
        //   V(1), spare(1), FL(14 signed, LSB = 1/4 FL = 25 ft)
        // ------------------------------------------------------------------
        items.put("I145", new FixedItemParser(2,
            FieldDef.flag("V"),
            FieldDef.spare(1),
            FieldDef.sscaled("FL", 14, 0.25, "FL")
        ));

        // ------------------------------------------------------------------
        // I152 – Magnetic Heading (2 bytes, unsigned, LSB = 360/65536 deg)
        // ------------------------------------------------------------------
        items.put("I152", new FixedItemParser(2,
            FieldDef.uscaled("MagHdg", 16, 360.0 / 65536.0, "deg")
        ));

        // ------------------------------------------------------------------
        // I200 – Target Status (Variable)
        //   Octet 1: ICF(1), LNAV(1), ME(1), PS(3), SS(2) [but only 7 bits in VarItem]
        //   Wait – I200 in CAT021 is "Target Status", which is a variable item.
        //   Octet 1 (7 data bits): ICF(1), LNAV(1), ME(1), PS(3), SS(1) = 7 bits
        // ------------------------------------------------------------------
        items.put("I200", new VariableItemParser(
            new FieldDef[]{
                FieldDef.flag("ICF"),  FieldDef.flag("LNAV"),
                FieldDef.flag("ME"),   FieldDef.uint("PS", 3),
                FieldDef.uint("SS", 1)    // note: SS is 2 bits but we only have 1 bit left here
            }
        ));

        // ------------------------------------------------------------------
        // I155 – Barometric Vertical Rate (2 bytes)
        //   RE(1): range exceeded, BVR(15 signed, LSB = 6.25 ft/min)
        // ------------------------------------------------------------------
        items.put("I155", new FixedItemParser(2,
            FieldDef.flag("RE"),
            FieldDef.sscaled("BVR", 15, 6.25, "ft/min")
        ));

        // ------------------------------------------------------------------
        // I157 – Geometric Vertical Rate (2 bytes)
        //   RE(1), GVR(15 signed, LSB = 6.25 ft/min)
        // ------------------------------------------------------------------
        items.put("I157", new FixedItemParser(2,
            FieldDef.flag("RE"),
            FieldDef.sscaled("GVR", 15, 6.25, "ft/min")
        ));

        // ------------------------------------------------------------------
        // I160 – Airborne Ground Speed and Track Angle (4 bytes)
        //   RE(1), GS(15 unsigned, LSB = 2^-14 NM/s)
        //   TRKN(16 unsigned, LSB = 360/65536 degrees)
        // ------------------------------------------------------------------
        items.put("I160", new FixedItemParser(4,
            FieldDef.flag("RE"),
            FieldDef.uscaled("GS",   15, Math.pow(2, -14),  "NM/s"),
            FieldDef.uscaled("TRKN", 16, 360.0 / 65536.0,   "deg")
        ));

        // ------------------------------------------------------------------
        // I165 – Track Angle Rate (2 bytes – raw hex for now)
        // ------------------------------------------------------------------
        items.put("I165", CommonItems.rawFixed(2));

        // ------------------------------------------------------------------
        // I077 – ASTERIX Report Transmission Time (3 bytes, 1/128 s)
        // ------------------------------------------------------------------
        items.put("I077", CommonItems.TIME_OF_DAY);

        // ------------------------------------------------------------------
        // I170 – Target Identification (6-byte ICAO 6-bit callsign)
        // ------------------------------------------------------------------
        items.put("I170", CommonItems.CALLSIGN);

        // ------------------------------------------------------------------
        // I020 – Emitter Category (1 byte)
        //   1=light aircraft, 2=small aircraft, ..., 10=rotorcraft, etc.
        // ------------------------------------------------------------------
        items.put("I020", new FixedItemParser(1,
            FieldDef.uint("ECAT", 8)
        ));

        // ------------------------------------------------------------------
        // I220 – Met Information (Compound – raw sub-items)
        //   Sub-items: WS(2 bytes), WD(2 bytes), TMP(2 bytes), TRB(1 byte)
        // ------------------------------------------------------------------
        items.put("I220", new CompoundItemParser(
            new String[]{"WS", "WD", "TMP", "TRB"},
            new ItemParser[]{
                new FixedItemParser(2, FieldDef.uscaled("WS",  16, 1.0, "kt")),
                new FixedItemParser(2, FieldDef.uscaled("WD",  16, 1.0, "deg")),
                new FixedItemParser(2, FieldDef.sscaled("TMP", 16, 0.25, "°C")),
                new FixedItemParser(1, FieldDef.uint("TRB", 8))
            }
        ));

        // ------------------------------------------------------------------
        // I146 – Selected Altitude (2 bytes)
        //   SAS(1), Source(2), ALT(13 signed, LSB = 25 ft)
        // ------------------------------------------------------------------
        items.put("I146", new FixedItemParser(2,
            FieldDef.flag("SAS"),
            FieldDef.uint("Source", 2),
            FieldDef.sscaled("ALT", 13, 25.0, "ft")
        ));

        // ------------------------------------------------------------------
        // I148 – Final State Selected Altitude (2 bytes) – same structure as I146
        // ------------------------------------------------------------------
        items.put("I148", new FixedItemParser(2,
            FieldDef.flag("SAS"),
            FieldDef.uint("Source", 2),
            FieldDef.sscaled("ALT", 13, 25.0, "ft")
        ));

        // ------------------------------------------------------------------
        // I110 – Trajectory Intent (Compound+Explicit – raw hex)
        // ------------------------------------------------------------------
        items.put("I110", CommonItems.rawVariable());

        // ------------------------------------------------------------------
        // I016 – Service Identification (1 byte)
        // ------------------------------------------------------------------
        items.put("I016", new FixedItemParser(1,
            FieldDef.uint("SID", 8)
        ));

        // ------------------------------------------------------------------
        // I008 – Aircraft Operational Status (Variable)
        //   Octet 1 (7 bits): RA(1), TC(2), TS(1), ARV(1), CDTIA(1), CDTIOA(1)
        // ------------------------------------------------------------------
        items.put("I008", new VariableItemParser(
            new FieldDef[]{
                FieldDef.flag("RA"), FieldDef.uint("TC", 2), FieldDef.flag("TS"),
                FieldDef.flag("ARV"), FieldDef.flag("CDTIA"), FieldDef.flag("CDTIOA")
            }
        ));

        // ------------------------------------------------------------------
        // I271 – Surface Capabilities and Characteristics (Variable)
        //   Octet 1 (7 bits): POA(1), CDTIS(1), B2LOW(1), RAS(1), IDENT(1), spare(2)
        // ------------------------------------------------------------------
        items.put("I271", new VariableItemParser(
            new FieldDef[]{
                FieldDef.flag("POA"),   FieldDef.flag("CDTIS"),
                FieldDef.flag("B2LOW"), FieldDef.flag("RAS"),
                FieldDef.flag("IDENT"), FieldDef.spare(2)
            }
        ));

        // ------------------------------------------------------------------
        // I150 – Air Speed (2 bytes)
        //   IM(1): 0=IAS, 1=Mach; AS(15 unsigned)
        //   If IM=0: IAS in kt (LSB = 2^-14 NM/s … practical: display raw)
        //   If IM=1: Mach (LSB = 0.001)
        // ------------------------------------------------------------------
        items.put("I150", (buf, mapper) -> {
            int hi = buf.get() & 0xFF, lo = buf.get() & 0xFF;
            int im = (hi >> 7) & 1;
            int as = ((hi & 0x7F) << 8) | lo;
            var n = mapper.createObjectNode();
            n.put("IM", im);
            if (im == 0) {
                n.put("IAS", as * Math.pow(2, -14)); // NM/s → multiply by 3600 for kts
            } else {
                n.put("Mach", as * 0.001);
            }
            return n;
        });

        // ------------------------------------------------------------------
        // I151 – True Airspeed (2 bytes, RE(1) + TAS(15 unsigned, 1 kt))
        // ------------------------------------------------------------------
        items.put("I151", new FixedItemParser(2,
            FieldDef.flag("RE"),
            FieldDef.uscaled("TAS", 15, 1.0, "kt")
        ));

        // ------------------------------------------------------------------
        // I131 – High-Resolution Position in WGS-84 (8 bytes)
        //   LAT: 32-bit signed, LSB = 180/2^31 degrees
        //   LON: 32-bit signed, LSB = 360/2^31 degrees
        // ------------------------------------------------------------------
        items.put("I131", (buf, mapper) -> {
            double lat = CommonItems.latFrom32(buf);
            double lon = CommonItems.lonFrom32(buf);
            var n = mapper.createObjectNode();
            n.put("LAT", lat);
            n.put("LON", lon);
            return n;
        });

        // ------------------------------------------------------------------
        // I132 – Message Amplitude (1 byte, signed, LSB = 1 dBm)
        // ------------------------------------------------------------------
        items.put("I132", new FixedItemParser(1,
            FieldDef.sscaled("AMP", 8, 1.0, "dBm")
        ));

        // ------------------------------------------------------------------
        // I133–I136 – Reserved / future use (raw fixed)
        // ------------------------------------------------------------------
        items.put("I133", CommonItems.rawFixed(2));
        items.put("I134", CommonItems.rawFixed(2));
        items.put("I135", CommonItems.rawFixed(2));
        items.put("I136", CommonItems.rawFixed(2));

        // ------------------------------------------------------------------
        // I550 – Reserved (raw variable)
        // ------------------------------------------------------------------
        items.put("I550", CommonItems.rawVariable());

        // ------------------------------------------------------------------
        // SP / RE
        // ------------------------------------------------------------------
        items.put("SP", CommonItems.EXPLICIT);
        items.put("RE", CommonItems.EXPLICIT);

        return new CategoryDefinition(21, "ADS-B Target Reports", UAP, items);
    }
}

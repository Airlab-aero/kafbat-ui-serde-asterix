package io.airlab.kafbat.serde.asterix.category;

import io.airlab.kafbat.serde.asterix.parser.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ASTERIX Category 010 – Monosensor Surface Movement Data (Edition 1.1).
 *
 * <p>Used for airport surface movement surveillance (SMR / MLAT).
 *
 * <p>Reference: EUROCONTROL-SPEC-0010 Edition 1.1, 2007-03-01.
 */
final class Cat010 {

    private Cat010() {}

    // -----------------------------------------------------------------------
    // UAP
    // -----------------------------------------------------------------------
    private static final String[] UAP = {
        // Octet 1
        "I010", "I000", "I020", "I140", "I041", "I040", "I042",
        // Octet 2
        "I200", "I202", "I161", "I170", "I060", "I220", "I245",
        // Octet 3
        "I250", "I300", "I090", "I091", "I270", "I550", "I310",
        // Octet 4
        "I500", "I280", "I131", "I210", "SP",   "RE",   null
    };

    // -----------------------------------------------------------------------
    // Item parsers
    // -----------------------------------------------------------------------

    static CategoryDefinition definition() {
        Map<String, ItemParser> items = new LinkedHashMap<>();

        // I010 – Data Source Identifier
        items.put("I010", CommonItems.DSI);

        // I000 – Message Type (1 byte)
        //   1=Target Report, 2=Start of Update Cycle,
        //   3=Periodic Status, 4=Event-triggered Status
        items.put("I000", new FixedItemParser(1,
            FieldDef.uint("MT", 8)
        ));

        // I020 – Target Report Descriptor (Variable, up to 3 octets)
        //   Octet 1: TYP(3), DCR(1), CHN(1), GBS(1), CRT(1)
        //   Octet 2: SIM(1), TST(1), RAB(1), LOP(1), TOT(2), spare(1)
        //   Octet 3: SPI(1), spare(6)
        items.put("I020", new VariableItemParser(
            new FieldDef[]{
                FieldDef.uint("TYP", 3), FieldDef.flag("DCR"),
                FieldDef.flag("CHN"),    FieldDef.flag("GBS"), FieldDef.flag("CRT")
            },
            new FieldDef[]{
                FieldDef.flag("SIM"),    FieldDef.flag("TST"),
                FieldDef.flag("RAB"),    FieldDef.flag("LOP"),
                FieldDef.uint("TOT", 2), FieldDef.spare(1)
            },
            new FieldDef[]{
                FieldDef.flag("SPI"),    FieldDef.spare(6)
            }
        ));

        // I140 – Time of Day (3 bytes, 1/128 s)
        items.put("I140", CommonItems.TIME_OF_DAY);

        // I041 – Position in WGS-84 Co-ordinates (8 bytes)
        //   LAT: 32-bit signed, LSB = 180/2^31 degrees
        //   LON: 32-bit signed, LSB = 360/2^31 degrees
        items.put("I041", (buf, mapper) -> {
            double lat = CommonItems.latFrom32(buf);
            double lon = CommonItems.lonFrom32(buf);
            var n = mapper.createObjectNode();
            n.put("LAT", lat);
            n.put("LON", lon);
            return n;
        });

        // I040 – Measured Position in Slant Polar Coordinates (4 bytes)
        items.put("I040", new FixedItemParser(4,
            FieldDef.uscaled("RHO",   16, 1.0 / 256.0,     "NM"),
            FieldDef.uscaled("THETA", 16, 360.0 / 65536.0, "deg")
        ));

        // I042 – Calculated Position in Cartesian Coordinates (4 bytes)
        //   X, Y: signed, LSB = 1 m  (CAT010 uses 1 m, unlike CAT048 which uses 1/128 NM)
        items.put("I042", new FixedItemParser(4,
            FieldDef.sscaled("X", 16, 1.0, "m"),
            FieldDef.sscaled("Y", 16, 1.0, "m")
        ));

        // I200 – Calculated Track Velocity in Polar Representation (4 bytes)
        //   GSPD: unsigned, LSB = 1 kt / 1000;  HDNG: unsigned, LSB = 360/65536 deg
        items.put("I200", new FixedItemParser(4,
            FieldDef.uscaled("GSPD", 16, 1.0 / 1000.0,    "kt"),
            FieldDef.uscaled("HDNG", 16, 360.0 / 65536.0, "deg")
        ));

        // I202 – Calculated Track Velocity in Cartesian Coordinates (4 bytes)
        //   Vx, Vy: signed, LSB = 0.25 m/s
        items.put("I202", new FixedItemParser(4,
            FieldDef.sscaled("Vx", 16, 0.25, "m/s"),
            FieldDef.sscaled("Vy", 16, 0.25, "m/s")
        ));

        // I161 – Track Number (2 bytes): spare(3) + TRK(13)
        items.put("I161", new FixedItemParser(2,
            FieldDef.spare(3),
            FieldDef.uint("TRK", 13)
        ));

        // I170 – Track Status (Variable)
        //   Octet 1: CNF(1), TRE(1), CST(1), MAH(1), TCC(1), STH(1), spare(1)
        //   Octet 2: TOM(2), DOU(1), MRS(2), spare(2)
        items.put("I170", new VariableItemParser(
            new FieldDef[]{
                FieldDef.flag("CNF"), FieldDef.flag("TRE"), FieldDef.flag("CST"),
                FieldDef.flag("MAH"), FieldDef.flag("TCC"), FieldDef.flag("STH"),
                FieldDef.spare(1)
            },
            new FieldDef[]{
                FieldDef.uint("TOM", 2), FieldDef.flag("DOU"),
                FieldDef.uint("MRS", 2), FieldDef.spare(2)
            }
        ));

        // I060 – Mode-3/A Code (2 bytes) – same as CAT048
        items.put("I060", CommonItems.MODE3A);

        // I220 – Target Address (Mode S, 3 bytes)
        items.put("I220", CommonItems.MODES_ADDRESS);

        // I245 – Target Identification (6-byte ICAO callsign)
        //   Note: CAT010 I245 has STI(2) spare(6) before the 6-char ID,
        //   making it 7 bytes total.
        //   Format: STI(2), spare(6), Callsign-6bit(48) = 7 bytes
        items.put("I245", (buf, mapper) -> {
            int b0 = buf.get() & 0xFF;
            int sti = (b0 >> 6) & 0x03;
            long raw = 0;
            for (int i = 0; i < 6; i++) raw = (raw << 8) | (buf.get() & 0xFF);
            // Decode 6-bit ICAO chars via CommonItems.CALLSIGN logic
            char[] icao6 = {' ','A','B','C','D','E','F','G','H','I','J','K','L','M',
                             'N','O','P','Q','R','S','T','U','V','W','X','Y','Z',' ',
                             ' ',' ',' ',' ','0','1','2','3','4','5','6','7','8','9',
                             ' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',
                             ' ',' ',' ',' ',' ',' ',' ',' ',' ',' '};
            StringBuilder id = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                int c6 = (int)((raw >>> ((7 - i) * 6)) & 0x3F);
                id.append(c6 < icao6.length ? icao6[c6] : '?');
            }
            var n = mapper.createObjectNode();
            n.put("STI", sti);
            n.put("TId", id.toString().stripTrailing());
            return n;
        });

        // I250 – Mode S MB Data (Repetitive, 8 bytes per record)
        //   MB(56 bits) + BDS1(4) + BDS2(4)
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

        // I300 – Vehicle Fleet Identification (1 byte)
        items.put("I300", new FixedItemParser(1, FieldDef.uint("VFI", 8)));

        // I090 – Measured Height (2 bytes signed, LSB = 25 ft = 1/4 FL)
        items.put("I090", new FixedItemParser(2,
            FieldDef.sscaled("FL", 16, 25.0, "ft")
        ));

        // I091 – Measured Height (Barometric, 2 bytes signed, LSB = 25 ft)
        items.put("I091", new FixedItemParser(2,
            FieldDef.sscaled("FLBARO", 16, 25.0, "ft")
        ));

        // I270 – Target Size and Orientation (Variable – raw hex)
        items.put("I270", CommonItems.rawVariable());

        // I550 – System Status (Fixed 2 bytes – raw hex)
        items.put("I550", CommonItems.rawFixed(2));

        // I310 – Pre-programmed Message (1 byte)
        //   TRB(1), MSG(7)
        items.put("I310", new FixedItemParser(1,
            FieldDef.flag("TRB"),
            FieldDef.uint("MSG", 7)
        ));

        // I500 – Standard Deviation of Position (4 bytes)
        //   devX(16, LSB=1m, unsigned), devY(16, LSB=1m, unsigned)
        items.put("I500", new FixedItemParser(4,
            FieldDef.uscaled("devX", 16, 1.0, "m"),
            FieldDef.uscaled("devY", 16, 1.0, "m")
        ));

        // I280 – Presence (Variable – raw hex)
        items.put("I280", CommonItems.rawVariable());

        // I131 – Amplitude of Primary Plot (1 byte signed, LSB = 1 dBm)
        items.put("I131", new FixedItemParser(1,
            FieldDef.sscaled("AMP", 8, 1.0, "dBm")
        ));

        // I210 – Calculated Acceleration (2 bytes)
        //   Ax(8, signed, LSB=0.25 m/s²), Ay(8, signed, LSB=0.25 m/s²)
        items.put("I210", new FixedItemParser(2,
            FieldDef.sscaled("Ax", 8, 0.25, "m/s²"),
            FieldDef.sscaled("Ay", 8, 0.25, "m/s²")
        ));

        // SP / RE
        items.put("SP", CommonItems.EXPLICIT);
        items.put("RE", CommonItems.EXPLICIT);

        return new CategoryDefinition(10, "Monosensor Surface Movement Data", UAP, items);
    }
}

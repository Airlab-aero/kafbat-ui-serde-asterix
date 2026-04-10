package io.airlab.kafbat.serde.asterix.category;

import io.airlab.kafbat.serde.asterix.parser.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ASTERIX Category 002 – Monoradar Service Messages (Edition 1.2).
 *
 * <p>These messages carry radar service information such as north-marker,
 * sector-crossing, time-of-day, and status reports.
 *
 * <p>Reference: EUROCONTROL-SPEC-0149-2 Edition 1.2, 2024-03-15.
 */
final class Cat002 {

    private Cat002() {}

    // -----------------------------------------------------------------------
    // UAP – 7 item slots per FSPEC octet
    // -----------------------------------------------------------------------
    private static final String[] UAP = {
        // FSPEC octet 1
        "I010", "I000", "I020", "I030", "I041", "I050", "I060",
        // FSPEC octet 2
        "I070", "I100", "I090", "I080", "SP",   "RE",   null
    };

    // -----------------------------------------------------------------------
    // Item parsers
    // -----------------------------------------------------------------------

    static CategoryDefinition definition() {
        Map<String, io.airlab.kafbat.serde.asterix.parser.ItemParser> items = new LinkedHashMap<>();

        // I010 – Data Source Identifier (2 bytes)
        items.put("I010", CommonItems.DSI);

        // I000 – Message Type (1 byte)
        //   1=North marker, 2=Sector crossing, 3=South marker (non-standard),
        //   8=Activate BZ filtering, 9=Stop BZ filtering
        items.put("I000", new FixedItemParser(1,
            FieldDef.uint("MT", 8)
        ));

        // I020 – Sector Number (1 byte): azimuth of current sector
        //   LSB = 360/256 = 1.40625 degrees
        items.put("I020", new FixedItemParser(1,
            FieldDef.uscaled("SN", 8, 360.0 / 256.0, "deg")
        ));

        // I030 – Time of Day (3 bytes, UTC since midnight)
        items.put("I030", CommonItems.TIME_OF_DAY);

        // I041 – Antenna Rotation Speed (2 bytes)
        //   Period of antenna revolution, LSB = 1/128 s
        items.put("I041", new FixedItemParser(2,
            FieldDef.uscaled("ROT", 16, 1.0 / 128.0, "s")
        ));

        // I050 – Station Configuration Status (Variable, up to 2 octets)
        //   Octet 1 (7 bits): NOGO(1), OVL(1), TSV(1), ANT(1), TXW(3)
        //   Octet 2 (7 bits): IFF1(1), IFF2(1), MSS(1), SCF(1), DLF(1), OVL2(1), OVL3(1)
        items.put("I050", new VariableItemParser(
            new FieldDef[]{
                FieldDef.flag("NOGO"), FieldDef.flag("OVL"), FieldDef.flag("TSV"),
                FieldDef.flag("ANT"),  FieldDef.uint("TXW", 3)
            },
            new FieldDef[]{
                FieldDef.flag("IFF1"), FieldDef.flag("IFF2"), FieldDef.flag("MSS"),
                FieldDef.flag("SCF"),  FieldDef.flag("DLF"),  FieldDef.flag("OVL2"),
                FieldDef.flag("OVL3")
            }
        ));

        // I060 – Station Processing Mode (Variable, up to 2 octets)
        //   Octet 1 (7 bits): spare(1), RED(3), SRB(2), SBT(1)
        //   Octet 2 (7 bits): PRF(1), PRFCAB(3), STAG(3)
        items.put("I060", new VariableItemParser(
            new FieldDef[]{
                FieldDef.spare(1),    FieldDef.uint("RED", 3),
                FieldDef.uint("SRB", 2), FieldDef.flag("SBT")
            },
            new FieldDef[]{
                FieldDef.flag("PRF"), FieldDef.uint("PRFCAB", 3), FieldDef.uint("STAG", 3)
            }
        ));

        // I070 – Plot Count Values (Repetitive, 2 bytes per record)
        //   REP + n × [A(1), IDENT(5), COUNT(10)]
        items.put("I070", new RepetitiveItemParser(2,
            FieldDef.flag("A"),
            FieldDef.uint("IDENT", 5),
            FieldDef.uint("COUNT", 10)
        ));

        // I100 – Dynamic Window – Type 1 (Fixed, 8 bytes)
        //   Polar window: RhoStart, RhoEnd, ThetaStart, ThetaEnd
        items.put("I100", new FixedItemParser(8,
            FieldDef.uscaled("RhoStart",   16, 1.0 / 128.0,        "NM"),
            FieldDef.uscaled("RhoEnd",     16, 1.0 / 128.0,        "NM"),
            FieldDef.uscaled("ThetaStart", 16, 360.0 / 65536.0,    "deg"),
            FieldDef.uscaled("ThetaEnd",   16, 360.0 / 65536.0,    "deg")
        ));

        // I090 – Collimation Error (Fixed, 2 bytes)
        //   RE (8 signed, LSB=1/128 NM), AE (8 signed, LSB=360/2^14 deg)
        items.put("I090", new FixedItemParser(2,
            FieldDef.sscaled("RE", 8, 1.0 / 128.0,        "NM"),
            FieldDef.sscaled("AE", 8, 360.0 / 16384.0,    "deg")
        ));

        // I080 – Warning/Error Conditions (Variable – raw hex)
        items.put("I080", CommonItems.rawVariable());

        // SP / RE – Special Purpose / Reserved Expansion
        items.put("SP", CommonItems.EXPLICIT);
        items.put("RE", CommonItems.EXPLICIT);

        return new CategoryDefinition(2, "Monoradar Service Messages", UAP, items);
    }
}

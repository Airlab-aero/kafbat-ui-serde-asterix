package io.airlab.kafbat.serde.asterix.category;

import io.airlab.kafbat.serde.asterix.parser.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ASTERIX Category 034 – Monoradar Service Messages (Edition 1.29).
 *
 * <p>Similar to CAT002 but used for more complex radar installations.
 * Carries north-marker, sector-crossing, and system configuration data.
 *
 * <p>Reference: EUROCONTROL-SPEC-0034 Edition 1.29, 2024-07-01.
 */
final class Cat034 {

    private Cat034() {}

    // -----------------------------------------------------------------------
    // UAP
    // -----------------------------------------------------------------------
    private static final String[] UAP = {
        // Octet 1
        "I010", "I000", "I030", "I020", "I041", "I050", "I060",
        // Octet 2
        null,   null,   null,   null,   null,   "SP",   "RE"
    };

    // -----------------------------------------------------------------------
    // Item parsers
    // -----------------------------------------------------------------------

    static CategoryDefinition definition() {
        Map<String, ItemParser> items = new LinkedHashMap<>();

        // I010 – Data Source Identifier (2 bytes)
        items.put("I010", CommonItems.DSI);

        // I000 – Message Type (1 byte)
        //   1=North marker, 2=Sector crossing, 3=Geographical filtering,
        //   4=Jamming strobe
        items.put("I000", new FixedItemParser(1,
            FieldDef.uint("MT", 8)
        ));

        // I030 – Time of Day (3 bytes, 1/128 s)
        items.put("I030", CommonItems.TIME_OF_DAY);

        // I020 – Sector Number (1 byte, LSB = 360/256 degrees)
        items.put("I020", new FixedItemParser(1,
            FieldDef.uscaled("SN", 8, 360.0 / 256.0, "deg")
        ));

        // I041 – Antenna Rotation Period (2 bytes, LSB = 1/128 s)
        items.put("I041", new FixedItemParser(2,
            FieldDef.uscaled("ROT", 16, 1.0 / 128.0, "s")
        ));

        // I050 – System Configuration and Status (Compound)
        //   Sub-items: COM (1 byte), PSR (2 bytes), SSR (2 bytes), MDS (4 bytes)
        //   We return raw hex per sub-item for simplicity.
        items.put("I050", new CompoundItemParser(
            new String[]{"COM", "spare2", "PSR", "SSR", "MDS"},
            new ItemParser[]{
                CommonItems.rawFixed(1),   // COM
                CommonItems.rawFixed(1),   // position 2 (spare in some editions)
                CommonItems.rawFixed(2),   // PSR
                CommonItems.rawFixed(2),   // SSR
                CommonItems.rawFixed(4)    // MDS
            }
        ));

        // I060 – System Processing Mode (Compound)
        //   Sub-items: COM (1 byte), PSR (1 byte), SSR (1 byte), MDS (2 bytes)
        items.put("I060", new CompoundItemParser(
            new String[]{"COM", "spare2", "PSR", "SSR", "MDS"},
            new ItemParser[]{
                CommonItems.rawFixed(1),
                CommonItems.rawFixed(1),
                CommonItems.rawFixed(1),
                CommonItems.rawFixed(1),
                CommonItems.rawFixed(2)
            }
        ));

        // SP / RE
        items.put("SP", CommonItems.EXPLICIT);
        items.put("RE", CommonItems.EXPLICIT);

        return new CategoryDefinition(34, "Monoradar Service Messages", UAP, items);
    }
}

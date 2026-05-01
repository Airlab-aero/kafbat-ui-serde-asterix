package io.airlab.kafbat.serde.asterix.category;

import io.airlab.kafbat.serde.asterix.parser.ItemParser;

import java.util.Map;

/**
 * Immutable descriptor for one ASTERIX category.
 *
 * <p>The {@link #uap} array lists item IDs in User Application Profile order.
 * Each consecutive group of 7 positions corresponds to one FSPEC octet
 * (the 8th bit of each octet is the FX continuation bit, handled by the parser).
 * {@code null} entries represent reserved/unassigned FSPEC positions.
 *
 * <p>The {@link #items} map provides an {@link ItemParser} for every item ID
 * that appears in {@code uap}.  Items that cannot be decoded should still have
 * an entry (e.g. an {@link io.airlab.kafbat.serde.asterix.parser.ExplicitItemParser}
 * or a raw-hex lambda) so that the record parser can advance the buffer past them.
 */
public final class CategoryDefinition {

    /** ASTERIX category number (1–255). */
    public final int category;

    /** Human-readable name, e.g. {@code "Monoradar Target Reports"}. */
    public final String name;

    /**
     * User Application Profile: ordered item IDs.
     * 7 entries per FSPEC octet; {@code null} = reserved position.
     */
    public final String[] uap;

    /** Item parsers keyed by item ID (e.g. {@code "I010"}, {@code "SP"}). */
    public final Map<String, ItemParser> items;

    public CategoryDefinition(int category, String name,
                               String[] uap, Map<String, ItemParser> items) {
        this.category = category;
        this.name     = name;
        this.uap      = uap;
        this.items    = Map.copyOf(items);
    }
}

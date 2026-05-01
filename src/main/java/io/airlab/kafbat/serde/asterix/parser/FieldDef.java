package io.airlab.kafbat.serde.asterix.parser;

/**
 * Describes a single named bit-field inside an ASTERIX fixed or variable data item.
 *
 * <p>Bits are extracted MSB-first from the item's byte stream. The physical value is:
 * <pre>  physical = raw_integer * lsb</pre>
 * For integer (unscaled) fields set {@code lsb = 1.0}.
 */
public final class FieldDef {

    /** Field name used as JSON key. Use "spare" for padding bits. */
    public final String name;
    /** Number of bits occupied by this field. */
    public final int bits;
    /** Whether the raw value is a signed two's-complement integer. */
    public final boolean signed;
    /** Scaling factor: physical_value = raw * lsb. 1.0 means no scaling. */
    public final double lsb;
    /** Physical unit label (e.g. "NM", "deg", "s", "ft", "kt"). Empty = dimensionless. */
    public final String unit;
    /** If true the field is padding and will be omitted from the JSON output. */
    public final boolean spare;

    private FieldDef(String name, int bits, boolean signed,
                     double lsb, String unit, boolean spare) {
        if (bits < 1 || bits > 63) throw new IllegalArgumentException("bits must be 1-63, got: " + bits);
        this.name   = name;
        this.bits   = bits;
        this.signed = signed;
        this.lsb    = lsb;
        this.unit   = unit;
        this.spare  = spare;
    }

    // -----------------------------------------------------------------------
    // Factory helpers
    // -----------------------------------------------------------------------

    /** Raw unsigned integer. */
    public static FieldDef uint(String name, int bits) {
        return new FieldDef(name, bits, false, 1.0, "", false);
    }

    /** Raw signed two's-complement integer. */
    public static FieldDef sint(String name, int bits) {
        return new FieldDef(name, bits, true, 1.0, "", false);
    }

    /** Scaled unsigned value: physical = raw * lsb. */
    public static FieldDef uscaled(String name, int bits, double lsb, String unit) {
        return new FieldDef(name, bits, false, lsb, unit, false);
    }

    /** Scaled signed two's-complement value: physical = raw * lsb. */
    public static FieldDef sscaled(String name, int bits, double lsb, String unit) {
        return new FieldDef(name, bits, true, lsb, unit, false);
    }

    /** Single flag bit. Stored as 0 or 1 in JSON. */
    public static FieldDef flag(String name) {
        return new FieldDef(name, 1, false, 1.0, "", false);
    }

    /** Spare / padding bits – omitted from JSON output. */
    public static FieldDef spare(int bits) {
        return new FieldDef("spare", bits, false, 1.0, "", true);
    }

    @Override
    public String toString() {
        return "FieldDef{" + name + "," + bits + "b" + (signed ? ",signed" : "") + "}";
    }
}

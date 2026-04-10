package io.airlab.kafbat.serde.asterix.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for ASTERIX <em>Compound</em> data items.
 *
 * <p>A compound item begins with a Primary Sub-Field (PSF) that works exactly
 * like an ASTERIX FSPEC: each octet has 7 presence bits (MSB first) plus an
 * FX bit (LSB).  FX = 1 means another PSF octet follows.
 *
 * <p>Sub-items follow in the order dictated by the PSF bit positions.
 * {@code subNames[i]} and {@code subParsers[i]} correspond to PSF position
 * {@code i} (0-based, MSB of first PSF octet = position 0).
 *
 * <p><b>Limitation:</b> if the PSF indicates a sub-item position beyond the
 * provided arrays (an unknown extension), parsing stops and the partial result
 * is returned, because the sub-item length is unknown.
 */
public class CompoundItemParser implements ItemParser {

    private final String[]     subNames;
    private final ItemParser[] subParsers;

    public CompoundItemParser(String[] subNames, ItemParser[] subParsers) {
        if (subNames.length != subParsers.length) {
            throw new IllegalArgumentException(
                "subNames and subParsers must have the same length");
        }
        this.subNames   = subNames;
        this.subParsers = subParsers;
    }

    @Override
    public ObjectNode parse(ByteBuffer buffer, ObjectMapper mapper) {
        // Read PSF (mirrors FSPEC structure)
        List<Integer> psf = new ArrayList<>();
        int b;
        do {
            b = buffer.get() & 0xFF;
            psf.add(b);
        } while ((b & 0x01) == 1);

        ObjectNode result = mapper.createObjectNode();
        int uapIdx = 0;

        outer:
        for (int psfByte : psf) {
            for (int bit = 7; bit >= 1; bit--) {
                boolean present = (psfByte & (1 << bit)) != 0;
                if (present) {
                    if (uapIdx >= subParsers.length) {
                        // Unknown sub-item – cannot determine its length; stop here.
                        break outer;
                    }
                    ObjectNode sub = subParsers[uapIdx].parse(buffer, mapper);
                    result.set(subNames[uapIdx], sub);
                }
                uapIdx++;
            }
        }
        return result;
    }
}

package io.airlab.kafbat.serde.asterix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.airlab.kafbat.serde.asterix.category.CategoryDefinition;
import io.airlab.kafbat.serde.asterix.category.CategoryRegistry;
import io.airlab.kafbat.serde.asterix.parser.AsterixParser;
import io.airlab.kafbat.serde.asterix.spec.SpecLoader;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for multi-edition support: loading, registry lookup, and parser
 * edition-override behaviour.
 *
 * <p>CAT002 (3 editions: 1.0, 1.1, 1.2) and CAT048 (6 editions: 1.27–1.32)
 * are used as representative examples because their edition files are all
 * bundled in the JAR.
 */
class MultiEditionTest {

    // ── CategoryRegistry multi-edition API ────────────────────────────────

    @Test
    void registry_storesMultipleEditionsPerCategory() throws Exception {
        CategoryDefinition ed10 = SpecLoader.loadSpec("asterix-specs/cat002/1.0.json");
        CategoryDefinition ed11 = SpecLoader.loadSpec("asterix-specs/cat002/1.1.json");
        CategoryDefinition ed12 = SpecLoader.loadSpec("asterix-specs/cat002/1.2.json");
        assertThat(ed10).isNotNull();
        assertThat(ed11).isNotNull();
        assertThat(ed12).isNotNull();

        CategoryRegistry registry = new CategoryRegistry();
        registry.register(ed10, false);
        registry.register(ed11, false);
        registry.register(ed12, true);   // mark 1.2 as latest

        assertThat(registry.editions(2)).containsExactlyInAnyOrder("1.0", "1.1", "1.2");
        assertThat(registry.get(2).edition).isEqualTo("1.2");             // latest
        assertThat(registry.get(2, "1.0").edition).isEqualTo("1.0");
        assertThat(registry.get(2, "1.1").edition).isEqualTo("1.1");
        assertThat(registry.get(2, "1.2").edition).isEqualTo("1.2");
        assertThat(registry.get(2, "9.9")).isNull();                       // unknown edition
    }

    @Test
    void registry_withBuiltins_containsAllManifestEditions() {
        CategoryRegistry registry = CategoryRegistry.withBuiltins();

        // CAT048 has 6 editions in the manifest
        Set<String> cat48Editions = registry.editions(48);
        assertThat(cat48Editions)
            .as("all CAT048 editions must be registered")
            .containsAll(Set.of("1.27", "1.28", "1.29", "1.30", "1.31", "1.32"));

        // CAT002 has 3 editions
        Set<String> cat2Editions = registry.editions(2);
        assertThat(cat2Editions)
            .as("all CAT002 editions must be registered")
            .containsAll(Set.of("1.0", "1.1", "1.2"));

        // Latest for CAT048 must be 1.32
        assertThat(registry.get(48).edition).isEqualTo("1.32");
        assertThat(registry.get(2).edition).isEqualTo("1.2");
    }

    // ── Edition field in CategoryDefinition ───────────────────────────────

    @Test
    void loadSpec_setsEditionField() throws Exception {
        CategoryDefinition def = SpecLoader.loadSpec("asterix-specs/cat048/1.29.json");
        assertThat(def).isNotNull();
        assertThat(def.edition).isEqualTo("1.29");
        assertThat(def.category).isEqualTo(48);
    }

    @Test
    void builtinDefinition_hasEditionBuiltin() {
        // withBuiltins() falls back to hand-written definitions for uncovered cats.
        // Verify that if specs are absent, the fallback definitions carry "builtin" edition.
        CategoryRegistry registry = new CategoryRegistry();
        registry.register(new CategoryDefinition(99, "Test", new String[0], Map.of()));
        assertThat(registry.get(99).edition).isEqualTo("builtin");
    }

    // ── AsterixParser edition overrides ───────────────────────────────────

    @Test
    void parser_withoutOverride_usesLatestEdition() {
        CategoryRegistry registry = CategoryRegistry.withBuiltins();
        AsterixParser parser = new AsterixParser(registry);

        // CAT002 north marker, FSPEC 0xC0 → I010 + I000
        byte[] msg = { 0x02, 0x00, 0x07, (byte) 0xC0, 0x00, 0x01, 0x01 };
        ArrayNode result = parser.parse(msg);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("edition").asText()).isEqualTo("1.2");  // latest
    }

    @Test
    void parser_withEditionOverride_usesSpecifiedEdition() {
        CategoryRegistry registry = CategoryRegistry.withBuiltins();
        AsterixParser parser = new AsterixParser(registry, Map.of(2, "1.0"));

        byte[] msg = { 0x02, 0x00, 0x07, (byte) 0xC0, 0x00, 0x01, 0x01 };
        ArrayNode result = parser.parse(msg);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("edition").asText())
            .as("parser should use the overridden edition 1.0 for CAT002")
            .isEqualTo("1.0");
    }

    @Test
    void parser_editionOverride_onlyAffectsSpecifiedCategory() {
        CategoryRegistry registry = CategoryRegistry.withBuiltins();
        // Override CAT002 to ed 1.0, leave CAT048 at latest (1.32)
        AsterixParser parser = new AsterixParser(registry, Map.of(2, "1.0"));

        // Two-block message: CAT002 then CAT048
        byte[] cat2  = { 0x02, 0x00, 0x07, (byte) 0xC0, 0x00, 0x01, 0x01 };
        byte[] cat48 = { 0x30, 0x00, 0x0D, (byte) 0xD0,
                         0x00, 0x01, 0x46, 0x50, 0x00, 0x64, 0x00, 0x40, 0x00 };
        byte[] combined = new byte[cat2.length + cat48.length];
        System.arraycopy(cat2,  0, combined, 0,           cat2.length);
        System.arraycopy(cat48, 0, combined, cat2.length, cat48.length);

        ArrayNode result = parser.parse(combined);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("edition").asText()).isEqualTo("1.0");   // overridden
        assertThat(result.get(1).get("edition").asText()).isEqualTo("1.32");  // default latest
    }

    @Test
    void parser_unknownEditionOverride_fallsBackToUnknownCategory() {
        CategoryRegistry registry = CategoryRegistry.withBuiltins();
        AsterixParser parser = new AsterixParser(registry, Map.of(2, "9.9"));

        byte[] msg = { 0x02, 0x00, 0x07, (byte) 0xC0, 0x00, 0x01, 0x01 };
        ArrayNode result = parser.parse(msg);

        // Edition 9.9 does not exist → catDef == null → treated as unknown category
        assertThat(result).hasSize(1);
        JsonNode block = result.get(0);
        assertThat(block.get("unknown").asBoolean()).isTrue();
        assertThat(block.has("hex")).isTrue();
    }

    // ── SpecLoader loads all editions ──────────────────────────────────────

    @Test
    void specLoader_loadAll_registersAllEditions() {
        CategoryRegistry registry = SpecLoader.loadAll();

        // CAT048: 6 editions expected
        assertThat(registry.editions(48)).hasSize(6);
        // CAT002: 3 editions expected
        assertThat(registry.editions(2)).hasSize(3);
        // Single-edition categories still work
        assertThat(registry.editions(7)).hasSize(1);
    }

    @Test
    void specLoader_loadAll_latestEditionIsCorrect() {
        CategoryRegistry registry = SpecLoader.loadAll();

        assertThat(registry.get(48).edition).isEqualTo("1.32");
        assertThat(registry.get(2).edition).isEqualTo("1.2");
        assertThat(registry.get(21).edition).isEqualTo("2.7");
    }
}

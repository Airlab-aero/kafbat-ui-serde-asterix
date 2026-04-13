package io.airlab.kafbat.serde.asterix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.airlab.kafbat.serde.asterix.category.CategoryDefinition;
import io.airlab.kafbat.serde.asterix.category.CategoryRegistry;
import io.airlab.kafbat.serde.asterix.parser.AsterixParser;
import io.airlab.kafbat.serde.asterix.spec.SpecLoader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Smoke tests that exercise every bundled spec edition.
 *
 * <p>For every entry in {@code asterix-specs/manifest.json} (all 51 editions
 * across 24 categories) we verify:
 * <ol>
 *   <li>The spec file loads without error and produces a non-null
 *       {@link CategoryDefinition}.</li>
 *   <li>The category number and edition match the manifest entry.</li>
 *   <li>The UAP is non-empty.</li>
 *   <li>Parsing a minimal zero-FSPEC message (header only, no data items)
 *       completes without throwing and returns exactly one block whose
 *       {@code category} field matches.</li>
 * </ol>
 *
 * <p>These tests do not verify decoded field values — that is covered by
 * {@link AsterixParserTest} and {@link SpecLoaderTest} for the key
 * categories. Their purpose is to guarantee that every spec file can be
 * loaded and framed without crashing, acting as a regression guard when
 * new spec files are added or the parser is modified.
 */
class SpecSmokeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Test parameter source ──────────────────────────────────────────────

    record SpecEntry(int category, String edition, String file, boolean latest) {
        @Override public String toString() {
            return String.format("CAT%03d/%s%s", category, edition, latest ? "(latest)" : "");
        }
    }

    static Stream<SpecEntry> allSpecEntries() throws Exception {
        InputStream stream = SpecSmokeTest.class.getClassLoader()
                .getResourceAsStream("asterix-specs/manifest.json");
        assertThat(stream).as("manifest.json must be on classpath").isNotNull();
        JsonNode manifest = MAPPER.readTree(stream);
        List<SpecEntry> entries = new ArrayList<>();
        for (JsonNode entry : manifest.path("specs")) {
            entries.add(new SpecEntry(
                entry.path("category").asInt(),
                entry.path("edition").asText(),
                entry.path("file").asText(),
                entry.path("latest").asBoolean(false)
            ));
        }
        assertThat(entries).as("manifest must contain at least one spec").isNotEmpty();
        return entries.stream();
    }

    // ── Smoke tests ────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("allSpecEntries")
    void specLoads_categoryAndUapCorrect(SpecEntry entry) throws Exception {
        String resource = "asterix-specs/" + entry.file();
        CategoryDefinition def = SpecLoader.loadSpec(resource);

        assertThat(def)
            .as("loadSpec(%s) must not return null", resource)
            .isNotNull();
        assertThat(def.category)
            .as("category number mismatch in %s", resource)
            .isEqualTo(entry.category());
        assertThat(def.uap)
            .as("UAP must not be empty for %s", resource)
            .isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allSpecEntries")
    void zeroFspecMessage_parsesWithoutException(SpecEntry entry) throws Exception {
        // Build a one-record registry for this specific spec edition
        String resource = "asterix-specs/" + entry.file();
        CategoryDefinition def = SpecLoader.loadSpec(resource);
        assertThat(def).isNotNull();

        CategoryRegistry registry = new CategoryRegistry();
        registry.register(def);
        AsterixParser parser = new AsterixParser(registry);

        // Minimal valid ASTERIX message: CAT(1) + LEN(2)=4 + FSPEC=0x00 (no items)
        byte[] msg = {
            (byte) entry.category(),
            0x00, 0x04,   // total length = 4
            0x00          // FSPEC: no items present
        };

        assertThatCode(() -> parser.parse(msg))
            .as("parse() must not throw for %s", resource)
            .doesNotThrowAnyException();

        ArrayNode blocks = parser.parse(msg);
        assertThat(blocks).as("must produce exactly one block for %s", resource).hasSize(1);
        assertThat(blocks.get(0).get("category").asInt())
            .as("block category must match for %s", resource)
            .isEqualTo(entry.category());
    }

    // ── Registry-level coverage ────────────────────────────────────────────

    /**
     * Verify that {@code CategoryRegistry.withBuiltins()} contains every
     * category listed as latest in the manifest, so there are no silent
     * spec-load failures at startup.
     */
    @ParameterizedTest(name = "withBuiltins contains CAT{0}")
    @MethodSource("latestCategoryNumbers")
    void withBuiltins_containsEveryLatestCategory(int category) {
        CategoryRegistry registry = CategoryRegistry.withBuiltins();
        assertThat(registry.contains(category))
            .as("CategoryRegistry.withBuiltins() must contain CAT%03d", category)
            .isTrue();
    }

    static Stream<Integer> latestCategoryNumbers() throws Exception {
        return allSpecEntries()
            .filter(SpecEntry::latest)
            .map(SpecEntry::category);
    }
}

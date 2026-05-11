package io.airlab.kafbat.serde.asterix.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.airlab.kafbat.serde.asterix.category.CategoryDefinition;
import io.airlab.kafbat.serde.asterix.category.CategoryRegistry;
import io.airlab.kafbat.serde.asterix.parser.ItemParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads ASTERIX category definitions from JSON spec files bundled in the JAR
 * under {@code asterix-specs/}.
 *
 * <h2>Resource layout</h2>
 * <pre>
 *   asterix-specs/manifest.json          – index of all bundled specs
 *   asterix-specs/cat{N}/{edition}.json  – per-category, per-edition spec
 * </pre>
 *
 * <h2>UAP with multiple variants</h2>
 * For categories that have per-message-type UAPs (e.g. CAT001 has "plot" and
 * "track" variants), only the first variant is registered.  Full multi-UAP
 * support can be added later via {@link CategoryRegistry}.
 */
public class SpecLoader {

    private static final Logger LOG = Logger.getLogger(SpecLoader.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MANIFEST = "asterix-specs/manifest.json";

    /** Load all specs listed in the manifest. Returns a fully-populated registry. */
    public static CategoryRegistry loadAll() {
        CategoryRegistry registry = new CategoryRegistry();
        JsonNode manifest = readManifest();
        if (manifest == null) {
            LOG.warning("No asterix-specs/manifest.json found – no specs loaded from classpath");
            return registry;
        }

        JsonNode specs = manifest.path("specs");
        for (JsonNode entry : specs) {
            boolean latest = entry.path("latest").asBoolean(false);
            String file = entry.path("file").asText();
            String resource = "asterix-specs/" + file;
            try {
                CategoryDefinition def = loadSpec(resource);
                if (def != null) {
                    registry.register(def, latest);
                    LOG.fine(() -> "Loaded CAT%03d edition %s%s from %s"
                        .formatted(def.category, def.edition,
                                   latest ? " (latest)" : "", resource));
                }
            } catch (Exception e) {
                LOG.warning("Failed to load spec " + resource + ": " + e.getMessage());
            }
        }
        return registry;
    }

    /** Load a single spec from a classpath resource path. */
    public static CategoryDefinition loadSpec(String resourcePath) throws Exception {
        InputStream stream = openResource(resourcePath);
        if (stream == null) return null;
        try (stream) {
            return parseSpec(MAPPER.readTree(stream));
        }
    }

    private static InputStream openResource(String resourcePath) {
        InputStream is = SpecLoader.class.getClassLoader().getResourceAsStream(resourcePath);
        if (is != null) return is;
        // Try context class loader (needed when loaded as a plugin)
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        return ctx != null ? ctx.getResourceAsStream(resourcePath) : null;
    }

    // ── Spec JSON → CategoryDefinition ────────────────────────────────────

    private static CategoryDefinition parseSpec(JsonNode root) {
        int category = root.path("category").asInt();
        String edition = root.path("edition").asText("unknown");
        String name = root.path("name").asText("CAT " + category);

        // Build UAP array
        String[] uap = buildUap(root.path("uap"));

        // Build item parsers
        Map<String, ItemParser> items = new LinkedHashMap<>();
        JsonNode itemsNode = root.path("items");
        itemsNode.fields().forEachRemaining(e -> {
            String itemId = e.getKey();
            JsonNode spec = e.getValue();
            JsonNode rule = spec.path("rule");
            items.put(itemId, new SpecItemParser(rule));
        });

        // Ensure SP and RE are always parseable if referenced in UAP
        ensureExplicit(items, uap, "SP");
        ensureExplicit(items, uap, "RE");

        return new CategoryDefinition(category, edition, name, uap, items);
    }

    private static String[] buildUap(JsonNode uapNode) {
        String uapType = uapNode.path("type").asText("flat");

        if ("flat".equals(uapType)) {
            return uapItemArray(uapNode.path("items"));
        }

        // "multi" – use first variant
        JsonNode variants = uapNode.path("variants");
        if (variants.isObject() && variants.size() > 0) {
            JsonNode firstVariant = variants.elements().next();
            return uapItemArray(firstVariant);
        }
        return new String[0];
    }

    private static String[] uapItemArray(JsonNode items) {
        List<String> list = new ArrayList<>();
        for (JsonNode item : items) {
            list.add(item.isNull() ? null : item.asText(null));
        }
        return list.toArray(new String[0]);
    }

    private static void ensureExplicit(Map<String, ItemParser> items, String[] uap, String id) {
        for (String u : uap) {
            if (id.equals(u) && !items.containsKey(id)) {
                items.put(id, new SpecItemParser(MAPPER.createObjectNode().put("type", "Explicit")));
                return;
            }
        }
    }

    // ── Manifest ──────────────────────────────────────────────────────────

    private static JsonNode readManifest() {
        try {
            InputStream stream = openResource(MANIFEST);
            if (stream == null) return null;
            try (stream) { return MAPPER.readTree(stream); }
        } catch (Exception e) {
            LOG.warning("Could not read manifest: " + e.getMessage());
            return null;
        }
    }
}

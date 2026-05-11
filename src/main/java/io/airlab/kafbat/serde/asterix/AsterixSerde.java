package io.airlab.kafbat.serde.asterix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.airlab.kafbat.serde.asterix.category.CategoryRegistry;
import io.airlab.kafbat.serde.asterix.parser.AsterixParser;
import io.kafbat.ui.serde.api.*;
import io.kafbat.ui.serde.api.Serde.Target;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Kafbat UI Serde plugin for ASTERIX binary surveillance data.
 *
 * <h2>What it does</h2>
 * <ul>
 *   <li><b>Deserialise</b>: Converts raw ASTERIX binary messages to pretty-printed JSON.
 *       Supports all categories bundled in the spec manifest.  Unknown categories are
 *       returned as a hex dump.</li>
 *   <li><b>Serialise</b>: Accepts a hex-encoded string (e.g. {@code "30000D..."}) and
 *       converts it back to raw bytes for publishing test messages.</li>
 * </ul>
 *
 * <h2>Configuration properties (all optional)</h2>
 * <pre>
 *   topicKeysPattern   – regex; key serde applied only to matching topics
 *   topicValuesPattern – regex; value serde applied only to matching topics
 *   cat002Edition      – edition to use for CAT002 (e.g. "1.1"); default: latest
 *   cat048Edition      – edition to use for CAT048 (e.g. "1.29"); default: latest
 *   cat021Edition      – edition to use for CAT021 (e.g. "2.1"); default: latest
 *   (repeat for any category: cat{NNN}Edition)
 * </pre>
 *
 * <h2>Kafbat UI YAML example</h2>
 * <pre>
 *   kafka:
 *     clusters:
 *       - name: my-cluster
 *         serde:
 *           - name: ASTERIX
 *             className: io.airlab.kafbat.serde.asterix.AsterixSerde
 *             filePath: /opt/serdes/kafbat-ui-serde-asterix-1.0.0-SNAPSHOT-uber.jar
 *             topicValuesPattern: "asterix.*"
 *             properties:
 *               cat048Edition: "1.29"   # override for legacy producers
 *               cat021Edition: "2.1"
 * </pre>
 */
public class AsterixSerde implements Serde {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AsterixParser parser;
    private Pattern keyPattern;
    private Pattern valuePattern;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void configure(PropertyResolver serdeProperties,
                          PropertyResolver clusterProperties,
                          PropertyResolver globalProperties) {

        CategoryRegistry registry = CategoryRegistry.withBuiltins();

        // Collect per-category edition overrides from "cat{NNN}Edition" properties.
        // Accept both zero-padded ("cat048Edition") and unpadded ("cat48Edition") forms.
        Map<Integer, String> editionOverrides = new HashMap<>();
        for (int cat = 1; cat <= 255; cat++) {
            final int catFinal = cat;
            String padded   = String.format("cat%03dEdition", cat);
            String unpadded = "cat" + cat + "Edition";
            serdeProperties.getProperty(padded, String.class)
                .or(() -> serdeProperties.getProperty(unpadded, String.class))
                .ifPresent(edition -> editionOverrides.put(catFinal, edition));
        }

        this.parser = new AsterixParser(registry, editionOverrides);

        serdeProperties.getProperty("topicKeysPattern", String.class)
            .ifPresent(p -> keyPattern = Pattern.compile(p));
        serdeProperties.getProperty("topicValuesPattern", String.class)
            .ifPresent(p -> valuePattern = Pattern.compile(p));
    }

    @Override
    public Optional<String> getDescription() {
        return Optional.of(
            "ASTERIX (All Purpose STructured Eurocontrol suRveillance Information eXchange) " +
            "binary protocol decoder.  Supports all bundled categories and editions; " +
            "falls back to a hex dump for unknown categories.\n\n" +
            "**Deserialise** → JSON  |  **Serialise** ← hex string\n\n" +
            "Use `cat{NNN}Edition` properties to pin a specific spec edition per category " +
            "(e.g. `cat048Edition: \"1.29\"`)."
        );
    }

    @Override
    public Optional<SchemaDescription> getSchema(String topic, Target type) {
        String schema = """
            {
              "type": "array",
              "description": "Array of ASTERIX data blocks parsed from the Kafka message",
              "items": {
                "type": "object",
                "properties": {
                  "category": { "type": "integer", "description": "ASTERIX category number" },
                  "edition":  { "type": "string",  "description": "Spec edition used (e.g. 1.32)" },
                  "name":     { "type": "string",  "description": "Category name" },
                  "records":  {
                    "type": "array",
                    "description": "Decoded data records within this data block",
                    "items": { "type": "object" }
                  }
                }
              }
            }
            """;
        return Optional.of(new SchemaDescription(schema, Map.of()));
    }

    // -----------------------------------------------------------------------
    // Capability checks
    // -----------------------------------------------------------------------

    @Override
    public boolean canDeserialize(String topic, Target type) {
        Pattern pat = (type == Target.KEY) ? keyPattern : valuePattern;
        return pat == null || pat.matcher(topic).find();
    }

    @Override
    public boolean canSerialize(String topic, Target type) {
        Pattern pat = (type == Target.KEY) ? keyPattern : valuePattern;
        return pat == null || pat.matcher(topic).find();
    }

    // -----------------------------------------------------------------------
    // Serializer
    // -----------------------------------------------------------------------

    @Override
    public Serializer serializer(String topic, Target type) {
        return input -> {
            if (input == null || input.isBlank()) return null;
            String hex = input.trim().replaceAll("\\s+", "");
            if (hex.length() % 2 != 0) {
                throw new IllegalArgumentException(
                    "Hex string must have an even number of characters, got: " + hex.length());
            }
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return bytes;
        };
    }

    // -----------------------------------------------------------------------
    // Deserializer
    // -----------------------------------------------------------------------

    @Override
    public Deserializer deserializer(String topic, Target type) {
        return (headers, data) -> {
            if (data == null || data.length == 0) {
                return new DeserializeResult("", DeserializeResult.Type.STRING, Map.of());
            }
            try {
                ArrayNode result = parser.parse(data);
                String json = MAPPER.writerWithDefaultPrettyPrinter()
                                    .writeValueAsString(result);
                return new DeserializeResult(json, DeserializeResult.Type.JSON, Map.of());
            } catch (Exception e) {
                String msg = "ASTERIX parse error: " + e.getMessage();
                return new DeserializeResult(msg, DeserializeResult.Type.STRING, Map.of());
            }
        };
    }

}

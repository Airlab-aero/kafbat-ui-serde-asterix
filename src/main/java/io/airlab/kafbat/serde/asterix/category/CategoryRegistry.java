package io.airlab.kafbat.serde.asterix.category;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry of known ASTERIX {@link CategoryDefinition}s.
 *
 * <p>Pre-populated with CAT002, CAT010, CAT021, CAT034, and CAT048.
 * Additional categories can be registered at runtime via {@link #register}.
 */
public class CategoryRegistry {

    private final Map<Integer, CategoryDefinition> definitions = new HashMap<>();

    /** Creates a registry pre-loaded with all built-in category definitions. */
    public static CategoryRegistry withBuiltins() {
        CategoryRegistry r = new CategoryRegistry();
        r.register(Cat002.definition());
        r.register(Cat010.definition());
        r.register(Cat021.definition());
        r.register(Cat034.definition());
        r.register(Cat048.definition());
        return r;
    }

    /** Register (or replace) a category definition. */
    public void register(CategoryDefinition def) {
        definitions.put(def.category, def);
    }

    /**
     * Look up a category definition by number.
     *
     * @return the definition, or {@code null} if the category is unknown
     */
    public CategoryDefinition get(int category) {
        return definitions.get(category);
    }

    /** Returns {@code true} if this registry knows about {@code category}. */
    public boolean contains(int category) {
        return definitions.containsKey(category);
    }
}

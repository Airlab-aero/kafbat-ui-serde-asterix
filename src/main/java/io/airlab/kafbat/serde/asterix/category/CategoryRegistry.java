package io.airlab.kafbat.serde.asterix.category;

import io.airlab.kafbat.serde.asterix.spec.SpecLoader;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of known ASTERIX {@link CategoryDefinition}s, indexed by both
 * category number and edition string.
 *
 * <p>Multiple editions of the same category can coexist.  Each category has
 * one designated <em>latest</em> edition that is returned by
 * {@link #get(int)}.  A specific edition can be retrieved with
 * {@link #get(int, String)}.
 *
 * <p>{@link #withBuiltins()} loads all editions from the bundled JSON specs,
 * then falls back to hand-written definitions for any categories not covered.
 */
public class CategoryRegistry {

    /** category → (edition → definition), insertion-ordered per category. */
    private final Map<Integer, LinkedHashMap<String, CategoryDefinition>> editions =
            new ConcurrentHashMap<>();

    /** category → edition string designated as "latest". */
    private final Map<Integer, String> latestEditions = new ConcurrentHashMap<>();

    /**
     * Creates a registry populated from the bundled JSON specs (all categories,
     * all editions).  Hand-written definitions are used as fallback when no
     * spec file is present.
     */
    public static CategoryRegistry withBuiltins() {
        CategoryRegistry r = SpecLoader.loadAll();

        if (!r.contains(2))   r.register(Cat002.definition(), true);
        if (!r.contains(10))  r.register(Cat010.definition(), true);
        if (!r.contains(21))  r.register(Cat021.definition(), true);
        if (!r.contains(34))  r.register(Cat034.definition(), true);
        if (!r.contains(48))  r.register(Cat048.definition(), true);
        return r;
    }

    /**
     * Register a definition, optionally marking it as the latest edition for
     * its category.  Calling {@code register(def, true)} twice for the same
     * category moves the "latest" pointer to the second definition.
     */
    public void register(CategoryDefinition def, boolean markAsLatest) {
        editions.computeIfAbsent(def.category, k -> new LinkedHashMap<>())
                .put(def.edition, def);
        if (markAsLatest) {
            latestEditions.put(def.category, def.edition);
        } else {
            // If no latest is set yet, use this one as a default
            latestEditions.putIfAbsent(def.category, def.edition);
        }
    }

    /**
     * Register a definition and mark it as the latest edition.
     * Convenience overload; equivalent to {@code register(def, true)}.
     */
    public void register(CategoryDefinition def) {
        register(def, true);
    }

    /**
     * Return the <em>latest</em> edition for the given category, or
     * {@code null} if the category is unknown.
     */
    public CategoryDefinition get(int category) {
        String latest = latestEditions.get(category);
        if (latest == null) return null;
        LinkedHashMap<String, CategoryDefinition> eds = editions.get(category);
        return eds == null ? null : eds.get(latest);
    }

    /**
     * Return a specific edition for the given category, or {@code null} if
     * the category or edition is unknown.
     */
    public CategoryDefinition get(int category, String edition) {
        LinkedHashMap<String, CategoryDefinition> eds = editions.get(category);
        return eds == null ? null : eds.get(edition);
    }

    /**
     * Return an unmodifiable view of all registered edition strings for a
     * category, in registration order (oldest → newest).
     * Returns an empty set if the category is unknown.
     */
    public Set<String> editions(int category) {
        LinkedHashMap<String, CategoryDefinition> eds = editions.get(category);
        return eds == null ? Set.of() : Collections.unmodifiableSet(eds.keySet());
    }

    /** Returns {@code true} if any edition of {@code category} is registered. */
    public boolean contains(int category) {
        LinkedHashMap<String, CategoryDefinition> eds = editions.get(category);
        return eds != null && !eds.isEmpty();
    }
}

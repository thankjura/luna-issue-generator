package ru.slie.luna.rest.client.generator.data;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds {@link GeneratorConfig} from layers, each overriding the previous one:
 * <ol>
 *     <li>generator-default.yaml bundled into the jar</li>
 *     <li>the config file (-c)</li>
 *     <li>environment variables {@code LUNA_GEN_<PATH>}, path segments separated by a double underscore:
 *     {@code LUNA_GEN_ISSUES__COUNT=1000000}, {@code LUNA_GEN_FIELDS__PER_TYPE__STRING=50}</li>
 *     <li>{@code --set path=value} options: {@code --set issues.count=1000000}</li>
 * </ol>
 * Key matching ignores case, '-' and '_', so {@code per_type}, {@code perType} and {@code PER_TYPE} are the same key.
 */
public class ConfigLoader {
    public static final String ENV_PREFIX = "LUNA_GEN_";
    private static final String DEFAULTS = "generator-default.yaml";

    private final YAMLMapper yaml = YAMLMapper.builder()
                                              .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                              .build();

    public GeneratorConfig load(Path configFile, Map<String, String> env, List<String> overrides) {
        ObjectNode tree;
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(DEFAULTS)) {
            tree = (ObjectNode) yaml.readTree(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (configFile != null) {
            try {
                JsonNode file = yaml.readTree(Files.readString(configFile));
                if (file instanceof ObjectNode fileObject) {
                    merge(tree, fileObject);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read config " + configFile, e);
            }
        }

        env.entrySet().stream()
           .filter(e -> e.getKey().startsWith(ENV_PREFIX))
           .sorted(Map.Entry.comparingByKey())
           .forEach(e -> set(tree, e.getKey().substring(ENV_PREFIX.length()).split("__"), e.getValue()));

        if (overrides != null) {
            for (String override : overrides) {
                int eq = override.indexOf('=');
                if (eq <= 0) {
                    throw new IllegalArgumentException("Expected path=value: " + override);
                }
                set(tree, override.substring(0, eq).split("\\."), override.substring(eq + 1));
            }
        }

        return yaml.treeToValue(tree, GeneratorConfig.class);
    }

    public String toYaml(GeneratorConfig config) {
        return yaml.writeValueAsString(config);
    }

    private static void merge(ObjectNode target, ObjectNode source) {
        for (Map.Entry<String, JsonNode> entry : source.properties()) {
            String key = findKey(target, entry.getKey());
            JsonNode existing = target.get(key);
            if (existing instanceof ObjectNode existingObject && entry.getValue() instanceof ObjectNode sourceObject) {
                merge(existingObject, sourceObject);
            } else {
                target.set(key, entry.getValue());
            }
        }
    }

    private void set(ObjectNode root, String[] path, String rawValue) {
        ObjectNode node = root;
        for (int i = 0; i < path.length - 1; i++) {
            String key = findKey(node, path[i]);
            if (!(node.get(key) instanceof ObjectNode)) {
                node.set(key, node.objectNode());
            }
            node = (ObjectNode) node.get(key);
        }
        JsonNode value = yaml.readTree(rawValue.isEmpty() ? "\"\"" : rawValue);
        if (value == null || value.isMissingNode()) {
            value = node.textNode(rawValue);
        }
        node.set(findKey(node, path[path.length - 1]), value);
    }

    /** Existing key equal to the requested one ignoring case, '-' and '_', or a camelCase form of it. */
    private static String findKey(ObjectNode node, String requested) {
        String normalized = normalize(requested);
        Iterator<String> names = node.propertyNames().iterator();
        while (names.hasNext()) {
            String name = names.next();
            if (normalize(name).equals(normalized)) {
                return name;
            }
        }
        return toCamelCase(requested);
    }

    private static String normalize(String key) {
        return key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static String toCamelCase(String key) {
        if (!key.contains("_") && !key.contains("-") && !key.equals(key.toUpperCase(Locale.ROOT))) {
            return key;
        }
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : key.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '_' || c == '-') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }

    public static ObjectMapper json() {
        return new ObjectMapper();
    }
}

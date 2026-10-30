package bdsm.integration.load;

import java.util.HashMap;
import java.util.Map;

final class Options {

    private final Map<String, String> values = new HashMap<>();

    Options(String[] args) {
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("Expected an option, got: " + args[i]);
            values.put(args[i].substring(2), args[i + 1]);
        }
    }

    String get(String name) {
        String value = values.get(name);
        if (value == null) throw new IllegalArgumentException("Missing option --" + name);
        return value;
    }

    String get(String name, String defaultValue) {
        return values.getOrDefault(name, defaultValue);
    }

    int getInt(String name) {
        return Integer.parseInt(get(name));
    }

    int getInt(String name, int defaultValue) {
        return values.containsKey(name) ? Integer.parseInt(values.get(name)) : defaultValue;
    }
}

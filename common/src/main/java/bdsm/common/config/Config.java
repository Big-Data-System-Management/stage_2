package bdsm.common.config;

import java.nio.file.Path;

public final class Config {

    private Config() {
    }

    public static String get(String name, String defaultValue) {
        String value = System.getProperty(name, System.getenv(name));
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    public static int getInt(String name, int defaultValue) {
        String value = get(name, null);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Setting " + name + " must be an integer, got: " + value);
        }
    }

    public static Path getPath(String name, String defaultValue) {
        return Path.of(get(name, defaultValue));
    }
}

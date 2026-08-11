package com.servertune.config;

/**
 * One validated configuration setting: the path, the value that passed validation, and - when
 * validation failed - the default that replaced it.
 *
 * <p>Deliberately free of Bukkit types, so the validation rules can be unit tested without a
 * running server. {@link ConfigValidator} is a thin shell over {@link ConfigRules}: it reads
 * each value from config.yml, asks for the correction, and writes a replacement back only for
 * the entries that carry a replacement.
 *
 * @param path         the config.yml path, as written by the operator
 * @param value        the operator's value, as parsed from YAML
 * @param replacement  the value that will actually be used, null when the original was valid
 * @param message      a human-readable explanation of the correction, null when valid
 */
public record ConfigValue(String path, Object value, Object replacement, String message) {

    public boolean isValid() {
        return replacement == null;
    }
}

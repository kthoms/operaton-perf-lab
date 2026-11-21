package io.github.kthoms.perflab.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigLoader {

    private static final Pattern SHORT_DURATION = Pattern.compile("(\\d+)([smhd])");

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .registerModule(new SimpleModule().addDeserializer(Duration.class, new ShortDurationDeserializer()))
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private ConfigLoader() {}

    public static PerfLabConfig load(Path yml) {
        try {
            return MAPPER.readValue(yml.toFile(), PerfLabConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load config " + yml + ": " + e.getMessage(), e);
        }
    }

    static Duration parseDuration(String text) {
        Matcher m = SHORT_DURATION.matcher(text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid duration '" + text + "' (expected e.g. 5s, 15m, 20h, 2d)");
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> Duration.ofDays(n);
        };
    }

    private static final class ShortDurationDeserializer extends JsonDeserializer<Duration> {
        @Override
        public Duration deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            try {
                return parseDuration(p.getValueAsString());
            } catch (IllegalArgumentException e) {
                throw ctxt.weirdStringException(p.getValueAsString(), Duration.class, e.getMessage());
            }
        }
    }
}

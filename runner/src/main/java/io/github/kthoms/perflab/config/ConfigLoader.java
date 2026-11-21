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
            PerfLabConfig config = MAPPER.readValue(yml.toFile(), PerfLabConfig.class);
            if (config == null) {
                throw new IllegalArgumentException("Config " + yml + " is empty");
            }
            validate(config, yml);
            return config;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load config " + yml + ": " + e.getMessage(), e);
        }
    }

    private static void validate(PerfLabConfig c, Path yml) {
        require(c.dataDir(), "dataDir", yml);
        require(c.backupDir(), "backupDir", yml);
        require(c.upstreamUrl(), "upstreamUrl", yml);
        require(c.resultsDb(), "resultsDb", yml);
        require(c.resultsDb().jdbcUrl(), "resultsDb.jdbcUrl", yml);
        require(c.resultsDb().user(), "resultsDb.user", yml);
        require(c.resultsDb().password(), "resultsDb.password", yml);
        require(c.branches(), "branches", yml);
        for (var b : c.branches()) {
            require(b, "branches[]", yml);
            require(b.name(), "branches[].name", yml);
            require(b.javaHome(), "branches[].javaHome", yml);
            require(b.mavenArgs(), "branches[].mavenArgs", yml);
        }
        require(c.benchmarks(), "benchmarks", yml);
        require(c.idle(), "idle", yml);
        require(c.idle().minUserIdle(), "idle.minUserIdle", yml);
        require(c.idle().heavyProcessNames(), "idle.heavyProcessNames", yml);
        require(c.idle().pollInterval(), "idle.pollInterval", yml);
        require(c.idle().activityCheckInterval(), "idle.activityCheckInterval", yml);
    }

    private static void require(Object value, String key, Path yml) {
        if (value == null) {
            throw new IllegalArgumentException("Config " + yml + ": required key '" + key + "' is missing");
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

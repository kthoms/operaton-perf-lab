package io.github.kthoms.perflab.results;

import java.util.Map;

public record Measurement(String kpi, double value, String unit, Map<String, String> dims) {}

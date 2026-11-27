package io.github.kthoms.perflab.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.persistence.StrongUuidGenerator;
import org.operaton.bpm.engine.variable.VariableMap;

/**
 * The two processes of the BPMN engine comparison (see {@code scenarios/NOTICE.md}) and an engine
 * configured like the Spring Boot apps measured there: UUID ids, a pool of 10 connections, no job
 * executor, schema created on start and dropped on close (every run starts empty).
 */
final class Scenarios {

  static final String SCENARIO_A = "ScenarioA";
  static final String SCENARIO_B = "ScenarioB";
  static final String[] COUNTRIES = {"DE", "AT", "CH", "FR", "NL"};
  static final String[] CUSTOMER_TYPES = {"standard", "premium", "business", "student"};

  private Scenarios() {}

  static ProcessEngine buildEngine(String jdbcUrl, String user, String password, String history) {
    var configuration = new StandaloneProcessEngineConfiguration();
    configuration
        .setProcessEngineName("perflab-bench-" + java.util.UUID.randomUUID())
        .setJdbcUrl(jdbcUrl)
        .setJdbcUsername(user)
        .setJdbcPassword(password)
        .setJdbcDriver(driver(jdbcUrl))
        .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP)
        .setHistory(history)
        .setJobExecutorActivate(false);
    configuration.setIdGenerator(new StrongUuidGenerator());
    configuration.setEnforceHistoryTimeToLive(false);
    return configuration.buildProcessEngine();
  }

  static String driver(String jdbcUrl) {
    if (jdbcUrl.startsWith("jdbc:h2:")) {
      return "org.h2.Driver";
    }
    if (jdbcUrl.startsWith("jdbc:postgresql:")) {
      return "org.postgresql.Driver";
    }
    throw new IllegalArgumentException("unsupported JDBC URL: " + jdbcUrl);
  }

  /** Deploys scenario A, scenario B and its decision table in one deployment. */
  static void deploy(ProcessEngine engine) {
    engine
        .getRepositoryService()
        .createDeployment()
        .name("engine-comparison-scenarios")
        .addClasspathResource("scenarios/scenario-a.bpmn")
        .addClasspathResource("scenarios/scenario-b.bpmn")
        .addClasspathResource("scenarios/pricing.dmn")
        .deploy();
  }

  /** Starts scenario A and fails unless counter = 3 comes back. */
  static void runScenarioA(RuntimeService runtime) {
    VariableMap result =
        runtime
            .createProcessInstanceByKey(SCENARIO_A)
            .setVariable("counter", 0)
            .executeWithVariablesInReturn()
            .getVariables();
    Object counter = result.get("counter");
    if (!(counter instanceof Number n) || n.longValue() != 3) {
      throw new IllegalStateException("scenario A returned counter=" + counter + ", expected 3");
    }
  }

  /** One input of scenario B and the fee the decision table must return for it. */
  record PricingInput(int age, int amount, String country, String customerType, long expectedFee) {}

  /** Starts scenario B and fails unless the expected fee comes back. */
  static void runScenarioB(RuntimeService runtime, PricingInput input) {
    VariableMap result =
        runtime
            .createProcessInstanceByKey(SCENARIO_B)
            .setVariables(
                Map.of(
                    "age", input.age(),
                    "amount", input.amount(),
                    "country", input.country(),
                    "customerType", input.customerType()))
            .executeWithVariablesInReturn()
            .getVariables();
    Object fee = result.get("fee");
    if (!(fee instanceof Number n) || n.longValue() != input.expectedFee()) {
      throw new IllegalStateException(
          "scenario B returned fee=" + fee + " for " + input + ", expected " + input.expectedFee());
    }
  }

  /**
   * {@code count} random inputs (fixed seed, so every run evaluates the same requests) in the
   * ranges of the original load script, each with its expected fee from {@code pricing-rules.json}.
   */
  static List<PricingInput> pricingInputs(int count, long seed) {
    List<Rule> rules = rules();
    Random random = new Random(seed);
    List<PricingInput> inputs = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      int age = 18 + random.nextInt(99 - 18 + 1);
      int amount = random.nextInt(100_000 + 1);
      String country = COUNTRIES[random.nextInt(COUNTRIES.length)];
      String type = CUSTOMER_TYPES[random.nextInt(CUSTOMER_TYPES.length)];
      long fee = 0;
      for (Rule r : rules) {
        if (r.matches(age, amount, country, type)) {
          fee += r.fee();
        }
      }
      inputs.add(new PricingInput(age, amount, country, type, fee));
    }
    return inputs;
  }

  record Rule(int[] age, int[] amount, String country, String customerType, long fee) {
    boolean matches(int a, int am, String c, String t) {
      return (age == null || (age[0] <= a && a <= age[1]))
          && (amount == null || (amount[0] <= am && am <= amount[1]))
          && (country == null || country.equals(c))
          && (customerType == null || customerType.equals(t));
    }
  }

  static List<Rule> rules() {
    try (InputStream in = Scenarios.class.getResourceAsStream("/scenarios/pricing-rules.json")) {
      if (in == null) {
        throw new IllegalStateException("scenarios/pricing-rules.json not on the classpath");
      }
      List<Rule> rules = new ArrayList<>();
      for (JsonNode r : new ObjectMapper().readTree(in)) {
        rules.add(
            new Rule(
                range(r.get("age")),
                range(r.get("amount")),
                text(r.get("country")),
                text(r.get("customerType")),
                r.get("fee").asLong()));
      }
      return rules;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static int[] range(JsonNode node) {
    return node == null || node.isNull() ? null : new int[] {node.get(0).asInt(), node.get(1).asInt()};
  }

  private static String text(JsonNode node) {
    return node == null || node.isNull() ? null : node.asText();
  }

  // -- statistics and system properties shared by the benchmarks --

  static double median(List<Double> values) {
    return percentile(values, 50);
  }

  /** Nearest-rank percentile (p in 0..100) of a non-empty list. */
  static double percentile(List<Double> values, double p) {
    double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    if (sorted.length == 0) {
      throw new IllegalArgumentException("no values");
    }
    if (p == 50 && sorted.length % 2 == 0) {
      return (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
    }
    int rank = (int) Math.ceil(p / 100 * sorted.length);
    return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
  }

  static String property(String name, String defaultValue) {
    String value = System.getProperty(name);
    return value == null || value.isBlank() ? defaultValue : value.trim();
  }

  static int intProperty(String name, int defaultValue) {
    String value = System.getProperty(name);
    return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
  }

  static List<Integer> intListProperty(String name, List<Integer> defaultValue) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    List<Integer> out = new ArrayList<>();
    for (String part : value.split(",")) {
      if (!part.isBlank()) {
        int n = Integer.parseInt(part.trim());
        if (n < 1) {
          throw new IllegalArgumentException(name + " must hold positive numbers: " + value);
        }
        out.add(n);
      }
    }
    if (out.isEmpty()) {
      throw new IllegalArgumentException(name + " is empty: " + value);
    }
    return List.copyOf(out);
  }
}

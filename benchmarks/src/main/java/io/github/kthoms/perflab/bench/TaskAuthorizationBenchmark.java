package io.github.kthoms.perflab.bench;

import static org.operaton.bpm.engine.authorization.Authorization.ANY;
import static org.operaton.bpm.engine.authorization.Authorization.AUTH_TYPE_GRANT;
import static org.operaton.bpm.engine.authorization.Permissions.READ;
import static org.operaton.bpm.engine.authorization.Resources.TASK;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.operaton.bpm.engine.AuthorizationService;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineConfiguration;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.authorization.Authorization;
import org.operaton.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.identity.Authentication;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;

/**
 * Task query performance with authorization enabled, ported from {@code
 * TaskAuthorizationExistsBenchmark} of operaton/operaton#3202. Starts {@code tasks} user-task
 * instances, grants TASK/READ to the user and to {@code groups} groups, enables authorization and
 * times {@code iterations} iterations after {@code warmup} untimed ones. One iteration runs the
 * three queries of the original once: {@code listPage}, {@code listPage} ordered by create time
 * desc, and {@code count}.
 *
 * <p>Run as a program, it reads {@code benchmark.*} system properties and writes the JSON result
 * to {@code -Dbenchmark.output}. The schema is created on start and dropped on close, so every run
 * starts from an empty database.
 */
public class TaskAuthorizationBenchmark {

  public static final String ID = "task-authorization";
  static final String PROCESS_KEY = "taskAuthorizationExistsBenchmark";
  static final String USER_ID = "task-auth-benchmark-user";
  static final int PAGE_SIZE = 15;

  public record Options(
      String database,
      String jdbcUrl,
      String jdbcUser,
      String jdbcPassword,
      int tasks,
      int groups,
      int warmup,
      int iterations) {

    static Options fromSystemProperties() {
      return new Options(
          property("benchmark.database", "h2"),
          property("benchmark.jdbcUrl", "jdbc:h2:mem:perflab-bench;DB_CLOSE_DELAY=-1"),
          property("benchmark.jdbcUser", "sa"),
          System.getProperty("benchmark.jdbcPassword", ""),
          intProperty("benchmark.tasks", 5000),
          intProperty("benchmark.groups", 10),
          intProperty("benchmark.warmup", 30),
          intProperty("benchmark.iterations", 200));
    }

    Map<String, Integer> params() {
      Map<String, Integer> params = new LinkedHashMap<>();
      params.put("tasks", tasks);
      params.put("groups", groups);
      params.put("warmup", warmup);
      params.put("iterations", iterations);
      params.put("pageSize", PAGE_SIZE);
      return params;
    }
  }

  public static void main(String[] args) throws Exception {
    String output = System.getProperty("benchmark.output");
    if (output == null || output.isBlank()) {
      throw new IllegalArgumentException("-Dbenchmark.output=<file> is required");
    }
    new TaskAuthorizationBenchmark().run(Options.fromSystemProperties()).write(Path.of(output));
  }

  public BenchmarkResult run(Options options) {
    ProcessEngine engine = buildEngine(options);
    try {
      var configuration =
          (StandaloneProcessEngineConfiguration) engine.getProcessEngineConfiguration();
      assertNoExistingTasks(engine);
      setupData(engine, options);
      createAuthorizations(engine.getAuthorizationService(), options);

      configuration.setAuthorizationEnabled(true);
      engine.getIdentityService().setAuthentication(new Authentication(USER_ID, groupIds(options.groups())));
      try {
        TaskService taskService = engine.getTaskService();
        long visible = taskService.createTaskQuery().count();
        if (visible != options.tasks()) {
          throw new IllegalStateException(
              "Expected " + options.tasks() + " visible tasks but got " + visible);
        }
        for (int i = 0; i < options.warmup(); i++) {
          iteration(taskService);
        }
        List<Double> iterationsMs = new ArrayList<>(options.iterations());
        for (int i = 0; i < options.iterations(); i++) {
          long start = System.nanoTime();
          iteration(taskService);
          iterationsMs.add((System.nanoTime() - start) / 1_000_000d);
        }
        return new BenchmarkResult(ID, options.database(), options.params(), iterationsMs);
      } finally {
        engine.getIdentityService().clearAuthentication();
      }
    } finally {
      engine.close();
    }
  }

  private static ProcessEngine buildEngine(Options options) {
    var configuration = new StandaloneProcessEngineConfiguration();
    configuration
        .setProcessEngineName("perflab-bench-" + UUID.randomUUID())
        .setJdbcUrl(options.jdbcUrl())
        .setJdbcUsername(options.jdbcUser())
        .setJdbcPassword(options.jdbcPassword())
        .setJdbcDriver(driver(options.jdbcUrl()))
        .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP)
        .setHistory(ProcessEngineConfiguration.HISTORY_FULL)
        .setJobExecutorActivate(false);
    configuration.setEnforceHistoryTimeToLive(false);
    return configuration.buildProcessEngine();
  }

  private static String driver(String jdbcUrl) {
    if (jdbcUrl.startsWith("jdbc:h2:")) {
      return "org.h2.Driver";
    }
    if (jdbcUrl.startsWith("jdbc:postgresql:")) {
      return "org.postgresql.Driver";
    }
    throw new IllegalArgumentException("unsupported JDBC URL: " + jdbcUrl);
  }

  /** One timed unit: the three queries of the original benchmark. */
  private static long iteration(TaskService taskService) {
    long observed = taskService.createTaskQuery().listPage(0, PAGE_SIZE).size();
    observed +=
        taskService.createTaskQuery().orderByTaskCreateTime().desc().listPage(0, PAGE_SIZE).size();
    observed += taskService.createTaskQuery().count();
    return observed;
  }

  private static void setupData(ProcessEngine engine, Options options) {
    BpmnModelInstance process =
        Bpmn.createExecutableProcess(PROCESS_KEY)
            .operatonHistoryTimeToLive(180)
            .startEvent()
            .userTask("wait")
            .name("Benchmark task")
            .endEvent()
            .done();
    engine
        .getRepositoryService()
        .createDeployment()
        .name("task-authorization-exists-benchmark")
        .addModelInstance("task-authorization-exists-benchmark.bpmn", process)
        .deploy();
    for (int i = 0; i < options.tasks(); i++) {
      engine.getRuntimeService().startProcessInstanceByKey(PROCESS_KEY);
    }
  }

  private static void createAuthorizations(AuthorizationService service, Options options) {
    grant(service, USER_ID, null, ANY);
    grant(service, ANY, null, ANY);
    for (String groupId : groupIds(options.groups())) {
      grant(service, null, groupId, ANY);
      grant(service, null, groupId, PROCESS_KEY);
    }
  }

  private static void grant(
      AuthorizationService service, String userId, String groupId, String resourceId) {
    Authorization authorization = service.createNewAuthorization(AUTH_TYPE_GRANT);
    authorization.setResource(TASK);
    authorization.setResourceId(resourceId);
    authorization.addPermission(READ);
    if (userId != null) {
      authorization.setUserId(userId);
    } else {
      authorization.setGroupId(groupId);
    }
    service.saveAuthorization(authorization);
  }

  private static void assertNoExistingTasks(ProcessEngine engine) {
    long existing = engine.getTaskService().createTaskQuery().count();
    if (existing > 0) {
      throw new IllegalStateException(
          "Task authorization benchmark expects an empty task table, but found " + existing
              + " tasks. Use a fresh database.");
    }
  }

  static List<String> groupIds(int count) {
    List<String> ids = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      ids.add("task-auth-benchmark-group-" + i);
    }
    return ids;
  }

  private static String property(String name, String defaultValue) {
    String value = System.getProperty(name);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private static int intProperty(String name, int defaultValue) {
    String value = System.getProperty(name);
    return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
  }
}

package io.github.kthoms.perflab.idle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Probe backed by ioreg, sysctl, pmset and ps. Any failure is reported conservatively so the idle
 * gate stays closed: user active, infinite load, on battery.
 */
public class MacActivityProbe implements ActivityProbe {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final Pattern HID_IDLE = Pattern.compile("\"HIDIdleTime\"\\s*=\\s*(\\d+)");
  private static final Pattern LOADAVG = Pattern.compile("\\{\\s*([0-9]+(?:[.,][0-9]+)?)");

  private static final String[] IOREG = {"ioreg", "-c", "IOHIDSystem"};
  private static final String[] SYSCTL = {"sysctl", "-n", "vm.loadavg"};
  private static final String[] PMSET = {"pmset", "-g", "batt"};
  private static final String[] PS = {"ps", "-Ao", "pid,ppid,comm"};
  private static final System.Logger LOG = System.getLogger(MacActivityProbe.class.getName());

  private final Set<String> heavyNames;
  private final Function<String[], String> runner;
  private final System.Logger logger;
  private final Map<String, Boolean> failing = new ConcurrentHashMap<>();

  public MacActivityProbe(List<String> heavyProcessNames) {
    this(heavyProcessNames, MacActivityProbe::run, LOG);
  }

  MacActivityProbe(
      List<String> heavyProcessNames, Function<String[], String> runner, System.Logger logger) {
    this.heavyNames = Set.copyOf(heavyProcessNames);
    this.runner = runner;
    this.logger = logger;
  }

  @Override
  public Optional<Duration> userIdle() {
    return guarded(
        "ioreg", () -> Optional.of(parseHidIdleNanos(runner.apply(IOREG))), Optional.empty());
  }

  @Override
  public double load1() {
    return guarded(
        "sysctl", () -> parseLoad1(runner.apply(SYSCTL)), Double.POSITIVE_INFINITY);
  }

  @Override
  public boolean onAcPower() {
    return guarded("pmset", () -> parseOnAc(runner.apply(PMSET)), false);
  }

  @Override
  public List<String> heavyForeignProcesses() {
    return guarded(
        "ps",
        () -> parseHeavyForeign(runner.apply(PS), heavyNames, ProcessHandle.current().pid()),
        List.of("ps-failed"));
  }

  /** Runs a probe; on failure returns the conservative fallback and logs only state changes. */
  private <T> T guarded(String name, Supplier<T> probe, T fallback) {
    try {
      T value = probe.get();
      if (Boolean.TRUE.equals(failing.put(name, false))) {
        logger.log(System.Logger.Level.INFO, "probe {0} recovered", name);
      }
      return value;
    } catch (RuntimeException e) {
      if (!Boolean.TRUE.equals(failing.put(name, true))) {
        logger.log(
            System.Logger.Level.WARNING,
            "probe " + name + " failed, gate stays closed: " + e.getMessage(),
            e);
      }
      return fallback;
    }
  }

  /** Extracts HIDIdleTime (nanoseconds) from `ioreg -c IOHIDSystem` output. */
  public static Duration parseHidIdleNanos(String ioregOutput) {
    Matcher m = HID_IDLE.matcher(ioregOutput);
    if (!m.find()) {
      throw new IllegalArgumentException("no HIDIdleTime in ioreg output");
    }
    return Duration.ofNanos(Long.parseLong(m.group(1)));
  }

  /** Extracts the 1-minute load from `sysctl -n vm.loadavg`, e.g. "{ 1.23 1.45 1.67 }". */
  public static double parseLoad1(String sysctlOutput) {
    Matcher m = LOADAVG.matcher(sysctlOutput);
    if (!m.find()) {
      throw new IllegalArgumentException("unparseable vm.loadavg: " + sysctlOutput);
    }
    return Double.parseDouble(m.group(1).replace(',', '.'));
  }

  /** True iff `pmset -g batt` reports AC Power as the current source. */
  public static boolean parseOnAc(String pmsetOutput) {
    return pmsetOutput.contains("AC Power");
  }

  /**
   * Names of processes (basename of comm) listed in {@code heavyNames}, excluding {@code ownPid}
   * and all its descendants. Input is `ps -Ao pid,ppid,comm` output.
   */
  public static List<String> parseHeavyForeign(String psOutput, Set<String> heavyNames, long ownPid) {
    record Proc(long pid, long ppid, String name) {}
    List<Proc> procs = new ArrayList<>();
    for (String line : psOutput.split("\\R")) {
      String[] parts = line.trim().split("\\s+", 3);
      if (parts.length < 3 || !parts[0].matches("\\d+") || !parts[1].matches("\\d+")) {
        continue;
      }
      String comm = parts[2].trim();
      String base = comm.substring(comm.lastIndexOf('/') + 1);
      procs.add(new Proc(Long.parseLong(parts[0]), Long.parseLong(parts[1]), base));
    }
    Map<Long, List<Long>> children = new HashMap<>();
    for (Proc p : procs) {
      children.computeIfAbsent(p.ppid(), k -> new ArrayList<>()).add(p.pid());
    }
    Set<Long> own = new HashSet<>();
    List<Long> stack = new ArrayList<>(List.of(ownPid));
    while (!stack.isEmpty()) {
      long pid = stack.remove(stack.size() - 1);
      if (own.add(pid)) {
        stack.addAll(children.getOrDefault(pid, List.of()));
      }
    }
    List<String> out = new ArrayList<>();
    for (Proc p : procs) {
      if (heavyNames.contains(p.name()) && !own.contains(p.pid())) {
        out.add(p.name());
      }
    }
    return out;
  }

  private static String run(String[] command) {
    Path out = null;
    try {
      out = Files.createTempFile("perflab-probe", ".out");
      Process p =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(out.toFile())
              .start();
      if (!p.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        p.destroyForcibly();
        throw new IllegalStateException("timeout: " + command[0]);
      }
      if (p.exitValue() != 0) {
        throw new IllegalStateException(command[0] + " exited " + p.exitValue());
      }
      return Files.readString(out);
    } catch (IOException e) {
      throw new IllegalStateException(command[0] + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted: " + command[0], e);
    } finally {
      if (out != null) {
        try {
          Files.deleteIfExists(out);
        } catch (IOException ignored) {
          // best effort
        }
      }
    }
  }
}

package io.github.kthoms.perflab.extract;

import io.github.kthoms.perflab.results.Measurement;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Extracts test durations and counts from Surefire XML reports below a checkout. */
public final class SurefireExtractor {
  private SurefireExtractor() {}

  private record Suite(String module, String className, double seconds, long tests) {}

  public static List<Measurement> extract(Path checkoutRoot) {
    List<Path> reports;
    try (Stream<Path> walk = Files.walk(checkoutRoot)) {
      reports = walk.filter(p -> isReport(checkoutRoot, p)).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    Map<String, double[]> perModule = new LinkedHashMap<>(); // [seconds, tests]
    List<Measurement> out = new ArrayList<>();
    for (Path report : reports) {
      Suite suite = parse(checkoutRoot, report);
      if (suite == null) {
        continue;
      }
      out.add(
          new Measurement(
              "test.class.duration",
              suite.seconds(),
              "s",
              Map.of("module", suite.module(), "class", suite.className())));
      double[] acc = perModule.computeIfAbsent(suite.module(), k -> new double[2]);
      acc[0] += suite.seconds();
      acc[1] += suite.tests();
    }
    perModule.forEach(
        (module, acc) -> {
          out.add(new Measurement("test.module.duration", acc[0], "s", Map.of("module", module)));
          out.add(new Measurement("test.module.count", acc[1], "count", Map.of("module", module)));
        });
    return out;
  }

  private static boolean isReport(Path root, Path p) {
    String name = p.getFileName().toString();
    if (!Files.isRegularFile(p) || !name.startsWith("TEST-") || !name.endsWith(".xml")) {
      return false;
    }
    Path rel = root.relativize(p);
    int n = rel.getNameCount();
    if (n < 3
        || !rel.getName(n - 2).toString().equals("surefire-reports")
        || !rel.getName(n - 3).toString().equals("target")) {
      return false;
    }
    for (Path part : rel) {
      if (part.toString().equals("node_modules")) {
        return false;
      }
    }
    return true;
  }

  private static String moduleOf(Path root, Path report) {
    Path rel = root.relativize(report);
    int n = rel.getNameCount();
    if (n == 3) {
      return ".";
    }
    return rel.subpath(0, n - 3).toString().replace('\\', '/');
  }

  private static Suite parse(Path root, Path report) {
    XMLInputFactory f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    try (InputStream in = Files.newInputStream(report)) {
      XMLStreamReader r = f.createXMLStreamReader(in);
      try {
        while (r.hasNext()) {
          if (r.next() == XMLStreamConstants.START_ELEMENT) {
            if (!r.getLocalName().equals("testsuite")) {
              return null;
            }
            String name = r.getAttributeValue(null, "name");
            String time = r.getAttributeValue(null, "time");
            String tests = r.getAttributeValue(null, "tests");
            if (name == null || time == null) {
              return null;
            }
            double seconds = Double.parseDouble(time.replace(",", "").trim());
            long count = tests == null ? 0 : Long.parseLong(tests.replace(",", "").trim());
            return new Suite(moduleOf(root, report), name, seconds, count);
          }
        }
        return null;
      } finally {
        r.close();
      }
    } catch (XMLStreamException | NumberFormatException e) {
      return null; // unreadable report: skip
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

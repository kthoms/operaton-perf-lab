package io.github.kthoms.perflab.env;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Environment description of a run. Only {@code stable} entries define the segment; {@code
 * volatileInfo} (load, idle time) is stored for diagnosis but never affects comparability.
 */
public record Fingerprint(Map<String, String> stable, Map<String, String> volatileInfo) {

  /** First 12 hex chars of SHA-256 over the sorted stable entries. */
  public String segment() {
    StringBuilder sb = new StringBuilder();
    new TreeMap<>(stable)
        .forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).substring(0, 12);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

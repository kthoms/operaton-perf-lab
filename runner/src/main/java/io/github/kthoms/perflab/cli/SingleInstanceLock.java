package io.github.kthoms.perflab.cli;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.ByteBuffer;

/** Exclusive advisory file lock; the OS releases it when the process dies. */
final class SingleInstanceLock implements AutoCloseable {

  static final class AlreadyRunningException extends RuntimeException {
    AlreadyRunningException(Path file) {
      super("another perflab daemon is already running (lock held on " + file + ")");
    }
  }

  private final FileChannel channel;
  private final FileLock lock;

  private SingleInstanceLock(FileChannel channel, FileLock lock) {
    this.channel = channel;
    this.lock = lock;
  }

  /** @throws AlreadyRunningException if the lock is held by another process (or this JVM) */
  static SingleInstanceLock acquire(Path file) throws IOException {
    Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    FileChannel channel =
        FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock lock;
    try {
      lock = channel.tryLock();
    } catch (OverlappingFileLockException e) {
      lock = null;
    } catch (IOException | RuntimeException e) {
      channel.close();
      throw e;
    }
    if (lock == null) {
      channel.close();
      throw new AlreadyRunningException(file);
    }
    channel.truncate(0);
    channel.write(
        ByteBuffer.wrap((ProcessHandle.current().pid() + "\n").getBytes(StandardCharsets.UTF_8)), 0);
    return new SingleInstanceLock(channel, lock);
  }

  @Override
  public void close() throws IOException {
    try {
      lock.release();
    } finally {
      channel.close();
    }
  }
}

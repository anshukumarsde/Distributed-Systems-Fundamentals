import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class CounterStore {
    @FunctionalInterface
    private interface IOOperation<T> {
        T run() throws IOException;
    }

    private static final Map<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path statePath;
    private final Path lockPath;

    public CounterStore(Path statePath) {
        this.statePath = statePath.toAbsolutePath().normalize();
        this.lockPath = this.statePath.resolveSibling(this.statePath.getFileName() + ".lock");
    }

    public CounterState read() throws IOException {
        return withLock(this::readUnlocked);
    }

    public CounterState increment() throws IOException {
        return withLock(() -> {
            CounterState current = readUnlocked();
            CounterState updated = new CounterState(current.value() + 1, current.version() + 1);
            writeUnlocked(updated);
            return updated;
        });
    }

    public CounterState reset() throws IOException {
        return withLock(() -> {
            CounterState current = readUnlocked();
            CounterState updated = new CounterState(0, current.version() + 1);
            writeUnlocked(updated);
            return updated;
        });
    }

    CounterState unsafeWriteValue(long staleValue) throws IOException {
        return withLock(() -> {
            CounterState current = readUnlocked();
            CounterState updated = new CounterState(staleValue, current.version() + 1);
            writeUnlocked(updated);
            return updated;
        });
    }

    private CounterState withLock(IOOperation<CounterState> operation) throws IOException {
        Path parent = statePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        jvmLock.lock();
        try (FileChannel channel = FileChannel.open(
                lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return operation.run();
        } finally {
            jvmLock.unlock();
        }
    }

    private CounterState readUnlocked() throws IOException {
        if (!Files.exists(statePath)) {
            return new CounterState(0, 0);
        }
        String[] fields = Files.readString(statePath, StandardCharsets.UTF_8).strip().split("\\R");
        if (fields.length != 2) {
            throw new IOException("Invalid counter state file: expected value and version");
        }
        try {
            return new CounterState(Long.parseLong(fields[0]), Long.parseLong(fields[1]));
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid counter state file: value and version must be integers", exception);
        }
    }

    private void writeUnlocked(CounterState state) throws IOException {
        Files.writeString(
                statePath,
                state.value() + "\n" + state.version() + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }
}

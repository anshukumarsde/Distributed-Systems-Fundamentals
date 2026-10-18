import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class RaceConditionDemo {
    private static final int WORKERS = 8;

    private RaceConditionDemo() {}

    public static Map<String, Object> run(CounterStore store) throws Exception {
        CountDownLatch ready = new CountDownLatch(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(WORKERS);
        CounterState before = store.read();
        try {
            List<Future<CounterState>> futures = new ArrayList<>();
            for (int i = 0; i < WORKERS; i++) {
                futures.add(workers.submit(() -> {
                    long staleValue = store.read().value();
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start race demo");
                    }
                    return store.unsafeWriteValue(staleValue + 1);
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for race demo workers");
            }
            start.countDown();
            for (Future<CounterState> future : futures) {
                future.get();
            }
        } catch (ExecutionException exception) {
            throw new IOException("Race demo worker failed", exception.getCause());
        } finally {
            start.countDown();
            workers.shutdownNow();
        }

        CounterState after = store.read();
        long actualIncrease = after.value() - before.value();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requested", WORKERS);
        result.put("actual_increase", actualIncrease);
        result.put("lost_updates", WORKERS - actualIncrease);
        result.put("value", after.value());
        result.put("version", after.version());
        return result;
    }
}

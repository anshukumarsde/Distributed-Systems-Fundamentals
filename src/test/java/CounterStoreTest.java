import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class CounterStoreTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("counter-store-test");
        try {
            CounterStore store = new CounterStore(directory.resolve("state.txt"));
            ExecutorService workers = Executors.newFixedThreadPool(16);
            try {
                List<Future<CounterState>> futures = new ArrayList<>();
                for (int i = 0; i < 64; i++) {
                    futures.add(workers.submit(store::increment));
                }
                for (Future<CounterState> future : futures) {
                    future.get();
                }
            } finally {
                workers.shutdownNow();
            }

            CounterState afterIncrements = store.read();
            if (afterIncrements.value() != 64 || afterIncrements.version() != 64) {
                throw new AssertionError("Atomic increments lost: " + afterIncrements);
            }

            var race = RaceConditionDemo.run(store);
            if (!race.get("requested").equals(8)
                    || !race.get("actual_increase").equals(1L)
                    || !race.get("lost_updates").equals(7L)) {
                throw new AssertionError("Race demo did not expose lost updates: " + race);
            }
            verifyTwoServersShareState(directory);
            System.out.println("CounterStore tests passed: atomic increments, multi-server sharing, and lost-update demo.");
        } finally {
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(directory);
        }
    }

    private static void verifyTwoServersShareState(Path directory) throws Exception {
        int firstPort;
        int secondPort;
        try (ServerSocket firstSocket = new ServerSocket(0);
             ServerSocket secondSocket = new ServerSocket(0)) {
            firstPort = firstSocket.getLocalPort();
            secondPort = secondSocket.getLocalPort();
        }

        Path statePath = directory.resolve("multi-server-state.txt");
        Process first = startServer(directory, statePath, firstPort, "test-server-one");
        Process second = startServer(directory, statePath, secondPort, "test-server-two");
        try {
            awaitServer(first, firstPort);
            awaitServer(second, secondPort);

            HttpClient client = HttpClient.newHttpClient();
            String page = send(client, firstPort, "/", "GET");
            if (!page.contains("Distributed Counter Lab")) {
                throw new AssertionError("Java server did not serve the demo page");
            }
            send(client, firstPort, "/api/increment", "POST");
            String state = send(client, secondPort, "/api/state", "GET");
            if (!state.contains("\"value\":1") || !state.contains("\"server_id\":\"test-server-two\"")) {
                throw new AssertionError("Second Java server did not read shared state: " + state);
            }

            ExecutorService apiWorkers = Executors.newFixedThreadPool(8);
            try {
                List<Future<String>> increments = new ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    int targetPort = i % 2 == 0 ? firstPort : secondPort;
                    increments.add(apiWorkers.submit(() -> send(client, targetPort, "/api/increment", "POST")));
                }
                for (Future<String> increment : increments) {
                    increment.get();
                }
            } finally {
                apiWorkers.shutdownNow();
            }
            state = send(client, secondPort, "/api/state", "GET");
            if (!state.contains("\"value\":33") || !state.contains("\"version\":33")) {
                throw new AssertionError("Concurrent cross-server increments were lost: " + state);
            }

            String race = send(client, secondPort, "/api/race-demo", "POST");
            if (!race.contains("\"actual_increase\":1") || !race.contains("\"lost_updates\":7")) {
                throw new AssertionError("HTTP race demo response was unexpected: " + race);
            }
        } finally {
            first.destroy();
            second.destroy();
            if (!first.waitFor(5, TimeUnit.SECONDS)) {
                first.destroyForcibly();
            }
            if (!second.waitFor(5, TimeUnit.SECONDS)) {
                second.destroyForcibly();
            }
        }
    }

    private static Process startServer(Path directory, Path statePath, int port, String serverId)
            throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path log = directory.resolve(serverId + ".log");
        ProcessBuilder builder = new ProcessBuilder(
                java.toString(),
                "--add-modules",
                "jdk.httpserver",
                "-cp",
                System.getProperty("java.class.path"),
                "DistributedCounterServer");
        builder.directory(directory.toFile());
        builder.environment().put("STATE_PATH", statePath.toString());
        builder.environment().put("PORT", Integer.toString(port));
        builder.environment().put("APP_SERVER_ID", serverId);
        builder.environment().put("PAGE_PATH", Path.of("index.html").toAbsolutePath().toString());
        return builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static void awaitServer(Process process, int port) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        IOException lastError = null;
        while (System.nanoTime() < deadline && process.isAlive()) {
            try {
                send(client, port, "/api/state", "GET");
                return;
            } catch (IOException exception) {
                lastError = exception;
                Thread.sleep(50);
            }
        }
        throw new AssertionError("Java server did not start on port " + port, lastError);
    }

    private static String send(HttpClient client, int port, String path, String method) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(3));
        if (method.equals("POST")) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.GET();
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new AssertionError("Unexpected HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }
}

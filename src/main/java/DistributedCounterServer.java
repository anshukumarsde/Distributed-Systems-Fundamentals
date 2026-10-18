import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DistributedCounterServer {
    private DistributedCounterServer() {}

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8000"));
        String serverId = System.getenv().getOrDefault("APP_SERVER_ID", "server-" + port);
        Path statePath = Path.of(System.getenv().getOrDefault("STATE_PATH", "distributed_state.txt"));
        Path pagePath = Path.of(System.getenv().getOrDefault("PAGE_PATH", "index.html"));

        CounterStore store = new CounterStore(statePath);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        ExecutorService requests = Executors.newCachedThreadPool();
        server.setExecutor(requests);
        server.createContext("/", new CounterHttpHandler(store, serverId, pagePath));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            requests.shutdown();
        }));
        server.start();

        System.out.println("Server " + serverId + " listening on http://localhost:" + port);
        System.out.println("Shared system of record: " + statePath.toAbsolutePath().normalize());
    }
}

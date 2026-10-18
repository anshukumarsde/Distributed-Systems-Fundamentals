import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CounterHttpHandler implements HttpHandler {
    private final CounterStore store;
    private final String serverId;
    private final Path pagePath;

    public CounterHttpHandler(CounterStore store, String serverId, Path pagePath) {
        this.store = store;
        this.serverId = serverId;
        this.pagePath = pagePath;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (method.equals("GET") && path.equals("/api/state")) {
                sendState(exchange, store.read());
            } else if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                sendPage(exchange);
            } else if (method.equals("POST") && path.equals("/api/increment")) {
                sendState(exchange, store.increment());
            } else if (method.equals("POST") && path.equals("/api/reset")) {
                sendState(exchange, store.reset());
            } else if (method.equals("POST") && path.equals("/api/race-demo")) {
                Map<String, Object> result = new LinkedHashMap<>(RaceConditionDemo.run(store));
                result.put("server_id", serverId);
                JsonResponses.send(exchange, 200, result);
            } else {
                JsonResponses.send(exchange, 404, Map.of("error", "Not found"));
            }
        } catch (Exception exception) {
            System.err.println("[" + serverId + "] Request failed: " + exception.getMessage());
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            JsonResponses.send(exchange, 500, Map.of("error", "Request failed; check the server log"));
        } finally {
            exchange.close();
        }
    }

    private void sendState(HttpExchange exchange, CounterState state) throws IOException {
        JsonResponses.send(exchange, 200, Map.of(
                "value", state.value(),
                "version", state.version(),
                "server_id", serverId));
    }

    private void sendPage(HttpExchange exchange) throws IOException {
        byte[] page = Files.readAllBytes(pagePath);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, page.length);
        exchange.getResponseBody().write(page);
    }
}

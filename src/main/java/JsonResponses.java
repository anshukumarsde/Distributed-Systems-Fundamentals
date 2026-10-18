import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class JsonResponses {
    private JsonResponses() {}

    public static void send(HttpExchange exchange, int status, Map<String, ?> values) throws IOException {
        byte[] body = toJson(values).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static String toJson(Map<String, ?> values) {
        StringBuilder body = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!first) {
                body.append(',');
            }
            first = false;
            body.append('"').append(escape(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean) {
                body.append(value);
            } else {
                body.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        return body.append('}').toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}

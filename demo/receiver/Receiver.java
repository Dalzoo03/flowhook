import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Receiver implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;

    private Receiver(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    static Receiver start(int port, int delayMillis, PrintStream log) throws IOException {
        if (port < 0 || port > 65535 || delayMillis < 0 || delayMillis > 30000) {
            throw new IllegalArgumentException("포트는 0~65535, 지연은 0~30000ms 범위여야 합니다.");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        // shortcut: 로컬 소량 요청 실습용 풀이다. 부하 실험 전에는 유한 대기열과 거절 정책을 적용한다.
        ExecutorService executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                receive(exchange, delayMillis, log);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return new Receiver(server, executor);
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 2) throw new IllegalArgumentException("사용법: Receiver [port=9090] [delayMillis=8000]");
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9090;
        int delay = args.length > 1 ? Integer.parseInt(args[1]) : 8000;
        Receiver receiver = start(port, delay, System.out);
        Runtime.getRuntime().addShutdownHook(new Thread(receiver::close));
        System.out.println("Receiver started: http://127.0.0.1:" + receiver.port() + "/webhooks (delay=" + delay + "ms)");
    }

    private static void receive(HttpExchange exchange, int delayMillis, PrintStream log) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/webhooks")) {
            respond(exchange, 404, "{\"error\":\"not_found\"}");
            return;
        }
        if (!exchange.getRequestMethod().equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "POST");
            respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String query = exchange.getRequestURI().getRawQuery();
        String mode = query == null ? "success" : switch (query) {
            case "mode=success" -> "success";
            case "mode=failure" -> "failure";
            case "mode=delay" -> "delay";
            default -> null;
        };
        if (mode == null) {
            respond(exchange, 400, "{\"error\":\"invalid_mode\"}");
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            respond(exchange, 415, "{\"error\":\"application_json_required\"}");
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(65537);
        if (body.length > 65536) {
            respond(exchange, 413, "{\"error\":\"body_too_large\"}");
            return;
        }
        log.println("received mode=" + mode + " bytes=" + body.length
                + " Content-Type=" + safe(contentType, 128)
                + " X-FlowHook-Delivery-Id=" + safe(exchange.getRequestHeaders().getFirst("X-FlowHook-Delivery-Id"), 128)
                + " body=" + safe(new String(body, StandardCharsets.UTF_8), 4096));
        if (mode.equals("delay")) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                respond(exchange, 503, "{\"error\":\"interrupted\"}");
                return;
            }
        }
        respond(exchange, mode.equals("failure") ? 500 : 200, "{\"mode\":\"" + mode + "\"}");
    }

    private static String safe(String value, int limit) {
        if (value == null) return "-";
        String clipped = value.length() > limit ? value.substring(0, limit) + "...[truncated]" : value;
        return clipped.replace("\r", "\\r").replace("\n", "\\n").replace("\u001b", "\\u001b");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}

package cl.usach.lsg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adaptador de recompensas para el cliente de escritorio LSG (overlay de E. Rodríguez).
 *
 * Implementa el contrato "LSG Game Adapter API 1.0.0": un servidor HTTP local en
 * 127.0.0.1:7878 que el cliente usa después de cobrar un canje en LifeSync-Games.
 *
 *   GET  /health  → 200 {"status":"ok","game_ready":true|false}
 *   POST /reward  {"reward_id": n}
 *                 → 200 {"ok":true}
 *                 → 400 cuerpo vacío, JSON inválido o reward_id no entero positivo
 *                 → 422 {"ok":false,"error":"unknown_reward"|"game_not_ready",...}
 *                 → 503 {"error":"game_not_ready",...}   (no hay partida lista)
 *                 → 504 {"error":"application_timeout",...}  (Lua no confirmó a tiempo)
 *
 * Las recompensas se aplican en Lua, en el hilo del juego: este servidor solo las deja
 * en una cola. Lua la vacía en cada tick (lsgModRewardNext) y confirma el resultado
 * (lsgModRewardDone). El hilo HTTP espera esa confirmación hasta APPLY_TIMEOUT_MS.
 *
 * Se usa un ServerSocket (módulo java.base) y no com.sun.net.httpserver, porque el
 * runtime de Java incluido en el juego podría no traer el módulo jdk.httpserver.
 */
final class LsgAdapter {

    static final int DEFAULT_PORT = 7878;
    static final long APPLY_TIMEOUT_MS = 5_000;
    /** Si Lua deja de enviar latidos (menú principal, juego cerrado), la partida no está lista. */
    static final long HEARTBEAT_TIMEOUT_MS = 3_000;
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 5_000;

    /** Una recompensa recibida, esperando que Lua la aplique. */
    static final class Pending {
        final long ticket;
        final int rewardId;
        final CompletableFuture<String> outcome = new CompletableFuture<>();
        Pending(long ticket, int rewardId) { this.ticket = ticket; this.rewardId = rewardId; }
    }

    private static final LinkedBlockingQueue<Pending> QUEUE = new LinkedBlockingQueue<>();
    private static final Map<Long, Pending> IN_PROGRESS = new ConcurrentHashMap<>();
    private static final Set<Integer> SUPPORTED = ConcurrentHashMap.newKeySet();
    private static final AtomicLong TICKETS = new AtomicLong();

    private static volatile boolean playerReady = false;
    private static volatile long lastHeartbeat = 0;
    private static ServerSocket server;
    private static ExecutorService handlers;
    private static int port = -1;

    private LsgAdapter() {}

    // ------------------------------------------------------------------ ciclo de vida

    /**
     * Inicia el servidor (una sola vez por sesión del juego) y registra las recompensas
     * que el catálogo de Lua sabe aplicar. Llamarlo de nuevo solo actualiza el catálogo.
     *
     * @param rewardIds ids separados por coma, p. ej. "101,102,201"
     * @return "OK|<puerto>" o "ERROR|PORT_IN_USE|<mensaje>"
     */
    static synchronized String start(int requestedPort, String rewardIds) {
        SUPPORTED.clear();
        if (rewardIds != null) {
            for (String part : rewardIds.split(",")) {
                try {
                    int id = Integer.parseInt(part.trim());
                    if (id > 0) SUPPORTED.add(id);
                } catch (NumberFormatException ignored) { }
            }
        }
        if (server != null && !server.isClosed()) return "OK|" + port;
        try {
            ServerSocket s = new ServerSocket(requestedPort, 16, InetAddress.getLoopbackAddress());
            server = s;
            port = s.getLocalPort();
            handlers = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "lsg-adapter-http");
                t.setDaemon(true);
                return t;
            });
            Thread accept = new Thread(() -> acceptLoop(s), "lsg-adapter-accept");
            accept.setDaemon(true);            // no impide cerrar el juego
            accept.start();
            log("Adaptador de recompensas escuchando en http://127.0.0.1:" + port
                    + " (" + SUPPORTED.size() + " recompensas)");
            return "OK|" + port;
        } catch (IOException e) {
            server = null;
            log("No se pudo abrir el puerto " + requestedPort + ": " + e.getMessage());
            return "ERROR|PORT_IN_USE|" + e.getMessage();
        }
    }

    /** Solo para pruebas: cierra el servidor y limpia el estado. */
    static synchronized void stop() {
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        server = null;
        if (handlers != null) handlers.shutdownNow();
        handlers = null;
        QUEUE.clear();
        IN_PROGRESS.clear();
        playerReady = false;
        lastHeartbeat = 0;
    }

    static int port() { return port; }

    // --------------------------------------------------------------- interfaz con Lua

    /** Lua lo llama en cada tick: indica si hay un personaje vivo en una partida cargada. */
    static void heartbeat(boolean ready) {
        playerReady = ready;
        lastHeartbeat = System.currentTimeMillis();
    }

    static boolean gameReady() {
        return playerReady && System.currentTimeMillis() - lastHeartbeat < HEARTBEAT_TIMEOUT_MS;
    }

    /** Siguiente recompensa por aplicar: "" si no hay, o "<ticket>|<reward_id>". */
    static String next() {
        Pending p = QUEUE.poll();
        if (p == null) return "";
        IN_PROGRESS.put(p.ticket, p);
        return p.ticket + "|" + p.rewardId;
    }

    /**
     * Resultado de aplicar una recompensa, informado por Lua.
     * @param result "OK", "UNKNOWN_REWARD|<detalle>", "GAME_NOT_READY|<detalle>" o "FAILED|<detalle>"
     */
    static void done(long ticket, String result) {
        Pending p = IN_PROGRESS.remove(ticket);
        if (p != null) p.outcome.complete(result == null ? "FAILED|sin resultado" : result);
    }

    // ------------------------------------------------------------------------- HTTP

    private static void acceptLoop(ServerSocket s) {
        while (!s.isClosed()) {
            try {
                Socket client = s.accept();
                ExecutorService h = handlers;
                if (h == null) { client.close(); continue; }
                h.submit(() -> handle(client));
            } catch (IOException e) {
                if (!s.isClosed()) log("Error aceptando conexión: " + e.getMessage());
            }
        }
    }

    private static void handle(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(SOCKET_TIMEOUT_MS);
            Request req = Request.read(c.getInputStream());
            Response res = req == null
                    ? new Response(400, "{\"error\":\"Bad Request\"}")
                    : route(req);
            res.write(c.getOutputStream());
        } catch (SocketTimeoutException ignored) {
            // Cliente lento o conexión abierta sin petición: se descarta.
        } catch (Exception e) {
            log("Error atendiendo petición: " + e);
        }
    }

    static Response route(Request req) {
        String path = req.path;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);

        if (path.equals("/health")) {
            if (!req.method.equals("GET")) return methodNotAllowed();
            return new Response(200, "{\"status\":\"ok\",\"game_ready\":" + gameReady() + "}");
        }
        if (path.equals("/reward")) {
            if (!req.method.equals("POST")) return methodNotAllowed();
            return reward(req.body);
        }
        return new Response(404, "{\"error\":\"Not Found\"}");
    }

    private static Response reward(String body) {
        if (body == null || body.trim().isEmpty()) {
            return new Response(400, "{\"error\":\"Empty body\"}");
        }
        Map<String, Object> json;
        try {
            json = MiniJson.obj(MiniJson.parse(body));
        } catch (RuntimeException e) {
            return new Response(400, "{\"error\":\"Invalid JSON\"}");
        }
        Integer rewardId = positiveInt(json == null ? null : json.get("reward_id"));
        if (rewardId == null) {
            return new Response(400, "{\"error\":\"'reward_id' must be a positive integer\"}");
        }
        if (!gameReady()) {
            return new Response(503, "{\"error\":\"game_not_ready\",\"summary\":\"The game is not ready to apply rewards.\"}");
        }
        if (!SUPPORTED.contains(rewardId)) {
            log("Recompensa desconocida: " + rewardId);
            return unknownReward();
        }

        Pending p = new Pending(TICKETS.incrementAndGet(), rewardId);
        QUEUE.add(p);
        log("Recompensa " + rewardId + " recibida (ticket " + p.ticket + ")");
        String outcome;
        try {
            outcome = p.outcome.get(APPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Los puntos ya se cobraron en LSG: la recompensa queda en la cola y se aplica
            // apenas el juego vuelva a procesar ticks, aunque el cliente vea un 504.
            log("Recompensa " + rewardId + " sin confirmar en " + APPLY_TIMEOUT_MS + " ms; queda pendiente");
            return new Response(504, "{\"error\":\"application_timeout\",\"summary\":\"Could not confirm reward result.\"}");
        } catch (Exception e) {
            return internalError(e.toString());
        }

        String code = outcome.split("\\|", 2)[0];
        String detail = outcome.contains("|") ? outcome.split("\\|", 2)[1] : "";
        switch (code) {
            case "OK":
                log("Recompensa " + rewardId + " aplicada");
                return new Response(200, "{\"ok\":true}");
            case "UNKNOWN_REWARD":
                log("Recompensa " + rewardId + " no aplicable: " + detail);
                return unknownReward();
            case "GAME_NOT_READY":
                return new Response(422, "{\"ok\":false,\"error\":\"game_not_ready\",\"summary\":\"No game is ready to apply rewards.\"}");
            default:
                log("Recompensa " + rewardId + " falló: " + detail);
                return internalError(detail);
        }
    }

    private static Response unknownReward() {
        return new Response(422, "{\"ok\":false,\"error\":\"unknown_reward\",\"summary\":\"Reward is not supported.\"}");
    }

    private static Response internalError(String detail) {
        return new Response(500, "{\"error\":\"Internal Server Error\",\"summary\":\"" + jsonEscape(detail) + "\"}");
    }

    private static Response methodNotAllowed() {
        return new Response(405, "{\"error\":\"Method Not Allowed\"}");
    }

    /** reward_id válido: número entero mayor que cero (acepta 5 y 5.0, rechaza 5.5, "5", true). */
    private static Integer positiveInt(Object v) {
        if (!(v instanceof Number)) return null;
        double d = ((Number) v).doubleValue();
        if (d < 1 || d > Integer.MAX_VALUE || d != Math.floor(d)) return null;
        return (int) d;
    }

    static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
                    else b.append(ch);
            }
        }
        return b.toString();
    }

    private static void log(String msg) {
        System.out.println("[LSGMod] " + msg);
    }

    // ---------------------------------------------------------- HTTP/1.1 mínimo

    static final class Request {
        final String method, path, body;

        Request(String method, String path, String body) {
            this.method = method; this.path = path; this.body = body;
        }

        /** Lee línea de petición, encabezados y cuerpo (Content-Length). null si es inválida. */
        static Request read(InputStream in) throws IOException {
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return null;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return null;
            int length = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals("content-length")) {
                    try { length = Integer.parseInt(line.substring(colon + 1).trim()); }
                    catch (NumberFormatException e) { return null; }
                }
            }
            if (length < 0 || length > MAX_BODY_BYTES) return null;
            byte[] body = in.readNBytes(length);
            return new Request(parts[0].toUpperCase(Locale.ROOT), parts[1],
                    new String(body, StandardCharsets.UTF_8));
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int ch;
            while ((ch = in.read()) != -1) {
                if (ch == '\n') break;
                if (ch != '\r') b.write(ch);
                if (b.size() > 8192) throw new IOException("línea demasiado larga");
            }
            if (ch == -1 && b.size() == 0) return null;
            return b.toString(StandardCharsets.UTF_8);
        }
    }

    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) { this.status = status; this.body = body; }

        void write(OutputStream out) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
        }

        private static String reason(int s) {
            switch (s) {
                case 200: return "OK";
                case 400: return "Bad Request";
                case 404: return "Not Found";
                case 405: return "Method Not Allowed";
                case 422: return "Unprocessable Entity";
                case 503: return "Service Unavailable";
                case 504: return "Gateway Timeout";
                default: return "Internal Server Error";
            }
        }
    }
}

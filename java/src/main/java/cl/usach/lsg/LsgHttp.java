package cl.usach.lsg;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Peticiones HTTP bloqueantes. Solo deben llamarse desde hilos secundarios
 * (LsgTasks); nunca desde el hilo de Lua.
 */
final class LsgHttp {

    static final class Response {
        final int status;
        final String body;
        Response(int status, String body) { this.status = status; this.body = body; }
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private LsgHttp() {}

    /** Errores del servidor que suelen ser transitorios (p. ej. arranque o reconexión a la BD). */
    private static final int MAX_RETRIES = 2;
    private static final long[] BACKOFF_MS = {1000, 2500};

    static Response get(String url, String bearer) throws LsgException {
        return sendWithRetry(base(url, bearer).GET().build());
    }

    /** Para operaciones seguras de repetir (login, renovación). NUNCA para canjes. */
    static Response postJsonRetryable(String url, String bearer, String json) throws LsgException {
        return sendWithRetry(base(url, bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "{}" : json, StandardCharsets.UTF_8))
                .build());
    }

    static Response postJson(String url, String bearer, String json) throws LsgException {
        return send(base(url, bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "{}" : json, StandardCharsets.UTF_8))
                .build());
    }

    static Response postForm(String url, Map<String, String> form) throws LsgException {
        StringJoiner body = new StringJoiner("&");
        for (Map.Entry<String, String> e : form.entrySet()) {
            body.add(enc(e.getKey()) + "=" + enc(e.getValue()));
        }
        return sendWithRetry(base(url, null)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build());
    }

    private static HttpRequest.Builder base(String url, String bearer) throws LsgException {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json");
            if (bearer != null) b.header("Authorization", "Bearer " + bearer);
            return b;
        } catch (IllegalArgumentException e) {
            throw new LsgException("CONFIG", "URL inválida: " + url);
        }
    }

    /**
     * Reintenta ante fallos de red o respuestas 5xx, con espera creciente.
     * Solo se usa en operaciones idempotentes: repetirlas no tiene efectos secundarios.
     */
    private static Response sendWithRetry(HttpRequest req) throws LsgException {
        for (int attempt = 0; ; attempt++) {
            try {
                Response r = send(req);
                if (r.status < 500 || attempt >= MAX_RETRIES) return r;
                System.out.println("[LSGMod] HTTP " + r.status + " en " + req.uri().getPath() + "; reintento " + (attempt + 1));
            } catch (LsgException e) {
                if (!"NETWORK".equals(e.code()) || attempt >= MAX_RETRIES) throw e;
                System.out.println("[LSGMod] Sin red en " + req.uri().getPath() + "; reintento " + (attempt + 1));
            }
            try {
                Thread.sleep(BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)]);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new LsgException("NETWORK", "Petición interrumpida");
            }
        }
    }

    private static Response send(HttpRequest req) throws LsgException {
        try {
            HttpResponse<String> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(r.statusCode(), r.body());
        } catch (IOException e) {
            throw new LsgException("NETWORK", "Sin conexión con LSG (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LsgException("NETWORK", "Petición interrumpida");
        }
    }

    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
}

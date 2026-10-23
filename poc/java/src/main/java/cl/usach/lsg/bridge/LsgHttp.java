package cl.usach.lsg.bridge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Peticiones HTTP asíncronas para el puente LSG.
 *
 * Lua corre en el hilo principal del juego y no puede esperar una respuesta de red
 * sin congelar el renderizado. Por eso el modelo es "iniciar y consultar":
 *   1. start(...) lanza la petición en segundo plano y devuelve un id de inmediato.
 *   2. Lua llama poll(id) en cada tick hasta que el resultado deja de ser PENDING.
 *
 * Esta clase no depende del juego: se puede probar fuera de Project Zomboid.
 */
public final class LsgHttp {

    public static final String PENDING = "PENDING";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Map<String, String> RESULTS = new ConcurrentHashMap<>();
    private static final AtomicLong COUNTER = new AtomicLong();

    private LsgHttp() {}

    /** Inicia una petición GET. Devuelve el id para consultar el resultado. */
    public static String startGet(String url, String bearerToken) {
        String id = "req-" + COUNTER.incrementAndGet();
        RESULTS.put(id, PENDING);
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET();
            if (bearerToken != null && !bearerToken.isEmpty()) {
                b.header("Authorization", "Bearer " + bearerToken);
            }
            CLIENT.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                    .thenAccept(resp -> RESULTS.put(id, "OK|" + resp.statusCode() + "|" + resp.body()))
                    .exceptionally(err -> {
                        Throwable cause = err.getCause() != null ? err.getCause() : err;
                        String msg = cause.getMessage() != null ? cause.getMessage() : "sin detalle (¿servidor caído o sin red?)";
                        RESULTS.put(id, "ERROR|" + cause.getClass().getSimpleName() + ": " + msg);
                        return null;
                    });
        } catch (Exception e) {   // URL mal formada, etc.
            RESULTS.put(id, "ERROR|" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return id;
    }

    /**
     * Devuelve PENDING, "OK|<código HTTP>|<cuerpo>" o "ERROR|<mensaje>".
     * Un resultado final se entrega una sola vez y luego se libera.
     */
    public static String poll(String id) {
        String r = RESULTS.get(id);
        if (r == null) return "ERROR|id desconocido: " + id;
        if (!PENDING.equals(r)) RESULTS.remove(id);
        return r;
    }

    /** Cantidad de peticiones en curso (útil para diagnóstico). */
    public static int inFlight() {
        int n = 0;
        for (String v : RESULTS.values()) if (PENDING.equals(v)) n++;
        return n;
    }
}

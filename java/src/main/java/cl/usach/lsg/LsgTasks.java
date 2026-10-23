package cl.usach.lsg;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Modelo "iniciar y consultar": cada operación corre en un hilo secundario y Lua
 * consulta su resultado con poll(id) desde el hilo principal, sin bloquearlo.
 *
 * Resultados: "PENDING", "OK|<datos>" o "ERROR|<CÓDIGO>|<mensaje>".
 */
final class LsgTasks {

    static final String PENDING = "PENDING";

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "lsg-worker");
        t.setDaemon(true);            // no impide cerrar el juego
        return t;
    });
    private static final Map<String, String> RESULTS = new ConcurrentHashMap<>();
    private static final AtomicLong COUNTER = new AtomicLong();

    private LsgTasks() {}

    static String submit(Callable<String> work) {
        String id = "task-" + COUNTER.incrementAndGet();
        RESULTS.put(id, PENDING);
        POOL.submit(() -> {
            String result;
            try {
                result = "OK|" + work.call();
            } catch (LsgException e) {
                result = "ERROR|" + e.code() + "|" + e.getMessage();
            } catch (Exception e) {
                result = "ERROR|INTERNAL|" + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            RESULTS.put(id, result);
        });
        return id;
    }

    static String poll(String id) {
        String r = RESULTS.get(id);
        if (r == null) return "ERROR|INTERNAL|tarea desconocida: " + id;
        if (!PENDING.equals(r)) RESULTS.remove(id);
        return r;
    }
}

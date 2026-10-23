import cl.usach.lsg.bridge.LsgBridge;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Verificación fuera del juego. Levanta un servidor HTTP local con 0,5 s de
 * latencia y simula el bucle de ticks de Lua (~60 por segundo).
 *   java -cp <clases> LocalCheck            → 4 casos locales
 *   java -cp <clases> LocalCheck <baseUrl>  → solo contra esa URL (p. ej. LSG real)
 */
public class LocalCheck {
    static String run(String base) throws Exception {
        String id = LsgBridge.health(base);
        int ticks = 0;
        String r;
        while ((r = LsgBridge.poll(id)).equals("PENDING")) {
            Thread.sleep(16);
            ticks++;
        }
        return "tras " + ticks + " ticks sin bloquear: " + r;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Paso 1 -> " + LsgBridge.ping());
        if (args.length > 0) {
            System.out.println("Paso 2 -> " + run(args[0]));
            System.exit(0);
        }

        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 8765), 0);
        srv.createContext("/", ex -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
            boolean ok = ex.getRequestURI().getPath().equals("/lsg-core-api/health");
            byte[] body = (ok ? "{\"status\":\"ok\"}" : "{\"detail\":\"Not Found\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(ok ? 200 : 404, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        srv.start();
        System.out.println("Caso 1 (responde) -> " + run("http://127.0.0.1:8765/lsg-core-api"));
        System.out.println("Caso 2 (ruta inexistente) -> " + run("http://127.0.0.1:8765/otra"));
        System.out.println("Caso 3 (servidor caído) -> " + run("http://127.0.0.1:9/lsg-core-api"));
        System.out.println("Caso 4 (URL inválida) -> " + run("no es una url"));
        srv.stop(0);
        System.exit(0);
    }
}

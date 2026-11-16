package cl.usach.lsg;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pruebas del adaptador de recompensas (contrato "LSG Game Adapter API 1.0.0").
 *
 * Hace de cliente LSG (overlay) con peticiones HTTP reales, y de bucle de ticks de Lua
 * con un hilo que envía latidos y aplica las recompensas de la cola.
 *
 *   java -cp <clases> cl.usach.lsg.AdapterCheck
 */
public class AdapterCheck {

    static int passed = 0, failed = 0;
    static int port;

    /** Comportamiento del "Lua" simulado para cada reward_id. */
    static final Map<Integer, String> LUA_RESULT = new ConcurrentHashMap<>();
    static volatile boolean luaRunning = true;   // false = el juego no procesa ticks
    static volatile boolean playerAlive = false;

    public static void main(String[] args) throws Exception {
        // Puerto 0 = cualquiera libre (en el juego se usa 7878).
        String start = LsgAdapter.start(0, "101, 102,201,abc,-3");
        port = LsgAdapter.port();
        check("1. Inicio del servidor: " + start, start.startsWith("OK|"));
        check("1b. Iniciar de nuevo no abre otro puerto", LsgAdapter.start(0, "101,102,201").equals("OK|" + port));

        Thread lua = new Thread(AdapterCheck::luaLoop, "lua-simulado");
        lua.setDaemon(true);
        lua.start();

        // --- /health
        Thread.sleep(100);
        Res h = get("/health");
        check("2. /health sin personaje → game_ready false: " + h, h.status == 200
                && h.body.equals("{\"status\":\"ok\",\"game_ready\":false}"));
        check("2b. Respuesta cumple el esquema (status, game_ready)", keys(h.body).equals(Set.of("status", "game_ready")));

        Res r = post("/reward", "{\"reward_id\":101}");
        check("3. /reward sin partida → 503 game_not_ready: " + r, r.status == 503 && r.body.contains("\"game_not_ready\""));

        playerAlive = true;
        Thread.sleep(100);
        h = get("/health");
        check("4. /health con personaje → game_ready true", h.body.contains("\"game_ready\":true"));

        // --- validación del cuerpo (400)
        check("5. Cuerpo vacío → 400 Empty body", is(post("/reward", ""), 400, "{\"error\":\"Empty body\"}"));
        check("5b. JSON inválido → 400 Invalid JSON", is(post("/reward", "{reward_id:1"), 400, "{\"error\":\"Invalid JSON\"}"));
        String badId = "{\"error\":\"'reward_id' must be a positive integer\"}";
        check("5c. reward_id como texto → 400", is(post("/reward", "{\"reward_id\":\"101\"}"), 400, badId));
        check("5d. reward_id = 0 → 400", is(post("/reward", "{\"reward_id\":0}"), 400, badId));
        check("5e. reward_id decimal → 400", is(post("/reward", "{\"reward_id\":1.5}"), 400, badId));
        check("5f. Sin reward_id → 400", is(post("/reward", "{}"), 400, badId));
        check("5g. Arreglo en vez de objeto → 400", is(post("/reward", "[101]"), 400, badId));

        // --- recompensas
        r = post("/reward", "{\"reward_id\":999}");
        check("6. Recompensa no registrada → 422 unknown_reward: " + r, r.status == 422
                && r.body.equals("{\"ok\":false,\"error\":\"unknown_reward\",\"summary\":\"Reward is not supported.\"}"));

        LUA_RESULT.put(101, "OK");
        r = post("/reward", "{\"reward_id\":101}");
        check("7. Recompensa aplicada por Lua → 200 {\"ok\":true}: " + r, is(r, 200, "{\"ok\":true}"));
        check("7b. Respuesta cumple el esquema (solo ok)", keys(r.body).equals(Set.of("ok")));

        LUA_RESULT.put(102, "UNKNOWN_REWARD|objeto inexistente");
        r = post("/reward", "{\"reward_id\":102}");
        check("8. Lua no puede aplicarla → 422 unknown_reward", r.status == 422 && r.body.contains("unknown_reward"));

        LUA_RESULT.put(201, "FAILED|error de prueba");
        r = post("/reward", "{\"reward_id\":201}");
        check("9. Error al aplicar → 500 con detalle: " + r, r.status == 500
                && keys(r.body).equals(Set.of("error", "summary")));

        LUA_RESULT.put(201, "GAME_NOT_READY|murió");
        r = post("/reward", "{\"reward_id\":201}");
        check("9b. Personaje murió al aplicar → 422 game_not_ready", r.status == 422 && r.body.contains("game_not_ready"));

        // --- concurrencia: /health responde mientras una recompensa espera a Lua
        LUA_RESULT.put(101, "SLOW");
        long[] healthMs = new long[1];
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(200);
                long t0 = System.nanoTime();
                get("/health");
                healthMs[0] = (System.nanoTime() - t0) / 1_000_000;
            } catch (Exception ignored) { }
        });
        t.start();
        r = post("/reward", "{\"reward_id\":101}");
        t.join();
        check("10. /health no se bloquea durante un canje (" + healthMs[0] + " ms)", healthMs[0] < 500 && r.status == 200);

        // --- el juego deja de procesar ticks (pausa larga, carga) → 504 y la recompensa queda pendiente
        luaRunning = false;
        LsgAdapter.heartbeat(true);          // último latido justo antes
        LUA_RESULT.put(101, "OK");
        long t0 = System.currentTimeMillis();
        r = post("/reward", "{\"reward_id\":101}");
        long waited = System.currentTimeMillis() - t0;
        check("11. Sin confirmación → 504 application_timeout tras ~5 s (" + waited + " ms)",
                r.status == 504 && r.body.contains("application_timeout") && waited >= 4_900);
        String pending = LsgAdapter.next();
        check("11b. La recompensa sigue en la cola para aplicarla después: " + pending, pending.endsWith("|101"));
        LsgAdapter.done(Long.parseLong(pending.split("\\|")[0]), "OK");

        // --- latido vencido (menú principal o juego cerrado)
        Thread.sleep(LsgAdapter.HEARTBEAT_TIMEOUT_MS + 200);
        h = get("/health");
        check("12. Sin latidos por más de 3 s → game_ready false", h.body.contains("\"game_ready\":false"));

        // --- rutas y métodos
        check("13. Ruta desconocida → 404", get("/otra").status == 404);
        check("13b. GET /reward → 405", get("/reward").status == 405);
        check("13c. /health/ con barra final también responde", get("/health/").status == 200);

        // --- un segundo proceso no puede tomar el mismo puerto
        String second = LsgAdapter.start(port, "1");   // ya iniciado → misma instancia
        java.net.ServerSocket other = null;
        boolean busy;
        try { other = new java.net.ServerSocket(port, 1, java.net.InetAddress.getLoopbackAddress()); busy = false; }
        catch (java.io.IOException e) { busy = true; }
        finally { if (other != null) other.close(); }
        check("14. El puerto queda ocupado por el adaptador (" + second + ")", busy);

        LsgAdapter.stop();
        System.out.println("\nResultado: " + passed + " aprobadas, " + failed + " fallidas");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** Simula LSG_Rewards.lua: latido en cada tick y aplicación de la cola. */
    static void luaLoop() {
        while (true) {
            try {
                Thread.sleep(16);
                if (!luaRunning) continue;
                LsgAdapter.heartbeat(playerAlive);
                String next;
                while (!(next = LsgAdapter.next()).isEmpty()) {
                    String[] p = next.split("\\|");
                    long ticket = Long.parseLong(p[0]);
                    String result = LUA_RESULT.getOrDefault(Integer.parseInt(p[1]), "OK");
                    if (result.equals("SLOW")) { Thread.sleep(1000); result = "OK"; }
                    LsgAdapter.done(ticket, result);
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ utilidades

    static final class Res {
        final int status; final String body;
        Res(int s, String b) { status = s; body = b; }
        public String toString() { return status + " " + body; }
    }

    static Res get(String path) throws Exception { return call("GET", path, null); }
    static Res post(String path, String body) throws Exception { return call("POST", path, body); }

    static Res call(String method, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(2000);
        c.setReadTimeout(10_000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int status = c.getResponseCode();
        InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
        String text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        c.disconnect();
        return new Res(status, text);
    }

    static boolean is(Res r, int status, String body) { return r.status == status && r.body.equals(body); }

    static Set<String> keys(String json) {
        Map<String, Object> m = MiniJson.obj(MiniJson.parse(json));
        return m == null ? Set.of() : new TreeSet<>(m.keySet());
    }

    static void check(String name, boolean ok) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "  OK     " : "  FALLA  ") + name);
    }
}

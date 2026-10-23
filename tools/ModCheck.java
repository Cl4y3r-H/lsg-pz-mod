package cl.usach.lsg;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pruebas fuera del juego con un servidor falso de LSG-Auth y LSG-Core-API.
 * Simula el bucle de ticks de Lua: llama a lsgModPoll cada ~16 ms hasta tener resultado.
 *
 *   java -cp <clases> cl.usach.lsg.ModCheck
 */
public class ModCheck {

    static int passed = 0, failed = 0;
    static final AtomicInteger logins = new AtomicInteger(), refreshes = new AtomicInteger();
    static volatile long loginTokenTtl = 7200;     // vigencia del token que entrega el login falso
    static volatile boolean reject401Once = false;
    static volatile int latencyMs = 300;
    static volatile int fail500 = 0;           // cuántas veces el login falso responde 500

    public static void main(String[] args) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 8766), 0);
        srv.createContext("/lsg-auth/login", ModCheck::login);
        srv.createContext("/lsg-auth/token/refresh", ex -> reply(ex, 200, "{\"access_token\":\"" + jwt(7200) + "\"}"));
        srv.createContext("/lsg-core-api/players/50/points/balance", ModCheck::balance);
        srv.start();

        Path dir = Files.createTempDirectory("lsgmod");
        Path cfg = dir.resolve("LSG").resolve("lsg.properties");
        System.setProperty("lsg.config", cfg.toString());

        // 1. Primera ejecución: no hay configuración → se crea la plantilla.
        check("1. Sin configuración crea plantilla", LsgMod.lsgModStatus().startsWith("CREATED|") && Files.exists(cfg));
        check("1b. Login sin credenciales da ERROR|CONFIG", run(LsgMod.lsgModLogin()).startsWith("ERROR|CONFIG|"));

        // 2. Credenciales incorrectas.
        writeCfg(cfg, "otra-clave");
        check("2. Contraseña incorrecta da ERROR|AUTH", run(LsgMod.lsgModLogin()).startsWith("ERROR|AUTH|"));

        // 3. Credenciales correctas.
        writeCfg(cfg, "secreta");
        String login = run(LsgMod.lsgModLogin());
        check("3. Login correcto devuelve id y roles: " + login, login.equals("OK|50|player"));

        // 4. Saldo con nombres de dimensión desde la configuración.
        String bal = run(LsgMod.lsgModBalance());
        check("4. Saldo: " + bal, bal.equals("OK|Dim 1: 131 | Físico: 114 | Dim 3: 0"));

        // 4b. Formato compacto para la ventana de Lua.
        String data = run(LsgMod.lsgModBalanceData());
        check("4b. Datos para la ventana: " + data, data.equals("OK|1=Dim 1=131;2=Físico=114;3=Dim 3=0"));

        // 5. Token a punto de vencer → se renueva sin nuevo login.
        writeCfg(cfg, "secreta");
        loginTokenTtl = 300;                 // menos que el margen de 10 minutos
        int r0 = refreshes.get(), l0 = logins.get();
        run(LsgMod.lsgModLogin());           // login nuevo con token corto
        run(LsgMod.lsgModBalance());         // debe renovar antes de consultar
        check("5. Token por vencer se renueva (refresh=" + (refreshes.get() - r0) + ", logins=" + (logins.get() - l0) + ")",
                refreshes.get() - r0 == 1 && logins.get() - l0 == 1);
        loginTokenTtl = 7200;

        // 6. La Core API rechaza el token una vez (401) → re-login y reintento transparente.
        reject401Once = true;
        int l1 = logins.get();
        String bal2 = run(LsgMod.lsgModBalance());
        check("6. 401 en Core API → re-login y reintento: " + bal2, bal2.startsWith("OK|") && logins.get() - l1 == 1);

        // 7. Nunca bloquea: con 300 ms de latencia, Lua sigue haciendo ticks.
        String id = LsgMod.lsgModBalance();
        int ticks = 0;
        String res;
        while ((res = LsgMod.lsgModPoll(id)).equals("PENDING")) { Thread.sleep(16); ticks++; }
        check("7. Sin bloqueo: " + ticks + " ticks mientras respondía el servidor", ticks >= 10 && res.startsWith("OK|"));

        // 7b. Error 500 transitorio en el login → reintento automático y éxito.
        writeCfg(cfg, "secreta");
        fail500 = 1;
        String l500 = run(LsgMod.lsgModLogin());
        check("7b. 500 transitorio en login → reintento y éxito: " + l500, l500.equals("OK|50|player") && fail500 == 0);

        // 7c. Error 500 persistente → se informa como HTTP_500 tras los reintentos.
        writeCfg(cfg, "secreta");
        fail500 = 5;
        String p500 = run(LsgMod.lsgModLogin());
        check("7c. 500 persistente → " + p500, p500.startsWith("ERROR|HTTP_500|"));
        fail500 = 0;

        // 8. Servidor caído.
        srv.stop(0);
        writeCfg(cfg, "secreta");            // sesión nueva → obliga a contactar al servidor
        check("8. Servidor caído da ERROR|NETWORK", run(LsgMod.lsgModLogin()).startsWith("ERROR|NETWORK|"));

        System.out.println("\nResultado: " + passed + " aprobadas, " + failed + " fallidas");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------- servidor falso
    static void login(HttpExchange ex) throws IOException {
        String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        sleep();
        if (fail500 > 0) { fail500--; reply(ex, 500, "Internal Server Error"); return; }
        if (!form.contains("password=secreta")) { reply(ex, 401, "{\"detail\":\"Incorrect\"}"); return; }
        logins.incrementAndGet();
        reply(ex, 200, "{\"access_token\":\"" + jwt(loginTokenTtl) + "\",\"token_type\":\"bearer\","
                + "\"expires_in_seconds\":" + loginTokenTtl + ",\"player\":{\"id_players\":50,\"name\":\"test\","
                + "\"email\":\"t@usach.cl\",\"age\":30,\"roles\":[\"player\"]}}");
    }

    static void balance(HttpExchange ex) throws IOException {
        sleep();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) { reply(ex, 401, "{}"); return; }
        if (reject401Once) { reject401Once = false; reply(ex, 401, "{\"detail\":\"expired\"}"); return; }
        reply(ex, 200, "[{\"id_players\":50,\"id_point_dimension\":1,\"balance\":131},"
                + "{\"id_players\":50,\"id_point_dimension\":2,\"balance\":114},"
                + "{\"id_players\":50,\"id_point_dimension\":3,\"balance\":0}]");
    }

    static void reply(HttpExchange ex, int status, String body) throws IOException {
        if (ex.getRequestURI().getPath().endsWith("/refresh")) refreshes.incrementAndGet();
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static String jwt(long ttl) {
        Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
        long exp = System.currentTimeMillis() / 1000 + ttl;
        return e.encodeToString("{\"alg\":\"HS256\"}".getBytes()) + "."
                + e.encodeToString(("{\"sub\":\"50\",\"exp\":" + exp + "}").getBytes()) + ".firma";
    }

    static void sleep() { try { Thread.sleep(latencyMs); } catch (InterruptedException ignored) { } }

    // ---------------------------------------------------------------- utilidades
    static void writeCfg(Path cfg, String password) throws IOException {
        Files.write(cfg, String.join("\n",
                "email=t@usach.cl", "password=" + password,
                "auth_url=http://127.0.0.1:8766/lsg-auth", "core_url=http://127.0.0.1:8766/lsg-core-api",
                "dim.2=Físico", "").getBytes(StandardCharsets.UTF_8));
        LsgMod.reset();
    }

    static String run(String id) throws InterruptedException {
        String r;
        while ((r = LsgMod.lsgModPoll(id)).equals("PENDING")) Thread.sleep(16);
        return r;
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  OK     " : "  FALLA  ") + name);
        if (ok) passed++; else failed++;
    }
}

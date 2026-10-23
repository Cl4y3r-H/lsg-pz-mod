package cl.usach.lsg;

import se.krka.kahlua.integration.annotations.LuaMethod;

/**
 * Funciones expuestas a Lua (globales, la vía validada en la prueba de concepto).
 *
 *   lsgModVersion()   → versión del mod
 *   lsgModStatus()    → estado de la configuración: OK | CREATED|<ruta> | INCOMPLETE|<ruta> | UNREADABLE|<ruta>
 *   lsgModLogin()     → id de tarea; resultado OK|<id_jugador>|<roles>
 *   lsgModBalance()   → id de tarea; resultado OK|<resumen del saldo>
 *   lsgModBalanceData() → id de tarea; resultado OK|<id=nombre=puntos;...> (para la ventana)
 *   lsgModPoll(id)    → PENDING | OK|... | ERROR|<CÓDIGO>|<mensaje>
 *
 * Todas retornan de inmediato: el trabajo de red ocurre en hilos secundarios.
 */
public class LsgMod {

    public static final String VERSION = "0.3.2";

    private static LsgConfig config;
    private static LsgSession session;
    private static LsgClient client;

    private static long configStamp = Long.MIN_VALUE;

    /** Relee la configuración si cambió en disco: el jugador puede corregirla sin reiniciar el juego. */
    private static synchronized void init() {
        long stamp = stamp();
        if (config == null || config.status() != LsgConfig.Status.OK || stamp != configStamp) {
            config = LsgConfig.load();
            session = new LsgSession(config);
            client = new LsgClient(config, session);
            configStamp = stamp();
        }
    }

    private static long stamp() {
        try {
            return java.nio.file.Files.getLastModifiedTime(LsgConfig.defaultPath()).toMillis();
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    @LuaMethod(name = "lsgModVersion", global = true)
    public static String lsgModVersion() { return VERSION; }

    @LuaMethod(name = "lsgModStatus", global = true)
    public static String lsgModStatus() {
        init();
        return config.status() == LsgConfig.Status.OK ? "OK" : config.status() + "|" + config.path();
    }

    @LuaMethod(name = "lsgModLogin", global = true)
    public static String lsgModLogin() {
        init();
        return LsgTasks.submit(() -> session.playerId() + "|" + String.join(",", session.roles()));
    }

    @LuaMethod(name = "lsgModBalance", global = true)
    public static String lsgModBalance() {
        init();
        return LsgTasks.submit(() -> LsgClient.summary(client.balance()));
    }

    @LuaMethod(name = "lsgModBalanceData", global = true)
    public static String lsgModBalanceData() {
        init();
        return LsgTasks.submit(() -> LsgClient.encode(client.balance()));
    }

    @LuaMethod(name = "lsgModPoll", global = true)
    public static String lsgModPoll(String id) { return LsgTasks.poll(id); }

    /** Solo para pruebas: fuerza a releer la configuración. */
    static synchronized void reset() { config = null; }
}

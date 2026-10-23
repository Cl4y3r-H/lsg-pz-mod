package cl.usach.lsg.bridge;

import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.integration.annotations.LuaMethod;

/**
 * Funciones expuestas a Lua para la prueba de concepto.
 *
 * Se exponen de dos formas para aumentar la probabilidad de éxito:
 *   - Como funciones globales:  lsgPing(), lsgHealth(url), lsgPoll(id)
 *   - Como clase:               LsgBridge.ping(), LsgBridge.health(url), LsgBridge.poll(id)
 * El script Lua prueba ambas y reporta cuál funcionó.
 *
 * Los parámetros y retornos son String: es el tipo que Kahlua convierte sin ambigüedad.
 */
@Exposer.LuaClass
public class LsgBridge {

    public static final String VERSION = "0.1.0";

    // ---------------------------------------------------------- Paso 1: invocación
    @LuaMethod(name = "lsgPing", global = true)
    public static String lsgPing() {
        return ping();
    }

    public static String ping() {
        return "pong desde Java " + System.getProperty("java.version") + " (LSGBridgePoC " + VERSION + ")";
    }

    // ---------------------------------------------------------- Paso 2: red asíncrona
    @LuaMethod(name = "lsgHealth", global = true)
    public static String lsgHealth(String baseUrl) {
        return health(baseUrl);
    }

    public static String health(String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return LsgHttp.startGet(base + "/health", null);
    }

    @LuaMethod(name = "lsgPoll", global = true)
    public static String lsgPoll(String id) {
        return poll(id);
    }

    public static String poll(String id) {
        return LsgHttp.poll(id);
    }
}

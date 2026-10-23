package cl.usach.lsg;

/** Punto de entrada que ZombieBuddy ejecuta al cargar el JAR del mod. */
public class Main {
    public static void main(String[] args) {
        System.out.println("[LSGMod] Capa Java " + LsgMod.VERSION + " cargada. Java "
                + System.getProperty("java.version") + ". Configuración: " + LsgConfig.defaultPath());
    }
}

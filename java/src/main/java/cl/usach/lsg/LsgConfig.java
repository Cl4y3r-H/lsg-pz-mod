package cl.usach.lsg;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Configuración del mod: ~/Zomboid/LSG/lsg.properties
 *
 * Si el archivo no existe, se crea una plantilla para que el jugador complete su
 * correo y contraseña de LifeSync-Games. La ruta puede cambiarse con la propiedad
 * de sistema "lsg.config" (se usa en las pruebas).
 *
 * LIMITACIÓN CONOCIDA: la contraseña queda en texto plano en el equipo del jugador.
 * Se resolverá con la ventana de login (fase F4) o con un token de larga duración
 * que debe confirmar el laboratorio.
 */
public final class LsgConfig {

    public enum Status { OK, CREATED, INCOMPLETE, UNREADABLE }

    public static final String DEFAULT_AUTH = "https://lsg.diinf.usach.cl/lsg-auth";
    public static final String DEFAULT_CORE = "https://lsg.diinf.usach.cl/lsg-core-api";

    private static final String TEMPLATE = String.join("\n",
            "# Configuración del mod LSG (LifeSync-Games) para Project Zomboid",
            "# 1. Escribe tu correo y contraseña de LifeSync-Games.",
            "# 2. Guarda el archivo y vuelve a cargar la partida.",
            "# Este archivo es personal: no lo compartas.",
            "email=",
            "password=",
            "",
            "auth_url=" + DEFAULT_AUTH,
            "core_url=" + DEFAULT_CORE,
            "",
            "# Nombres de las dimensiones de puntos (dim.<id>=<nombre>).",
            "# Confirmar los IDs con el laboratorio; si un ID no tiene nombre se muestra como 'Dim <id>'.",
            "# dim.2=Físico",
            "");

    private final Path path;
    private final Properties props = new Properties();
    private Status status;

    private LsgConfig(Path path) { this.path = path; }

    public static Path defaultPath() {
        String override = System.getProperty("lsg.config");
        if (override != null && !override.isEmpty()) return Paths.get(override);
        return Paths.get(System.getProperty("user.home"), "Zomboid", "LSG", "lsg.properties");
    }

    public static LsgConfig load() { return load(defaultPath()); }

    public static LsgConfig load(Path path) {
        LsgConfig c = new LsgConfig(path);
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.write(path, TEMPLATE.getBytes(StandardCharsets.UTF_8));
                c.status = Status.CREATED;
                return c;
            }
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                c.props.load(r);
            }
            c.status = c.email().isEmpty() || c.password().isEmpty() ? Status.INCOMPLETE : Status.OK;
        } catch (IOException e) {
            c.status = Status.UNREADABLE;
        }
        return c;
    }

    public Status status() { return status; }
    public Path path() { return path; }

    public String email() { return get("email", ""); }
    public String password() { return get("password", ""); }
    public String authUrl() { return trimSlash(get("auth_url", DEFAULT_AUTH)); }
    public String coreUrl() { return trimSlash(get("core_url", DEFAULT_CORE)); }

    public String dimensionName(long id) {
        String name = get("dim." + id, "");
        return name.isEmpty() ? "Dim " + id : name;
    }

    private String get(String key, String def) {
        String v = props.getProperty(key);
        return v == null ? def : v.trim();
    }

    private static String trimSlash(String u) {
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}

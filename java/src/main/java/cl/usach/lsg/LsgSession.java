package cl.usach.lsg;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sesión con LifeSync-Games: mantiene un token JWT válido sin intervención del jugador.
 *
 *   sin token                       → POST {auth}/login
 *   quedan < 10 min de vigencia     → POST {auth}/token/refresh
 *   el refresh falla (401/403)      → login de nuevo
 *   la Core API responde 401        → invalidate() y un único reintento (LsgClient)
 *
 * Misma lógica que LsgAuth de los sensores (Python), validada con pruebas.
 */
final class LsgSession {

    static final long REFRESH_MARGIN_SECONDS = 600;

    private final LsgConfig cfg;
    private String token;
    private long expEpoch;
    private long playerId = -1;
    private List<String> roles = new ArrayList<>();

    LsgSession(LsgConfig cfg) { this.cfg = cfg; }

    synchronized String token() throws LsgException {
        long now = System.currentTimeMillis() / 1000;
        if (token == null) {
            login();
        } else if (expEpoch - now < REFRESH_MARGIN_SECONDS) {
            try {
                refresh();
            } catch (LsgException e) {
                if (e.httpStatus() == 401 || e.httpStatus() == 403) login(); else throw e;
            }
        }
        return token;
    }

    synchronized long playerId() throws LsgException {
        if (playerId < 0) token();
        return playerId;
    }

    synchronized List<String> roles() { return new ArrayList<>(roles); }

    synchronized void invalidate() { token = null; }

    // ------------------------------------------------------------------ login
    private void login() throws LsgException {
        if (cfg.status() != LsgConfig.Status.OK) {
            throw new LsgException("CONFIG", "Completa email y password en " + cfg.path());
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("username", cfg.email());
        form.put("password", cfg.password());
        LsgHttp.Response r = LsgHttp.postForm(cfg.authUrl() + "/login", form);
        if (r.status == 401 || r.status == 400 || r.status == 422) {
            throw new LsgException("AUTH", "Correo o contraseña incorrectos", r.status);
        }
        if (r.status != 200) {
            System.out.println("[LSGMod] Login HTTP " + r.status + ": " + r.body);
            throw new LsgException("HTTP_" + r.status, "LSG-Auth respondió " + r.status, r.status);
        }

        Map<String, Object> body = parseObject(r.body);
        String t = MiniJson.str(body, "access_token");
        if (t == null) throw new LsgException("PARSE", "La respuesta de login no trae access_token");
        Long expiresIn = MiniJson.num(body, "expires_in_seconds");
        setToken(t, expiresIn);

        Map<String, Object> player = MiniJson.obj(body.get("player"));
        Long id = MiniJson.num(player, "id_players");
        if (id == null) throw new LsgException("PARSE", "La respuesta de login no trae player.id_players");
        playerId = id;
        roles = new ArrayList<>();
        List<Object> rl = MiniJson.arr(player == null ? null : player.get("roles"));
        if (rl != null) for (Object o : rl) roles.add(String.valueOf(o));
        System.out.println("[LSGMod] Login OK: jugador " + playerId + ", roles " + roles);
    }

    // ---------------------------------------------------------------- refresh
    private void refresh() throws LsgException {
        LsgHttp.Response r = LsgHttp.postJsonRetryable(cfg.authUrl() + "/token/refresh", token, null);
        if (r.status != 200) throw new LsgException("HTTP_" + r.status, "No se pudo renovar el token", r.status);
        String t = MiniJson.str(parseObject(r.body), "access_token");
        if (t == null) throw new LsgException("PARSE", "La renovación no trae access_token");
        setToken(t, null);
        System.out.println("[LSGMod] Token renovado");
    }

    private void setToken(String t, Long expiresIn) {
        token = t;
        long jwtExp = jwtExp(t);
        long now = System.currentTimeMillis() / 1000;
        expEpoch = jwtExp > 0 ? jwtExp : now + (expiresIn != null ? expiresIn : 300);
    }

    /** Lee el claim "exp" del JWT sin verificar la firma (solo para planificar la renovación). */
    static long jwtExp(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return -1;
            String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Long exp = MiniJson.num(MiniJson.obj(MiniJson.parse(json)), "exp");
            return exp == null ? -1 : exp;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static Map<String, Object> parseObject(String body) throws LsgException {
        try {
            Map<String, Object> m = MiniJson.obj(MiniJson.parse(body));
            if (m == null) throw new LsgException("PARSE", "Se esperaba un objeto JSON");
            return m;
        } catch (IllegalArgumentException e) {
            throw new LsgException("PARSE", e.getMessage());
        }
    }
}

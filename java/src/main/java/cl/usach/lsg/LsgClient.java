package cl.usach.lsg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Operaciones de LSG-Core-API usadas por el mod. Bloqueantes: llamar desde LsgTasks. */
final class LsgClient {

    static final class Balance {
        final long dimensionId;
        final String name;
        final long points;
        Balance(long dimensionId, String name, long points) {
            this.dimensionId = dimensionId; this.name = name; this.points = points;
        }
    }

    private final LsgConfig cfg;
    private final LsgSession session;

    LsgClient(LsgConfig cfg, LsgSession session) { this.cfg = cfg; this.session = session; }

    /** GET /players/{id}/points/balance → [{"id_players", "id_point_dimension", "balance"}] */
    List<Balance> balance() throws LsgException {
        String url = cfg.coreUrl() + "/players/" + session.playerId() + "/points/balance";
        LsgHttp.Response r = getWithReauth(url);
        if (r.status != 200) throw new LsgException("HTTP_" + r.status, "No se pudo obtener el saldo", r.status);
        System.out.println("[LSGMod] Saldo (respuesta cruda): " + r.body);

        List<Object> items;
        try {
            items = MiniJson.arr(MiniJson.parse(r.body));
        } catch (IllegalArgumentException e) {
            throw new LsgException("PARSE", e.getMessage());
        }
        if (items == null) throw new LsgException("PARSE", "Se esperaba una lista de saldos");

        List<Balance> out = new ArrayList<>();
        for (Object o : items) {
            Map<String, Object> m = MiniJson.obj(o);
            Long dim = MiniJson.num(m, "id_point_dimension");
            Long pts = MiniJson.num(m, "balance");
            if (dim != null && pts != null) out.add(new Balance(dim, cfg.dimensionName(dim), pts));
        }
        return out;
    }

    static String summary(List<Balance> balances) {
        if (balances.isEmpty()) return "Sin puntos registrados";
        StringBuilder b = new StringBuilder();
        for (Balance x : balances) {
            if (b.length() > 0) b.append(" | ");
            b.append(x.name).append(": ").append(x.points);
        }
        return b.toString();
    }

    /**
     * Formato compacto para Lua (Kahlua no tiene parser JSON):
     *   "1=Dim 1=131;2=Físico=114"   →   id=nombre=puntos separados por ';'
     * Los caracteres '=' y ';' se eliminan de los nombres para no romper el formato.
     */
    static String encode(List<Balance> balances) {
        StringBuilder b = new StringBuilder();
        for (Balance x : balances) {
            if (b.length() > 0) b.append(';');
            b.append(x.dimensionId).append('=')
             .append(x.name.replace("=", " ").replace(";", " ").replace("|", " "))
             .append('=').append(x.points);
        }
        return b.toString();
    }

    /** Si el token fue rechazado (401), se renueva la sesión y se reintenta una sola vez. */
    private LsgHttp.Response getWithReauth(String url) throws LsgException {
        LsgHttp.Response r = LsgHttp.get(url, session.token());
        if (r.status == 401) {
            session.invalidate();
            r = LsgHttp.get(url, session.token());
        }
        return r;
    }
}

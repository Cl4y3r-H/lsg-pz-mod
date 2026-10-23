"""Exploración de solo lectura de LifeSync-Games con la cuenta developer.

No crea, modifica ni abona nada. Inicia sesión, revisa los roles del token y
recopila lo necesario para configurar los sensores y el mod:
  * el esquema real (OpenAPI) de los endpoints de abono, sensores y videojuegos;
  * los atributos, dimensiones, sensores y videojuegos existentes;
  * el saldo y los sensores vinculados a la cuenta.

Uso (Python 3.11 o superior, sin dependencias):
    python lsg_explorar.py
El resultado queda en lsg_exploracion.json (sin contraseña ni token).
"""
from __future__ import annotations

import getpass
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

AUTH = "https://lsg.diinf.usach.cl/lsg-auth"
CORE = "https://lsg.diinf.usach.cl/lsg-core-api"

# Endpoints cuyo formato de petición necesitamos conocer (método, fragmento de ruta).
INTERESTING = [
    ("post", "/points/adjust"),
    ("post", "/sensors"),
    ("post", "/link"),
    ("post", "/link-endpoint"),
    ("post", "/ingest/webhook"),
    ("post", "/videogames"),
    ("post", "/mechanics"),
    ("post", "/redeem"),
    ("post", "/offline/sync"),
    ("get", "dimension"),
]


def http(method: str, url: str, token: str | None = None, form: dict | None = None) -> tuple[int, object]:
    data = None
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            body = r.read().decode("utf-8")
            return r.status, json.loads(body) if body else None
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(body)
        except ValueError:
            return e.code, body[:300]
    except (urllib.error.URLError, TimeoutError) as e:
        return 0, f"sin conexión: {e}"


def resolve(schema: object, spec: dict, depth: int = 0) -> object:
    """Reemplaza los $ref del esquema OpenAPI por su contenido (hasta 4 niveles)."""
    if depth > 4:
        return schema
    if isinstance(schema, dict):
        if "$ref" in schema:
            node: object = spec
            for part in schema["$ref"].lstrip("#/").split("/"):
                node = node.get(part, {}) if isinstance(node, dict) else {}
            return resolve(node, spec, depth + 1)
        return {k: resolve(v, spec, depth + 1) for k, v in schema.items()}
    if isinstance(schema, list):
        return [resolve(x, spec, depth + 1) for x in schema]
    return schema


def summarize_endpoints(spec: dict) -> list[dict]:
    out = []
    for path, ops in spec.get("paths", {}).items():
        for method, op in ops.items():
            if not isinstance(op, dict):
                continue
            if not any(method == m and frag in path for m, frag in INTERESTING):
                continue
            body = op.get("requestBody", {}).get("content", {})
            schema = None
            for ctype in ("application/json", "application/x-www-form-urlencoded"):
                if ctype in body:
                    schema = resolve(body[ctype].get("schema", {}), spec)
                    break
            desc = op.get("description", "") or ""
            roles = [line.strip() for line in desc.splitlines() if "oles" in line]
            out.append({
                "method": method.upper(),
                "path": path,
                "summary": op.get("summary"),
                "roles": roles,
                "params": [p.get("name") for p in op.get("parameters", []) if isinstance(p, dict)],
                "body_required": (schema or {}).get("required") if isinstance(schema, dict) else None,
                "body_properties": sorted((schema or {}).get("properties", {}).keys()) if isinstance(schema, dict) else None,
                "body_schema": schema,
            })
    return sorted(out, key=lambda e: (e["path"], e["method"]))


def main() -> int:
    print("Exploración de LifeSync-Games (solo lectura)\n")
    email = input("Correo LSG: ").strip()
    password = getpass.getpass("Contraseña LSG (no se muestra): ")

    status, login = http("POST", f"{AUTH}/login", form={"username": email, "password": password})
    if status != 200 or not isinstance(login, dict):
        print(f"No se pudo iniciar sesión (HTTP {status}): {login}")
        return 1
    token = login["access_token"]
    player = login.get("player", {})
    pid = player.get("id_players")
    roles = player.get("roles", [])
    print(f"Sesión iniciada: jugador {pid}, roles {roles}")
    if "developer" not in roles:
        print("AVISO: el token no incluye el rol developer.")

    report: dict = {"player_id": pid, "roles": roles}

    status, spec = http("GET", f"{CORE}/openapi.json")
    if status == 200 and isinstance(spec, dict):
        report["api_version"] = spec.get("info", {}).get("version")
        report["endpoints"] = summarize_endpoints(spec)
        print(f"Esquema OpenAPI leído (versión {report['api_version']}): {len(report['endpoints'])} endpoints relevantes")
        for e in report["endpoints"]:
            print(f"  {e['method']:5} {e['path']}  campos={e['body_properties']}  obligatorios={e['body_required']}")
    else:
        report["endpoints_error"] = f"HTTP {status}"
        print(f"No se pudo leer openapi.json (HTTP {status})")

    queries = {
        "attributes": "/attributes",
        "attributes_map": "/attributes-map",
        "admin_point_dimensions": "/admin/point-dimensions",
        "sensors": "/sensors",
        "videogames": "/videogames",
        "my_sensors": f"/sensors/players/{pid}",
        "my_balance": f"/players/{pid}/points/balance",
    }
    report["queries"] = {}
    print()
    for key, path in queries.items():
        status, data = http("GET", f"{CORE}{path}", token)
        report["queries"][key] = {"path": path, "status": status, "data": data}
        size = len(data) if isinstance(data, list) else ""
        print(f"  GET {path:40} → HTTP {status} {size}")

    with open("lsg_exploracion.json", "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)
    print("\nListo. Resultado guardado en lsg_exploracion.json (no contiene tu contraseña ni tu token).")
    return 0


if __name__ == "__main__":
    sys.exit(main())

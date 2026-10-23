"""Da de alta los sensores de fuerza y lectura en LifeSync-Games y los vincula a un jugador.

Requiere una cuenta con rol developer (o researcher/admin). Es idempotente: si un
sensor, endpoint o vínculo ya existe, lo reutiliza en vez de duplicarlo.

Pasos (según el flujo del manual de LSG-Core-API, sección 5):
  1. POST /sensors                              crear el sensor (si no existe)
  2. POST /sensors/{id}/endpoints               crear su endpoint de ingesta (si no existe)
  3. POST /sensors/players/{pid}/link           vincular el sensor al jugador
  4. POST /sensors/players/{pid}/link-endpoint  activar el endpoint → id_players_sensor_endpoint

Al final muestra los valores para el [lsg] de cada config.toml y los guarda en
lsg_configuracion.json. Uso:
    python lsg_configurar.py                 # vincula los sensores a tu propia cuenta
    python lsg_configurar.py --jugador 61    # vincula a otro participante (rol researcher/developer)
"""
from __future__ import annotations

import argparse
import getpass
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

AUTH = "https://lsg.diinf.usach.cl/lsg-auth"
CORE = "https://lsg.diinf.usach.cl/lsg-core-api"

SENSORS = [
    {
        "key": "fuerza",
        "name": "LSG-PZ Fuerza (wger)",
        "description": "Sensor de entrenamiento de fuerza: valida series registradas en wger y otorga "
                       "puntos Físicos (10 pts por 3 series válidas, tope 30/día). Tesis C. Hernández, USACH.",
        "base_url": "https://github.com/Cl4y3r-H/lsg-strength-sensor",
        "attribute_id": 2,             # Físico → point_dimension 2 (FISICO_BASE)
        "endpoint": {
            "name": "series-validadas",
            "description": "Resumen diario: series registradas, válidas, motivos de rechazo y puntos.",
            "specific_parameters": {"source": "wger", "attribute_id": 2, "points_per_block": 10,
                                    "sets_per_block": 3, "daily_cap": 30},
        },
    },
    {
        "key": "lectura",
        "name": "LSG-PZ Lectura (KOReader)",
        "description": "Sensor de ocio lector: detecta bloques de lectura continua en KOReader y otorga "
                       "puntos Lingüísticos (5 pts por 30 min, tope 20/día). Tesis C. Hernández, USACH.",
        "base_url": "https://github.com/Cl4y3r-H/lsg-reading-sensor",
        "attribute_id": 5,             # Lingüístico → point_dimension 7 (LINGUISTICO_BASE)
        "endpoint": {
            "name": "bloques-lectura",
            "description": "Resumen diario: bloques de lectura continua, minutos activos y puntos.",
            "specific_parameters": {"source": "koreader", "attribute_id": 5, "minutes_per_block": 30,
                                    "points_per_block": 5, "daily_cap": 20, "max_gap_minutes": 5},
        },
    },
]


class ApiError(Exception):
    pass


def http(method: str, url: str, token: str | None = None, body: dict | None = None,
         form: dict | None = None) -> object:
    headers = {"Accept": "application/json"}
    data = None
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    elif form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = r.read().decode("utf-8")
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raise ApiError(f"{method} {url} → HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:300]}") from None
    except (urllib.error.URLError, TimeoutError) as e:
        raise ApiError(f"{method} {url} → sin conexión: {e}") from None


def first_id(obj: object, *keys: str) -> int | None:
    if isinstance(obj, dict):
        for k in keys:
            if isinstance(obj.get(k), int):
                return obj[k]
        for v in obj.values():          # algunas respuestas envuelven el recurso creado
            found = first_id(v, *keys)
            if found is not None:
                return found
    return None


def ensure_sensor(token: str, spec: dict) -> int:
    for s in http("GET", f"{CORE}/sensors", token) or []:
        if s.get("name") == spec["name"]:
            print(f"  sensor existente: {spec['name']} (id {s['id_online_sensor']})")
            return s["id_online_sensor"]
    created = http("POST", f"{CORE}/sensors", token, body={
        "name": spec["name"], "description": spec["description"], "base_url": spec["base_url"]})
    sid = first_id(created, "id_online_sensor", "sensor_id", "id")
    if sid is None:   # si la respuesta no trae el id, se busca por nombre
        sid = next(s["id_online_sensor"] for s in http("GET", f"{CORE}/sensors", token) if s.get("name") == spec["name"])
    print(f"  sensor creado: {spec['name']} (id {sid})")
    return sid


def ensure_endpoint(token: str, sensor_id: int, ep: dict) -> int:
    for e in http("GET", f"{CORE}/sensors/{sensor_id}/endpoints", token) or []:
        if e.get("name") == ep["name"]:
            print(f"  endpoint existente: {ep['name']} (id {e['id_sensor_endpoint']})")
            return e["id_sensor_endpoint"]
    created = http("POST", f"{CORE}/sensors/{sensor_id}/endpoints", token, body=ep)
    eid = first_id(created, "id_sensor_endpoint", "sensor_endpoint_id", "id")
    if eid is None:
        eid = next(e["id_sensor_endpoint"] for e in http("GET", f"{CORE}/sensors/{sensor_id}/endpoints", token)
                   if e.get("name") == ep["name"])
    print(f"  endpoint creado: {ep['name']} (id {eid})")
    return eid


def ensure_links(token: str, player_id: int, sensor_id: int, endpoint_id: int) -> int:
    for row in http("GET", f"{CORE}/sensors/players/{player_id}", token) or []:
        if row.get("id_sensor_endpoint") == endpoint_id and row.get("id_players_sensor_endpoint"):
            print(f"  vínculo existente: id_players_sensor_endpoint {row['id_players_sensor_endpoint']}")
            return row["id_players_sensor_endpoint"]
    try:
        http("POST", f"{CORE}/sensors/players/{player_id}/link", token, body={"sensor_id": sensor_id, "tokens": None})
        print(f"  sensor {sensor_id} vinculado al jugador {player_id}")
    except ApiError as e:
        print(f"  aviso al vincular el sensor (puede que ya estuviera vinculado): {e}")
    created = http("POST", f"{CORE}/sensors/players/{player_id}/link-endpoint", token,
                   body={"sensor_endpoint_id": endpoint_id, "activated": True, "schedule_time": None})
    pse = first_id(created, "id_players_sensor_endpoint")
    print(f"  endpoint activado: id_players_sensor_endpoint {pse}")
    return pse


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--jugador", type=int, help="id del jugador a vincular (por defecto, tu cuenta)")
    args = ap.parse_args()

    print("Configuración de sensores LSG-PZ en LifeSync-Games\n")
    email = input("Correo LSG (cuenta developer): ").strip()
    password = getpass.getpass("Contraseña (no se muestra): ")
    try:
        login = http("POST", f"{AUTH}/login", form={"username": email, "password": password})
    except ApiError as e:
        print(f"No se pudo iniciar sesión: {e}")
        return 1
    token = login["access_token"]
    me = login.get("player", {})
    roles = me.get("roles", [])
    if not {"developer", "researcher", "admin"} & set(roles):
        print(f"Tu cuenta tiene roles {roles}: se necesita developer, researcher o admin.")
        return 1
    player_id = args.jugador or me["id_players"]

    print(f"\nSe crearán (o reutilizarán) 2 sensores y se vincularán al jugador {player_id}:")
    for s in SENSORS:
        print(f"  - {s['name']} → endpoint '{s['endpoint']['name']}'")
    if input("\n¿Continuar? (s/n): ").strip().lower() != "s":
        print("Cancelado; no se modificó nada.")
        return 0

    result = {"player_id": player_id, "sensors": {}}
    try:
        for s in SENSORS:
            print(f"\n[{s['key']}] {s['name']}")
            sid = ensure_sensor(token, s)
            eid = ensure_endpoint(token, sid, s["endpoint"])
            pse = ensure_links(token, player_id, sid, eid)
            result["sensors"][s["key"]] = {"sensor_id": sid, "sensor_endpoint_id": eid,
                                           "players_sensor_endpoint_id": pse, "attribute_id": s["attribute_id"]}
    except ApiError as e:
        print(f"\nERROR: {e}")
        print("Lo creado hasta este punto se reutilizará si vuelves a ejecutar el script.")
        return 1

    with open("lsg_configuracion.json", "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2)

    print("\n" + "=" * 64)
    for key, folder in (("fuerza", "lsg-strength-sensor"), ("lectura", "lsg-reading-sensor")):
        r = result["sensors"][key]
        print(f"\n# {folder}/config.toml → sección [lsg]")
        print(f"sensor_endpoint_id = {r['sensor_endpoint_id']}")
        print(f"players_sensor_endpoint_id = {r['players_sensor_endpoint_id']}")
        print(f"attribute_id = {r['attribute_id']}")
        print('credit_strategy = "adjust"')
        print("send_webhook = true")
    print("\nGuardado en lsg_configuracion.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())

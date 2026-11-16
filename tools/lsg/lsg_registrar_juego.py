"""Registra Project Zomboid y sus recompensas en LifeSync-Games para el overlay del cliente LSG.

El overlay del cliente de escritorio LSG (E. Rodríguez) detecta el juego por su nombre
(parte del título de la ventana) y su ejecutable, y muestra como canjeables las
mecánicas asociadas al videojuego. Cada mecánica lleva en "options":
    reward_id    → id que el cliente envía al mod (POST http://127.0.0.1:7878/reward)
    cost_amount  → puntos que se descuentan
    attribute_id → atributo que paga (2 = Físico, 5 = Lingüístico)

Es idempotente: reutiliza el videojuego si ya existe (y corrige su ejecutable) y solo
asocia las mecánicas cuyo reward_id aún no está registrado. Pasos:
  1. GET  /videogames                         buscar "Project Zomboid"
  2. POST /videogames  o  PUT /videogames/{id} crear o actualizar el ejecutable
  3. GET  /videogames/{id}/mechanics          mecánicas ya asociadas
  4. POST /videogames/{id}/mechanics/bulk     crear y asociar las que faltan

Requiere una cuenta con permisos para administrar videojuegos (por confirmar con el
laboratorio si basta el rol developer). Uso:
    python lsg_registrar_juego.py --simular                     # muestra lo que haría, sin cambios
    python lsg_registrar_juego.py
    python lsg_registrar_juego.py --ejecutable ProjectZomboid64.exe
    python lsg_registrar_juego.py --solo 101,102,201     # solo esas recompensas

Antes de registrar, carga una partida con el mod y revisa en console.txt la línea
"Recompensas disponibles para el overlay": registra solo esas (--solo). Los puntos
se cobran antes de entregar la recompensa, así que una recompensa que el juego no
puede aplicar haría perder puntos al jugador.
"""
from __future__ import annotations

import argparse
import getpass
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

AUTH = "https://lsg.diinf.usach.cl/lsg-auth"
CORE = "https://lsg.diinf.usach.cl/lsg-core-api"
CATALOGO = Path(__file__).with_name("catalogo_recompensas.json")


class ApiError(Exception):
    pass


def http(method: str, url: str, token: str | None = None, body: dict | None = None,
         form: dict | None = None):
    headers = {"Accept": "application/json"}
    data = None
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if form is not None:
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


def reward_id_of(mechanic: dict) -> int | None:
    options = mechanic.get("options") or {}
    try:
        return int(options.get("reward_id"))
    except (TypeError, ValueError):
        return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--simular", action="store_true", help="muestra los cambios sin aplicarlos")
    ap.add_argument("--ejecutable", help="nombre del proceso del juego (por defecto, el del catálogo)")
    ap.add_argument("--solo", help="reward_id a registrar, separados por coma (por defecto, todos)")
    args = ap.parse_args()

    catalogo = json.loads(CATALOGO.read_text(encoding="utf-8"))
    juego = dict(catalogo["videojuego"])
    if args.ejecutable:
        juego["executable"] = args.ejecutable
    mecanicas = catalogo["mecanicas"]
    if args.solo:
        elegidos = {int(x) for x in args.solo.split(",") if x.strip()}
        desconocidos = elegidos - {m["reward_id"] for m in mecanicas}
        if desconocidos:
            print(f"reward_id que no están en el catálogo: {sorted(desconocidos)}")
            return 1
        mecanicas = [m for m in mecanicas if m["reward_id"] in elegidos]

    print("Registro de Project Zomboid en LifeSync-Games (overlay del cliente LSG)\n")
    email = input("Correo LSG: ").strip()
    password = getpass.getpass("Contraseña (no se muestra): ")
    try:
        login = http("POST", f"{AUTH}/login", form={"username": email, "password": password})
    except ApiError as e:
        print(f"No se pudo iniciar sesión: {e}")
        return 1
    token = login["access_token"]
    print(f"Sesión iniciada (roles: {', '.join(login.get('player', {}).get('roles', []))})\n")

    try:
        existentes = http("GET", f"{CORE}/videogames", token) or []
        actual = next((v for v in existentes if str(v.get("name", "")).strip().lower()
                       == juego["name"].lower()), None)

        if actual is None:
            print(f"1. Crear videojuego '{juego['name']}' (ejecutable {juego['executable']})")
            game_id = None
        else:
            game_id = actual["id_videogame"]
            print(f"1. Videojuego ya registrado: id {game_id}, ejecutable {actual.get('executable')!r}")
            if actual.get("executable") != juego["executable"]:
                print(f"   → se actualizará el ejecutable a {juego['executable']!r}")

        ya = set()
        if game_id is not None:
            for m in http("GET", f"{CORE}/videogames/{game_id}/mechanics", token) or []:
                rid = reward_id_of(m)
                if rid is not None:
                    ya.add(rid)
        faltan = [m for m in mecanicas if m["reward_id"] not in ya]
        print(f"2. Mecánicas: {len(mecanicas) - len(faltan)} ya asociadas, {len(faltan)} por asociar")
        for m in faltan:
            print(f"   - [{m['reward_id']}] {m['name']}: {m['cost_amount']} pts (atributo {m['attribute_id']})")

        if args.simular:
            print("\nSimulación: no se modificó nada.")
            return 0
        if input("\n¿Continuar? (s/n): ").strip().lower() != "s":
            print("Cancelado; no se modificó nada.")
            return 0

        if game_id is None:
            game_id = http("POST", f"{CORE}/videogames", token, body=juego)["id_videogame"]
            print(f"Videojuego creado: id {game_id}")
        elif actual.get("executable") != juego["executable"]:
            http("PUT", f"{CORE}/videogames/{game_id}", token, body={"executable": juego["executable"]})
            print("Ejecutable actualizado")

        if faltan:
            payload = {"mechanics": [{
                "name": m["name"],
                "description": m["description"],
                "type": m["type"],
                "options": {"reward_id": m["reward_id"], "cost_amount": m["cost_amount"],
                            "attribute_id": m["attribute_id"]},
            } for m in faltan]}
            res = http("POST", f"{CORE}/videogames/{game_id}/mechanics/bulk", token, body=payload)
            print(f"Mecánicas asociadas: {json.dumps(res, ensure_ascii=False)[:500]}")
    except ApiError as e:
        print(f"\nERROR: {e}")
        if "HTTP 403" in str(e):
            print("Tu cuenta no tiene permiso para esta operación: pide al laboratorio el rol necesario.")
        print("Lo creado hasta este punto se reutilizará si vuelves a ejecutar el script.")
        return 1

    print(f"\nListo. id_videogame = {game_id}")
    print("Este id también sirve como game_id de los sensores si usan credit_strategy = \"offline_sync\".")
    return 0


if __name__ == "__main__":
    sys.exit(main())

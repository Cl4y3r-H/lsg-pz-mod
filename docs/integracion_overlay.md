# Integración con el overlay del cliente LSG

Guía para usar LSGMod 0.4.0 con el cliente de escritorio de LifeSync-Games
(`lsg-client` 1.0.0, E. Rodríguez), según la solicitud del profesor guía.

## 1. Cómo funciona el cliente LSG

Lo que sigue se obtuvo revisando el instalador `lsg-client-1.0.0-setup.exe`. No es documentación
oficial: conviene confirmarlo con Enrique.

| Pieza | Función |
|---|---|
| Backend local `127.0.0.1:3333` | Lo levanta el propio cliente. Hace de intermediario con LifeSync-Games: perfil **remoto** (producción, `lsg.diinf.usach.cl`, por defecto) o **local** (Docker en `localhost:8001` y `8012`). |
| Watcher | Cada 2 s busca un proceso cuyo nombre sea el `executable` de un videojuego registrado y cuya ventana contenga su `name` en el título. |
| Overlay (`lsg-overlay.exe`) | Ventana sobre el juego (tecla **F3**). Muestra el saldo por atributo y las mecánicas canjeables del juego. |
| Adaptador del juego `127.0.0.1:7878` | Lo implementa **el mod**. El overlay consulta `GET /health` y, después de cobrar un canje, envía `POST /reward`. |

Flujo de un canje:

1. El overlay muestra cada mecánica con el costo de `options.cost_amount` en el atributo `options.attribute_id`.
2. El botón **Redeem** solo se habilita con saldo suficiente y `game_ready = true`.
3. El cliente llama a `POST /videogames/{id}/players/{pid}/redeem` y LifeSync-Games descuenta los puntos.
4. Luego envía `POST http://127.0.0.1:7878/reward` con `{"reward_id": options.reward_id}`.
5. El mod aplica la recompensa y responde `{"ok": true}`.

## 2. Registrar Project Zomboid y sus mecánicas

### 2.1 Confirmar el ejecutable y el título de la ventana

Con el juego abierto: **Administrador de tareas** → clic derecho sobre Project Zomboid →
**Propiedades**. El nombre del archivo es el `executable` (se espera `ProjectZomboid64.exe`). El
título de la ventana debe contener `Project Zomboid`, que es el `name` registrado.

### 2.2 Confirmar qué recompensas acepta el juego

Carga una partida con el mod y busca en `~/Zomboid/console.txt`:

```
[LSGMod] Recompensas disponibles para el overlay: 101, 102, 103, 201, 202, 401, 402
[LSGMod] AVISO: recompensa 301 (...) excluida: el objeto ... no existe en esta versión del juego
```

Registra **solo** las recompensas disponibles. Los puntos se cobran antes de que el mod reciba la
recompensa, así que una mecánica que el juego no puede aplicar le haría perder puntos al jugador.

### 2.3 Ejecutar el script

```
cd tools/lsg
python lsg_registrar_juego.py --simular                       # revisa sin modificar nada
python lsg_registrar_juego.py --solo 101,102,103,201,202,401,402
```

El script:

- crea el videojuego (o corrige su ejecutable);
- asocia las mecánicas con `options = {reward_id, cost_amount, attribute_id}`;
- no duplica las que ya existen, así que se puede volver a ejecutar sin problema;
- al final muestra el `id_videogame`, que también sirve como `game_id` de los sensores si usan `offline_sync`.

Si responde **HTTP 403**, la cuenta no tiene permiso para administrar videojuegos. En ese caso
se puede hacer desde las pantallas de administración del cliente LSG con una cuenta que sí lo
tenga, o pedirlo al laboratorio con los datos de `catalogo_recompensas.json`.

## 3. Catálogo de recompensas (provisional, GDD Tabla 3.2)

| reward_id | Recompensa | Atributo | Costo | Efecto |
|---|---|---|---|---|
| 101 | Clavos ×25 | Físico (2) | 10 | Objetos al inventario |
| 102 | Cinta adhesiva | Físico (2) | 15 | Objeto al inventario |
| 103 | Pegamento | Físico (2) | 15 | Objeto al inventario |
| 201 | Aguja de sutura ×2 | Físico (2) | 15 | Objetos al inventario |
| 202 | Antibióticos | Físico (2) | 40 | Objeto al inventario |
| 301 | Revista de generadores | Lingüístico (5) | 50 | Objeto al inventario |
| 401 | Carpintería vol. 1 | Lingüístico (5) | 10 | Multiplicador ×3 (niveles 1–2) |
| 402 | Carpintería vol. 2 | Lingüístico (5) | 20 | Multiplicador ×5 (niveles 3–4) |

Para agregar o cambiar una recompensa, edita **en conjunto** `LSG_Rewards.lua` (efecto en el juego)
y `tools/lsg/catalogo_recompensas.json` (costo y atributo en LSG). Luego ejecuta
`python tools/check_catalogo.py` y `python tools/lua_ascii.py mod/LSGMod/42/media/lua`.

Los nombres de objetos (`Base.Nails`, etc.) y de habilidades (`Woodwork` = Carpintería) aún no
se han verificado en Build 42. El mod excluye automáticamente los que no existan (ver 2.2).

## 4. Riesgos conocidos (para conversar con Enrique)

1. **Cobro antes de la entrega.** Si el mod no responde, el jugador pierde los puntos. Mitigación
   del mod: si el juego no confirma en 5 s (responde 504), la recompensa queda en cola y se aplica
   apenas el juego vuelve a procesar ticks.
2. **Sin identificador del canje.** `POST /reward` solo trae `reward_id`, así que el mod no puede
   distinguir un reintento de un segundo canje. Se sugiere agregar el `points_ledger_id` del canje
   al contrato.
3. **Pantalla completa.** El overlay es una ventana aparte; en pantalla completa exclusiva podría no
   verse. Usar **Ventana sin bordes**.
4. **Un solo juego a la vez.** El puerto 7878 es fijo: si hay dos instancias del juego abiertas,
   la segunda no puede abrir el adaptador y lo avisa en pantalla.

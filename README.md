# LSGMod — LifeSync-Games para Project Zomboid (Build 42)

Versión 0.4.0 · Integración con el overlay del cliente de escritorio LSG, además de las actividades 2.4, 2.6, 2.7, 2.9 y 2.12 de la carta Gantt.

El saldo y los canjes se muestran en el **overlay del cliente LSG** (E. Rodríguez), que se
dibuja sobre el juego (tecla **F3** del cliente). El mod recibe las recompensas canjeadas
mediante un servidor local en `127.0.0.1:7878` y las aplica en la partida: objetos en el
inventario o multiplicadores de experiencia (GDD, Tabla 3.2).

La ventana propia del mod (**F9**) se mantiene como opción secundaria para ver el saldo; solo
funciona si se configura una cuenta en `lsg.properties`.

## Integración con el cliente LSG

```
Overlay (F3) ──► cliente LSG (127.0.0.1:3333) ──► LifeSync-Games: descuenta los puntos
     │
     └──► POST 127.0.0.1:7878/reward {"reward_id": n} ──► LsgAdapter (Java) ──► cola
                                                                              │
                     LSG_Rewards.lua (cada tick, hilo del juego) ◄────────────┘
                     entrega objeto / multiplicador y confirma el resultado
```

| Ruta del adaptador | Respuesta |
|---|---|
| `GET /health` | `{"status":"ok","game_ready":true}` si hay un personaje vivo en partida; `false` en el menú, al morir o si el juego no procesa ticks hace 3 s |
| `POST /reward` | `200 {"ok":true}` aplicada · `400` cuerpo inválido · `422 unknown_reward` no está en el catálogo · `503 game_not_ready` · `504 application_timeout` si no se confirma en 5 s (queda en cola y se aplica después) · `500` error del juego |

El contrato completo ("LSG Game Adapter API 1.0.0") lo publica el propio cliente en
`http://127.0.0.1:3333/docs`. El registro del juego y de las mecánicas se explica en
[`docs/integracion_overlay.md`](docs/integracion_overlay.md).

## Estructura

```
lsg-mod/
├── mod/LSGMod/                      ← carpeta del mod (va en ~/Zomboid/mods/)
│   ├── 42/mod.info                  ← require=\ZombieBuddy, javaJarFile, javaPkgName
│   ├── 42/media/java/LSGMod.jar
│   ├── 42/media/lua/client/LSG_Main.lua            ← estado, login, tareas, tecla F9
│   ├── 42/media/lua/client/LSG_Rewards.lua         ← catálogo y aplicación de recompensas
│   ├── 42/media/lua/client/UI/ISLifeSyncWindow.lua ← ventana (ISCollapsableWindow)
│   └── common/
├── java/src/main/java/cl/usach/lsg/
│   ├── Main.java          ← ZombieBuddy lo ejecuta al cargar el JAR
│   ├── LsgMod.java        ← funciones expuestas a Lua (lsgMod*)
│   ├── LsgAdapter.java    ← servidor 127.0.0.1:7878 para el overlay (solo java.base)
│   ├── LsgConfig.java     ← lee ~/Zomboid/LSG/lsg.properties (crea plantilla si no existe)
│   ├── LsgSession.java    ← login, renovación del token JWT y re-login
│   ├── LsgClient.java     ← operaciones de LSG-Core-API (saldo)
│   ├── LsgTasks.java      ← modelo "iniciar y consultar" en hilos secundarios
│   ├── LsgHttp.java       ← peticiones HTTP
│   ├── LsgException.java  ← errores con código estable (CONFIG, AUTH, NETWORK, HTTP_xxx, PARSE)
│   └── MiniJson.java      ← parser JSON sin dependencias
├── tools/ModCheck.java    ← 12 pruebas contra un servidor LSG falso
├── tools/AdapterCheck.java ← 27 pruebas del contrato del adaptador (hace de overlay y de Lua)
├── tools/lua_test/        ← 16 pruebas de los .lua con la API del juego simulada
├── tools/check_catalogo.py ← verifica que el catálogo de Lua y el JSON coincidan
├── tools/lsg/             ← scripts de la cuenta LSG: sensores y registro del juego/mecánicas
├── docs/integracion_overlay.md ← cómo registrar el juego y probar con el overlay
├── tools/lua_ascii.py     ← convierte tildes de los .lua a escapes (\ddd)
└── instalar_lsgmod.ps1    ← instalador para Windows
```

## Funciones expuestas a Lua

| Función | Retorna |
|---|---|
| `lsgModVersion()` | Versión del mod |
| `lsgModStatus()` | `OK`, o `CREATED\|<ruta>` / `INCOMPLETE\|<ruta>` / `UNREADABLE\|<ruta>` |
| `lsgModLogin()` | Id de tarea → `OK\|<id_jugador>\|<roles>` |
| `lsgModBalance()` | Id de tarea → `OK\|Dim 1: 131 \| Físico: 114` |
| `lsgModBalanceData()` | Id de tarea → `OK\|1=Dim 1=131;2=Físico=114` (para la ventana) |
| `lsgModPoll(id)` | `PENDING`, `OK\|...` o `ERROR\|<CÓDIGO>\|<mensaje>` |
| `lsgModRewardStart(ids)` | Inicia el adaptador con los `reward_id` válidos → `OK\|7878` o `ERROR\|PORT_IN_USE\|...` |
| `lsgModRewardHeartbeat(listo)` | Latido de cada tick (`"1"` si hay personaje vivo) |
| `lsgModRewardNext()` | `""` o `<ticket>\|<reward_id>` |
| `lsgModRewardDone(ticket, res)` | `res`: `OK`, `UNKNOWN_REWARD\|...`, `GAME_NOT_READY\|...` o `FAILED\|...` |

## Cambios en 0.4.0

- Adaptador de recompensas (`LsgAdapter`) según el contrato del cliente LSG: servidor HTTP en
  `127.0.0.1:7878` hecho con `ServerSocket` (módulo `java.base`), porque el Java incluido en el juego
  podría no traer `jdk.httpserver`. Solo escucha en la interfaz local.
- `LSG_Rewards.lua`: catálogo provisional del GDD (insumos, médicos, revista de generadores y
  multiplicadores de Carpintería), entrega al inventario o al suelo si el personaje va
  sobrecargado (HU4) y aviso sobre el personaje. Al cargar la partida valida cada entrada y excluye
  las que no existen en la versión del juego.
- El adaptador funciona sin cuenta en `lsg.properties`: el cliente LSG maneja el login. Sin cuenta,
  el mod ya no muestra un error, sino un aviso para usar el overlay.
- `tools/lsg/lsg_registrar_juego.py` y `catalogo_recompensas.json`: registran Project Zomboid y sus
  mecánicas (con `reward_id`, `cost_amount` y `attribute_id`) en LifeSync-Games.

## Cambios en 0.3.3

- Nombres por defecto de las dimensiones de puntos, según `GET /admin/point-dimensions` (23-10-2026): 1 Social, 2 Físico, 3 Afectivo, 4 Mental, 5 Condición física, 6 Regulación emocional, 7 Lingüístico. Ya no hace falta configurarlos en `lsg.properties`.
- `tools/lsg/`: scripts para la cuenta developer: `lsg_explorar.py` (exploración de solo lectura de la API) y `lsg_configurar.py` (alta y vinculación de los sensores, idempotente).

## Cambios en 0.3.2

- Corrección definitiva de tildes: en Build 42, Kahlua arma los textos como bytes y los decodifica como UTF-8. El escape de un solo byte (`\243`) quedaba inválido; ahora se escriben los bytes UTF-8 de cada carácter (`ó` = `\195\179`). `tools/lua_ascii.py` ya genera este formato.
- Al cargar la partida, el log muestra una línea de prueba de tildes para verificarlo.

## Cambios en 0.3.1

- Corrección de tildes: Project Zomboid no decodifica como UTF-8 los textos de los archivos `.lua` de los mods ("Versión" aparecía como "Versi?n"). Los textos con tildes ahora usan escapes decimales (`\243` = ó), que Kahlua convierte al carácter correcto. Los textos que vienen desde Java no tienen este problema.
- `tools/lua_ascii.py` hace esta conversión automáticamente: ejecutarlo después de editar cualquier `.lua`:
  `python tools/lua_ascii.py mod/LSGMod/42/media/lua`

## Cambios en 0.3.0

- Ventana `ISLifeSyncWindow` (F9): estado de la conexión con color (verde conectado, amarillo conectando, rojo sin conexión), saldo por dimensión, tiempo desde la última actualización y mensajes de error.
- Si la ventana no se puede abrir (p. ej. por un cambio de la API del juego), el error queda en `console.txt` y el mod sigue funcionando.
- Nueva función `lsgModBalanceData()` con el saldo en formato compacto para Lua.

## Cambios en 0.2.1

- Las peticiones idempotentes (login, renovación, consultas GET) se reintentan hasta 2 veces ante errores 5xx o de red, con 1 s y 2,5 s de espera. Los canjes futuros NO se reintentarán automáticamente.
- Si el login falla por un error transitorio, Lua lo reintenta hasta 3 veces cada ~10 s y lo informa en pantalla.
- Los errores 5xx se muestran como "LifeSync-Games no está disponible por ahora".

## Configuración

Archivo `~/Zomboid/LSG/lsg.properties` (en Windows `C:\Users\<usuario>\Zomboid\LSG\lsg.properties`):

```properties
email=tu_correo@usach.cl
password=tu_contraseña
auth_url=https://lsg.diinf.usach.cl/lsg-auth
core_url=https://lsg.diinf.usach.cl/lsg-core-api
dim.2=Físico
```

- Si no existe, el mod crea una plantilla al cargar la partida y avisa en pantalla.
- Si se edita con el juego abierto, el mod la relee en la siguiente operación.
- Las líneas `dim.<id>=<nombre>` dan nombre a las dimensiones de puntos. Sin nombre se muestran como `Dim <id>`.
- Una barra invertida `\` en la contraseña debe escribirse `\\` (formato .properties). El instalador lo hace solo.

**Limitación conocida:** la contraseña queda en texto plano en el equipo del jugador. Se resolverá
con la ventana de login (fase F4) o con un token de larga duración, a confirmar con el laboratorio.

## Instalación y prueba (Windows)

1. Dejar `LSGMod_mod.zip` e `instalar_lsgmod.ps1` en la misma carpeta y ejecutar el script
   con PowerShell. La cuenta en `lsg.properties` es opcional desde la 0.4.0 (solo la usa la ventana F9).
2. En el menú **Mods**: activar **ZombieBuddy** y **LifeSync-Games (LSG)**, y **desactivar LSG Bridge PoC**
   (ambos usan F9).
3. Instalar y abrir el cliente LSG, iniciar sesión (perfil remoto, el de producción) y dejarlo abierto.
4. Usar el modo de pantalla **Ventana sin bordes** del juego, para que el overlay quede visible.
5. Cargar o crear una partida. Ver [`docs/integracion_overlay.md`](docs/integracion_overlay.md) para
   registrar el juego y sus mecánicas.

| Paso | Resultado esperado |
|---|---|
| Al cargar | `LSG listo: canjea tus puntos desde el overlay del cliente LSG (F3)`; en `console.txt`, `Recompensas disponibles para el overlay: ...` |
| Abrir `http://127.0.0.1:7878/health` en el navegador | `{"status":"ok","game_ready":true}` |
| F3 (cliente LSG) | El overlay muestra saldo, mecánicas y el estado `Game Ready` |
| Canjear una mecánica | Se descuentan los puntos, aparece `LifeSync: <recompensa>` sobre el personaje y el objeto en el inventario |
| Canjear yendo sobrecargado | El objeto queda en el suelo junto al personaje |
| Volver al menú principal | El overlay pasa a `Game Loading` y no permite canjear |
| F9 (con cuenta configurada) | Ventana propia del mod con el saldo |

En `~/Zomboid/console.txt`, las líneas `[LSGMod]` muestran el detalle: recompensas excluidas del
catálogo, cada recompensa recibida y su resultado.

## Pruebas fuera del juego

```
Paso 1 sin configuración crea plantilla ................ OK
Login sin credenciales → ERROR|CONFIG ................... OK
Contraseña incorrecta → ERROR|AUTH ...................... OK
Login correcto → OK|50|player ........................... OK
Saldo → OK|Dim 1: 131 | Físico: 114 | Dim 3: 0 .......... OK
Token por vencer → 1 renovación, sin nuevo login ........ OK
401 en Core API → re-login y reintento .................. OK
Sin bloqueo: 22 ticks mientras respondía el servidor .... OK
500 transitorio en login → reintento y éxito ........... OK
500 persistente → ERROR|HTTP_500 tras 2 reintentos ...... OK
Datos para la ventana: 1=Dim 1=131;2=Físico=114;3=Dim 3=0  OK
Servidor caído → ERROR|NETWORK .......................... OK
Resultado: 12 aprobadas, 0 fallidas
```

Además: `AdapterCheck` (27 pruebas del contrato del overlay), `tools/lua_test` (16 pruebas de Lua)
y `tools/check_catalogo.py`. Todas se ejecutan en GitHub Actions.

## Compilación

```bash
cd java
gradle deploy -PpzDir="<carpeta de Project Zomboid>" -PzbJar="<ruta a ZombieBuddy.jar>"
```

El JAR incluido se compiló con bytecode Java 17 (el juego corre sobre Java 25), igual que la
prueba de concepto validada.

## Contexto

Parte del trabajo de título *"Aplicación del framework LifeSync-Games al videojuego Project Zomboid
a través de un mod y sensores virtuales especializados"* (Claudio Hernández Hernández, Departamento
de Ingeniería Informática, Universidad de Santiago de Chile, 2026).

| Repositorio | Contenido |
|---|---|
| [lsg-pz-mod](https://github.com/Cl4y3r-H/lsg-pz-mod) | Mod de Project Zomboid (Build 42) y prueba de concepto Java ↔ Lua |
| [lsg-strength-sensor](https://github.com/Cl4y3r-H/lsg-strength-sensor) | Sensor de entrenamiento de fuerza (wger) |
| [lsg-reading-sensor](https://github.com/Cl4y3r-H/lsg-reading-sensor) | Sensor de ocio lector (KOReader) |

La carpeta [`poc/`](poc/) conserva la prueba de concepto `LSGBridgePoC` (validada el 13-10-2026 en
Project Zomboid 42.21 con ZombieBuddy 2.3.4), como evidencia de la validación de la arquitectura.
`tools/ci-stubs/` contiene versiones mínimas de las anotaciones de Kahlua y ZombieBuddy, usadas
solo para compilar y probar en GitHub Actions; nunca se incluyen en el JAR del mod.

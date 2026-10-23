# LSGMod — LifeSync-Games para Project Zomboid (Build 42)

Versión 0.3.2 · Actividades 2.4 (estructura), 2.6 (cliente autenticado), 2.7 (saldo), 2.9 (errores) y 2.12 (menú temprano) de la carta Gantt.

Al cargar la partida el mod inicia sesión en LifeSync-Games. La tecla **F9** abre o cierra la
ventana **LifeSync-Games**, que muestra el estado de la conexión y el saldo por dimensión, con
botones para actualizar y cerrar.

## Estructura

```
lsg-mod/
├── mod/LSGMod/                      ← carpeta del mod (va en ~/Zomboid/mods/)
│   ├── 42/mod.info                  ← require=\ZombieBuddy, javaJarFile, javaPkgName
│   ├── 42/media/java/LSGMod.jar
│   ├── 42/media/lua/client/LSG_Main.lua            ← estado, login, tareas, tecla F9
│   ├── 42/media/lua/client/UI/ISLifeSyncWindow.lua ← ventana (ISCollapsableWindow)
│   └── common/
├── java/src/main/java/cl/usach/lsg/
│   ├── Main.java          ← ZombieBuddy lo ejecuta al cargar el JAR
│   ├── LsgMod.java        ← funciones expuestas a Lua (lsgMod*)
│   ├── LsgConfig.java     ← lee ~/Zomboid/LSG/lsg.properties (crea plantilla si no existe)
│   ├── LsgSession.java    ← login, renovación del token JWT y re-login
│   ├── LsgClient.java     ← operaciones de LSG-Core-API (saldo)
│   ├── LsgTasks.java      ← modelo "iniciar y consultar" en hilos secundarios
│   ├── LsgHttp.java       ← peticiones HTTP
│   ├── LsgException.java  ← errores con código estable (CONFIG, AUTH, NETWORK, HTTP_xxx, PARSE)
│   └── MiniJson.java      ← parser JSON sin dependencias
├── tools/ModCheck.java    ← 12 pruebas contra un servidor LSG falso
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
   con PowerShell. Responder `s` para crear la configuración con tu correo y contraseña.
2. En el menú **Mods**: activar **ZombieBuddy** y **LifeSync-Games (LSG)**, y **desactivar LSG Bridge PoC**
   (ambos usan F9).
3. Cargar o crear una partida.

| Paso | Resultado esperado |
|---|---|
| Al cargar | `LSG conectado (jugador <id>). Presiona F9 para abrir LifeSync-Games` |
| F9 | Se abre la ventana; el botón dice `Consultando...` y luego aparece el saldo o `Sin puntos registrados` |
| Actualizar sin internet | `Estado: Sin conexión` en rojo y el mensaje de error, sin congelar el juego |
| F9 otra vez | Se cierra la ventana |
| Contraseña incorrecta | `Correo o contraseña de LSG incorrectos` |

En `~/Zomboid/console.txt`, las líneas `[LSGMod]` muestran el detalle, incluida la respuesta
cruda del saldo, útil para identificar los IDs de dimensión.

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

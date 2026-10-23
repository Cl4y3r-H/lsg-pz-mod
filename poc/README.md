# LSG Bridge PoC — prueba de concepto Java ↔ Lua (Project Zomboid Build 42)

Objetivo (actividad 2.2 de la carta Gantt): demostrar que un mod de Build 42 puede
**ejecutar código Java propio desde Lua** y **hacer peticiones de red sin congelar el juego**.
Si ambos pasos funcionan, la arquitectura híbrida Java/Lua de la propuesta queda validada.

## Cómo se carga Java en Build 42: ZombieBuddy

Los mods de Project Zomboid son oficialmente solo Lua. Para cargar Java se usa
**ZombieBuddy**, un framework open source basado en un *Java agent* que carga el JAR
declarado en `mod.info` y expone clases y funciones Java a Lua.

- Repositorio: https://github.com/zed-0xff/ZombieBuddy
- Workshop: https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853

## Estructura

```
lsg-bridge-poc/
├── mod/LSGBridgePoC/              ← carpeta del mod (copiar a ~/Zomboid/mods/)
│   ├── 42/
│   │   ├── mod.info               ← require=\ZombieBuddy, javaJarFile, javaPkgName
│   │   └── media/
│   │       ├── java/LSGBridgePoC.jar
│   │       └── lua/client/LSG_BridgePoC.lua
│   └── common/
├── java/                          ← código fuente Java + Gradle
│   └── src/main/java/cl/usach/lsg/bridge/
│       ├── Main.java              ← ZombieBuddy lo ejecuta al cargar el JAR
│       ├── LsgBridge.java         ← funciones expuestas a Lua
│       └── LsgHttp.java           ← peticiones HTTP asíncronas (no depende del juego)
└── tools/LocalCheck.java          ← prueba fuera del juego
```

## Pasos

### 1. Instalar ZombieBuddy
- **Windows:** ejecutar `ZombieBuddyInstaller.exe` (releases del repositorio) y elegir los modos de lanzamiento.
- **Linux:** copiar `ZombieBuddy.jar` a la carpeta del juego y agregar en las opciones de lanzamiento de Steam:
  `-javaagent:ZombieBuddy.jar --`
- Suscribirse o instalar el mod ZombieBuddy (Workshop).

### 2. Compilar el JAR (recomendado)
El ZIP incluye un `LSGBridgePoC.jar` precompilado, pero se generó contra *stubs* de las
anotaciones. Lo correcto es compilar contra los JAR reales:

```bash
cd java
gradle deploy -PpzDir="<carpeta de Project Zomboid>" -PzbJar="<ruta a ZombieBuddy.jar>"
```

Verificar que `projectzomboid.jar` exista en la carpeta del juego; si las clases de Kahlua
están en otra ubicación, ajustar la ruta en `build.gradle`.

### 3. Instalar el mod
Copiar `mod/LSGBridgePoC` a `~/Zomboid/mods/` (Windows: `C:\Users\<usuario>\Zomboid\mods\`),
activar **ZombieBuddy** y **LSG Bridge PoC** en el gestor de mods y empezar una partida.
ZombieBuddy puede pedir aprobar el JAR la primera vez.

### 4. Ejecutar la prueba
| Paso | Acción | Resultado esperado |
|---|---|---|
| 1 | Cargar la partida | El personaje dice `PASO 1 OK (...): pong desde Java 17.x ...` |
| 2 | Presionar **F9** | Tras unos segundos: `PASO 2 OK (tras N ticks sin congelar el juego): OK\|200\|...` |

Todo queda también en `~/Zomboid/console.txt` con el prefijo `[LSGBridgePoC]`.

## Interpretación del resultado

| Resultado | Significado | Siguiente paso |
|---|---|---|
| Paso 1 y 2 OK | Arquitectura híbrida validada | Continuar con 2.4–2.6 (estructura y cliente REST) |
| Paso 1 OK, paso 2 con `ERROR` | Java funciona; problema de red o URL | Revisar conexión, firewall o URL |
| Paso 1 OK, paso 2 congela el juego | No debería ocurrir: la petición es asíncrona | Revisar `console.txt` |
| `PASO 1 FALLO: Java no está expuesto` | ZombieBuddy no cargó el JAR | Revisar instalación, `mod.info` y `console.txt` |
| ZombieBuddy no funciona en B42 | Arquitectura híbrida inviable | Activar plan B (actividad 2.3): proceso externo + archivos |

## Prueba fuera del juego (ya ejecutada)

`tools/LocalCheck.java` simula el bucle de ticks de Lua contra un servidor local con 0,5 s de latencia:

```
Paso 1 -> pong desde Java 21.0.10 (LSGBridgePoC 0.1.0)
Caso 1 (responde)         -> tras 43 ticks sin bloquear: OK|200|{"status":"ok"}
Caso 2 (ruta inexistente) -> tras 32 ticks sin bloquear: OK|404|{"detail":"Not Found"}
Caso 3 (servidor caído)   -> tras 1 ticks sin bloquear: ERROR|ConnectException: sin detalle (¿servidor caído o sin red?)
Caso 4 (URL inválida)     -> tras 0 ticks sin bloquear: ERROR|IllegalArgumentException: ...
```

Para repetirla contra LSG real: `java -cp <clases> LocalCheck https://lsg.diinf.usach.cl/lsg-core-api`

## Consideraciones para la tesis

- **Instalación para participantes:** cada participante deberá instalar ZombieBuddy, que
  modifica las opciones de lanzamiento del juego. Hay que incluirlo en la guía de instalación.
- **Seguridad:** los mods Java tienen acceso irrestricto al equipo. Conviene declararlo ante el
  Comité de Ética y publicar el código fuente del mod.
- **Versión de Java:** el JAR se compila con bytecode Java 17, compatible con el runtime del
  juego aunque este use una versión superior.

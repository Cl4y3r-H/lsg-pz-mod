--[[
  LSG Bridge PoC — prueba de concepto Java <-> Lua para Project Zomboid Build 42.

  Paso 1 (al cargar la partida): Lua llama a una función escrita en Java.
  Paso 2 (tecla F9):             Java hace un GET a /health de LSG en segundo plano
                                 y Lua consulta el resultado en cada tick, sin congelar el juego.

  Los resultados se muestran sobre el personaje y en console.txt con el prefijo [LSGBridgePoC].
]]

local LSG_BASE = "https://lsg.diinf.usach.cl/lsg-core-api"
local POLL_EVERY_TICKS = 10

local bridge = nil      -- funciones Java resueltas
local pendingId = nil   -- id de la petición en curso
local waitedTicks = 0

local function report(msg)
    msg = tostring(msg)
    print("[LSGBridgePoC] " .. msg)
    local player = getPlayer()
    if player then player:Say(msg) end
end

-- Busca las funciones Java por las dos vías de exposición y dice cuál funcionó.
local function resolveBridge()
    if lsgPing and lsgHealth and lsgPoll then
        return { via = "funciones globales", ping = lsgPing, health = lsgHealth, poll = lsgPoll }
    end
    if LsgBridge and LsgBridge.ping then
        return {
            via = "clase LsgBridge",
            ping = function() return LsgBridge.ping() end,
            health = function(url) return LsgBridge.health(url) end,
            poll = function(id) return LsgBridge.poll(id) end,
        }
    end
    return nil
end

local function onGameStart()
    bridge = resolveBridge()
    if not bridge then
        report("PASO 1 FALLO: Java no esta expuesto a Lua. Revisar ZombieBuddy y console.txt")
        return
    end
    local ok, result = pcall(bridge.ping)
    if ok then
        report("PASO 1 OK (" .. bridge.via .. "): " .. tostring(result))
        report("Presiona F9 para el paso 2 (GET /health)")
    else
        report("PASO 1 FALLO al invocar Java: " .. tostring(result))
        bridge = nil
    end
end

local function onKeyPressed(key)
    if key ~= Keyboard.KEY_F9 or not bridge or pendingId then return end
    pendingId = bridge.health(LSG_BASE)
    waitedTicks = 0
    report("PASO 2: consultando " .. LSG_BASE .. "/health ...")
end

local function onTick()
    if not pendingId then return end
    waitedTicks = waitedTicks + 1
    if waitedTicks % POLL_EVERY_TICKS ~= 0 then return end

    local result = bridge.poll(pendingId)
    if result == "PENDING" then return end
    pendingId = nil

    -- El juego siguió corriendo mientras se esperaba: esa es la evidencia de no bloqueo.
    local detail = " (tras " .. waitedTicks .. " ticks sin congelar el juego)"
    if string.sub(result, 1, 3) == "OK|" then
        report("PASO 2 OK" .. detail .. ": " .. result)
    else
        report("PASO 2 FALLO" .. detail .. ": " .. result)
    end
end

Events.OnGameStart.Add(onGameStart)
Events.OnKeyPressed.Add(onKeyPressed)
Events.OnTick.Add(onTick)

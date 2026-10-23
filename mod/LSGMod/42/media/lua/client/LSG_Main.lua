--[[
  LSGMod — cliente Lua del mod LifeSync-Games para Project Zomboid (Build 42).

  Versión 0.3.2 (actividades 2.4, 2.6, 2.7, 2.9 y 2.12):
    * Al cargar la partida: revisa la configuración e inicia sesión en LSG.
    * Tecla F9: abre o cierra la ventana LifeSync-Games con el saldo por dimensión.

  Toda la red ocurre en Java (hilos secundarios). Lua solo inicia tareas y consulta
  su resultado cada pocos ticks, así el juego nunca se congela.

  LSG.state es la única fuente de datos de la interfaz: la ventana solo lo dibuja.
]]

LSG = LSG or {}

local POLL_EVERY_TICKS = 10
local TOGGLE_KEY = Keyboard.KEY_F9
local LOGIN_RETRY_TICKS = 600   -- ~10 s a 60 ticks por segundo
local LOGIN_MAX_RETRIES = 3

local tasks = {}                -- id de tarea -> función que recibe el resultado
local tickCount = 0
local loginRetries = 0
local loginRetryAt = nil

-- Estado compartido con la ventana.
--   connection: "idle" | "connecting" | "connected" | "offline" | "config" | "error"
LSG.state = {
    connection = "idle",
    playerId = nil,
    balances = {},              -- lista de { id = n, name = "...", points = n }
    loading = false,
    lastUpdateMs = nil,
    message = nil,              -- texto para el jugador (errores o avisos)
}

-- ------------------------------------------------------------------ utilidades
function LSG.nowMs()
    if getTimestampMs then return getTimestampMs() end
    return 0
end

local function say(msg)
    msg = tostring(msg)
    print("[LSGMod] " .. msg)
    local player = getPlayer()
    if player then player:Say(msg) end
end

local function notify()
    if LSG.window and LSG.window.onStateChanged then LSG.window:onStateChanged() end
end

-- Divide "A|B|C" en {"A", "B", "C"} (máximo n partes; la última conserva los separadores).
local function split(text, sep, n)
    local parts, start = {}, 1
    while not n or #parts < n - 1 do
        local i = string.find(text, sep, start, true)
        if not i then break end
        table.insert(parts, string.sub(text, start, i - 1))
        start = i + #sep
    end
    table.insert(parts, string.sub(text, start))
    return parts
end
LSG.split = split

local FRIENDLY = {
    CONFIG = "Falta configurar tu cuenta LSG",
    AUTH = "Correo o contrase\195\177a de LSG incorrectos",
    NETWORK = "Sin conexi\195\179n con LifeSync-Games",
    PARSE = "Respuesta inesperada de LSG",
}

local function errorCode(result)
    return split(result, "|", 3)[2] or "?"
end

local function isTransient(code)
    return code == "NETWORK" or string.sub(code, 1, 6) == "HTTP_5"
end

local function errorText(result)
    local p = split(result, "|", 3)       -- ERROR | CÓDIGO | mensaje
    local code, detail = p[2] or "?", p[3] or ""
    local base = FRIENDLY[code]
    if not base and string.sub(code, 1, 6) == "HTTP_5" then
        base = "LifeSync-Games no est\195\161 disponible por ahora"
    end
    print("[LSGMod] Detalle del error (" .. code .. "): " .. detail)
    return base or ("Error de LSG (" .. code .. ")")
end

-- "1=Dim 1=131;2=Físico=114" -> { {id=1,name="Dim 1",points=131}, ... }
local function parseBalances(data)
    local out = {}
    if data == nil or data == "" then return out end
    for _, item in ipairs(split(data, ";")) do
        local f = split(item, "=", 3)
        if #f == 3 then
            table.insert(out, { id = tonumber(f[1]), name = f[2], points = tonumber(f[3]) or 0 })
        end
    end
    return out
end

local function startTask(id, onDone)
    tasks[id] = onDone
end

-- --------------------------------------------------------------------- login
local onLoginResult

local function startLogin()
    LSG.state.connection = "connecting"
    LSG.state.message = nil
    notify()
    startTask(lsgModLogin(), onLoginResult)
end

onLoginResult = function(result)
    local st = LSG.state
    if string.sub(result, 1, 3) == "OK|" then
        local p = split(result, "|", 3)    -- OK | id_jugador | roles
        st.connection = "connected"
        st.playerId = p[2]
        st.message = nil
        loginRetries = 0
        say("LSG conectado (jugador " .. p[2] .. "). Presiona F9 para abrir LifeSync-Games")
    else
        local code = errorCode(result)
        local text = errorText(result)
        st.message = text
        if code == "CONFIG" then
            st.connection = "config"
        elseif isTransient(code) then
            st.connection = "offline"
            if loginRetries < LOGIN_MAX_RETRIES then
                loginRetries = loginRetries + 1
                loginRetryAt = tickCount + LOGIN_RETRY_TICKS
                st.message = text .. ". Reintentando (" .. loginRetries .. "/" .. LOGIN_MAX_RETRIES .. ")"
            end
        else
            st.connection = "error"
        end
        say(st.message)
    end
    notify()
end

-- --------------------------------------------------------------------- saldo
local function onBalanceResult(result)
    local st = LSG.state
    st.loading = false
    if string.sub(result, 1, 3) == "OK|" then
        st.balances = parseBalances(string.sub(result, 4))
        st.lastUpdateMs = LSG.nowMs()
        st.connection = "connected"
        st.message = nil
    else
        local code = errorCode(result)
        st.message = errorText(result)
        if isTransient(code) then st.connection = "offline" end
        if code == "CONFIG" then st.connection = "config" end
    end
    notify()
end

function LSG.requestBalance()
    if not lsgModBalanceData or LSG.state.loading then return end
    LSG.state.loading = true
    notify()
    startTask(lsgModBalanceData(), onBalanceResult)
end

-- -------------------------------------------------------------------- ventana
function LSG.toggleWindow()
    if LSG.window then
        LSG.window:close()
        return
    end
    local ok, err = pcall(function()
        LSG.window = ISLifeSyncWindow.open()
    end)
    if not ok then
        -- Si la interfaz falla (p. ej. un cambio de API en una actualización), el saldo
        -- se sigue pudiendo consultar con el mensaje sobre el personaje.
        print("[LSGMod] No se pudo abrir la ventana: " .. tostring(err))
        LSG.window = nil
        say("LSG: no se pudo abrir la ventana (ver console.txt)")
        return
    end
    LSG.requestBalance()
end

-- -------------------------------------------------------------------- eventos
local function onGameStart()
    if not lsgModStatus then
        LSG.state.connection = "error"
        LSG.state.message = "La capa Java no est\195\161 disponible. Revisa que ZombieBuddy est\195\169 activo"
        say("LSG: " .. LSG.state.message)
        return
    end
    print("[LSGMod] Versi\195\179n " .. lsgModVersion())
    print("[LSGMod] Prueba de tildes (debe decir cancion con tilde en la o): canci\195\179n")

    local status = lsgModStatus()
    if status == "OK" then
        startLogin()
        return
    end
    local p = split(status, "|", 2)
    LSG.state.connection = "config"
    if p[1] == "CREATED" or p[1] == "INCOMPLETE" then
        LSG.state.message = "Completa tu correo y contrase\195\177a en " .. tostring(p[2])
    else
        LSG.state.message = "No se pudo leer la configuraci\195\179n en " .. tostring(p[2])
    end
    say("LSG: " .. LSG.state.message)
end

local function onKeyPressed(key)
    if key == TOGGLE_KEY then LSG.toggleWindow() end
end

local function onTick()
    tickCount = tickCount + 1
    if loginRetryAt and tickCount >= loginRetryAt then
        loginRetryAt = nil
        startLogin()
    end
    if tickCount % POLL_EVERY_TICKS ~= 0 then return end

    local finished = {}
    for id, onDone in pairs(tasks) do
        local result = lsgModPoll(id)
        if result ~= "PENDING" then
            table.insert(finished, { id = id, onDone = onDone, result = result })
        end
    end
    -- Se eliminan después de recorrer la tabla, para no modificarla durante pairs().
    for _, f in ipairs(finished) do
        tasks[f.id] = nil
        f.onDone(f.result)
    end
end

Events.OnGameStart.Add(onGameStart)
Events.OnKeyPressed.Add(onKeyPressed)
Events.OnTick.Add(onTick)

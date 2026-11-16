--[[
  LSGMod — recompensas canjeadas desde el overlay del cliente LSG (versión 0.4.0).

  Flujo (contrato "LSG Game Adapter API" del cliente de escritorio LSG):
    1. El jugador canjea una mecánica en el overlay (tecla F3 del cliente LSG).
    2. El cliente descuenta los puntos en LifeSync-Games y envía POST /reward
       {"reward_id": n} al servidor local del mod (127.0.0.1:7878, capa Java).
    3. Java deja la recompensa en una cola; este archivo la toma en cada tick,
       la aplica en el hilo del juego y confirma el resultado a Java.

  Cada reward_id de este catálogo debe coincidir con el campo "reward_id" de las
  opciones de la mecánica registrada en LifeSync-Games (junto a cost_amount y
  attribute_id). Ver docs/registro_mecanicas.md.

  IMPORTANTE: los puntos ya se cobraron cuando llega la recompensa. Por eso el
  catálogo se valida al cargar la partida: una entrada inválida (objeto que no
  existe en esta versión del juego) se excluye y se avisa en console.txt, para
  corregirla antes de registrarla en LSG.
]]

LSG = LSG or {}
LSG.Rewards = LSG.Rewards or {}
local R = LSG.Rewards

local MAX_PER_TICK = 5

-- ------------------------------------------------------------------ catálogo
-- Valores provisionales del GDD (Memoria, cap. 3, Tabla 3.2).
--   kind = "item":          entrega count unidades de item (tipo completo, p. ej. "Base.Nails")
--   kind = "xp_multiplier": multiplicador de experiencia para perk entre minLevel y maxLevel,
--                           igual que al leer el libro de habilidad correspondiente
R.catalog = {
    -- Insumos (Físico)
    [101] = { name = "Clavos",            kind = "item", item = "Base.Nails",      count = 25 },
    [102] = { name = "Cinta adhesiva",    kind = "item", item = "Base.DuctTape",   count = 1 },
    [103] = { name = "Pegamento",         kind = "item", item = "Base.Glue",       count = 1 },
    -- Médico (Físico)
    [201] = { name = "Aguja de sutura",   kind = "item", item = "Base.SutureNeedle", count = 2 },
    [202] = { name = "Antibi\195\179ticos",      kind = "item", item = "Base.Antibiotics",  count = 1 },
    -- Anti-azar (Lingüístico)
    [301] = { name = "Revista de generadores", kind = "item", item = "Base.ElectronicsMag4", count = 1 },
    -- Aprendizaje (Lingüístico): ejemplo con Carpintería, volúmenes 1 y 2
    [401] = { name = "Carpinter\195\173a vol. 1 (x3, niveles 1-2)", kind = "xp_multiplier",
              perk = "Woodwork", multiplier = 3, minLevel = 0, maxLevel = 2 },
    [402] = { name = "Carpinter\195\173a vol. 2 (x5, niveles 3-4)", kind = "xp_multiplier",
              perk = "Woodwork", multiplier = 5, minLevel = 2, maxLevel = 4 },
}

R.status = "stopped"    -- "stopped" | "listening" | "error"
R.port = nil
R.lastMessage = nil

-- ---------------------------------------------------------------- utilidades
local function log(msg)
    print("[LSGMod] " .. tostring(msg))
end

local function notifyPlayer(player, text)
    log(text)
    local ok = pcall(function()
        HaloTextHelper.addTextWithArrow(player, text, true, HaloTextHelper.getColorGreen())
    end)
    if not ok then player:Say(text) end
end

local function playerReady()
    local player = getPlayer()
    return player ~= nil and not player:isDead()
end

local function itemExists(fullType)
    local ok, found = pcall(function()
        return getScriptManager():FindItem(fullType) ~= nil
    end)
    return ok and found
end

local function perkExists(name)
    return Perks ~= nil and Perks[name] ~= nil
end

-- Revisa que cada entrada se pueda aplicar en esta versión del juego.
local function validEntry(id, e)
    if e.kind == "item" then
        if not e.item or not itemExists(e.item) then
            return false, "el objeto " .. tostring(e.item) .. " no existe en esta versi\195\179n del juego"
        end
        return true
    elseif e.kind == "xp_multiplier" then
        if not perkExists(e.perk) then
            return false, "la habilidad " .. tostring(e.perk) .. " no existe"
        end
        return true
    end
    return false, "tipo desconocido: " .. tostring(e.kind)
end

-- ------------------------------------------------------------- aplicación
-- Entrega count unidades: al inventario, o al suelo junto al personaje si va sobrecargado (HU4).
local function giveItems(player, fullType, count)
    local inv = player:getInventory()
    local square = player:getCurrentSquare()
    local onFloor = 0
    for _ = 1, count do
        local overloaded = inv:getCapacityWeight() >= player:getMaxWeight()
        if overloaded and square then
            square:AddWorldInventoryItem(fullType, 0.5, 0.5, 0)
            onFloor = onFloor + 1
        else
            inv:AddItem(fullType)
        end
    end
    return onFloor
end

local function applyXpMultiplier(player, e)
    player:getXp():addXpMultiplier(Perks[e.perk], e.multiplier, e.minLevel, e.maxLevel)
end

-- Devuelve el resultado para Java: OK | UNKNOWN_REWARD|... | GAME_NOT_READY|... | FAILED|...
local function applyReward(rewardId)
    local e = R.catalog[rewardId]
    if not e then return "UNKNOWN_REWARD|no est\195\161 en el cat\195\161logo" end
    if not playerReady() then return "GAME_NOT_READY|no hay personaje vivo" end
    local player = getPlayer()

    local ok, err = pcall(function()
        if e.kind == "item" then
            local onFloor = giveItems(player, e.item, e.count or 1)
            local text = "LifeSync: " .. e.name
            if (e.count or 1) > 1 then text = text .. " x" .. e.count end
            if onFloor > 0 then text = text .. " (en el suelo: vas sobrecargado)" end
            notifyPlayer(player, text)
        elseif e.kind == "xp_multiplier" then
            applyXpMultiplier(player, e)
            notifyPlayer(player, "LifeSync: " .. e.name)
        else
            error("tipo desconocido: " .. tostring(e.kind))
        end
    end)
    if not ok then
        log("Error al aplicar la recompensa " .. rewardId .. ": " .. tostring(err))
        return "FAILED|" .. tostring(err)
    end
    return "OK"
end

-- ------------------------------------------------------------------ ciclo
-- Inicia el servidor local con las recompensas válidas. Se llama al cargar la partida.
function R.start()
    if not lsgModRewardStart then
        R.status = "error"
        R.lastMessage = "La capa Java no est\195\161 disponible"
        return false
    end
    local ids = {}
    for id, e in pairs(R.catalog) do
        local ok, why = validEntry(id, e)
        if ok then
            table.insert(ids, tostring(id))
        else
            log("AVISO: recompensa " .. id .. " (" .. tostring(e.name) .. ") excluida: " .. why
                .. ". No la registres en LSG hasta corregirla.")
        end
    end
    table.sort(ids)
    local result = lsgModRewardStart(table.concat(ids, ","))
    if string.sub(result, 1, 3) == "OK|" then
        R.status = "listening"
        R.port = string.sub(result, 4)
        R.lastMessage = nil
        log("Recompensas disponibles para el overlay: " .. table.concat(ids, ", "))
        return true
    end
    R.status = "error"
    R.lastMessage = "No se pudo abrir el puerto 7878 (\194\191otra instancia del juego abierta?)"
    log(R.lastMessage .. " Detalle: " .. result)
    return false
end

-- Se ejecuta en cada tick, también con el juego en pausa: latido y cola de recompensas.
function R.onTick()
    if R.status ~= "listening" then return end
    lsgModRewardHeartbeat(playerReady() and "1" or "0")
    for _ = 1, MAX_PER_TICK do
        local nextReward = lsgModRewardNext()
        if nextReward == nil or nextReward == "" then break end
        local sep = string.find(nextReward, "|", 1, true)
        local ticket = string.sub(nextReward, 1, sep - 1)
        local rewardId = tonumber(string.sub(nextReward, sep + 1))
        lsgModRewardDone(ticket, applyReward(rewardId))
    end
end

if Events.OnTickEvenPaused then
    Events.OnTickEvenPaused.Add(R.onTick)
else
    Events.OnTick.Add(R.onTick)
end

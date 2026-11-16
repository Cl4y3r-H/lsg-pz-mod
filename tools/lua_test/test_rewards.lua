-- Pruebas de LSG_Rewards.lua y LSG_Main.lua con la API de Project Zomboid y la capa Java simuladas.
--   lua tools/lua_test/test_rewards.lua mod/LSGMod/42/media/lua/client
local MOD = arg[1]
local passed, failed = 0, 0
local function check(name, ok) if ok then passed = passed + 1 else failed = failed + 1 end
  print((ok and "  OK     " or "  FALLA  ") .. name) end

-- Eventos
local handlers = {}
Events = setmetatable({}, { __index = function(t, k)
  local ev = { Add = function(f) handlers[k] = handlers[k] or {}; table.insert(handlers[k], f) end }
  rawset(t, k, ev); return ev end })
local function fire(name, ...) for _, f in ipairs(handlers[name] or {}) do f(...) end end
Keyboard = { KEY_F9 = 67 }

-- Mundo simulado
local KNOWN_ITEMS = { ["Base.Nails"]=1, ["Base.DuctTape"]=1, ["Base.Glue"]=1, ["Base.SutureNeedle"]=1,
  ["Base.Antibiotics"]=1 }   -- ElectronicsMag4 NO existe: debe quedar excluida
function getScriptManager() return { FindItem = function(_, t) return KNOWN_ITEMS[t] end } end
Perks = { Woodwork = "PERK_WOODWORK" }
local floor, said, xpCalls = {}, {}, {}
local inventory = { items = {}, weight = 10 }
function inventory:getCapacityWeight() return self.weight end
function inventory:AddItem(t) table.insert(self.items, t); self.weight = self.weight + 1 end
local player = { dead = false, maxWeight = 50 }
function player:isDead() return self.dead end
function player:getInventory() return inventory end
function player:getMaxWeight() return self.maxWeight end
function player:getCurrentSquare() return { AddWorldInventoryItem = function(_, t) table.insert(floor, t) end } end
function player:getXp() return { addXpMultiplier = function(_, perk, m, a, b) table.insert(xpCalls, {perk, m, a, b}) end } end
function player:Say(t) table.insert(said, t) end
local currentPlayer = player
function getPlayer() return currentPlayer end
HaloTextHelper = { getColorGreen = function() return "green" end,
  addTextWithArrow = function(p, t) table.insert(said, t) end }

-- Capa Java simulada
local registered, queue, results, heartbeats = nil, {}, {}, {}
local ticket = 0
function lsgModRewardStart(ids) registered = ids; return "OK|7878" end
function lsgModRewardHeartbeat(r) table.insert(heartbeats, r); return "OK" end
function lsgModRewardNext() return table.remove(queue, 1) or "" end
function lsgModRewardDone(t, r) results[t] = r; return "OK" end
local function receive(id) ticket = ticket + 1; table.insert(queue, ticket .. "|" .. id); return tostring(ticket) end
function lsgModVersion() return "0.4.0" end
function lsgModStatus() return "CREATED|C:/Users/x/Zomboid/LSG/lsg.properties" end
function lsgModPoll() return "PENDING" end
function getTimestampMs() return 0 end

dofile(MOD .. "/LSG_Main.lua")
dofile(MOD .. "/LSG_Rewards.lua")
check("1. Usa OnTickEvenPaused para el latido", handlers.OnTickEvenPaused ~= nil)

fire("OnGameStart")
check("2. Inicia el adaptador al cargar la partida aunque no haya cuenta en el mod", LSG.Rewards.status == "listening")
check("2b. Registra solo recompensas válidas: " .. tostring(registered), registered == "101,102,103,201,202,401,402")
check("2c. Excluye la revista (objeto inexistente)", not string.find(registered, "301"))
check("2d. Sin cuenta configurada no muestra error, sino aviso del overlay: " .. tostring(said[1]),
  said[1] ~= nil and string.find(said[1], "overlay", 1, true) ~= nil)

fire("OnTickEvenPaused")
check("3. Latido con personaje vivo = 1", heartbeats[#heartbeats] == "1")

local t = receive(101); fire("OnTickEvenPaused")
check("4. Clavos: 25 unidades al inventario → OK", results[t] == "OK" and #inventory.items == 25)

inventory.weight = 60   -- sobrecargado
t = receive(102); fire("OnTickEvenPaused")
check("5. Sobrecargado: la cinta queda en el suelo (HU4)", results[t] == "OK" and floor[1] == "Base.DuctTape")
check("5b. Avisa que quedó en el suelo: " .. said[#said], string.find(said[#said], "suelo", 1, true) ~= nil)
inventory.weight = 10

t = receive(401); fire("OnTickEvenPaused")
local c = xpCalls[1]
check("6. Multiplicador: addXpMultiplier(Woodwork, 3, 0, 2)", results[t] == "OK" and c and c[1] == "PERK_WOODWORK"
  and c[2] == 3 and c[3] == 0 and c[4] == 2)

t = receive(999); fire("OnTickEvenPaused")
check("7. Id fuera del catálogo → UNKNOWN_REWARD", string.sub(results[t], 1, 14) == "UNKNOWN_REWARD")

player.dead = true
t = receive(101); fire("OnTickEvenPaused")
check("8. Personaje muerto → GAME_NOT_READY y latido 0", string.sub(results[t], 1, 14) == "GAME_NOT_READY"
  and heartbeats[#heartbeats] == "0")
player.dead = false

local orig = inventory.AddItem
inventory.AddItem = function() error("fallo simulado") end
t = receive(103); fire("OnTickEvenPaused")
check("9. Error del juego al entregar → FAILED con detalle", string.sub(results[t], 1, 7) == "FAILED|")
inventory.AddItem = orig

for i = 1, 7 do receive(101) end
fire("OnTickEvenPaused")
check("10. Máximo 5 recompensas por tick (quedan 2 en cola)", #queue == 2)
fire("OnTickEvenPaused")
check("10b. El siguiente tick procesa el resto", #queue == 0)

currentPlayer = nil
fire("OnTickEvenPaused")
check("11. En el menú (sin personaje) el latido es 0", heartbeats[#heartbeats] == "0")

print(("\nResultado: %d aprobadas, %d fallidas"):format(passed, failed))
os.exit(failed == 0 and 0 or 1)

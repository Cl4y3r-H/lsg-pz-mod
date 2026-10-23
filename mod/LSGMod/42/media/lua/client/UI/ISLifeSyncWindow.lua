--[[
  ISLifeSyncWindow — ventana del mod LifeSync-Games (GDD §8, actividad 2.12).

  Versión temprana: muestra el estado de la conexión y el saldo por dimensión,
  con botones para actualizar y cerrar. El catálogo de recompensas (pestañas,
  canjes) se agregará en la fase F4.

  La ventana no habla con LSG: solo dibuja LSG.state y llama a LSG.requestBalance().
]]

require "ISUI/ISCollapsableWindow"
require "ISUI/ISButton"

ISLifeSyncWindow = ISCollapsableWindow:derive("ISLifeSyncWindow")

local FONT = UIFont.Small
local FONT_TITLE = UIFont.Medium
local PAD = 12
local WIDTH = 330
local HEIGHT = 300
local MAX_ROWS = 6

local COLORS = {
    connected  = { 0.55, 0.90, 0.55 },
    connecting = { 0.95, 0.85, 0.40 },
    offline    = { 0.95, 0.40, 0.40 },
    config     = { 0.95, 0.70, 0.35 },
    error      = { 0.95, 0.40, 0.40 },
    idle       = { 0.80, 0.80, 0.80 },
    text       = { 0.95, 0.95, 0.95 },
    muted      = { 0.65, 0.65, 0.65 },
}

local STATUS_TEXT = {
    connected  = "Conectado",
    connecting = "Conectando...",
    offline    = "Sin conexi\195\179n",
    config     = "Falta configuraci\195\179n",
    error      = "Error",
    idle       = "Sin iniciar",
}

-- ------------------------------------------------------------------ creación
function ISLifeSyncWindow.open()
    local x = (getCore():getScreenWidth() - WIDTH) / 2
    local y = (getCore():getScreenHeight() - HEIGHT) / 2
    local w = ISLifeSyncWindow:new(x, y, WIDTH, HEIGHT)
    w:initialise()
    w:addToUIManager()
    return w
end

function ISLifeSyncWindow:new(x, y, width, height)
    local o = ISCollapsableWindow.new(self, x, y, width, height)
    o.title = "LifeSync-Games"
    o.resizable = false
    o.lineH = getTextManager():getFontHeight(FONT) + 4
    o.titleLineH = getTextManager():getFontHeight(FONT_TITLE) + 4
    return o
end

function ISLifeSyncWindow:createChildren()
    ISCollapsableWindow.createChildren(self)
    local btnH = math.max(25, self.lineH + 8)
    local btnW = 120
    local y = self:getHeight() - btnH - PAD

    self.btnRefresh = ISButton:new(PAD, y, btnW, btnH, "Actualizar", self, ISLifeSyncWindow.onRefreshClick)
    self.btnRefresh:initialise()
    self:addChild(self.btnRefresh)

    self.btnClose = ISButton:new(self:getWidth() - btnW - PAD, y, btnW, btnH, "Cerrar", self, ISLifeSyncWindow.onCloseClick)
    self.btnClose:initialise()
    self:addChild(self.btnClose)

    self:onStateChanged()
end

-- -------------------------------------------------------------------- acciones
function ISLifeSyncWindow:onRefreshClick()
    LSG.requestBalance()
end

function ISLifeSyncWindow:onCloseClick()
    self:close()
end

function ISLifeSyncWindow:close()
    self:setVisible(false)
    self:removeFromUIManager()
    if LSG.window == self then LSG.window = nil end
end

-- Se llama cuando cambia LSG.state: solo ajusta los botones; render() dibuja el resto.
function ISLifeSyncWindow:onStateChanged()
    if not self.btnRefresh then return end
    local st = LSG.state
    local busy = st.loading or st.connection == "connecting"
    self.btnRefresh:setEnable(not busy and st.connection ~= "config")
    self.btnRefresh:setTitle(st.loading and "Consultando..." or "Actualizar")
end

-- ---------------------------------------------------------------------- dibujo
local function drawColored(self, text, x, y, c, font)
    self:drawText(text, x, y, c[1], c[2], c[3], 1, font or FONT)
end

-- Divide un texto en líneas que caben en maxWidth píxeles (corte por palabras).
local function wrap(text, maxWidth)
    local lines, current = {}, ""
    for word in string.gmatch(text, "%S+") do
        local candidate = current == "" and word or (current .. " " .. word)
        if current ~= "" and getTextManager():MeasureStringX(FONT, candidate) > maxWidth then
            table.insert(lines, current)
            current = word
        else
            current = candidate
        end
    end
    if current ~= "" then table.insert(lines, current) end
    return lines
end

function ISLifeSyncWindow:render()
    ISCollapsableWindow.render(self)
    if self.isCollapsed then return end

    local st = LSG.state
    local right = self:getWidth() - PAD
    local y = (self.titleBarHeight and self:titleBarHeight() or 20) + PAD

    -- Estado de la conexión (indicador del caso E1 del GDD).
    local statusColor = COLORS[st.connection] or COLORS.idle
    local status = STATUS_TEXT[st.connection] or st.connection
    if st.connection == "connected" and st.playerId then
        status = status .. " \194\183 jugador " .. tostring(st.playerId)
    end
    drawColored(self, "Estado: " .. status, PAD, y, statusColor)
    y = y + self.lineH + 6

    -- Saldo por dimensión.
    drawColored(self, "Saldo por dimensi\195\179n", PAD, y, COLORS.text, FONT_TITLE)
    y = y + self.titleLineH + 2

    if #st.balances == 0 then
        local empty = st.lastUpdateMs and "Sin puntos registrados" or "Presiona Actualizar"
        drawColored(self, empty, PAD, y, COLORS.muted)
        y = y + self.lineH
    else
        for i, b in ipairs(st.balances) do
            if i > MAX_ROWS then
                drawColored(self, "(" .. (#st.balances - MAX_ROWS) .. " m\195\161s)", PAD, y, COLORS.muted)
                y = y + self.lineH
                break
            end
            drawColored(self, b.name, PAD, y, COLORS.text)
            self:drawTextRight(tostring(b.points) .. " pts", right, y, 1, 1, 1, 1, FONT)
            y = y + self.lineH
        end
    end

    -- Última actualización.
    if st.lastUpdateMs and st.lastUpdateMs > 0 then
        local secs = math.floor((LSG.nowMs() - st.lastUpdateMs) / 1000)
        drawColored(self, "Actualizado hace " .. tostring(math.max(secs, 0)) .. " s", PAD, y + 4, COLORS.muted)
    end

    -- Mensaje de error o aviso, encima de los botones.
    if st.message then
        local btnTop = self.btnRefresh and self.btnRefresh:getY() or (self:getHeight() - 40)
        local lines = wrap(st.message, self:getWidth() - 2 * PAD)
        local my = btnTop - 6 - #lines * self.lineH
        for _, line in ipairs(lines) do
            drawColored(self, line, PAD, my, statusColor)
            my = my + self.lineH
        end
    end
end

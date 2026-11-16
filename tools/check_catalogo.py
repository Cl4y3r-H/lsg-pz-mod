"""Verifica que el catálogo de Lua y tools/lsg/catalogo_recompensas.json tengan los mismos reward_id.

    python tools/check_catalogo.py
"""
import json
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
lua = (root / "mod/LSGMod/42/media/lua/client/LSG_Rewards.lua").read_text(encoding="utf-8")
lua_ids = {int(x) for x in re.findall(r"^\s*\[(\d+)\]\s*=\s*\{", lua, re.MULTILINE)}
data = json.loads((root / "tools/lsg/catalogo_recompensas.json").read_text(encoding="utf-8"))
json_ids = [m["reward_id"] for m in data["mecanicas"]]

errors = []
if len(json_ids) != len(set(json_ids)):
    errors.append("reward_id repetidos en el JSON")
if lua_ids - set(json_ids):
    errors.append(f"en Lua pero no en el JSON: {sorted(lua_ids - set(json_ids))}")
if set(json_ids) - lua_ids:
    errors.append(f"en el JSON pero no en Lua: {sorted(set(json_ids) - lua_ids)}")
for m in data["mecanicas"]:
    if m["attribute_id"] not in (2, 5) or m["cost_amount"] <= 0:
        errors.append(f"reward_id {m['reward_id']}: attribute_id o cost_amount inválido")

if errors:
    print("Catálogo inconsistente:\n  " + "\n  ".join(errors))
    sys.exit(1)
print(f"Catálogo consistente: {len(lua_ids)} recompensas ({', '.join(map(str, sorted(lua_ids)))})")

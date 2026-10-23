"""Convierte los caracteres no ASCII de los textos Lua a escapes decimales de sus bytes UTF-8.

En Build 42, Kahlua arma los textos como bytes y luego los decodifica como UTF-8. Un literal
con "ó" escrito directamente, o como \\243, termina como un byte suelto inválido y se ve "?".
Escribiendo los bytes UTF-8 de cada carácter ("ó" = \\195\\179) el archivo queda en ASCII
y el texto se decodifica bien. Los comentarios no se modifican.

Uso: python tools/lua_ascii.py mod/LSGMod/42/media/lua
"""
import pathlib, sys

def convert(src: str) -> str:
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith("--[[", i):                      # comentario de bloque
            j = src.find("]]", i + 4); j = n if j < 0 else j + 2
            out.append(src[i:j]); i = j; continue
        if src.startswith("--", i):                        # comentario de línea
            j = src.find("\n", i); j = n if j < 0 else j
            out.append(src[i:j]); i = j; continue
        if c in "\"'":                                     # literal de texto
            q, j, buf = c, i + 1, [c]
            while j < n and src[j] != q:
                ch = src[j]
                if ch == "\\":
                    buf.append(src[j:j + 2]); j += 2; continue
                if ord(ch) > 127:
                    buf.append("".join("\\%03d" % b for b in ch.encode("utf-8")))
                else:
                    buf.append(ch)
                j += 1
            buf.append(q); out.append("".join(buf)); i = j + 1; continue
        out.append(c); i += 1
    return "".join(out)

if __name__ == "__main__":
    for path in pathlib.Path(sys.argv[1]).rglob("*.lua"):
        text = path.read_text(encoding="utf-8")
        new = convert(text)
        if new != text:
            path.write_text(new, encoding="utf-8")
            print("convertido:", path)

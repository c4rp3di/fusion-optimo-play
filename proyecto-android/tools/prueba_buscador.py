"""Modelo de la búsqueda global de ListasActivity.buscar(): normaliza (minúsculas, sin acentos), exige TODAS las palabras en
nombre + categoría + número + tvg-id, junta duplicados (mismo nombre y URL) de varias listas y ordena: empieza por → contiene en el nombre → resto."""
import unicodedata

def norm(t): return "".join(c for c in unicodedata.normalize("NFD", t.lower()) if not unicodedata.category(c).startswith("M"))

class Canal:
    def __init__(s, nombre, url, grupo="Canales", numero=0, tvg=None): s.nombre, s.url, s.grupo, s.numero, s.tvg = nombre, url, grupo, numero, tvg

def indexar(listas):  # listas: [(nombreLista, [Canal])]
    idx = []
    for nl, canales in listas:
        for c in canales:
            nn = norm(c.nombre)
            idx.append(dict(c=c, lista=nl, norm=nn + " " + norm(c.grupo) + " " + str(c.numero) + " " + norm(c.tvg or ""), nn=nn))
    return idx

def buscar(idx, q):
    terminos = [t for t in norm(q.strip()).split(" ") if t]
    if not terminos: return []
    mapa = {}
    for e in idx:
        if not all(t in e["norm"] for t in terminos): continue
        k = e["nn"] + "|" + e["c"].url
        h = mapa.get(k)
        if h is None: mapa[k] = dict(e=e, otras=[])
        elif h["e"]["lista"] != e["lista"] and e["lista"] not in h["otras"]: h["otras"].append(e["lista"])
    rango = lambda h: 0 if h["e"]["nn"].startswith(terminos[0]) else (1 if all(t in h["e"]["nn"] for t in terminos) else 2)
    return sorted(mapa.values(), key=rango)   # sorted es estable, igual que sortedBy

listas = [
    ("Favoritos", [Canal("La 1 HD", "http://a/1", "Nacionales", 1)]),
    ("Lista TDT", [Canal("La 1 HD", "http://a/1", "Nacionales", 1), Canal("La Sexta", "http://a/6", "Nacionales", 6),
                   Canal("Canal Sur Andalucía", "http://a/cs", "Autonómicos", 40), Canal("Teledeporte", "http://a/td", "Deportes", 5)]),
    ("Lista Mundo", [Canal("Eurosport 1", "http://b/e1", "Deportes", 0, "eurosport.es"), Canal("Movistar La Liga", "http://b/ml", "Deportes", 0),
                    Canal("Antena 3", "http://b/a3", "Nacionales", 3), Canal("La 1 HD", "http://otra/1", "Nacionales", 1)]),
]
idx = indexar(listas)

def nombres(r): return [h["e"]["c"].nombre for h in r]

r = buscar(idx, "la 1")
assert nombres(r) == ["La 1 HD", "La 1 HD"], nombres(r)     # misma etiqueta pero URL distinta → dos resultados; el repetido entre listas se junta
assert r[0]["otras"] == ["Lista TDT"], r[0]["otras"]          # «La 1 HD» (http://a/1) está en Favoritos y en Lista TDT
print("duplicados entre listas juntos: OK ->", [(h['e']['lista'], h['otras']) for h in r])

assert nombres(buscar(idx, "ANDALUCIA")) == ["Canal Sur Andalucía"]          # sin acentos ni mayúsculas
assert nombres(buscar(idx, "andalucía canal")) == ["Canal Sur Andalucía"]    # varias palabras en cualquier orden
print("acentos / mayúsculas / varias palabras: OK")

r = buscar(idx, "deportes")                                                   # solo en la categoría
assert sorted(nombres(r)) == ["Eurosport 1", "Movistar La Liga", "Teledeporte"], nombres(r)
r = buscar(idx, "de")                                                         # «de» en el nombre (Teledeporte) antes que solo en la categoría (Deportes)
print("orden para «de»:", nombres(r))
assert nombres(r)[0] == "Teledeporte"
assert nombres(buscar(idx, "eurosport.es")) == ["Eurosport 1"]               # por tvg-id
assert nombres(buscar(idx, "40")) == ["Canal Sur Andalucía"]                  # por número de canal
assert buscar(idx, "   ") == [] and buscar(idx, "zzzz") == []
print("tvg-id / número / vacío / sin resultados: OK")

# orden: empieza por → contiene en el nombre → solo en categoría
extra = indexar([("X", [Canal("Liga Deportes", "u1", "Otros"), Canal("Teledeporte", "u2", "Otros"), Canal("Mar", "u3", "Deportes"), Canal("Deportes 1", "u4", "Otros")])])
assert nombres(buscar(extra, "deportes")) == ["Deportes 1", "Liga Deportes", "Mar"], nombres(buscar(extra, "deportes"))
print("orden empieza por → contiene → categoría: OK")
print("TODO OK")

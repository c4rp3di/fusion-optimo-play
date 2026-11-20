"""Valida la lógica de RarAlmacenado.kt (leerVolumen + ensamblado + MegaVirtual) contra volúmenes RAR5 sintéticos
construidos según la especificación (https://www.rarlab.com/technote.htm): firma, bloques con vint, volúmenes que continúan."""
import os, zlib, random

def vint(n):
    out = bytearray()
    while True:
        b = n & 0x7F; n >>= 7
        if n: out.append(b | 0x80)
        else: out.append(b); break
    return bytes(out)

def bloque(tipo, flags, cuerpo, extra=b"", datos=b""):
    # cuerpo = campos específicos del tipo; extra = área extra (al final de la cabecera)
    f = flags
    campos = vint(tipo) + vint(f | (1 if extra else 0) | (2 if datos else 0))
    if extra: campos += vint(len(extra))
    if datos: campos += vint(len(datos))
    cab = campos + cuerpo + extra
    tam = vint(len(cab))
    crc = zlib.crc32(tam + cab) & 0xFFFFFFFF
    return crc.to_bytes(4, "little") + tam + cab + datos

FIRMA5 = bytes([0x52,0x61,0x72,0x21,0x1A,0x07,0x01,0x00])

def principal(num):
    cuerpo = vint(0x1 | (0x2 if num > 0 else 0)) + (vint(num) if num > 0 else b"")
    return bloque(1, 0, cuerpo)

def archivo(nombre, datos, total, flags_cont=0, metodo=0, cifrado=False, con_crc=True, con_mtime=True):
    ff = (0x2 if con_mtime else 0) | (0x4 if con_crc else 0)
    cuerpo = vint(ff) + vint(total) + vint(0x20)
    if con_mtime: cuerpo += (1700000000).to_bytes(4, "little")
    if con_crc: cuerpo += (zlib.crc32(datos) & 0xFFFFFFFF).to_bytes(4, "little")
    comp = 0 | (metodo << 7)
    cuerpo += vint(comp) + vint(1) + vint(len(nombre.encode())) + nombre.encode()
    extra = b""
    if cifrado:
        reg = vint(1) + b"\x00" * 10   # tipo 1 = registro de cifrado
        extra += vint(len(reg)) + reg
    # un registro «hash» (tipo 2) para comprobar que se saltan registros extra
    reg2 = vint(2) + b"\x00" + b"\xAA" * 32
    extra = vint(len(reg2)) + reg2 + extra
    return bloque(2, flags_cont, cuerpo, extra=extra, datos=datos)

def servicio_rr(datos):
    cuerpo = vint(0) + vint(len(datos)) + vint(0) + vint(0) + vint(1) + vint(0) + vint(len(b"RR")) + b"RR"
    return bloque(3, 0, cuerpo, datos=datos)

FIN = lambda flags=0: bloque(5, 0, vint(flags))

def construir(archivos, capacidad, con_rr=False):
    """Volúmenes llenos hasta `capacidad` (como RAR real: datos + bloque de fin de ~8 bytes al final)."""
    vols = []
    num = 0
    actual = FIRMA5 + principal(0)
    fin_len = len(FIN(0x1))
    for nombre, contenido in archivos:
        resto = contenido; primero = True
        while True:
            # tamaño de datos que cabe: se prueba hasta que cabecera + datos + fin llenen el volumen
            libre = capacidad - len(actual) - fin_len
            trozo_len = min(len(resto), libre)
            while True:
                flags = (0x8 if not primero else 0) | (0x10 if len(resto) > trozo_len else 0)
                blq = archivo(nombre, resto[:trozo_len], len(contenido), flags_cont=flags)
                if len(blq) <= libre or trozo_len == 0: break
                trozo_len -= len(blq) - libre
            if len(blq) > libre or (trozo_len == 0 and resto):
                # no cabe ni la cabecera: nuevo volumen
                vols.append(actual + FIN(0x1)); num += 1
                actual = FIRMA5 + principal(num)
                continue
            actual += blq
            resto = resto[trozo_len:]; primero = False
            if not resto: break
            vols.append(actual + FIN(0x1)); num += 1
            actual = FIRMA5 + principal(num)
    if con_rr:
        actual += servicio_rr(b"\x55" * 700)
    vols.append(actual + FIN(0))
    return vols

# ---------------- port de RarAlmacenado.leerVolumen
class Lec:
    def __init__(s, b, p=0): s.b, s.p = b, p
    def vint(s):
        r = 0; sh = 0
        while True:
            if s.p >= len(s.b) or sh > 63: raise IOError("dañada")
            x = s.b[s.p]; s.p += 1
            r |= (x & 0x7F) << sh
            if not x & 0x80: return r
            sh += 7
    def salta(s, n):
        if n < 0 or s.p + n > len(s.b): raise IOError("dañada")
        s.p += n
    def bytes(s, n):
        if n < 0 or s.p + n > len(s.b): raise IOError("dañada")
        r = s.b[s.p:s.p+n]; s.p += n; return r

lecturas = 0
def leer_volumen(vol):
    global lecturas
    total = len(vol)
    cache = [b"", 0]
    def leer(ini, n):
        global lecturas
        fin = min(total, ini + n)
        if ini >= cache[1] and fin <= cache[1] + len(cache[0]):
            return cache[0][ini-cache[1]:fin-cache[1]]
        pedir = min(max(fin - ini, 16384), total - ini)
        lecturas += 1
        b = vol[ini:ini+pedir]; cache[0] = b; cache[1] = ini
        return b[:fin-ini]
    firma = leer(0, 8)
    assert firma == FIRMA5
    salida = []; pos = 8; guarda = 0
    while pos + 12 < total and guarda < 20000:
        guarda += 1
        buf = leer(pos, min(4096, total - pos))
        l = Lec(buf, 4)
        hsize = l.vint(); tras = l.p; blq = tras + hsize
        if hsize < 2 or blq > total - pos or hsize > (8 << 20): raise IOError("dañada")
        if blq > len(buf):
            buf = leer(pos, blq); l = Lec(buf, tras)
        fin_cab = blq
        tipo = l.vint(); flags = l.vint()
        extra = l.vint() if flags & 1 else 0
        datos = l.vint() if flags & 2 else 0
        if tipo == 2:
            ff = l.vint(); l.vint(); l.vint()
            if ff & 2: l.salta(4)
            if ff & 4: l.salta(4)
            comp = l.vint(); l.vint(); nl = l.vint()
            nombre = l.bytes(nl).decode()
            metodo = (comp >> 7) & 7
            cifrado = False
            if 0 < extra <= fin_cab:
                e = Lec(buf, fin_cab - extra)
                while e.p < fin_cab:
                    sz = e.vint(); ini = e.p
                    if sz <= 0: break
                    if e.vint() == 1: cifrado = True
                    e.p = ini + sz
            salida.append(dict(nombre=nombre, metodo=metodo, cifrado=cifrado, es_dir=bool(ff & 1),
                               de_anterior=bool(flags & 8), a_siguiente=bool(flags & 0x10), desde=pos + blq, tam=datos))
        elif tipo == 4: raise RuntimeError("cabeceras cifradas")
        elif tipo == 5: return salida
        pos += blq + datos
        if datos > 0 and total - pos <= 32: break
    return salida

def indexar(vols):
    mapa = {}
    for i, vol in enumerate(vols):
        for h in leer_volumen(vol):
            if h["es_dir"]: continue
            a = mapa.get(h["nombre"])
            if a is None:
                if h["de_anterior"]: continue
                a = dict(segs=[], alm=True, cif=False, sig=False); mapa[h["nombre"]] = a
            elif not h["de_anterior"]: continue
            a["segs"].append((i, h["desde"], h["tam"]))
            a["alm"] = a["alm"] and h["metodo"] == 0
            a["cif"] = a["cif"] or h["cifrado"]; a["sig"] = h["a_siguiente"]
    return mapa

def leer_virtual(vols, segs, ini, fin):
    """port de MegaVirtual.abrir/volcar"""
    inicios = []; acc = 0
    for (_, _, t) in segs: inicios.append(acc); acc += t
    out = bytearray()
    for (i, (p, desde, t)) in enumerate(segs):
        if t == 0: continue
        s0 = inicios[i]; s1 = s0 + t - 1
        if s1 < ini or s0 > fin: continue
        a = max(ini, s0); b = min(fin, s1)
        out += vols[p][desde + (a - s0): desde + (b - s0) + 1]
    return bytes(out)

random.seed(7)
video = bytes(random.getrandbits(8) for _ in range(300_000))
nfo = b"hola nfo " * 50
for cap, rr in [(70_000, False), (70_000, True), (120_000, False), (1_000_000, True)]:
    vols = construir([("Pelicula/peli.mkv", video), ("peli.nfo", nfo)], cap, con_rr=rr)
    lecturas = 0
    idx = indexar(vols)
    e = idx["Pelicula/peli.mkv"]
    tam = sum(t for _, _, t in e["segs"])
    assert e["alm"] and not e["cif"] and not e["sig"], e
    assert tam == len(video), (tam, len(video))
    assert leer_virtual(vols, e["segs"], 0, tam - 1) == video
    for _ in range(300):
        a = random.randrange(tam); b = random.randrange(a, min(tam, a + 90_000))
        assert leer_virtual(vols, e["segs"], a, b) == video[a:b+1], (a, b)
    assert leer_virtual(vols, e["segs"], tam - 1, tam - 1) == video[-1:]
    n = idx["peli.nfo"]; assert leer_virtual(vols, n["segs"], 0, sum(t for *_, t in n["segs"]) - 1) == nfo
    print(f"cap={cap:>9} rr={rr!s:5} volúmenes={len(vols):2}  segmentos={len(e['segs']):2}  lecturas remotas={lecturas}  OK")

# casos de no indexable / cifrado / comprimido
vols = [FIRMA5 + principal(0) + archivo("v.mkv", b"x" * 1000, 1000, metodo=3) + FIN()]
e = indexar(vols)["v.mkv"]; assert not e["alm"]; print("método ≠ 0 detectado (hay que extraer): OK")
vols = [FIRMA5 + principal(0) + archivo("v.mkv", b"x" * 1000, 1000, cifrado=True) + FIN()]
e = indexar(vols)["v.mkv"]; assert e["cif"]; print("registro de cifrado detectado: OK")
vols = [FIRMA5 + bloque(4, 0, vint(0) + vint(0)) + principal(0) + FIN()]
try: indexar(vols); raise SystemExit("debía fallar")
except RuntimeError: print("cabeceras cifradas → no indexable: OK")
print("TODO OK")

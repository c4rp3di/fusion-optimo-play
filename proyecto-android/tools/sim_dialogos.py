"""Modelo de la secuencia de diálogos del reproductor ante archivos comprimidos.
Android: al pulsar un elemento de un AlertDialog se ejecuta primero el onClick (que puede abrir otro diálogo AL INSTANTE)
y el cierre del diálogo pulsado llega DESPUÉS (mensaje en la cola), disparando su OnDismissListener."""

class Dlg:
    def __init__(s, tipo): s.tipo, s.visible, s.on_dismiss = tipo, True, None

def simular(logica):
    svc = dict(necesita=True)          # necesitaElegirPaquete
    pantalla = []                      # diálogos visibles (el último está encima)
    estado = dict(pidiendo=False, dlg=None)
    historial = []

    def mostrar(tipo, on_dismiss=None):
        d = Dlg(tipo); d.on_dismiss = on_dismiss; pantalla.append(d); historial.append(tipo); return d
    def cerrar(d):                     # cierre asíncrono: llega después del onClick
        d.visible = False
        pantalla.remove(d)
        if d.on_dismiss: d.on_dismiss(d)

    # ------------ lógica ANTIGUA (0.9.1): banderas repuestas en OnDismissListener
    if logica == "antigua":
        def poll():
            if svc["necesita"] and not estado["pidiendo"]:
                estado["pidiendo"] = True; mostrar("lista")
        def reset(d): estado["pidiendo"] = False
        def abrir_menu(): return mostrar("menu", reset)
        def abrir_confirmar(): return mostrar("confirmar", reset)
    # ------------ lógica NUEVA (0.9.2): se sabe qué diálogo hay en pantalla
    else:
        def poll():
            d = estado["dlg"]
            if svc["necesita"] and not (d is not None and d.visible):
                estado["dlg"] = mostrar("lista", olvidar)
        def olvidar(d):
            if estado["dlg"] is d: estado["dlg"] = None
        def abrir_menu():
            d = mostrar("menu", olvidar); estado["dlg"] = d; return d
        def abrir_confirmar():
            d = mostrar("confirmar", olvidar); estado["dlg"] = d; return d

    poll()                                           # sale la lista
    lista = pantalla[-1]
    abrir_menu(); cerrar(lista); poll(); poll()      # pulsa un paquete: menú nuevo y luego se cierra la lista
    menu = [d for d in pantalla if d.tipo == "menu"][-1]
    abrir_confirmar(); cerrar(menu); poll(); poll()  # «Descargar y extraer»: confirmación y luego se cierra el menú
    encima = pantalla[-1].tipo
    return historial, encima, [d.tipo for d in pantalla]

for nombre in ("antigua", "nueva"):
    hist, encima, pantalla = simular(nombre)
    print(f"{nombre:8} historial={hist}\n         encima del todo: {encima!r}  en pantalla: {pantalla}")
    assert (encima == "lista") == (nombre == "antigua")
print("OK: con la lógica antigua la lista se reabre ENCIMA de la confirmación; con la nueva queda la confirmación")

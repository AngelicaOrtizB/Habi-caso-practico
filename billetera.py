#!/usr/bin/env python3
"""
Menú de terminal para usar la billetera.

No tiene lógica de plata propia: todo lo hace llamando a la API REST, igual que lo
haría una app de celular. Solo usa la librería estándar de Python (no hay que instalar nada).

Uso:
    1. Levanta la app:  ./mvnw spring-boot:run
    2. En otra terminal: python3 billetera.py
"""

import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid
from datetime import datetime
from decimal import Decimal, InvalidOperation

BASE = os.environ.get("BILLETERA_URL", "http://localhost:8080")
REINTENTOS = 3


class ErrorApi(Exception):
    pass


# ------------------------------------------------------------------ API

def llamar(metodo, ruta, cuerpo=None, llave=None):
    headers = {"Content-Type": "application/json"}
    if llave:
        headers["Idempotency-Key"] = llave
    datos = json.dumps(cuerpo).encode() if cuerpo is not None else None
    pedido = urllib.request.Request(BASE + ruta, data=datos, headers=headers, method=metodo)
    try:
        with urllib.request.urlopen(pedido, timeout=10) as respuesta:
            return json.loads(respuesta.read() or "null")
    except urllib.error.HTTPError as e:
        # La API responde los errores con {"title": ..., "detail": ...} en español.
        try:
            detalle = json.loads(e.read()).get("detail")
        except ValueError:
            detalle = None
        raise ErrorApi(detalle or f"Error HTTP {e.code}")


def enviar_con_llave(ruta, cuerpo):
    """
    Genera UNA llave de idempotencia por operación y la reusa en cada reintento. Si la
    conexión se cae después de que el servidor movió la plata (o creó el cobro), el
    reintento devuelve el mismo resultado en vez de hacerlo dos veces.
    """
    llave = str(uuid.uuid4())
    for intento in range(1, REINTENTOS + 1):
        try:
            return llamar("POST", ruta, cuerpo, llave)
        except urllib.error.URLError:
            if intento == REINTENTOS:
                raise
            print(f"  (se cayó la conexión, reintento {intento + 1}/{REINTENTOS} con la misma llave)")


# ------------------------------------------------------------------ montos

def formato(centavos):
    """5000000 -> $50.000,00 (formato colombiano: punto de miles, coma de centavos)."""
    signo = "-" if centavos < 0 else ""
    pesos, cent = divmod(abs(centavos), 100)
    return f"{signo}${pesos:,}".replace(",", ".") + f",{cent:02d}"


def a_centavos(texto):
    """
    Convierte lo que escribe la persona a centavos, SIN pasar por float (0.1 + 0.2 en
    float da 0.30000000000000004). Acepta: 20000 · 20.000 · 20.000,50 · 20000,5 · $20.000
    """
    t = texto.strip().replace("$", "").replace(" ", "")
    if "," in t:
        entero, _, decimales = t.partition(",")
        t = entero.replace(".", "") + "." + decimales
    elif re.fullmatch(r"\d{1,3}(\.\d{3})+", t):
        t = t.replace(".", "")
    try:
        monto = Decimal(t)
    except InvalidOperation:
        monto = None
    # Decimal acepta "NaN" e "Infinity": no son montos.
    if monto is None or not monto.is_finite():
        raise ValueError("No entiendo ese monto. Ejemplos: 20000 · 20.000 · 20.000,50")
    if monto.as_tuple().exponent < -2:
        raise ValueError("El monto no puede tener más de 2 decimales (centavos)")
    if monto <= 0:
        raise ValueError("El monto debe ser mayor que cero")
    return int(monto * 100)


def fecha_local(iso):
    iso = iso.replace("Z", "+00:00")
    # Python 3.9 solo acepta 3 o 6 decimales en los segundos: se completan a 6.
    iso = re.sub(r"\.(\d+)", lambda m: "." + m.group(1).ljust(6, "0")[:6], iso)
    return datetime.fromisoformat(iso).astimezone().strftime("%d/%m %H:%M")


# ------------------------------------------------------------------ entrada

def preguntar(mensaje):
    return input(mensaje).strip()


def pedir_monto(mensaje):
    while True:
        try:
            return a_centavos(preguntar(mensaje))
        except ValueError as e:
            print(f"  ✗ {e}")


def confirmar(mensaje):
    return preguntar(f"{mensaje} (s/n): ").lower() in ("s", "si", "sí")


def elegir_cuenta(mensaje, excepto=None):
    """
    Muestra siempre la lista completa, para que cada cuenta tenga el mismo número en
    "¿Quién envía?" y en "¿Quién recibe?". La cuenta "excepto" no se puede elegir.
    """
    cuentas = llamar("GET", "/cuentas")
    if not cuentas:
        print("  No hay cuentas todavía. Crea una primero (opción 1).")
        return None
    print(f"\n{mensaje}")
    for i, c in enumerate(cuentas, 1):
        print(f"  {i}. {c['titular']:<20} {formato(c['saldoCentavos']):>18}")
    while True:
        opcion = preguntar("Número: ")
        if not (opcion.isdigit() and 1 <= int(opcion) <= len(cuentas)):
            print("  ✗ Elige un número de la lista")
        elif cuentas[int(opcion) - 1]["id"] == excepto:
            print("  ✗ Esa es la misma cuenta que envía. Elige otra")
        else:
            return cuentas[int(opcion) - 1]


# ------------------------------------------------------------------ opciones

def crear_cuenta():
    titular = preguntar("Nombre del titular: ")
    cuenta = llamar("POST", "/cuentas", {"titular": titular})
    print(f"  ✓ Cuenta creada para {cuenta['titular']}")


def cargar_saldo():
    cuenta = elegir_cuenta("¿A qué cuenta le cargas saldo?")
    if not cuenta:
        return
    monto = pedir_monto("Monto a cargar ($): ")
    enviar_con_llave(f"/cuentas/{cuenta['id']}/cargas", {"montoCentavos": monto})
    saldo = llamar("GET", f"/cuentas/{cuenta['id']}")["saldoCentavos"]
    print(f"  ✓ Cargaste {formato(monto)} a {cuenta['titular']}. Saldo nuevo: {formato(saldo)}")


def transferir():
    origen = elegir_cuenta("¿Quién envía?")
    if not origen:
        return
    destino = elegir_cuenta("¿Quién recibe?", excepto=origen["id"])
    if not destino:
        return
    monto = pedir_monto("Monto ($): ")
    descripcion = preguntar("Descripción (opcional, ej. 'La cena'): ") or None
    if not confirmar(f"¿Enviar {formato(monto)} de {origen['titular']} a {destino['titular']}?"):
        print("  Cancelado, no se movió plata.")
        return
    enviar_con_llave("/transferencias", {"origenId": origen["id"], "destinoId": destino["id"],
                                    "montoCentavos": monto, "descripcion": descripcion})
    print("  ✓ Transferencia hecha. Saldos nuevos:")
    for c in (origen, destino):
        saldo = llamar("GET", f"/cuentas/{c['id']}")["saldoCentavos"]
        print(f"    {c['titular']:<20} {formato(saldo):>18}")


def consultar_saldo():
    cuenta = elegir_cuenta("¿De qué cuenta?")
    if not cuenta:
        return
    saldo = llamar("GET", f"/cuentas/{cuenta['id']}")["saldoCentavos"]
    print(f"  Saldo de {cuenta['titular']}: {formato(saldo)}")


def ver_historial():
    cuenta = elegir_cuenta("¿De qué cuenta?")
    if not cuenta:
        return
    antes = None
    while True:
        ruta = f"/cuentas/{cuenta['id']}/historial?limite=10" + (f"&antesDe={antes}" if antes else "")
        pagina = llamar("GET", ruta)
        if not pagina["lineas"] and antes is None:
            print("  Esta cuenta no tiene movimientos.")
            return
        print(f"\n  {'Fecha':<12}{'Monto':>18}   Detalle")
        for linea in pagina["lineas"]:
            if linea["tipo"] == "CARGA":
                detalle = "Carga de saldo"
            elif linea["tipo"] == "PAGO_CUOTA" and linea["montoCentavos"] < 0:
                detalle = f"Pagaste tu cuota a {linea['contraparteTitular']}"
            elif linea["tipo"] == "PAGO_CUOTA":
                detalle = f"{linea['contraparteTitular']} te pagó su cuota"
            elif linea["montoCentavos"] < 0:
                detalle = f"Enviado a {linea['contraparteTitular']}"
            else:
                detalle = f"Recibido de {linea['contraparteTitular']}"
            if linea["descripcion"] and linea["tipo"] != "CARGA":
                detalle += f" · {linea['descripcion']}"
            print(f"  {fecha_local(linea['fecha']):<12}{formato(linea['montoCentavos']):>18}   {detalle}")
        antes = pagina["siguiente"]
        if antes is None or not confirmar("\n¿Ver movimientos más viejos?"):
            return


def ver_cuentas():
    cuentas = llamar("GET", "/cuentas")
    if not cuentas:
        print("  No hay cuentas todavía.")
        return
    print()
    for c in cuentas:
        print(f"  {c['titular']:<20} {formato(c['saldoCentavos']):>18}")


# ------------------------------------------------------------------ cobros divididos

ICONOS = {"PAGADA": "✓ pagó", "PENDIENTE": "… debe", "CANCELADA": "✗ cancelada"}


def mostrar_cobro(cobro):
    print(f"\n  {cobro['descripcion']} · cobra {cobro['cobradorTitular']} · {cobro['estado']}")
    print(f"  Total {formato(cobro['totalCentavos'])} · pagado {formato(cobro['pagadoCentavos'])}"
          f" · falta {formato(cobro['pendienteCentavos'])}")
    for cuota in cobro["cuotas"]:
        print(f"    {cuota['deudorTitular']:<20} {formato(cuota['montoCentavos']):>16}   {ICONOS[cuota['estado']]}")


def elegir_de_lista(elementos, describir):
    for i, elemento in enumerate(elementos, 1):
        print(f"  {i}. {describir(elemento)}")
    while True:
        opcion = preguntar("Número: ")
        if opcion.isdigit() and 1 <= int(opcion) <= len(elementos):
            return elementos[int(opcion) - 1]
        print("  ✗ Elige un número de la lista")


def crear_cobro():
    cobrador = elegir_cuenta("¿Quién pagó y ahora cobra?")
    if not cobrador:
        return
    cuentas = llamar("GET", "/cuentas")
    print("\n¿A quién le cobras? Escribe los números separados por coma (ej. 2,3,4)")
    for i, c in enumerate(cuentas, 1):
        print(f"  {i}. {c['titular']}")
    while True:
        numeros = [n.strip() for n in preguntar("Números: ").split(",") if n.strip()]
        if numeros and all(n.isdigit() and 1 <= int(n) <= len(cuentas) for n in numeros):
            deudores = [cuentas[int(n) - 1] for n in numeros]
            break
        print("  ✗ Escribe números de la lista, separados por coma")
    total = pedir_monto("Total a dividir ($): ")
    descripcion = preguntar("Descripción (ej. 'La cena'): ")
    nombres = ", ".join(d["titular"] for d in deudores)
    if not confirmar(f"¿Cobrar {formato(total)} de '{descripcion}' entre {nombres}?"):
        print("  Cancelado, no se creó el cobro.")
        return
    cobro = enviar_con_llave("/cobros", {
        "cobradorId": cobrador["id"], "totalCentavos": total, "descripcion": descripcion,
        "deudores": [d["id"] for d in deudores]})
    print("  ✓ Cobro creado:")
    mostrar_cobro(cobro)


def ver_cobros():
    cuenta = elegir_cuenta("¿Los cobros de quién?")
    if not cuenta:
        return
    cobros = llamar("GET", f"/cuentas/{cuenta['id']}/cobros")
    if not cobros:
        print(f"  {cuenta['titular']} no ha creado cobros.")
        return
    for cobro in cobros:
        mostrar_cobro(cobro)


def pagar_cuota():
    cuenta = elegir_cuenta("¿Quién paga?")
    if not cuenta:
        return
    pendientes = llamar("GET", f"/cuentas/{cuenta['id']}/cuotas-pendientes")
    if not pendientes:
        print(f"  {cuenta['titular']} no debe nada.")
        return
    print(f"\n{cuenta['titular']} debe:")
    cuota = elegir_de_lista(pendientes, lambda c: f"{formato(c['montoCentavos']):>16} a {c['cobradorTitular']}"
                                                  f" · {c['descripcion']}")
    if not confirmar(f"¿Pagar {formato(cuota['montoCentavos'])} a {cuota['cobradorTitular']}?"):
        print("  Cancelado, no se movió plata.")
        return
    pago = enviar_con_llave(f"/cuotas/{cuota['cuotaId']}/pago", None)
    print(f"  ✓ Cuota pagada. Saldo de {cuenta['titular']}: "
          f"{formato(llamar('GET', '/cuentas/' + cuenta['id'])['saldoCentavos'])}")
    if pago["estadoCobro"] == "COMPLETADO":
        print(f"  🎉 Con este pago, '{cuota['descripcion']}' quedó completo.")


def cancelar_cobro():
    cuenta = elegir_cuenta("¿Quién cancela uno de sus cobros?")
    if not cuenta:
        return
    abiertos = [c for c in llamar("GET", f"/cuentas/{cuenta['id']}/cobros") if c["estado"] == "ABIERTO"]
    if not abiertos:
        print(f"  {cuenta['titular']} no tiene cobros abiertos.")
        return
    print("\n¿Cuál cobro?")
    cobro = elegir_de_lista(abiertos, lambda c: f"{c['descripcion']} · falta {formato(c['pendienteCentavos'])}")
    if not confirmar("Se anulan las cuotas pendientes; lo ya pagado NO se devuelve. ¿Cancelar?"):
        return
    mostrar_cobro(llamar("POST", f"/cobros/{cobro['id']}/cancelacion"))


OPCIONES = {
    "1": ("Crear cuenta", crear_cuenta),
    "2": ("Cargar saldo", cargar_saldo),
    "3": ("Transferir", transferir),
    "4": ("Consultar saldo", consultar_saldo),
    "5": ("Ver historial", ver_historial),
    "6": ("Ver todas las cuentas", ver_cuentas),
    "7": ("Crear cobro dividido", crear_cobro),
    "8": ("Ver mis cobros (quién pagó)", ver_cobros),
    "9": ("Pagar una cuota", pagar_cuota),
    "10": ("Cancelar un cobro", cancelar_cobro),
}


def main():
    while True:
        print("\n=== Billetera ===")
        for numero, (nombre, _) in OPCIONES.items():
            print(f"{numero}. {nombre}")
        print("0. Salir")
        opcion = preguntar("> ")
        if opcion == "0":
            return
        if opcion not in OPCIONES:
            print("  ✗ Opción no válida")
            continue
        try:
            OPCIONES[opcion][1]()
        except ErrorApi as e:
            print(f"  ✗ {e}")
        except urllib.error.URLError:
            print(f"  ✗ No me puedo conectar a {BASE}. ¿Está corriendo ./mvnw spring-boot:run?")


if __name__ == "__main__":
    try:
        main()
    except (KeyboardInterrupt, EOFError):
        print()
    sys.exit(0)

#!/usr/bin/env python3
"""Puente heladera → un agente de APX (por ejemplo, el que te manda WhatsApp). Opcional.

El servicio de la heladera vive en tu servidor y el agente de APX en tu compu, así
que en vez de abrir puertos hacia la compu invertimos la dirección: este script
pregunta cada tanto si hay algo encolado y lo entrega. Si la compu está apagada,
los mensajes esperan; cuando vuelve, salen solos.

Se ejecuta cada 30s desde launchd (lo instala install.sh).
"""
import json
import os
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

# Conviene la dirección de Tailscale y no la de la LAN: macOS 26 le pide permiso de
# "red local" a los procesos de fondo y un agente de launchd no tiene cómo pedirlo,
# así que una conexión a 192.168.x.x se queda colgada para siempre.
SERVER = os.environ.get("HELADERA_SERVER", "http://127.0.0.1:8790")
PROJECT = os.environ.get("HELADERA_APX_PROJECT", "heladera")
APX = os.environ.get("HELADERA_APX_BIN", os.path.expanduser("~/Library/pnpm/apx"))
FROM = os.environ.get("HELADERA_APX_FROM", "heladera")
TO = os.environ.get("HELADERA_APX_TO", "roby")
# status = va con el resumen del agente. Poné blocker si querés que avise al toque.
SEVERITY = os.environ.get("HELADERA_APX_SEVERITY", "status")
TIMEOUT = 8


# Sin proxies: por defecto urllib le pregunta a SystemConfiguration cuál usar, y
# esa llamada se cuelga para siempre dentro de un agente de launchd.
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def api(path, payload=None):
    req = urllib.request.Request(
        SERVER + path,
        data=json.dumps(payload).encode() if payload is not None else None,
        headers={"Content-Type": "application/json"},
        method="POST" if payload is not None else "GET",
    )
    with _opener.open(req, timeout=TIMEOUT) as r:
        return json.loads(r.read().decode())


def apx_inaccesible():
    """El shim de apx vive en ~, pero el código real puede estar en un disco externo."""
    if not os.access(APX, os.R_OK):
        return APX
    try:
        with open(APX) as f:
            shim = f.read()
    except OSError:
        return APX
    for token in shim.split('"'):
        if token.endswith("index.js"):
            token = token.replace("$basedir", os.path.dirname(APX))
            real = os.path.normpath(os.path.join(os.path.dirname(APX), token))
            try:
                # abrir de verdad: os.access() mira los permisos POSIX y no ve
                # el bloqueo de privacidad de macOS sobre los discos externos.
                with open(real, "rb") as f:
                    f.read(1)
            except OSError:
                return real
            break
    return None


def deliver(text):
    # Nada de capture_output: apx deja corriendo al daemon, que hereda los pipes
    # y nunca los cierra, así que communicate() se queda esperando para siempre.
    # Con archivos temporales el proceso termina apenas termina apx.
    try:
        with tempfile.TemporaryFile("w+") as out:
            p = subprocess.run(
                [APX, "send", FROM, TO, text, "--severity", SEVERITY, "--project", PROJECT],
                stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT,
                timeout=45, start_new_session=True,
            )
            out.seek(0)
            salida = out.read()
        if p.returncode == 0:
            return True, ""
        return False, salida[-300:]
    except Exception as e:  # apx no está, la sesión expiró, lo que sea
        return False, str(e)[:300]


def main():
    # apx vive en el SSD externo y macOS no deja que un proceso de fondo lea
    # /Volumes sin "Acceso total al disco". Si no lo podemos leer, mejor decirlo
    # que quedarse colgado: el mensaje espera en la cola hasta que se arregle.
    falta = apx_inaccesible()
    if falta:
        print("no puedo leer", falta,
              "-> dale Acceso total al disco a /usr/bin/python3 en Ajustes del Sistema >"
              " Privacidad y seguridad, o movés apx a un disco interno", file=sys.stderr)
        return 1
    try:
        pend = api("/api/outbox?channel=roby&limit=5").get("messages", [])
    except (urllib.error.URLError, OSError) as e:
        print("servidor no disponible:", e, file=sys.stderr)
        return 0  # no es un error del relay: reintentamos en la próxima vuelta

    if not pend:
        return 0

    for msg in pend:
        ok, err = deliver(msg["text"])
        try:
            api(f"/api/outbox/{msg['id']}/ack", {"ok": ok, "error": err or None})
        except Exception as e:
            print("no pude confirmar el ack:", e, file=sys.stderr)
        print(("entregado" if ok else "falló") + f" #{msg['id']} {err}".rstrip())
    return 0


if __name__ == "__main__":
    sys.exit(main())

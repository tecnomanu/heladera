#!/usr/bin/env bash
# Deja una tablet Android lista para vivir pegada a la heladera.
#
#   ./setup-tablet.sh                      usa el único dispositivo conectado
#   ./setup-tablet.sh 192.168.1.50:5555  o el que le digas
#
# Se puede correr las veces que haga falta: no rompe nada si ya estaba hecho.
set -euo pipefail

DEV="${1:-}"
PKG=ar.tecnomanu.heladera
APK="$(dirname "$0")/../android/app/build/outputs/apk/debug/app-debug.apk"

adbx() { if [ -n "$DEV" ]; then adb -s "$DEV" "$@"; else adb "$@"; fi; }

say() { printf '\033[38;5;148m▸\033[0m %s\n' "$*"; }

[ -f "$APK" ] || { echo "No encuentro el APK. Corré:  cd android && ./gradlew assembleDebug"; exit 1; }

say "instalando la app"
adbx install -r "$APK" | tail -1

say "permiso de micrófono"
adbx shell pm grant $PKG android.permission.RECORD_AUDIO || true

say "que la pantalla no se apague nunca"
adbx shell settings put system screen_off_timeout 2147483647
adbx shell settings put global stay_on_while_plugged_in 7      # AC + USB + inalámbrico
adbx shell settings put secure screensaver_enabled 0 || true
adbx shell settings put system screen_brightness_mode 0 || true

say "sacándola del ahorro de energía"
adbx shell dumpsys deviceidle whitelist +$PKG || true

say "abriendo"
adbx shell am start -n $PKG/.MainActivity >/dev/null

cat <<'FIN'

Listo. Un par de cosas que conviene hacer a mano en la tablet:

  • Ajustes → Pantalla → Salvapantallas: apagado
  • Ajustes → Batería → optimización: excluir "Heladera"
  • Si la vas a dejar en modo kiosco (que el botón Home vuelva siempre a la
    lista), descomentá CATEGORY_HOME en AndroidManifest.xml, recompilá y elegí
    "Heladera" como pantalla de inicio.
  • Para manejarla sin cable:  adb tcpip 5555 && adb connect <ip-de-la-tablet>:5555

FIN

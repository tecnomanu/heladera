#!/usr/bin/env bash
# Instala el puente heladera → agente de APX como servicio de la Mac (opcional).
#
#   HELADERA_SERVER=http://<ip-de-tu-servidor>:8790 HELADERA_APX_TO=<agente> ./install.sh
#
# El script se copia a ~/.heladera/ a propósito: launchd no puede leer volúmenes
# externos sin "Acceso total al disco", por si el proyecto vive en un disco externo.
set -euo pipefail
: "${HELADERA_SERVER:?Decime dónde está la heladera: HELADERA_SERVER=http://<ip>:8790 ./install.sh}"
HELADERA_APX_TO="${HELADERA_APX_TO:-roby}"
APX_BIN="$(command -v apx || echo "$HOME/Library/pnpm/apx")"
SRC="$(cd "$(dirname "$0")" && pwd)"
DEST="$HOME/.heladera"
PLIST="$HOME/Library/LaunchAgents/ar.tecnomanu.heladera.relay.plist"

mkdir -p "$DEST" "$HOME/Library/LaunchAgents"
cp "$SRC/relay.py" "$DEST/relay.py"
chmod +x "$DEST/relay.py"

cat > "$PLIST" <<PLISTEOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key><string>ar.tecnomanu.heladera.relay</string>
    <key>ProgramArguments</key>
    <array>
        <string>/usr/bin/python3</string>
        <string>$DEST/relay.py</string>
    </array>
    <key>StartInterval</key><integer>30</integer>
    <key>RunAtLoad</key><true/>
    <key>WorkingDirectory</key><string>$HOME</string>
    <key>StandardOutPath</key><string>/tmp/heladera-relay.log</string>
    <key>StandardErrorPath</key><string>/tmp/heladera-relay.err</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>HELADERA_SERVER</key><string>$HELADERA_SERVER</string>
        <key>HELADERA_APX_PROJECT</key><string>heladera</string>
        <key>HELADERA_APX_TO</key><string>$HELADERA_APX_TO</string>
        <key>HELADERA_APX_BIN</key><string>$APX_BIN</string>
        <key>PATH</key><string>/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin:$HOME/Library/pnpm</string>
    </dict>
</dict>
</plist>
PLISTEOF

launchctl unload "$PLIST" 2>/dev/null || true
launchctl load "$PLIST"
echo "relay instalado. Log: /tmp/heladera-relay.log"

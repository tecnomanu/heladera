# AGENTS.md

> Para agentes (Claude Code, Codex, APX, …) que trabajan en este repo o lo instalan para alguien.

## Qué es

La lista de compras de una casa, en una tablet pegada a la heladera, manejada por voz.

| Parte | Dónde | Qué |
|---|---|---|
| Núcleo | `src/` (Node 18+, sin dependencias salvo `qrcode`) | API, panel web (`web/`), la lista (`~/.heladera/data.json`), motores enchufables de voz/LLM. CLI: `src/cli.js` (`heladera`) |
| Charla en vivo | `voz/` (Python, su propio venv) | WebSocket `/talk` en :8796: VAD + fin de turno + whisper + ollama con tools + voz, frase por frase. Ver `voz/README.md` |
| Motores locales opcionales | `engines-py/` | whisper y piper para el modo de grabar y mandar |
| App | `android/` (Java, sin AndroidX) | tablet: pantalla grande (`res/layout-w600dp`) + charla en vivo (`Charla.java`, `WsCliente.java`); teléfono: `res/layout` |
| Servicio | `deploy/` | unidades de systemd, `setup-tablet.sh` |

La configuración de cada casa vive en `~/.heladera/config.json` (o `$HELADERA_HOME`), nunca en el repo.

## Instalar el servidor para alguien

```bash
npm install -g github:tecnomanu/heladera
heladera init                      # interactivo; si no hay humano, mirá src/config.js y escribí el json
ollama pull qwen3:4b-instruct      # la charla en vivo necesita un modelo con tool calling
heladera voz instalar              # opcional: la charla en vivo
```

Servicios: README → "Instalarla en tu servidor". `heladera status` dice qué anda y qué no.

## Compilar e instalar la app

1. **JDK 17.** Con un JDK más nuevo Gradle puede fallar con
   "Unsupported class file major version". Buscá uno: `/usr/libexec/java_home -V`, `ls /opt/homebrew/opt | grep jdk`,
   `ls /usr/lib/jvm`. Si no hay, instalá `openjdk@17` (brew) o `openjdk-17-jdk` (apt), con permiso de la persona.
2. **Android SDK** con platform 35 y build-tools. Escribí `android/local.properties` con `sdk.dir=…`
   (está en `.gitignore`: es de cada máquina).
3. Compilar: `cd android && JAVA_HOME=<jdk17> ./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
4. Instalar: `adb devices` (USB con depuración activada, o `adb connect <ip>:5555`), después
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`. `deploy/setup-tablet.sh` la deja como pantalla fija.
5. Emparejar: en el servidor `heladera pair` da un link y un código; se abre en la tablet y la app queda configurada.
   La app no trae servidor de fábrica.

Verificar en la tablet: `adb exec-out screencap -p > s.png`; logs con `adb logcat | grep heladera`.

## Reglas

- Nada de una casa en el repo: ni tokens, ni IPs, ni nombres, ni audio grabado. `privado/` y `.agent/` están
  ignorados a propósito.
- Los textos de la app son para quien está en la cocina: qué puede hacer y qué pasó. Nada de nombres internos.
- Lo que no tiene vuelta (vaciar la lista, borrar 3 o más) se confirma. Tachar no reordena; el orden es de la persona.
- La tablet de referencia es vieja (Android 7, 1,4 GB): nada de librerías pesadas en la app; dibujar a mano está bien.
- En `voz/`: el prompt de sistema es fijo (la lista va en el mensaje), el historial lleva las tool_calls reales, y no
  se manda `num_ctx` a ollama. Las razones están en `voz/README.md` → "Trampas que costaron".
- Probar la charla sin tocar una lista de verdad: levantar otra heladera con `HELADERA_HOME=/tmp/x HELADERA_PORT=8798
  HELADERA_NO_AUTH=1 heladera server` y la charla con `VOZ_LIST_URL=http://127.0.0.1:8798 VOZ_NO_AUTH=1 VOZ_PORT=8797`.

# Heladera

La lista de compras de casa, en una tablet pegada a la heladera.

Tocás un botón, decís lo que falta y queda anotado. La pantalla muestra la lista
todo el tiempo. Cuando salís a comprar te la mandás al celular, vas tachando, y
listo.

Todo el procesamiento pasa donde vos quieras: podés instalar los motores de voz
en la misma máquina, o apuntar a los que ya tengas andando.

```bash
npm install -g github:tecnomanu/heladera
heladera init      # te pregunta todo y deja andando lo que elijas
heladera server
```

Y si querés hablarle como en una llamada (sin tocar nada para que se dé cuenta de que terminaste, y cortándola si le
hablás encima):

```bash
heladera voz instalar
heladera voz
```

La app de Android está en `android/`: se compila con un comando (ver [La app](#la-app-de-android)).

---

## Cómo está armado

```
  TABLET                         TU SERVIDOR                    MOTORES
  ┌──────────────────┐          ┌────────────────────┐         ┌──────────────┐
  │ app nativa       │  audio   │  heladera          │  ────►  │ voz a texto  │
  │ · lista visible  │ ───────► │  · API + panel web │         │ entender     │
  │ · HABLAR/SILENCIO│ ◄─────── │  · la lista        │  ◄────  │ texto a voz  │
  │ · manos libres   │  texto   │  · Telegram        │         └──────────────┘
  └──────────────────┘  y audio └────────────────────┘      local o remoto, vos elegís
```

La tablet solo graba, muestra y reproduce. Todo lo pesado pasa en el servidor,
así una tablet de 2015 no tiene que hacer nada difícil.

### Los motores son intercambiables

Ninguno está cableado: se eligen en `heladera init` o después en el panel `/admin`.

| | opciones |
|---|---|
| **entender** (LLM) | Ollama · llama.cpp · cualquier API compatible con OpenAI · o apagado, y anda con reglas |
| **voz a texto** | whisper propio (CPU) · whisper.cpp (GPU) · cualquier `/v1/audio/transcriptions` · tu endpoint |
| **texto a voz** | piper propio · cualquier `/v1/audio/speech` · la voz del Android · muda |

Si ya tenés un whisper o un piper andando, los apuntás y heladera no instala nada.
Si no tenés nada, `heladera init` te arma un entorno de Python con los dos.

### Lo que da cada combinación

Medido en un servidor con 36 núcleos y una Radeon RX 5700 XT:

| voz a texto | *"Anotame un kilo de fideos, dos latas de tomate y medio kilo de queso cremoso"* | tarda |
|---|---|---|
| whisper `small`, CPU | "Liso a NoTeLeTriPan en la lista" | 1,4 s |
| whisper `medium`, CPU | "Listo? Anotele chipán en la lista" | 3,6 s |
| whisper `large-v3-turbo`, CPU | correcto | 4,6 s |
| **whisper.cpp `large-v3-turbo`, GPU** | **correcto** | **0,6 s** |

Y para entender el pedido no hace falta un modelo grande. Medido sobre diez pedidos reales:

| modelo | aciertos | tarda | ocupa |
|---|---|---|---|
| qwen3 1.7B en GPU (llama.cpp) | **10/10** | **216 ms** | 1,8 GB |
| qwen3 8B en GPU (ollama) | 10/10 | 821 ms | 5,2 GB |

Con las dos cosas en GPU, el turno completo —escuchar, entender, contestar—
tarda **1,4 segundos**.

### Repartir las placas

Si tenés dos GPU conviene dedicarle una entera a la heladera y dejar la otra
para lo de siempre. Con Vulkan cada servicio elige la suya:

```bash
GGML_VK_VISIBLE_DEVICES=1 whisper-server …   # voz a texto
GGML_VK_VISIBLE_DEVICES=1 llama-server   …   # entender
```

Hay un `deploy/whisper-server.service` y un `deploy/llama-server.service` listos.
Vulkan además esquiva a ROCm, que en algunas placas es justamente lo que se
cuelga cuando dos procesos se la pelean.

---

## Usarla

**Con la voz.** Tocás `HABLAR`, suena un bip, hablás. Mientras hablás vas viendo
la transcripción en pantalla. Corta sola cuando terminás, o tocás `PARAR`.
En la tablet, con la [charla en vivo](voz/README.md) andando, es una conversación:
la heladera te contesta, te pregunta "¿algo más?" y sigue escuchando hasta que le
decís "nada más".

- *"anotame dos litros de leche descremada, un kilo de pan y yerba"*
- *"sacá el pan que ya lo compré"*
- *"qué falta"*
- *"mandame la lista por Telegram"*
- *"volvé atrás"* — deshace lo último

**De noche.** `SILENCIO` hace lo mismo pero contesta solo por pantalla.

**Sin tocar nada.** El botón `manos libres` del encabezado la deja escuchando.
Decís *"ok tableta"*, te contesta *"sí, acá estoy"* y ahí le pedís. Se apaga
desde el mismo botón. Las frases de activación se configuran en el panel.

**Con la mano.** Tocás el círculo para tacharlo: va a "Ya en el changuito", y si lo
tocás ahí vuelve a la lista. Tocando el renglón lo editás (nota, para cuándo,
destacar, borrar). Mantenerlo apretado elige varios para agrupar, tachar o borrar.
Se reordena arrastrando la agarradera de puntitos.

**En el súper.** El botón `En el súper` deja dos columnas: lo que falta, por
góndola, y el changuito. Tocás lo que vas metiendo y pasa al otro lado (si te
arrepentís, lo tocás de nuevo). `Terminé la compra` saca lo comprado y deja lo
que faltó para la próxima.

**Desde cualquier lado.** `http://<tu-servidor>:8790` es la misma lista, para ir
tachando mientras comprás. `/admin` es la configuración.

### Lo que no hace sin preguntar

La tablet escucha la cocina entera, así que todo lo que no tiene vuelta se
confirma hablando:

- vaciar la lista o cerrar la compra → *"¿Seguro que borro toda la lista?"*
- sacar 3 o más cosas de un saque → pregunta antes
- lo que no tenga que ver con la lista (la tele, una charla) → no hace nada

Y todo lo destructivo queda en un snapshot: *"volvé atrás"* lo repone.

---

## El CLI

```
heladera init      configura todo preguntando
heladera server    levanta la API y el panel web
heladera pair      link para configurar la tablet
heladera status    qué está andando y qué no
heladera logs      log de los motores locales
heladera config    muestra la configuración
heladera voz       la charla en vivo: instalar | servicio | (nada) para levantarla
```

Todo lo que hace `init` se puede cambiar después en `/admin`, desde cualquier
dispositivo conectado a la API. La configuración vive en `~/.heladera/config.json`
y cualquier variable de entorno la pisa (ver `.env.example`).

### Conectar la tablet

`heladera pair` te da un link. Lo abrís **en la tablet**, tocás el botón y la app
se configura sola: servidor y token, sin tipear nada. También tiene un QR para
escanear desde la app (Más → Ajustes).

---

## Instalarla en tu servidor (VPS o compu)

Cualquier Linux o Mac con Node 18+ en la red de tu casa (o alcanzable por Tailscale).

```bash
npm install -g github:tecnomanu/heladera
heladera init                      # motores, token, Telegram: pregunta todo
ollama pull qwen3:4b-instruct      # si usás ollama (recomendado para la charla en vivo)
heladera server                    # probala: http://<ip>:8790
```

Para que arranque sola con systemd (Linux):

```bash
sudo cp "$(npm root -g)/heladera/deploy/heladera.service" /etc/systemd/system/heladera@.service
sudo sed -i "s#/opt/heladera#$(npm root -g)/heladera#g" /etc/systemd/system/heladera@.service
sudo systemctl enable --now heladera@$USER

heladera voz instalar                              # la charla en vivo (opcional)
heladera voz servicio | sudo tee /etc/systemd/system/heladera-voz.service
sudo systemctl daemon-reload && sudo systemctl enable --now heladera-voz
```

Puertos: **8790** (la lista, el panel y la app) y **8796** (la charla en vivo). Abrilos
solo para tu red: los dos piden el token de la heladera.

### Con Docker

```bash
cp .env.example .env      # apuntá tu ollama y listo
docker compose up -d
```

---

## La app de Android

App nativa en Java, sin dependencias raras: anda desde Android 5 (probada en una
Samsung SM-T350 de 2015). En tablets muestra la pantalla grande (lista por góndola,
charla en vivo, modo súper); en el teléfono, la versión compacta.

Para compilarla hace falta el **JDK 17** (con uno más nuevo Gradle no arranca) y el
Android SDK (el de Android Studio sirve):

```bash
cd android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # en Linux: ~/Android/Sdk
JAVA_HOME=/ruta/al/jdk-17 ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
../deploy/setup-tablet.sh          # opcional: pantalla siempre prendida, arranca sola
```

**Con tu agente** (Claude Code, Codex, APX…): abrí este repo y pedile *"compilá la app
de la heladera e instalala en mi celular"*. En [`AGENTS.md`](AGENTS.md) están los pasos
y las trampas para que lo haga solo.

---

## Montar whisper en GPU (opcional, pero vale la pena)

Con una placa AMD o NVIDIA y Vulkan, `large-v3-turbo` pasa de 4,6 s a 0,6 s:

```bash
git clone --depth 1 https://github.com/ggml-org/whisper.cpp /opt/whisper-cpp
cd /opt/whisper-cpp && cmake -B build -DGGML_VULKAN=1 -DCMAKE_BUILD_TYPE=Release
cmake --build build -j --target whisper-server
curl -L -o models/ggml-large-v3-turbo.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo.bin
build/bin/whisper-server --host 0.0.0.0 --port 8792 \
  -m models/ggml-large-v3-turbo.bin -l es
```

Después en `/admin` elegís **whisper.cpp (GPU)** y le ponés `http://127.0.0.1:8792`.
Hay un `deploy/whisper-server.service` listo para copiar. Si tenés más de una
placa, `GGML_VK_VISIBLE_DEVICES=1` la manda a la segunda, así no pelea con lo que
esté usando la primera.

---

## La API

| | |
|---|---|
| `GET  /api/list` | la lista + `rev` para saber si cambió |
| `POST /api/voice` | audio WAV → transcribe, entiende, aplica y contesta |
| `POST /api/partial` | audio parcial → transcripción rápida, para ver mientras hablás |
| `POST /api/text` | lo mismo que voice pero escrito |
| `POST /api/wake` | ¿dijo la palabra de activación? |
| `POST /api/items` · `/api/items/{id}/toggle` · `/api/items/{id}/delete` | a mano |
| `POST /api/list/clear` · `/api/list/close` · `/api/undo` | vaciar, archivar, deshacer |
| `POST /api/send` | `{"channel":"telegram"}` o `"roby"` |
| `GET/POST /api/config` · `POST /api/config/check` | configuración y diagnóstico |
| `GET  /api/health` | estado de todos los motores |

Todo pide `Authorization: Bearer <token>` salvo `/api/health` y `/pair`.

---

## Cosas que aprendimos armándola

- **El LLM no devuelve JSON.** Devuelve una línea por acción (`+|leche|2|l|heladera|`).
  Son tres veces menos tokens de salida y en un 8B eso es contestar en un segundo
  en vez de ocho.
- **Un aviso tiene que decir de dónde viene.** Los avisos del micrófono viajan al
  hilo de la pantalla y pueden llegar cuando ese grabador ya se cerró. Al tocar
  HABLAR se apaga el de manos libres, y su último audio —ruido de la cocina—
  llegaba 150 ms después y se mandaba como si fuera el pedido. De ahí salía un
  "no te entendí" instantáneo, y alguna vez un "¡Suscríbete!" alucinado.
- **Nunca bloquear la pantalla esperando a la red.** Había un candado mientras se
  subía el audio; si el servidor tardaba, los botones quedaban muertos hasta 90
  segundos. Ahora cada turno tiene número y el viejo simplemente se descarta.
- **Contestá cuando la persona termina de hablar, no cuando terminaste de pensar.**
  Un "mmm" guardado en la tablet suena al instante y cambia por completo la
  sensación, aunque la respuesta real llegue dos segundos después.
- **El umbral de voz va sobre el ruido medido, no en un número fijo.** Un mínimo
  alto deja sorda a una tablet vieja: la SM-T350 capta cinco veces menos que un
  celular moderno. Y conviene histéresis, porque con un solo umbral las sílabas
  flojas del medio de una frase quedan afuera y la grabación se corta en
  *"anotame un kilo de fideos"*, justo antes de lo que faltaba.
- **`MIC` antes que `VOICE_RECOGNITION`.** La supresión de ruido de
  VOICE_RECOGNITION se come casi toda la señal en hardware viejo: el piso de
  ruido bajó de 31 a 5 con solo cambiar la fuente.
- **Amplificá por RMS, no por pico.** Un golpe de puerta dispara el pico, y
  amplificar por eso convierte el silencio en ruido blanco con el que whisper
  inventa frases enteras.
- **El `\b` de JavaScript es ASCII.** Después de "sacá" no ve un límite de
  palabra y la expresión no matchea nunca.
- **El token se guarda la primera vez.** Si no, cada reinicio inventa uno nuevo y
  la tablet queda afuera sin motivo.
- **Los modelos que razonan callan la respuesta.** Un qwen3 por la API estilo
  OpenAI devuelve el texto vacío porque todo quedó adentro del bloque de
  pensamiento: hay que apagarlo (`chat_template_kwargs.enable_thinking`) y
  limpiar los `<think>` por las dudas. Acá no hay nada que razonar.
- **Es más barato normalizar la jerga que pelearse con el prompt.** Un modelo
  chico no asocia "metele dos cervezas" con agregar y termina borrando. Cambiar
  el verbo antes de preguntarle lo arregla, y la persona sigue hablando como habla.
- **El `#token=` de una URL nunca llega al servidor.** Las páginas se sirven sin
  token y lo leen del fragmento; los datos sí lo piden.
- **Android 7 no tiene los emoji nuevos**: los botones dicen palabras.
- **Un agente de launchd no puede leer discos externos** ni usar `capture_output`
  con un comando que deja un daemon corriendo.

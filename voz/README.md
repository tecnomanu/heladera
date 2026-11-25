# Heladera · charla de voz en vivo

La tablet habla y la heladera contesta mientras piensa, como una llamada: no hay que tocar nada para que se dé
cuenta de que terminaste, podés hacer una pausa a mitad de frase y seguir, y si le hablás encima se calla.

```bash
heladera voz instalar     # una vez: arma ~/.heladera/voz/venv e instala lo que hace falta (~1 GB)
heladera voz              # la levanta en el puerto 8796
heladera voz servicio     # la unidad de systemd para que arranque sola
```

Usa la misma configuración que el resto (`~/.heladera/config.json`): el token de la heladera, su puerto, el whisper y
el ollama que elegiste en `heladera init`. La app de Android la usa sola en tablets (pantalla de 600dp o más);
en el teléfono sigue el modo de grabar y mandar.

## Qué necesita

| | |
|---|---|
| **ollama** con un modelo que use herramientas | `ollama pull qwen3:4b-instruct` (18 de 20 pedidos bien, ~0,9 s) |
| **whisper.cpp** (`whisper-server`) con `large-v3-turbo` | opcional pero recomendado: en GPU tarda 0,6 s. Sin él, el texto final lo hace faster-whisper en la CPU (varios segundos) |
| Python 3.9+ | para el entorno propio de la charla |

El resto (VAD, fin de turno, whisper chico para el texto en vivo, la voz) se baja solo de HuggingFace la primera vez.

## Cómo funciona

Un WebSocket por charla (`/talk?token=…`): sube audio del micrófono (PCM16 mono 16 kHz) y baja la voz de la
heladera más avisos (`partial`, `heard`, `state`, `saying`, `tool`, `interrupted`, `timing`, `end`; detalle al
principio de `server.py`). Sin el token de la heladera responde 403 antes de escuchar. `?mute=1` es "Escucharme":
hace lo que pediste y contesta solo en pantalla.

| Parte | Qué usa | Dónde |
|---|---|---|
| ¿Está hablando? | Silero VAD, cada 32 ms | CPU |
| ¿Terminó? | smart-turn v3 a los 160 ms de silencio (corte duro a 900 ms) | CPU |
| Texto en vivo | whisper `base` int8, cada 0,6 s mientras hablás | CPU |
| Texto final | whisper.cpp `large-v3-turbo` (`whisper-server`), arranca a los 64 ms de silencio | GPU (o CPU) |
| Entender y actuar | ollama, **con tool calling de verdad** (8 herramientas, `tools.py`) | GPU |
| Voz | Supertonic `F4` o Piper, frase por frase | CPU |

Si hacés una pausa a mitad de frase y seguís antes de que conteste, se une todo en un solo pedido; si hablás encima,
se calla; la tele y las charlas de la cocina se ignoran (`[[skip]]`). La confirmación ("Listo, anoté leche y pan.")
sale de lo que hicieron las herramientas, sin otra vuelta al modelo. Si el modelo tarda más de 1,2 s, dice una
muletilla ya hecha ("Dale, anoto." / "A ver."). "Nada más, gracias" cierra sin pasar por el modelo.

## Variables (todas opcionales)

| | default |
|---|---|
| `VOZ_PORT` / `VOZ_HOST` | 8796 / el `host` de la heladera |
| `VOZ_VOICE` | `supertonic:F4` (también `F1`–`F5`, `M1`–`M5`) o una voz de Piper (`es_AR-daniela-high`, buscada en `~/.heladera/engines/voices`) |
| `VOZ_LLM` / `VOZ_OLLAMA_URL` | `qwen3:4b-instruct` / el de la config |
| `VOZ_WHISPER_URL` | el `whispercpp` de la config; vacío = faster-whisper `VOZ_FINAL` (large-v3-turbo) en CPU |
| `VOZ_DRAFT` | `base` (el del texto en vivo) |
| `VOZ_LIST_URL` / `VOZ_LIST_TOKEN` | la heladera de la config / su token |
| `VOZ_HELADERA_CONFIG` | `~/.heladera/config.json` |
| `VOZ_NO_AUTH=1` | sin token (solo para pruebas contra una lista sin token) |

## Números (8 turnos con voces sintéticas, GPU RX 5700 XT + CPU Xeon)

Desde que terminás de hablar:

| | ms |
|---|---|
| decide que terminaste | ~320 |
| texto final en pantalla | ~600 |
| primera voz (muletilla) | ~1 200–1 800 |
| respuesta real ("Listo, anoté…") | 2 300 – 3 400 |
| cerrar ("nada más") | ~600 |

Lo que más pesa es el modelo generando el JSON de la herramienta (~56 tok/s, ~25 tokens por producto).

### Voces (primera frase, CPU libre, 8 hilos)

| Voz | primera frase | RTF |
|---|---|---|
| Piper `es_MX-claude-high` | 70 ms | 0,03 |
| Piper `es_AR-daniela-high` | 210 ms | 0,14 |
| Supertonic 3 (5 pasos) | 430–470 ms | 0,20 |
| Kokoro `ef_dora` | 550 ms | 0,28 |

Muestras en `muestras/` (`catalogo-voces.mp3`: 15 voces con la misma frase). Supertonic suena bastante más natural y
suma ~0,4 s por frase nueva; las muletillas y el "¿Algo más?" se hacen al arrancar, así que salen al toque igual.

### Modelo (20 pedidos de la heladera, tools reales)

| Modelo | aciertos | p50 |
|---|---|---|
| `qwen3:4b-instruct` | 18/20 | 0,9 s |
| `qwen3:8b` | 18/20 | 1,1 s |
| `qwen3:1.7b` | 7/20 | — |
| `llama3.2:3b` | 6/20 | — |

## Trampas que costaron

- **El prompt de sistema tiene que ser fijo.** Con la lista adentro, ollama releía prompt + herramientas (~1000
  tokens) en cada turno: 2,8 s. Con caché, 20 ms. La lista viaja en el mensaje del usuario (`pedido()`).
- **El historial lleva las llamadas a herramientas reales.** Si solo ve frases ("Listo, anoté…"), al quinto turno el
  modelo contesta "Borré la zanahoria" sin llamar a nada y la lista no cambia.
- **No mandar `num_ctx`** en los pedidos: si difiere del modelo cargado, ollama lo recarga.
- **Un pedido que se unió con el siguiente hay que cortarlo de verdad** (cerrar el socket): si no, sigue generando
  en otro slot de ollama y frena al nuevo.
- **La muletilla no cuenta como respuesta**, si no corta la unión de frases.
- **Unir pedazos con coma, no con punto**: "…con chorizo. Vacío y carbón." y el modelo tiraba el vacío.
- **El nombre entero, con su medida**: el modelo a veces deja la medida adentro ("kilo de papa") o parte el producto
  ("papa" + nota "fritas"). `limpiar()` y `juntar_nota()` lo arreglan antes de anotar; sin medida, lo de verdulería y
  carnicería va en kg.
- "Para mañana" no es una sublista; solo vale una sublista que nombraste o que ya existe.
- Si ollama comparte la placa con otros modelos, los desalojos hacen turnos de 5–45 s: conviene un solo modelo fijo
  (`OLLAMA_MAX_LOADED_MODELS=1`, `OLLAMA_KEEP_ALIVE=-1`).

## Probar sin micrófono

```
python probar.py u1.wav u2.wav …     # una charla, un turno por WAV (16 kHz mono); "!u3.wav" habla encima
```

La página `http://<servidor>:8796/#token=<token>` es un cliente de prueba con el micrófono del navegador (necesita
https o localhost para el micrófono; por la red, un túnel `ssh -L 8796:127.0.0.1:8796 <servidor>`). Los bancos de
voces, modelos y whisper están en `bancos/`.

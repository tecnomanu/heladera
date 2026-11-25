"""Heladera, charla de voz en vivo: la tablet habla y la heladera contesta mientras piensa, como una llamada.

Un WebSocket por charla. Sube PCM16 mono a 16 kHz; baja PCM16 mono a la frecuencia de la voz (el frame "ready" la
dice) y frames JSON:
  ready {sr}            ya se puede hablar
  partial {text}        lo que vas diciendo (whisper chico en CPU), se reemplaza a sí mismo
  heard {text}          lo que dijiste de verdad (whisper large-v3-turbo en la GPU)
  state {state}         listening | thinking | speaking
  tool {name, args, ok} lo que hizo sobre la lista
  saying {text}         la frase que está sonando
  interrupted           hablaste encima: tirar el audio que quede en el parlante
  timing {...}          cuánto tardó cada parte del turno (para medir)
  end                   la charla terminó

Cómo se toma el turno (igual que APX): Silero VAD cada 32 ms; a los 64 ms de silencio ya se manda a transcribir con
el modelo bueno (especulativo); a los 160 ms smart-turn decide si terminaste; a los 900 ms se corta igual. El modelo
de lenguaje contesta con herramientas; la confirmación sale de lo que hicieron las herramientas (sin otra vuelta al
modelo), y la charla suelta se lee frase por frase mientras el modelo la escribe.
"""
import asyncio
import contextlib
import hmac
import http.client
import json
import os
import re
import socket
import threading
import time
import unicodedata
import urllib.parse
import urllib.request
import uuid
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, JSONResponse

from tools import CATEGORIES, TOOLS, UNITS, juntar_nota, limpiar, pedido, system_prompt

HERE = Path(__file__).parent
SR_IN, FRAME = 16000, 512
FRAME_MS = FRAME / SR_IN * 1000
VAD_ON, VAD_ON_SPEAKING = 0.5, 0.85
START_FRAMES, BARGE_FRAMES = 3, 6
EARLY_STT_MS, TURN_CHECK_MS, TURN_MAX_MS = 64, 160, 900
MIN_SPEECH_MS, PARTIAL_EVERY_S = 250, 0.6
HOLD_S = 1.2  # si el modelo tarda más que esto, una muletilla corta (ya hecha) para que no quede en silencio
ADDING = re.compile(r"\b(anot|sum[aá]|pon[eé]|agreg|necesit|tra[eé]|compr[aá]\b|guard|met[eé]|arm[aá])", re.I)
HOLD = {"add": "Dale, anoto.", "other": "A ver."}
ECHO_S, HISTORY = 8, 48  # una charla corta entra entera: recortar corre el historial y ollama lo relee

# Lo que no venga por variables de entorno sale de la config de la heladera (la de `heladera init`): así la charla
# usa el mismo puerto, token, whisper y modelo que el resto, sin configurar dos veces.
HELADERA_CONFIG = os.environ.get("VOZ_HELADERA_CONFIG") or str(
    Path(os.environ.get("HELADERA_HOME", Path.home() / ".heladera")) / "config.json")


def _cfg():
    with contextlib.suppress(Exception):
        return json.load(open(HELADERA_CONFIG))
    return {}


CFG = _cfg()
_stt, _llm, _auth = CFG.get("stt") or {}, CFG.get("llm") or {}, CFG.get("auth") or {}
# whisper.cpp (`whisper-server`) para el texto final; sin él, faster-whisper en la CPU (anda, pero más lento)
WHISPER_URL = os.environ.get("VOZ_WHISPER_URL",
                             _stt.get("url", "") if _stt.get("provider") == "whispercpp" else "")
FINAL_MODEL = os.environ.get("VOZ_FINAL", "large-v3-turbo")
OLLAMA_URL = os.environ.get("VOZ_OLLAMA_URL",
                            _llm.get("url") if _llm.get("provider", "ollama") == "ollama" and _llm.get("url")
                            else "http://127.0.0.1:11434")
# Hace falta un modelo con tool calling; qwen3:4b-instruct acertó 18 de 20 pedidos (ver bancos/).
LLM_MODEL = os.environ.get("VOZ_LLM", "qwen3:4b-instruct")
LIST_URL = os.environ.get("VOZ_LIST_URL", f"http://127.0.0.1:{CFG.get('port', 8790)}")
# El token de la heladera: el mismo abre la lista y la charla (la tablet ya lo tiene desde que se emparejó).
# Nunca se imprime.
LIST_TOKEN = os.environ.get("VOZ_LIST_TOKEN") or (_auth.get("token", "") if _auth.get("enabled", True) else "")
if os.environ.get("VOZ_NO_AUTH"):  # pruebas contra una lista sin token
    LIST_TOKEN = ""
DRAFT_MODEL = os.environ.get("VOZ_DRAFT", "base")
# supertonic:F4 suena natural y se baja sola; una voz de piper (es_AR-daniela-high, …) es más rápida (ver README)
VOICE = os.environ.get("VOZ_VOICE", "supertonic:F4")
VOICES_DIR = Path(os.environ.get("VOZ_VOICES_DIR", Path.home() / ".heladera/engines/voices"))
TTS_THREADS = int(os.environ.get("VOZ_TTS_THREADS", "6"))
# Glosario y no oración: whisper escribe una oración de prompt encima del silencio como si la hubieras dicho.
STT_HINT = os.environ.get("VOZ_STT_HINT", "Heladera, yerba, lavandina, fideos, papel higiénico, chorizo, vacío, carbón.")

SENTENCE_END = re.compile(r"(?<=[.!?…])[\"')\]]?\s+")
SKIP = re.compile(r"\[\[\s*skip\s*\]\]|\[[^\]]*\]?", re.I)  # [[skip]] y la nota [Lista: …] nunca se dicen
NOISE = re.compile(r"(subt[ií]tulos|amara\.org|gracias por ver|thanks for watching)", re.I)
WORD = re.compile(r"\w+")
# Cerrar no necesita al modelo: "nada más, gracias" contesta al toque y no depende de que elija la herramienta.
CLOSE = re.compile(r"^\W*(no\W+)?(nada m[aá]s|eso es todo|eso era todo|es todo|ya est[aá]|listo,? eso es todo|chau|"
                   r"no,? gracias|no necesito nada m[aá]s)(\W+(gracias|chau|nada m[aá]s|eso es todo))*\W*$", re.I)
MARKUP = re.compile(r"[*_#`]+|[\U0001F000-\U0001FAFF☀-➿️‍]")

gpu_stt = ThreadPoolExecutor(1, thread_name_prefix="stt-gpu")   # whisper.cpp atiende de a uno
cpu_stt = ThreadPoolExecutor(1, thread_name_prefix="stt-cpu")
mouth_x = ThreadPoolExecutor(1, thread_name_prefix="tts")


def _hf(repo, file):
    from huggingface_hub import hf_hub_download
    return hf_hub_download(repo, file)


def norm(s):
    s = unicodedata.normalize("NFD", str(s or "").lower())
    return re.sub(r"\s+", " ", "".join(c for c in s if unicodedata.category(c) != "Mn")).strip()


# ---------------------------------------------------------------- oídos

class Vad:
    def __init__(self):
        import onnxruntime as ort
        o = ort.SessionOptions()
        o.intra_op_num_threads = o.inter_op_num_threads = 1
        self.s = ort.InferenceSession(_hf("onnx-community/silero-vad", "onnx/model.onnx"), o,
                                      providers=["CPUExecutionProvider"])
        self.inputs = {i.name for i in self.s.get_inputs()}


class VadStream:
    def __init__(self, vad):
        self.vad, self.state, self.ctx = vad, np.zeros((2, 1, 128), np.float32), np.zeros(64, np.float32)

    def prob(self, frame):
        x = np.concatenate([self.ctx, frame])[None, :].astype(np.float32)
        self.ctx = frame[-64:]
        feed = {"input": x, "state": self.state, "sr": np.array(SR_IN, dtype=np.int64)}
        out, self.state = self.vad.s.run(None, {k: v for k, v in feed.items() if k in self.vad.inputs})
        return float(out.reshape(-1)[0])


class EndOfTurn:
    """smart-turn v3: ¿terminó de hablar?, mirando los últimos 8 s."""
    SECONDS = 8

    def __init__(self):
        import onnxruntime as ort
        from transformers import WhisperFeatureExtractor
        o = ort.SessionOptions()
        o.intra_op_num_threads = 2
        self.s = ort.InferenceSession(_hf("pipecat-ai/smart-turn-v3", "smart-turn-v3.2-cpu.onnx"), o,
                                      providers=["CPUExecutionProvider"])
        self.inp = self.s.get_inputs()[0].name
        self.fx = WhisperFeatureExtractor(chunk_length=self.SECONDS)

    def prob(self, audio):
        n = self.SECONDS * SR_IN
        audio = audio[-n:]
        audio = np.pad(audio, (n - len(audio), 0)) if len(audio) < n else audio
        f = self.fx(audio, sampling_rate=SR_IN, return_tensors="np", padding="max_length", max_length=n,
                    truncation=True, do_normalize=True)
        p = float(np.asarray(self.s.run(None, {self.inp: f.input_features.astype(np.float32)})[0]).reshape(-1)[0])
        return p if 0 <= p <= 1 else float(1 / (1 + np.exp(-p)))


def wav_bytes(pcm16: bytes, sr: int) -> bytes:
    import struct
    return b"RIFF" + struct.pack("<I4s4sIHHIIHH4sI", 36 + len(pcm16), b"WAVE", b"fmt ", 16, 1, 1, sr, sr * 2, 2, 16,
                                 b"data", len(pcm16)) + pcm16


class Ears:
    def __init__(self):
        from faster_whisper import WhisperModel
        self.draft = WhisperModel(DRAFT_MODEL, device="cpu", compute_type="int8", cpu_threads=4)
        # Sin whisper-server, el texto final lo hace un whisper grande en la CPU. En español el tamaño importa
        # mucho ("small" escribe cualquier cosa); en GPU con whisper.cpp tarda 0,6 s, en CPU varios segundos.
        self.big = None if WHISPER_URL else WhisperModel(FINAL_MODEL, device="cpu", compute_type="int8",
                                                         cpu_threads=max(2, (os.cpu_count() or 4) // 2))

    def _final(self, audio, prompt):
        if self.big is not None:
            t = time.monotonic()
            segs, _ = self.big.transcribe(audio, language="es", beam_size=1, vad_filter=False,
                                          without_timestamps=True, condition_on_previous_text=False,
                                          initial_prompt=prompt)
            text = re.sub(r"\s+", " ", "".join(s.text for s in segs)).strip()
            print(f"[voz] final (cpu) {len(audio) / SR_IN:.1f}s en {time.monotonic() - t:.2f}s: {text}", flush=True)
            return text
        pcm = (np.clip(audio, -1, 1) * 32767).astype(np.int16).tobytes()
        b = uuid.uuid4().hex
        fields = {"response_format": "json", "language": "es", "prompt": prompt, "no_context": "true",
                  "temperature": "0"}
        body = b"".join(f"--{b}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
                        for k, v in fields.items())
        body += f"--{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\nContent-Type: audio/wav\r\n\r\n".encode()
        body += wav_bytes(pcm, SR_IN) + f"\r\n--{b}--\r\n".encode()
        req = urllib.request.Request(WHISPER_URL + "/inference", body,
                                     {"Content-Type": f"multipart/form-data; boundary={b}"})
        t = time.monotonic()
        text = json.load(urllib.request.urlopen(req, timeout=30)).get("text", "")
        text = re.sub(r"\s+", " ", text).strip()
        print(f"[voz] final {len(audio) / SR_IN:.1f}s en {time.monotonic() - t:.2f}s: {text}", flush=True)
        return text

    def final(self, audio, prompt=STT_HINT):
        return gpu_stt.submit(self._final, audio, prompt)

    def _partial(self, audio):
        segs, _ = self.draft.transcribe(audio, language="es", beam_size=1, vad_filter=False, without_timestamps=True,
                                        condition_on_previous_text=False, initial_prompt=STT_HINT)
        return "".join(s.text for s in segs).strip()

    def partial(self, audio):
        return cpu_stt.submit(self._partial, audio)


# ---------------------------------------------------------------- boca

class Mouth:
    """Piper en memoria, de a una frase: la primera suena mientras se hace la segunda."""

    def __init__(self, voice):
        import onnxruntime as ort
        from piper import PiperVoice
        path = VOICES_DIR / f"{voice}.onnx"
        self.v = PiperVoice.load(str(path))
        o = ort.SessionOptions()
        o.intra_op_num_threads, o.inter_op_num_threads = TTS_THREADS, 1
        self.v.session = ort.InferenceSession(str(path), o, providers=["CPUExecutionProvider"])
        self.sr = self.v.config.sample_rate
        self.cache = {}

    def _speak(self, text, push, stop):
        if text in self.cache:
            push(self.cache[text])
            return
        out = []
        for chunk in self.v.synthesize(text):
            if stop.is_set():
                return
            out.append(chunk.audio_int16_bytes)
            push(chunk.audio_int16_bytes)
        if len(text) < 60:
            self.cache[text] = b"".join(out)

    def speak(self, text, push, stop):
        return mouth_x.submit(self._speak, text, push, stop)


class MouthSupertonic(Mouth):
    """Supertonic 3 (VOZ_VOICE=supertonic:F4): suena mejor que Piper y tarda ~0,4 s más por frase.
    Hace la frase entera de una vez y la manda en pedazos, así un "callate" la corta enseguida."""

    def __init__(self, voice, steps=5):
        from supertonic import TTS
        self.tts = TTS(model="supertonic-3", intra_op_num_threads=TTS_THREADS, inter_op_num_threads=1)
        self.style = self.tts.get_voice_style(voice) if hasattr(self.tts, "get_voice_style") else voice
        self.steps = steps
        self.sr = int(getattr(self.tts, "sample_rate", 44100))
        self.cache = {}

    def _speak(self, text, push, stop):
        if text in self.cache:
            push(self.cache[text])
            return
        a, _ = self.tts.synthesize(text, voice_style=self.style, total_steps=self.steps, lang="es")
        pcm = (np.clip(np.asarray(a, np.float32).reshape(-1), -1, 1) * 32767).astype(np.int16).tobytes()
        step = self.sr // 5 * 2  # 200 ms
        for i in range(0, len(pcm), step):
            if stop.is_set():
                return
            push(pcm[i:i + step])
        if len(text) < 60:
            self.cache[text] = pcm


def make_mouth(voice):
    return MouthSupertonic(voice.split(":", 1)[1]) if voice.startswith("supertonic:") else Mouth(voice)


# ---------------------------------------------------------------- la lista (otra heladera, la de prueba)

def api(method, path, body=None):
    h = {"Content-Type": "application/json"}
    if LIST_TOKEN:
        h["Authorization"] = f"Bearer {LIST_TOKEN}"
    req = urllib.request.Request(LIST_URL + path, json.dumps(body).encode() if body is not None else None, h,
                                 method=method)
    return json.load(urllib.request.urlopen(req, timeout=10))


def lista():
    return api("GET", "/api/list")


def find_ids(names, items, done=None):
    found, missing = [], []
    for name in names:
        n = norm(name).rstrip("s")
        pool = [i for i in items if done is None or bool(i.get("done")) == done]
        hit = next((i for i in pool if norm(i["name"]).rstrip("s") == n), None) or \
            next((i for i in pool if n and (n in norm(i["name"]) or norm(i["name"]).rstrip("s") in n)), None)
        (found.append(hit) if hit else missing.append(name))
    return found, missing


def y(words):
    words = [w for w in words if w]
    return words[0] if len(words) == 1 else ", ".join(words[:-1]) + " y " + words[-1] if words else ""


# "para mañana" es un día, no una sublista.
WHEN = {"hoy", "manana", "pasado manana", "lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo",
        "finde", "fin de semana", "semana", "compras", "lista", "general", "super", "supermercado"}


def groups_now():
    try:
        return {i["group"] for i in lista()["items"] if i.get("group")}
    except Exception:
        return set()


def run_tool(name, a, heard=""):
    """Hace lo que pidió y devuelve (frase para decir, ok, fin de la charla)."""
    if name == "add_items":
        items = [limpiar(juntar_nota(i, heard)) for i in (a.get("items") or []) if isinstance(i, dict) and i.get("name")]
        group = (a.get("list") or "").strip() or None
        # Solo una sublista que nombró o que ya existe: el modelo a veces inventa "compras" para cosas sueltas.
        if group and (norm(group) in WHEN or (norm(group) not in norm(heard) and group not in groups_now())):
            group = None
        for i in items:
            # el modelo a veces se sale del enum ("roll"): mejor la medida de siempre que una inventada
            unit = i["unit"]
            cat = i.get("cat") if i.get("cat") in CATEGORIES else None
            api("POST", "/api/items", {"name": i["name"], "qty": i["qty"], "unit": unit,
                                       "cat": cat, "note": i.get("note") or None, "group": group,
                                       "source": "voz"})
        where = f" en la lista del {group}" if group else ""
        return (f"Listo, anoté {y([i['name'] for i in items])}{where}." if items else "No te entendí qué anotar."), \
            bool(items), False
    if name in ("check_off_items", "remove_items"):
        names = a.get("names") or []
        names = [names] if isinstance(names, str) else names
        st = lista()
        hits, missing = find_ids(names, st["items"], done=False if name == "check_off_items" else None)
        if hits:
            api("POST", "/api/items/bulk", {"ids": [i["id"] for i in hits],
                                             "what": "done" if name == "check_off_items" else "remove"})
        verb = "Taché" if name == "check_off_items" else "Borré"
        say = f"{verb} {y([i['name'] for i in hits])}." if hits else ""
        if missing:
            say += f" No encontré {y(missing)} en la lista."
        return say.strip(), bool(hits), False
    if name == "read_list":
        pend = [i["name"] for i in lista()["items"] if not i.get("done")]
        if not pend:
            return "No falta nada, está todo comprado.", True, False
        head = "Falta una cosa: " if len(pend) == 1 else f"Faltan {len(pend)} cosas: "
        return head + y(pend[:12]) + ("." if len(pend) <= 12 else ", y algunas más."), True, False
    if name == "clear_list":
        only_done = bool(a.get("only_done"))
        r = api("POST", "/api/list/clear", {"scope": "done" if only_done else "all"})
        return ("Borré lo que ya estaba comprado." if only_done else "Listo, vacié la lista."), r.get("cleared", 0) >= 0, False
    if name == "group_by_aisle":
        api("POST", "/api/list/organizar", {"deshacer": bool(a.get("undo"))})
        return ("Junté todo en una sola lista." if a.get("undo") else "Separé la lista por góndola."), True, False
    if name == "send_list":
        ch = a.get("channel") or "telegram"
        try:
            ok = bool(api("POST", "/api/send", {"channel": ch}).get("sent"))
        except Exception:
            ok = False
        return (f"Te la mandé por {ch}." if ok else f"No pude mandarla por {ch}."), ok, False
    if name == "end_conversation":
        return "Dale, cualquier cosa me avisás.", True, True
    return "", False, False


# ---------------------------------------------------------------- una charla

class Talk:
    def __init__(self, ws, ears, mouth, turn, vad, voiced=True):
        self.ws, self.ears, self.mouth, self.turn = ws, ears, mouth, turn
        self.vad = VadStream(vad)
        self.voiced = voiced  # False = modo "escucharme": hace, no habla
        self.pending = np.zeros(0, np.float32)
        self.preroll, self.utt = deque(maxlen=8), []
        self.in_speech, self.run, self.silence, self.speech_frames = False, 0, 0, 0
        self.stt, self.checked, self.barged = None, False, False
        self.partial_at, self.partial_task = 0.0, None
        self.reply_task, self.stop = None, threading.Event()
        # Lo que se está contestando y si ya empezó (habló o tocó la lista). Si seguís hablando antes de eso, lo
        # nuevo se suma al mismo pedido: una pausa a mitad de frase no parte "che, sumá yerba … y fideos" en dos.
        self.asking, self.answered = [], False
        self.playing_until = 0.0
        self.said = deque(maxlen=6)
        self.history = []
        self.t = {}  # tiempos del turno

    async def send(self, f):
        await self.ws.send_text(json.dumps(f, ensure_ascii=False))

    def speaking(self):
        return time.monotonic() < self.playing_until

    # --- hablar

    async def say(self, text, filler=False):
        """filler: la muletilla no cuenta como respuesta, lo que digas después se suma al mismo pedido."""
        if not text:
            return
        if not self.voiced:  # modo "escucharme": sin audio, pero la pantalla igual muestra la respuesta
            if not filler:
                self.answered = True
                self.t.setdefault("first_audio", time.monotonic())
                await self.send({"type": "saying", "text": text, "filler": False})
            return
        loop, q = asyncio.get_running_loop(), asyncio.Queue()
        self.stop = threading.Event()
        fut = self.mouth.speak(text, lambda b: loop.call_soon_threadsafe(q.put_nowait, b), self.stop)
        fut.add_done_callback(lambda _: loop.call_soon_threadsafe(q.put_nowait, None))
        first = True
        try:
            while (b := await q.get()) is not None:
                now = time.monotonic()
                if first:
                    first = False
                    self.answered = self.answered or not filler
                    self.t.setdefault("first_audio", now)
                    await self.send({"type": "saying", "text": text, "filler": filler})
                self.playing_until = max(now, self.playing_until) + len(b) / 2 / self.mouth.sr
                await self.ws.send_bytes(b)
        finally:
            self.stop.set()
            self.said.append((set(WORD.findall(text.lower())), self.playing_until))

    def echo(self, heard):
        words = WORD.findall(heard.lower())
        now = time.monotonic()
        return bool(words) and any(now - until < ECHO_S and sum(w in s for w in words) >= max(1, round(len(words) * .75))
                                   for s, until in self.said)

    # --- pensar

    async def hold_on(self, text):
        await asyncio.sleep(HOLD_S)
        await self.say(HOLD["add" if ADDING.search(text) and "?" not in text else "other"], filler=True)

    async def think(self, text, hold=None):
        """Una vuelta del modelo con herramientas. La charla suelta se lee por frases mientras llega."""
        try:
            st = await asyncio.to_thread(lista)
            items = [i["name"] for i in st["items"] if not i.get("done")]
            groups = sorted({i["group"] for i in st["items"] if i.get("group")})
        except Exception:
            items, groups = [], []
        # El historial lleva las llamadas a herramientas de verdad: si solo ve frases ("Listo, anoté…"), el modelo
        # empieza a contestar con la frase sin llamar a nada, y la lista no cambia.
        turn = [{"role": "user", "content": pedido(text, items, groups)}]
        body = {"model": LLM_MODEL, "stream": True, "think": False, "keep_alive": -1, "tools": TOOLS,
                "options": {"temperature": 0.2},
                "messages": [{"role": "system", "content": system_prompt()}, *self.history, *turn]}
        q = asyncio.Queue()
        loop = asyncio.get_running_loop()
        cancel = threading.Event()
        conn = {}

        def pump():
            try:
                u = urllib.parse.urlsplit(OLLAMA_URL)
                c = http.client.HTTPConnection(u.hostname, u.port or 80, timeout=60)
                conn["c"] = c
                c.request("POST", "/api/chat", json.dumps(body), {"Content-Type": "application/json"})
                r = c.getresponse()
                for line in r:
                    if cancel.is_set():
                        return
                    loop.call_soon_threadsafe(q.put_nowait, json.loads(line))
            except Exception as e:
                if not cancel.is_set():
                    loop.call_soon_threadsafe(q.put_nowait, {"error": repr(e)})
            finally:
                loop.call_soon_threadsafe(q.put_nowait, None)

        def hang_up():
            """Cortar la conexión es lo único que frena a ollama: con herramientas no manda nada hasta terminar, así
            que un pedido abandonado seguiría generando en el otro lugar y frenaría al nuevo."""
            cancel.set()
            c = conn.get("c")
            if c and c.sock:
                with contextlib.suppress(OSError):
                    c.sock.shutdown(socket.SHUT_RDWR)

        threading.Thread(target=pump, daemon=True).start()
        buf, spoken, calls, ended = "", [], [], False
        try:
            while (m := await q.get()) is not None:
                if "error" in m:
                    raise RuntimeError(m["error"])
                msg = m.get("message") or {}
                if (msg.get("content") or msg.get("tool_calls")) and "llm_first" not in self.t:
                    self.t["llm_first"] = time.monotonic()
                for tc in msg.get("tool_calls") or []:
                    f = tc["function"]
                    args = f.get("arguments") or {}
                    calls.append((f["name"], json.loads(args) if isinstance(args, str) else args))
                buf += msg.get("content") or ""
                while (s := SENTENCE_END.search(buf)):
                    line, buf = buf[:s.end()].strip(), buf[s.end():]
                    line = MARKUP.sub("", SKIP.sub("", line)).strip()
                    if line and not calls:
                        if hold and not hold.done():
                            hold.cancel()
                        await self.say(line)
                        spoken.append(line)
            self.t["llm_done"] = time.monotonic()
            line = MARKUP.sub("", SKIP.sub("", buf)).strip()
            if hold and not hold.done():
                hold.cancel()
            if line and not calls:
                await self.say(line)
                spoken.append(line)
            # Las herramientas: hacen y dicen lo que hicieron. Sin otra vuelta al modelo.
            if hold and not hold.done():
                hold.cancel()  # no empezó: ya no hace falta
            elif hold:
                await asyncio.sleep(0)
            if calls:
                self.answered = True  # la lista ya cambia: lo que digas después es otro pedido
                turn.append({"role": "assistant", "content": "",
                             "tool_calls": [{"function": {"name": n, "arguments": a}} for n, a in calls]})
            confirm = []
            for name, args in calls:
                try:
                    phrase, ok, end = await asyncio.to_thread(run_tool, name, args, text)
                except Exception as e:
                    phrase, ok, end = "No pude tocar la lista.", False, False
                    print(f"[voz] tool {name}: {e!r}", flush=True)
                turn.append({"role": "tool", "tool_name": name, "content": phrase or ("ok" if ok else "falló")})
                await self.send({"type": "tool", "name": name, "args": args, "ok": ok})
                ended = ended or end
                if phrase:
                    confirm.append(phrase)
            if calls:
                self.t["tools_done"] = time.monotonic()
                if not ended and not any(c[0] == "read_list" for c in calls):
                    confirm.append("¿Algo más?")
                for c in confirm:
                    await self.say(c)
                spoken += confirm
        except asyncio.CancelledError:
            if spoken or calls:  # nada dicho ni hecho: el pedido vuelve entero con lo que siga
                self.remember(turn + [{"role": "assistant", "content": " ".join(spoken) + " …"}])
            raise
        finally:
            hang_up()
        self.remember(turn + [{"role": "assistant", "content": " ".join(spoken) or "[[skip]]"}])
        return ended

    def remember(self, turn):
        """Los últimos pedidos enteros (cortar por la mitad dejaría una respuesta de herramienta sin su llamada)."""
        self.history += turn
        while len(self.history) > HISTORY:
            nxt = next((k for k, m in enumerate(self.history) if k > 0 and m["role"] == "user"), len(self.history))
            self.history = self.history[nxt:]

    # --- turnos

    def barge_in(self):
        """Hablaste encima: se calla. La respuesta en curso solo se cancela si ya era la de verdad; si sonaba la
        muletilla, el modelo sigue pensando (puede ser el eco del parlante, eso lo deciden las palabras)."""
        self.barged = True
        self.stop.set()
        if self.answered and self.reply_task and not self.reply_task.done():
            self.reply_task.cancel()
        self.playing_until = time.monotonic()

    async def feed(self, data):
        if getattr(self, "closing", False):
            return
        a = np.frombuffer(data[: len(data) // 2 * 2], np.int16).astype(np.float32) / 32768
        self.pending = np.concatenate([self.pending, a])
        while len(self.pending) >= FRAME:
            frame, self.pending = self.pending[:FRAME], self.pending[FRAME:]
            await self.frame(frame)

    async def frame(self, frame):
        p = self.vad.prob(frame)
        talking = self.speaking()
        voiced = p >= (VAD_ON_SPEAKING if talking else VAD_ON)
        now = time.monotonic()
        if not self.in_speech:
            self.preroll.append(frame)
            self.run = self.run + 1 if voiced else 0
            if self.run >= START_FRAMES:
                self.in_speech, self.utt, self.silence = True, list(self.preroll), 0
                self.speech_frames, self.checked, self.barged, self.stt, self.partial_at = self.run, False, False, None, 0
                self.over = talking
            return
        self.utt.append(frame)
        if talking:
            self.over = True
        if voiced:
            self.speech_frames += 1
            self.run = self.run + 1 if self.silence == 0 else 1
            if self.silence:
                self.silence, self.checked, self.stt = 0, False, None
            if talking and not self.barged and self.run >= BARGE_FRAMES:
                self.barge_in()
                await self.send({"type": "interrupted"})
            self.maybe_partial(talking)
            return
        self.silence += 1
        quiet = self.silence * FRAME_MS
        if self.silence == 1:
            self.t = {"speech_end": now - FRAME_MS / 1000}
        if quiet >= EARLY_STT_MS and self.stt is None and self.speech_frames * FRAME_MS >= MIN_SPEECH_MS:
            self.stt = self.ears.final(np.concatenate(self.utt))  # especulativo: si seguís hablando se descarta
        if quiet >= TURN_CHECK_MS and not self.checked:
            self.checked = True
            p_end = await asyncio.to_thread(self.turn.prob, np.concatenate(self.utt))
            if p_end >= 0.5:
                return await self.commit(f"smart-turn {p_end:.2f}")
        if quiet >= TURN_MAX_MS:
            await self.commit("silencio")

    def maybe_partial(self, talking):
        if (talking and not self.barged) or (self.partial_task and not self.partial_task.done()):
            return
        heard_s = len(self.utt) * FRAME / SR_IN
        if heard_s - self.partial_at < PARTIAL_EVERY_S:
            return
        self.partial_at = heard_s
        self.partial_task = asyncio.create_task(self.partial(np.concatenate(self.utt)))

    async def partial(self, audio):
        try:
            text = await asyncio.wrap_future(self.ears.partial(audio))
        except Exception:
            return
        if text and self.in_speech and not NOISE.search(text):
            await self.send({"type": "partial", "text": text})

    async def commit(self, why):
        stt, speech_ms = self.stt, self.speech_frames * FRAME_MS
        utt = self.utt
        self.in_speech, self.utt, self.run, self.stt = False, [], 0, None
        self.preroll.clear()
        self.t["commit"] = time.monotonic()
        self.t["why"] = why
        if speech_ms < MIN_SPEECH_MS:
            await self.send({"type": "partial", "text": ""})
            return
        if not stt:
            stt = self.ears.final(np.concatenate(utt))
        if getattr(self, "over", False):
            # Se dijo mientras sonaba la heladera: puede ser su propio eco ("A ver." por el parlante). Primero se
            # escucha qué dice; el eco se tira sin tocar la respuesta en curso.
            asyncio.create_task(self.overheard(stt))
            return
        self.take(stt)

    async def overheard(self, stt):
        try:
            text = (await asyncio.wrap_future(stt)).strip()
        except Exception:
            text = ""
        if not text or NOISE.search(text) or self.echo(text) or len(WORD.findall(text)) < 2:
            print(f"[voz] eco/ruido ignorado: {text!r}", flush=True)
            await self.send({"type": "partial", "text": ""})
            return
        if self.answered and self.reply_task and not self.reply_task.done():
            self.reply_task.cancel()  # era la persona, y la respuesta ya estaba sonando: lo nuevo manda
        f = asyncio.get_running_loop().create_future()
        f.set_result(text)
        self.take(f)

    def take(self, stt):
        going_on = bool(self.reply_task and not self.reply_task.done() and not self.answered)
        self.asking = [*self.asking, stt] if going_on else [stt]
        if self.reply_task and not self.reply_task.done():
            self.reply_task.cancel()
        self.reply_task = asyncio.create_task(self.answer(list(self.asking), dict(self.t), going_on))
        self.over = False

    async def answer(self, stts, t, going_on=False):
        self.t, self.answered = t, False
        texts = []
        for f in stts:
            try:  # shield: si llega otro pedido, estas mismas transcripciones las usa el que sigue
                texts.append((await asyncio.shield(asyncio.wrap_future(f) if not asyncio.isfuture(f) else f)).strip())
            except asyncio.CancelledError:
                raise
            except Exception as e:
                print(f"[voz] stt: {e!r}", flush=True)
        texts = [x for x in texts if x]
        text = ", ".join([re.sub(r"[.…]+$", "", x) for x in texts[:-1]] + texts[-1:])
        self.t["heard"] = time.monotonic()
        if going_on:
            self.t["joined"] = len(stts)
        if not text or NOISE.search(text) or self.echo(text) or all(w in STT_HINT.lower() for w in WORD.findall(text.lower())):
            await self.send({"type": "partial", "text": ""})
            await self.send({"type": "state", "state": "listening"})
            return
        await self.send({"type": "heard", "text": text, "joined": going_on})  # joined: reemplaza al pedido anterior
        await self.send({"type": "state", "state": "thinking"})
        ended = False
        try:
            if CLOSE.match(text):
                self.answered = True
                await self.send({"type": "tool", "name": "end_conversation", "args": {}, "ok": True})
                await self.say("Dale, cualquier cosa me avisás.")
                ended = True
            else:
                hold = asyncio.create_task(self.hold_on(text) if not going_on else asyncio.sleep(0))
                try:
                    ended = await self.think(text, hold)
                finally:
                    hold.cancel()
        except asyncio.CancelledError:
            raise
        except Exception as e:
            print(f"[voz] think: {e!r}", flush=True)
            await self.say("Se me trabó algo, ¿me lo repetís?")
        t0 = self.t.get("speech_end", self.t.get("commit"))
        ms = {k: round((v - t0) * 1000) for k, v in self.t.items() if isinstance(v, float) and k != "speech_end"}
        ms["why"] = self.t.get("why")
        await self.send({"type": "timing", **ms})
        print(f"[voz] turno: {ms}", flush=True)
        if ended:
            self.closing = True  # lo que llegue mientras se despide no es otro pedido
            await asyncio.sleep(max(0, self.playing_until - time.monotonic()) + 0.3)
            await self.send({"type": "end"})
            await self.ws.close()
            return
        await self.send({"type": "state", "state": "listening"})


# ---------------------------------------------------------------- servidor

app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
parts = {}


def warm_llm():
    """Carga el modelo y deja procesado el prompt con las herramientas (~1000 tokens, 2,8 s en frío): el primer
    pedido de la charla ya no los paga. Se llama al arrancar y al abrir cada charla (otro cliente de ollama pudo
    haber pisado la caché mientras tanto)."""
    body = {"model": LLM_MODEL, "stream": False, "think": False, "keep_alive": -1, "tools": TOOLS,
            "options": {"temperature": 0.2, "num_predict": 1},
            "messages": [{"role": "system", "content": system_prompt()}, {"role": "user", "content": "hola"}]}
    t = time.monotonic()
    try:
        urllib.request.urlopen(urllib.request.Request(OLLAMA_URL + "/api/chat", json.dumps(body).encode(),
                                                      {"Content-Type": "application/json"}), timeout=120).read()
        print(f"[voz] modelo caliente en {time.monotonic() - t:.1f}s", flush=True)
    except Exception as e:
        print(f"[voz] no pude calentar el modelo: {e!r}", flush=True)


@app.on_event("startup")
async def startup():
    t = time.monotonic()
    parts["vad"] = Vad()
    parts["turn"] = EndOfTurn()
    parts["ears"] = Ears()
    parts["mouth"] = make_mouth(VOICE)
    for line in ["Hola.", *HOLD.values(), "Dale, cualquier cosa me avisás.", "¿Algo más?"]:
        parts["mouth"].speak(line, lambda _: None, threading.Event()).result()
    parts["ears"].partial(np.zeros(SR_IN, np.float32)).result()
    # el modelo queda cargado: la primera charla no espera a ollama
    threading.Thread(target=warm_llm, daemon=True).start()
    print(f"[voz] listo en {time.monotonic() - t:.1f}s · voz {VOICE} · llm {LLM_MODEL} · borrador {DRAFT_MODEL}", flush=True)


@app.get("/")
async def index():
    return FileResponse(HERE / "index.html")


@app.get("/health")
async def health():
    return JSONResponse({"ok": bool(parts), "voice": VOICE, "llm": LLM_MODEL, "draft": DRAFT_MODEL})


@app.websocket("/talk")
async def talk(ws: WebSocket):
    # Con token configurado, la charla pide el mismo de la heladera (?token=…); sin él, se cierra antes de escuchar.
    if LIST_TOKEN and not hmac.compare_digest(ws.query_params.get("token", ""), LIST_TOKEN):
        await ws.close(code=4401)
        return
    await ws.accept()
    threading.Thread(target=warm_llm, daemon=True).start()
    t = Talk(ws, parts["ears"], parts["mouth"], parts["turn"], parts["vad"],
             voiced=ws.query_params.get("mute") != "1")
    await t.send({"type": "ready", "sr": parts["mouth"].sr})
    try:
        while True:
            msg = await ws.receive()
            if msg["type"] == "websocket.disconnect":
                break
            if msg.get("bytes"):
                await t.feed(msg["bytes"])
            elif msg.get("text"):
                f = json.loads(msg["text"])
                if f.get("type") == "text" and f.get("text"):  # escrito en vez de dicho (para probar)
                    t.barge_in()
                    t.t = {"speech_end": time.monotonic(), "commit": time.monotonic()}
                    fut = asyncio.get_running_loop().create_future()
                    fut.set_result(f["text"])
                    t.asking = [fut]
                    t.reply_task = asyncio.create_task(t.answer([fut], t.t))
    except (WebSocketDisconnect, RuntimeError):
        pass
    finally:
        t.barge_in()


if __name__ == "__main__":
    # Escucha donde escucha la heladera (0.0.0.0 por defecto): la tablet tiene que llegar. Sin el token no entra nadie.
    uvicorn.run(app, host=os.environ.get("VOZ_HOST", CFG.get("host", "127.0.0.1")),
                port=int(os.environ.get("VOZ_PORT", (CFG.get("voz") or {}).get("port", 8796))),
                log_level="warning", ws_max_size=8 << 20)

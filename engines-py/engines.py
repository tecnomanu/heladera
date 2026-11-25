"""Sidecar de heladera: whisper y piper, nada más.

Existe solo porque faster-whisper y piper son Python y no hay equivalente JS
decente. No sabe nada de la lista ni de la API: recibe audio y devuelve texto,
recibe texto y devuelve audio. Si ya tenés estos motores en otro lado, este
archivo no se usa.

Los modelos se cargan una sola vez y quedan en memoria: cargar piper desde disco
tarda 2 s y sintetizar tarda 0,2 s, así que levantarlo por cada frase sería
veinte veces más lento.
"""
import io
import logging
import os
import threading
import time
import wave
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import Response
import uvicorn

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("engines")

PORT = int(os.environ.get("HELADERA_SIDECAR_PORT", "8791"))
VOICES_DIR = Path(os.environ.get("HELADERA_VOICES_DIR", Path.home() / ".heladera/engines/voices"))
DEFAULT_STT = os.environ.get("HELADERA_STT_MODEL", "small")
DEFAULT_FAST = os.environ.get("HELADERA_STT_FAST_MODEL", "tiny")
DEFAULT_VOICE = os.environ.get("HELADERA_TTS_VOICE", "es_AR-daniela-high")
THREADS = int(os.environ.get("HELADERA_STT_THREADS", "8"))

app = FastAPI(title="heladera-engines")

_stt: dict = {}
_tts: dict = {}
_stt_lock = threading.Lock()
_tts_lock = threading.Lock()


def stt_model(name: str):
    with _stt_lock:
        if name not in _stt:
            from faster_whisper import WhisperModel
            t = time.time()
            _stt[name] = WhisperModel(name, device="cpu", compute_type="int8", cpu_threads=THREADS)
            log.info("whisper %s cargado en %.1fs", name, time.time() - t)
        return _stt[name]


def tts_voice(name: str):
    with _tts_lock:
        if name not in _tts:
            from piper import PiperVoice
            onnx = VOICES_DIR / f"{name}.onnx"
            if not onnx.exists():
                raise HTTPException(404, f"no tengo la voz {name} en {VOICES_DIR}")
            t = time.time()
            _tts[name] = PiperVoice.load(str(onnx))
            log.info("piper %s cargado en %.1fs", name, time.time() - t)
        return _tts[name]


@app.get("/health")
def health():
    voices = sorted(p.stem for p in VOICES_DIR.glob("*.onnx")) if VOICES_DIR.exists() else []
    return {
        "ok": True, "stt": True, "tts": bool(voices),
        "sttModels": sorted(_stt.keys()), "sttLoaded": sorted(_stt.keys()),
        "voices": voices, "loadedVoices": sorted(_tts.keys()),
    }


@app.post("/stt")
async def stt(
    file: UploadFile = File(...),
    model: str = Form(""),
    language: str = Form("es"),
    prompt: str = Form(""),
):
    audio = await file.read()
    # Para depurar el micrófono de un dispositivo: guarda lo que realmente llega.
    if os.environ.get("HELADERA_DUMP_AUDIO"):
        import datetime
        d = Path(os.environ["HELADERA_DUMP_AUDIO"])
        d.mkdir(parents=True, exist_ok=True)
        (d / f"{datetime.datetime.now():%H%M%S_%f}-{model or 'x'}.wav").write_bytes(audio)
    if len(audio) < 1000:
        return {"text": "", "model": model}
    name = model or DEFAULT_STT
    fast = name == DEFAULT_FAST
    m = stt_model(name)
    t = time.time()
    segments, _info = m.transcribe(
        io.BytesIO(audio),
        language=language or "es",
        beam_size=1 if fast else 5,
        vad_filter=True,
        vad_parameters={"min_silence_duration_ms": 400},
        condition_on_previous_text=False,
        initial_prompt=prompt or None,
    )
    text = " ".join(s.text.strip() for s in segments).strip()
    log.info("stt[%s] %.2fs -> %r", name, time.time() - t, text[:100])
    return {"text": text, "model": name, "seconds": round(time.time() - t, 2)}


@app.post("/tts")
async def tts(payload: dict):
    text = (payload.get("text") or "").strip()
    if not text:
        raise HTTPException(400, "falta text")
    voice = tts_voice(payload.get("voice") or DEFAULT_VOICE)
    buf = io.BytesIO()
    t = time.time()
    with _tts_lock:
        with wave.open(buf, "wb") as wf:
            voice.synthesize_wav(text, wf)
    log.info("tts %.2fs (%d bytes)", time.time() - t, buf.tell())
    return Response(content=buf.getvalue(), media_type="audio/wav")


@app.post("/warm")
def warm():
    for name in {DEFAULT_STT, DEFAULT_FAST}:
        threading.Thread(target=stt_model, args=(name,), daemon=True).start()
    threading.Thread(target=lambda: tts_voice(DEFAULT_VOICE), daemon=True).start()
    return {"ok": True}


@app.on_event("startup")
def on_start():
    log.info("sidecar escuchando en %s (voces en %s)", PORT, VOICES_DIR)
    warm()


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=PORT, log_level="warning")

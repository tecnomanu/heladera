"""Catálogo de voces: la misma frase de la heladera en cada voz rápida, con su tiempo hasta tener audio.

Arma muestras/catalogo.wav: "Voz uno" (dicho por Piper) + la muestra, y así. Imprime la tabla número → voz → ms.
Correr en el servidor con la CPU libre: venv/bin/python app/bancos/muestras_voces.py
"""
import io
import os
import time
import wave

import numpy as np

HERE = os.path.expanduser("~/heladera-voz")
VOICES = os.path.expanduser("~/.heladera/engines/voices")
OUT = os.path.join(HERE, "muestras")
SR = 24000
THREADS = 6
FRASE = "Listo, anoté un kilo de papas, papas fritas y una cerveza. ¿Algo más?"
NUMS = ["uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once", "doce", "trece",
        "catorce", "quince", "dieciséis", "diecisiete", "dieciocho"]


def resample(a, sr):
    if sr == SR:
        return a
    x = np.linspace(0, len(a) - 1, int(len(a) * SR / sr))
    return np.interp(x, np.arange(len(a)), a)


def piper_load(voice):
    from piper import PiperVoice
    import onnxruntime as ort
    v = PiperVoice.load(os.path.join(VOICES, voice + ".onnx"))
    so = ort.SessionOptions(); so.intra_op_num_threads = THREADS
    v.session = ort.InferenceSession(os.path.join(VOICES, voice + ".onnx"), so, providers=["CPUExecutionProvider"])

    def say(text):
        pcm = b"".join(c.audio_int16_bytes for c in v.synthesize(text))
        return np.frombuffer(pcm, np.int16).astype(np.float32) / 32768, v.config.sample_rate
    return say


def kokoro_load(voice):
    from kokoro_onnx import Kokoro
    k = Kokoro(os.path.join(HERE, "models", "kokoro-v1.0.int8.onnx"), os.path.join(HERE, "models", "voices-v1.0.bin"))
    return lambda text: k.create(text, voice=voice, speed=1.0, lang="es")


_st = {}


def supertonic_load(voice, steps=5):
    from supertonic import TTS
    if "tts" not in _st:
        _st["tts"] = TTS(model="supertonic-3", intra_op_num_threads=THREADS, inter_op_num_threads=1)
    tts = _st["tts"]
    style = tts.get_voice_style(voice) if hasattr(tts, "get_voice_style") else voice

    def say(text):
        a, _ = tts.synthesize(text, voice_style=style, total_steps=steps, lang="es")
        return np.asarray(a, np.float32).reshape(-1), tts.sample_rate if hasattr(tts, "sample_rate") else 44100
    return say


CANDIDATAS = [
    ("Piper claude (la de la prueba)", lambda: piper_load("es_MX-claude-high")),
    ("Piper daniela (argentina, la de la heladera actual)", lambda: piper_load("es_AR-daniela-high")),
    ("Kokoro dora", lambda: kokoro_load("ef_dora")),
    ("Kokoro alex", lambda: kokoro_load("em_alex")),
    ("Kokoro santa", lambda: kokoro_load("em_santa")),
    *[(f"Supertonic {v}", (lambda v=v: supertonic_load(v))) for v in ["F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"]],
]


def main():
    os.makedirs(OUT, exist_ok=True)
    label = piper_load("es_MX-claude-high")
    parts, table = [], []
    gap = np.zeros(int(SR * 0.5), np.float32)
    for n, (name, load) in enumerate(CANDIDATAS):
        try:
            say = load()
            say("Hola.")  # en caliente, como en la charla
            first = say("Listo, anoté un kilo de papas.")  # noqa: F841  calienta caches de frases largas
            t = time.time(); a, sr = say(FRASE); ms = (time.time() - t) * 1000
            t = time.time(); say("Listo, anoté leche."); short = (time.time() - t) * 1000
        except Exception as e:
            table.append((n + 1, name, f"falló: {e!r}"[:80]))
            continue
        a = resample(np.asarray(a, np.float32).reshape(-1), sr)
        a = a / max(1e-3, np.abs(a).max()) * 0.85
        la, lsr = label(f"Voz {NUMS[n]}.")
        parts += [resample(la, lsr) * 0.6, gap[: SR // 5], a, gap]
        table.append((n + 1, name, f"frase corta {short:.0f} ms · frase entera {ms:.0f} ms para {len(a) / SR:.1f} s de audio"))
        with wave.open(os.path.join(OUT, f"voz-{n + 1:02d}.wav"), "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
            w.writeframes((np.clip(a, -1, 1) * 32767).astype(np.int16).tobytes())
        print(table[-1], flush=True)
    with wave.open(os.path.join(OUT, "catalogo.wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(np.concatenate(parts), -1, 1) * 32767).astype(np.int16).tobytes())
    print("\n".join(f"{n:2d}. {name}: {info}" for n, name, info in table))


if __name__ == "__main__":
    main()

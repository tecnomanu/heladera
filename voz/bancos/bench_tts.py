"""TTS en CPU del servidor: cuánto tarda la primera frase (lo que espera la persona) y el RTF."""
import os, sys, time, wave, json
import numpy as np

HERE = os.path.expanduser("~/heladera-voz")
OUT = os.path.join(HERE, "tts-out"); os.makedirs(OUT, exist_ok=True)
VOICES = os.path.expanduser("~/.heladera/engines/voices")
LINES = ["Listo, anoté leche y pan.",
         "Te faltan tres cosas: leche, yerba y papel higiénico. ¿Algo más?",
         "Dale, armé la lista del asado con chorizo, vacío y carbón."]
THREADS = int(os.environ.get("T", "8"))
only = sys.argv[1:]

def save(name, pcm16, sr):
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm16)

def report(name, times, durs):
    rtf = sum(times) / sum(durs)
    print(json.dumps({"engine": name, "threads": THREADS, "first_line_ms": round(times[0] * 1000),
                      "per_line_ms": [round(t * 1000) for t in times], "audio_s": [round(d, 2) for d in durs],
                      "rtf": round(rtf, 3)}), flush=True)

def piper(voice):
    from piper import PiperVoice, SynthesisConfig
    import onnxruntime as ort
    t = time.time()
    v = PiperVoice.load(os.path.join(VOICES, voice + ".onnx"))
    # hilos: Piper 1.8 no expone opciones, recreo la sesión con los hilos pedidos
    so = ort.SessionOptions(); so.intra_op_num_threads = THREADS; so.inter_op_num_threads = 1
    v.session = ort.InferenceSession(os.path.join(VOICES, voice + ".onnx"), so, providers=["CPUExecutionProvider"])
    load = time.time() - t
    list(v.synthesize("Hola."))  # calentar
    times, durs = [], []
    for i, line in enumerate(LINES):
        t = time.time(); pcm = b"".join(c.audio_int16_bytes for c in v.synthesize(line)); times.append(time.time() - t)
        sr = v.config.sample_rate; durs.append(len(pcm) / 2 / sr); save(f"piper-{voice}-{i}", pcm, sr)
    report(f"piper:{voice} (load {load:.1f}s)", times, durs)

def kokoro(model, voice):
    from kokoro_onnx import Kokoro
    import onnxruntime as ort
    so = ort.SessionOptions(); so.intra_op_num_threads = THREADS; so.inter_op_num_threads = 1
    t = time.time()
    k = Kokoro.from_session(ort.InferenceSession(os.path.join(HERE, "models", model), so, providers=["CPUExecutionProvider"]),
                            os.path.join(HERE, "models", "voices-v1.0.bin"))
    load = time.time() - t
    k.create("Hola.", voice=voice, speed=1.0, lang="es")
    times, durs = [], []
    for i, line in enumerate(LINES):
        t = time.time(); a, sr = k.create(line, voice=voice, speed=1.0, lang="es"); times.append(time.time() - t)
        durs.append(len(a) / sr); save(f"kokoro-{model.split('.')[1]}-{voice}-{i}", (np.clip(a, -1, 1) * 32767).astype(np.int16).tobytes(), sr)
    report(f"kokoro:{model}:{voice} (load {load:.1f}s)", times, durs)

def supertonic(voice, steps):
    from supertonic import TTS
    t = time.time()
    tts = TTS(model="supertonic-3", intra_op_num_threads=THREADS, inter_op_num_threads=1)
    style = tts.get_voice_style(voice) if hasattr(tts, "get_voice_style") else voice
    load = time.time() - t
    tts.synthesize("Hola.", voice_style=style, total_steps=steps, lang="es")
    times, durs = [], []
    for i, line in enumerate(LINES):
        t = time.time(); a, d = tts.synthesize(line, voice_style=style, total_steps=steps, lang="es"); times.append(time.time() - t)
        a = np.asarray(a).reshape(-1); sr = tts.sample_rate if hasattr(tts, "sample_rate") else 44100
        durs.append(len(a) / sr); save(f"supertonic-{voice}-s{steps}-{i}", (np.clip(a, -1, 1) * 32767).astype(np.int16).tobytes(), sr)
    report(f"supertonic-3:{voice}:steps{steps} (load {load:.1f}s)", times, durs)

JOBS = {
    "piper-daniela": lambda: piper("es_AR-daniela-high"),
    "piper-claude": lambda: piper("es_MX-claude-high"),
    "piper-ald": lambda: piper("es_MX-ald-medium"),
    "kokoro-int8-dora": lambda: kokoro("kokoro-v1.0.int8.onnx", "ef_dora"),
    "kokoro-fp32-dora": lambda: kokoro("kokoro-v1.0.onnx", "ef_dora"),
    "supertonic-F1-8": lambda: supertonic("F1", 8),
    "supertonic-F1-5": lambda: supertonic("F1", 5),
    "supertonic-M1-5": lambda: supertonic("M1", 5),
}
for name, job in JOBS.items():
    if only and name not in only:
        continue
    try:
        job()
    except Exception as e:
        print(json.dumps({"engine": name, "error": repr(e)[:300]}), flush=True)

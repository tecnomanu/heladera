"""STT: whisper chico en CPU (texto en vivo) vs whisper.cpp large-v3-turbo en GPU (texto final)."""
import glob, io, json, os, sys, time, urllib.request, wave, uuid
import numpy as np

HERE = os.path.expanduser("~/heladera-voz")
FILES = sorted(glob.glob(os.path.join(HERE, "u*.wav")))
HINT = "Lista de compras."

def pcm(path):
    with wave.open(path) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768

def gpu(path):
    data = open(path, "rb").read()
    b = uuid.uuid4().hex
    body = (f"--{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\nContent-Type: audio/wav\r\n\r\n").encode() + data + \
           f"\r\n--{b}\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n--{b}\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\nes\r\n--{b}--\r\n".encode()
    req = urllib.request.Request("http://127.0.0.1:8792/inference", body, {"Content-Type": f"multipart/form-data; boundary={b}"})
    return json.load(urllib.request.urlopen(req, timeout=60))["text"].strip()

def run(name, fn):
    fn(FILES[0])  # calentar
    tot = 0
    for f in FILES:
        a = pcm(f); t = time.time(); txt = fn(f); dt = time.time() - t; tot += dt
        print(f"{name:22s} {os.path.basename(f)} {len(a)/16000:4.1f}s audio -> {dt*1000:5.0f} ms  {txt}", flush=True)
    print(f"{name:22s} TOTAL {tot*1000:.0f} ms", flush=True)

which = sys.argv[1:] or ["gpu", "tiny", "base", "small"]
if "gpu" in which:
    run("whispercpp-gpu-turbo", gpu)
from faster_whisper import WhisperModel
for size in [s for s in which if s != "gpu"]:
    thr = int(os.environ.get("T", "4"))
    m = WhisperModel(size, device="cpu", compute_type="int8", cpu_threads=thr)
    run(f"fw-{size}-int8-t{thr}", lambda f: "".join(s.text for s in m.transcribe(pcm(f), language="es", beam_size=1,
        vad_filter=False, without_timestamps=True, condition_on_previous_text=False, initial_prompt=HINT)[0]).strip())

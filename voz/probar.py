"""Charla de prueba sin micrófono: manda WAVs a 16 kHz en tiempo real, como si alguien hablara, y mide.

  python probar.py u1.wav u2.wav …      (una charla, un turno por archivo)
  python probar.py u4.wav !u3.wav        (el ! habla encima: arranca 0,8 s después de que la heladera empezó a hablar)

Entre turno y turno espera a que la heladera termine de hablar, más un respiro. Guarda lo que dijo en salida.wav.
"""
import asyncio
import json
import os
import sys
import time
import wave

import numpy as np
import websockets

URL = os.environ.get("VOZ_URL", "ws://127.0.0.1:8796/talk")  # con token: ws://…/talk?token=…
CHUNK = 512  # 32 ms


def pcm(path):
    with wave.open(path) as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1
        return w.readframes(w.getnframes())


async def main(files):
    out, sr = [], 22050
    async with websockets.connect(URL, max_size=8 << 20) as ws:
        state = {"speaking_until": 0.0, "done": asyncio.Event(), "turn_t0": 0.0, "ended": False}
        events = []

        async def reader():
            nonlocal sr
            try:
                async for m in ws:
                    now = time.monotonic()
                    if isinstance(m, bytes):
                        out.append(m)
                        state["speaking_until"] = max(now, state["speaking_until"]) + len(m) / 2 / sr
                        continue
                    f = json.loads(m)
                    rel = round((now - state["turn_t0"]) * 1000)
                    if f["type"] == "ready":
                        sr = f["sr"]
                    elif f["type"] == "interrupted":
                        print(f"   +{rel:5d}ms INTERRUMPIDA (tirar el audio que queda)", flush=True)
                    elif f["type"] in ("partial", "heard", "saying", "tool"):
                        if f["type"] == "saying":
                            state["said_at"] = now
                        txt = f.get("text") or f"{f.get('name')} {json.dumps(f.get('args'), ensure_ascii=False)}"
                        events.append((rel, f["type"], txt))
                        print(f"   +{rel:5d}ms {f['type']:8s} {txt}", flush=True)
                    elif f["type"] == "timing":
                        print(f"   timing (ms desde que terminaste de hablar): "
                              f"{ {k: v for k, v in f.items() if k != 'type'} }", flush=True)
                        state["done"].set()
                    elif f["type"] == "end":
                        state["ended"] = True
                        state["done"].set()
            except websockets.ConnectionClosed:
                state["ended"] = True
                state["done"].set()

        r = asyncio.create_task(reader())
        await asyncio.sleep(0.5)
        silence = bytes(CHUNK * 2)
        for k, path in enumerate(files):
            over = path.startswith("!")
            path = path.lstrip("!")
            data = pcm(path)
            print(f"\n>> {path} ({len(data) / 32000:.1f}s)", flush=True)
            state["done"].clear()
            state["turn_t0"] = time.monotonic()
            for i in range(0, len(data), CHUNK * 2):
                await ws.send(data[i:i + CHUNK * 2])
                await asyncio.sleep(CHUNK / 16000)
            print(f"   +{round((time.monotonic() - state['turn_t0']) * 1000):5d}ms (terminó el audio)", flush=True)
            # silencio de fondo mientras la heladera piensa y habla
            t_end = time.monotonic() + 15
            barge = k + 1 < len(files) and files[k + 1].startswith("!")
            while not state["done"].is_set() and time.monotonic() < t_end:
                if barge and state["speaking_until"] > time.monotonic() and state.get("said_at", 0) > state["turn_t0"] \
                        and time.monotonic() - state["said_at"] > 0.8:
                    break
                await ws.send(silence)
                await asyncio.sleep(CHUNK / 16000)
            while not barge and time.monotonic() < state["speaking_until"] + 0.6:
                await ws.send(silence)
                await asyncio.sleep(CHUNK / 16000)
            if state["ended"]:
                break
        r.cancel()
    with wave.open("salida.wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(b"".join(out))
    print(f"\nlo que dijo la heladera: salida.wav ({sum(map(len, out)) / 2 / sr:.1f}s)")


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1:]))

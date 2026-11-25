import json, sys, time, urllib.request
import os
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from tools import TOOLS, system_prompt

MODEL = sys.argv[1] if len(sys.argv) > 1 else "qwen3:4b-instruct"


def call(msgs, label, opts=None):
    body = {"model": MODEL, "stream": False, "think": False, "keep_alive": "2h", "tools": TOOLS,
            "options": {"temperature": 0.2, **(opts or {})},
            "messages": [{"role": "system", "content": system_prompt()}, *msgs]}
    t = time.time()
    r = json.load(urllib.request.urlopen(urllib.request.Request(
        "http://127.0.0.1:11434/api/chat", json.dumps(body).encode(), {"Content-Type": "application/json"})))
    ms = lambda k: round(r.get(k, 0) / 1e6)
    m = r["message"]
    out = [c["function"]["name"] for c in m.get("tool_calls") or []] or m.get("content", "")[:50]
    print(f"{label:22s} total {(time.time() - t) * 1000:5.0f}ms | prompt {r.get('prompt_eval_count')} tok {ms('prompt_eval_duration')}ms"
          f" | gen {r.get('eval_count')} tok {ms('eval_duration')}ms ({r.get('eval_count', 0) / max(1e-9, r.get('eval_duration', 1) / 1e9):.0f} tok/s)"
          f" | load {ms('load_duration')}ms | {out}", flush=True)


u = lambda t: {"role": "user", "content": t}
a = lambda t: {"role": "assistant", "content": t}
call([u("borrá la zanahoria que me equivoqué")], "sin historia")
call([u("borrá la zanahoria que me equivoqué")], "sin historia (2)")
call([u("anotame dos litros de leche, yerba y papel higiénico")], "add 3 sin historia")
h = [u("anotame leche y pan"), a("Listo, anoté leche y pan. ¿Algo más?"), u("ya compré la yerba"), a("Taché yerba. ¿Algo más?")]
call(h + [u("borrá la zanahoria que me equivoqué")], "con historia texto")
call(h + [u("borrá la zanahoria que me equivoqué")], "con historia texto (2)")

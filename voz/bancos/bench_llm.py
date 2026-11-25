"""Modelos chicos con tools de verdad (ollama /api/chat): aciertos y latencia en pedidos de la heladera."""
import json, os, sys, time, unicodedata, urllib.request

URL = os.environ.get("OLLAMA", "http://127.0.0.1:11434")
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from tools import TOOLS, pedido, system_prompt  # noqa: E402

LISTA = ["leche", "pan", "zanahoria", "detergente", "yerba", "banana"]

def n(s):
    s = unicodedata.normalize("NFD", str(s).lower())
    return "".join(c for c in s if unicodedata.category(c) != "Mn").strip()

def names(args, key="names"):
    v = args.get(key) or []
    if isinstance(v, str):
        v = [v]
    return sorted(n(x if isinstance(x, str) else x.get("name", "")) for x in v)

def items(args):
    return sorted(n(i.get("name", "")) for i in (args.get("items") or []) if isinstance(i, dict))

def has(got, wanted):  # cada nombre esperado aparece (singular/plural flojo)
    return len(got) == len(wanted) and all(any(w[:4] in g for g in got) for w in wanted)

CASES = [
    ("anotame dos litros de leche y un kilo de pan", lambda c: c == [("add_items", ["leche", "pan"])]),
    ("che, sumá yerba, fideos y papel higiénico", lambda c: c == [("add_items", ["fideo", "papel higienico", "yerba"])]),
    ("ya compré la yerba", lambda c: c == [("check_off_items", ["yerba"])]),
    ("qué falta comprar", lambda c: c == [("read_list", None)]),
    ("armame una lista para el asado con chorizo, vacío y carbón", lambda c: c == [("add_items@asado", ["carbon", "chorizo", "vacio"])]),
    ("borrá la zanahoria que me equivoqué", lambda c: c == [("remove_items", ["zanahoria"])]),
    ("necesito tomates y también una docena de huevos para mañana", lambda c: c == [("add_items", ["huevo", "tomate"])]),
    ("nada más, gracias", lambda c: c == [("end_conversation", None)]),
    ("borrá la lista", lambda c: c == [("clear_list", "all")]),
    ("sacá el detergente de la lista, no lo quiero", lambda c: c == [("remove_items", ["detergente"])]),
    ("listo el pan", lambda c: c == [("check_off_items", ["pan"])]),
    ("ordename todo por góndola", lambda c: c == [("group_by_aisle", None)]),
    ("mandame la lista por telegram", lambda c: c == [("send_list", "telegram")]),
    ("y en el noticiero de hoy hablaron de la inflación de octubre", lambda c: c == []),
    ("sumale dos bananas más", lambda c: c == [("add_items", ["banana"])]),
    ("ya tengo el pan y las bananas", lambda c: c == [("check_off_items", ["banana", "pan"])]),
    ("hola heladera, ¿cómo andás?", lambda c: c == []),
    ("borrá los que ya compré", lambda c: c == [("clear_list", "done")]),
    ("anotá medio kilo de queso cremoso y una lavandina", lambda c: c == [("add_items", ["lavandina", "queso cremoso"])]),
    ("eh no, la leche no, sacala", lambda c: c == [("remove_items", ["leche"])]),
]

def shape(calls):
    out = []
    for name, a in calls:
        if name == "add_items":
            lst = n(a.get("list") or "")
            out.append((name + (f"@{lst}" if lst else ""), items(a)))
        elif name in ("check_off_items", "remove_items"):
            out.append((name, names(a)))
        elif name == "clear_list":
            out.append((name, "done" if a.get("only_done") else "all"))
        elif name == "send_list":
            out.append((name, n(a.get("channel") or "")))
        else:
            out.append((name, None))
    return out

def fuzzy(expected_fn, got):
    # tolera singular/plural y artículos en los nombres
    norm = [(k, sorted({x.rstrip("s") if x.endswith(("es", "s")) and len(x) > 4 else x for x in v}) if isinstance(v, list) else v) for k, v in got]
    norm = [(k, [x[:-1] if x.endswith("e") and x[:-1] + "es" == x + "s" else x for x in v] if isinstance(v, list) else v) for k, v in norm]
    return expected_fn(got) or expected_fn(norm) or expected_fn([(k, [x.rstrip("s") for x in v] if isinstance(v, list) else v) for k, v in got])

def chat(model, text, think=False):
    body = {"model": model, "stream": True, "keep_alive": "30m", "tools": TOOLS,
            "options": {"temperature": 0},
            "messages": [{"role": "system", "content": system_prompt()}, {"role": "user", "content": pedido(text, LISTA, [])}]}
    if think is not None:
        body["think"] = think
    t0 = time.time(); first = None; calls, content = [], ""
    req = urllib.request.Request(URL + "/api/chat", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        for line in r:
            m = json.loads(line)
            msg = m.get("message") or {}
            if (msg.get("content") or msg.get("tool_calls")) and first is None:
                first = time.time() - t0
            content += msg.get("content") or ""
            for tc in msg.get("tool_calls") or []:
                f = tc["function"]; a = f.get("arguments") or {}
                if isinstance(a, str):
                    try: a = json.loads(a)
                    except Exception: a = {}
                calls.append((f["name"], a))
            if m.get("done"):
                break
    return calls, content.strip(), first, time.time() - t0

for model in sys.argv[1:]:
    chat(model, "hola")  # cargar
    ok, lat, firsts = 0, [], []
    for text, want in CASES:
        calls, content, first, total = chat(model, text)
        good = fuzzy(want, shape(calls))
        ok += good; lat.append(total); firsts.append(first or total)
        print(f"  {'OK ' if good else 'BAD'} {total*1000:5.0f}ms  {text[:45]:45s} -> {shape(calls)} {content[:60]!r}", flush=True)
    lat.sort(); firsts.sort()
    print(json.dumps({"model": model, "ok": f"{ok}/{len(CASES)}", "p50_ms": round(lat[len(lat)//2]*1000),
                      "p90_ms": round(lat[int(len(lat)*0.9)]*1000), "first_p50_ms": round(firsts[len(firsts)//2]*1000)}), flush=True)

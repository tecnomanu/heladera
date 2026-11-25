"""Las herramientas de la heladera para un modelo con tool calling, y su prompt.

Pocas herramientas y con listas adentro: un modelo chico elige bien entre ocho, no entre once.
Borrar y tachar van separadas y con descripciones que se excluyen: es el error más caro.
"""

CATEGORIES = ["produce", "butcher", "bakery", "dairy", "pantry", "drinks", "cleaning", "pharmacy", "pets",
              "hardware", "stationery", "other"]
UNITS = ["kg", "g", "l", "ml", "u", "pack", "dozen"]
# Lo mismo que src/labels.js (CAT_SCOPE.es): sin esto el modelo pone el vacío en ferretería y la leche en bebidas.
SCOPE = {"produce": "frutas y verduras frescas (papa, cebolla, tomate, banana)", "butcher": "carne, pollo, pescado, fiambres", "bakery": "pan, facturas, galletas",
         "dairy": "lácteos, huevos, congelados", "pantry": "secos, conservas, harina, arroz, fideos, aceite, yerba, café, snacks y papas fritas de paquete",
         "drinks": "agua, gaseosa, jugo, cerveza, vino", "cleaning": "limpieza, papel higiénico, bolsas",
         "pharmacy": "higiene, cosmética, remedios", "pets": "mascotas", "hardware": "ferretería, carbón, pilas",
         "stationery": "papelería", "other": "si ninguna encaja"}

NAMES = {"type": "array", "items": {"type": "string"}, "description": "nombres de productos, en singular"}

TOOLS = [
    {"type": "function", "function": {
        "name": "add_items",
        "description": "Agrega productos a la lista de compras (anotá, sumá, poné, necesito, falta, hay que comprar).",
        "parameters": {"type": "object", "required": ["items"], "properties": {
            "items": {"type": "array", "items": {"type": "object", "required": ["name", "qty", "unit", "cat"],
                "properties": {
                    "name": {"type": "string", "description": "el producto entero como lo dijo, en minúscula, sin cantidad ni medida: 'papas fritas', 'queso cremoso'; de '1 kilo de papas' va 'papa' (nunca 'kilo de papa' ni 'papa 1kg')"},
                    "qty": {"type": "number", "description": "cuántas de esa medida: 1 si no la dijo, 0.5 si dijo medio, una docena = 1"},
                    "unit": {"type": "string", "enum": UNITS,
                             "description": "la que dijo; si no dijo: kg lo que va por peso, l líquido suelto, dozen huevos, pack paquetes, u lo que se cuenta"},
                    "cat": {"type": "string", "enum": CATEGORIES,
                            "description": "góndola: " + "; ".join(f"{k} = {v}" for k, v in SCOPE.items())},
                    "note": {"type": "string", "description": "solo una marca o variedad aparte ('descremada', 'sin sal'). Nunca un pedazo del nombre: 'papas fritas' es el nombre entero, no papa + fritas"}}}},
            "list": {"type": "string", "description": "sublista: si dice 'lista para el asado', 'lo del cumple', 'para el desayuno', va el nombre corto (asado, cumple, desayuno). Si son cosas sueltas, omitir"}}}}},
    {"type": "function", "function": {
        "name": "check_off_items",
        "description": "TACHAR: ya lo compró o ya lo tiene (ya compré, ya tengo, listo el pan, tachá).",
        "parameters": {"type": "object", "required": ["names"], "properties": {"names": NAMES}}}},
    {"type": "function", "function": {
        "name": "remove_items",
        "description": "BORRAR un renglón que no quiere más o anotó mal (borrá, sacá de la lista, no lo quiero, me equivoqué).",
        "parameters": {"type": "object", "required": ["names"], "properties": {"names": NAMES}}}},
    {"type": "function", "function": {
        "name": "read_list",
        "description": "Dice en voz alta lo que falta comprar (qué falta, leeme la lista).",
        "parameters": {"type": "object", "properties": {}}}},
    {"type": "function", "function": {
        "name": "clear_list",
        "description": "Vacía la lista entera (borrá la lista, empecemos de cero), o con only_done=true borra solo lo ya comprado (borrá lo que ya compré, limpiá los tachados).",
        "parameters": {"type": "object", "properties": {"only_done": {"type": "boolean"}}}}},
    {"type": "function", "function": {
        "name": "group_by_aisle",
        "description": "Separa la lista en sublistas por góndola; undo=true la vuelve a juntar.",
        "parameters": {"type": "object", "properties": {"undo": {"type": "boolean"}}}}},
    {"type": "function", "function": {
        "name": "send_list",
        "description": "Manda la lista por un canal.",
        "parameters": {"type": "object", "required": ["channel"], "properties": {
            "channel": {"type": "string", "enum": ["telegram", "whatsapp"]}}}}},
    {"type": "function", "function": {
        "name": "end_conversation",
        "description": "La persona dijo que ya está, que no necesita nada más, o se despidió.",
        "parameters": {"type": "object", "properties": {}}}},
]


def system_prompt(lang="es-AR"):
    """Fijo de punta a punta: así ollama reusa lo ya procesado (prompt + herramientas, ~1000 tokens) en cada turno.
    Con la lista acá adentro, cada turno lo volvía a leer entero: 2,8 s en vez de 20 ms."""
    return """Sos la heladera de la casa: anotás la lista de compras por voz. Hablás en español rioplatense, con voseo.

- Si la persona pide algo sobre la lista, llamá a las herramientas y no escribas texto: la confirmación la digo yo.
  Nunca digas que anotaste, tachaste o borraste algo sin llamar a la herramienta.
- Una herramienta por tipo de acción, con todos los productos adentro.
- Cada producto con su cantidad y su medida, aunque no las diga: 1 kg de papa, 1 l de leche, 1 u de lo que se cuenta.
  Si dos o más palabras forman el producto (papas fritas, pan rallado, queso cremoso) van juntas en el nombre.
- Tachar (ya lo compró) y borrar (no lo quiere) son distintos. Ante la duda, no borres.
  "listo el pan" = ya compró el pan → check_off_items. "borrá lo que ya compré" → clear_list con only_done.
- La tablet escucha la tele y charlas de la cocina. Si no es un pedido para vos (noticias, charla entre otros), contestá exactamente [[skip]] y nada más.
- Si te hablan a vos sin pedir nada (un saludo), contestá en una frase corta y natural ("Bien, ¿qué anotamos?"), sin listas ni emojis.
- Cada pedido trae al final, entre corchetes, cómo está la lista en ese momento. Es para vos: no la repitas."""


def pedido(text, items, lists):
    """Lo que dijo, con el estado de la lista al lado (va en el mensaje, no en el prompt: ver system_prompt)."""
    have = ", ".join(items) if items else "vacía"
    subs = f" · Sublistas: {', '.join(lists)}" if lists else ""
    return f"{text}\n[Lista: {have}{subs}]"


# --- Limpieza de lo que manda el modelo -------------------------------------------------------------------------
# El modelo a veces deja la medida adentro del nombre ("kilo de papa", "papa 1kg"). Se saca de ahí y, si el modelo
# no la puso en su lugar, se usa la del nombre: el renglón siempre queda "papa · 1 kg".
import re as _re
import unicodedata as _ud

_NUM = {"un": 1, "una": 1, "uno": 1, "unos": 1, "unas": 1, "dos": 2, "tres": 3, "cuatro": 4, "cinco": 5, "seis": 6,
        "siete": 7, "ocho": 8, "nueve": 9, "diez": 10, "doce": 12, "medio": 0.5, "media": 0.5, "cuarto": 0.25,
        "kilo y medio": 1.5}
_UNIT = [(r"kilos?|kg|k", "kg"), (r"gramos?|grs?|g", "g"), (r"litros?|lts?|l", "l"), (r"mililitros?|ml|cc", "ml"),
         (r"docenas?", "dozen"), (r"paquetes?|packs?|cajas?|bolsas?|sachets?|rollos?|atados?|planchas?|maples?", "pack"),
         (r"unidades?|u|botellas?|latas?|frascos?|potes?|cajones?", "u")]
_UW = "|".join(f"(?:{w})" for w, _ in _UNIT)
_QW = r"\d+(?:[.,]\d+)?|" + "|".join(sorted(_NUM, key=len, reverse=True))
_LEAD = _re.compile(rf"^(?:(?P<q>{_QW})\s*)?(?:(?:un\s+)?(?P<u>{_UW})\b\.?\s*(?:y\s+medio\s+)?)(?:de\s+)?", _re.I)
_LEADQ = _re.compile(rf"^(?P<q>{_QW})\s+(?:de\s+)?(?=\w)", _re.I)
_TAIL = _re.compile(rf"\s*[,(-]?\s*(?P<q>\d+(?:[.,]\d+)?)\s*(?P<u>{_UW})?\.?\)?$", _re.I)
_ART = _re.compile(r"^(?:el|la|los|las|unos|unas|un|una)\s+", _re.I)


def _unit(word):
    for w, u in _UNIT:
        if _re.fullmatch(w, word or "", _re.I):
            return u
    return None


def _qty(word):
    if word is None:
        return None
    w = word.lower().replace(",", ".")
    try:
        return float(w) if "." in w else int(w)
    except ValueError:
        return _NUM.get(w)


def limpiar(item):
    """{name, qty, unit, ...} del modelo → el mismo dict con el nombre pelado y qty/unit siempre puestos."""
    i = dict(item)
    name = _ud.normalize("NFC", str(i.get("name") or "")).strip().lower()
    q = u = None
    m = _LEAD.match(name)
    if m and m.group("u"):
        q, u = _qty(m.group("q")), _unit(m.group("u"))
        if q is None and _re.match(r"^\s*medio", name):
            q = 0.5
        if "y medio" in m.group(0):
            q = (q or 1) + 0.5
        name = name[m.end():]
    else:
        m = _LEADQ.match(name)
        if m and _qty(m.group("q")) is not None:
            q, name = _qty(m.group("q")), name[m.end():]
    m = _TAIL.search(name)
    if m and (m.group("u") or q is None):
        q = q if q is not None else _qty(m.group("q"))
        u = u or _unit(m.group("u"))
        name = name[:m.start()]
    name = _ART.sub("", name).strip(" .,-")
    i["name"] = name or str(item.get("name") or "").strip()
    qty = i.get("qty")
    if isinstance(qty, str):
        qty = _qty(qty.strip())
    if not isinstance(qty, (int, float)) or qty <= 0:
        qty = None
    unit = i.get("unit") if i.get("unit") in UNITS else _unit(str(i.get("unit") or "").strip().rstrip("."))
    # Lo que estaba en el nombre lo dijo la persona; el modelo solo pone 1 u cuando no sabe.
    if q is not None and (qty in (None, 1) or unit in (None, "u")):
        qty = q
    if u and unit in (None, "u"):
        unit = u
    if unit == "dozen" and qty in (12, 6):  # "una docena de huevos" no son 12 docenas
        qty = qty / 12
    i["qty"] = qty if qty is not None else 1
    # Sin medida: lo que se pesa (verdulería, carnicería) va en kg; lo demás se cuenta.
    i["unit"] = unit or ("kg" if i.get("cat") in ("produce", "butcher") else "u")
    if isinstance(i["qty"], float) and i["qty"].is_integer():
        i["qty"] = int(i["qty"])
    return i


def juntar_nota(item, heard):
    """Si la "nota" es en realidad el final del nombre que dijo la persona ("papa" + "fritas" de "papas fritas"),
    se vuelve a pegar: es un solo producto, no una papa con un detalle."""
    note = str(item.get("note") or "").strip()
    if not note or not heard:
        return item
    sin_s = lambda w: w[:-2] if w.endswith("es") and len(w) > 4 else (w[:-1] if w.endswith("s") and len(w) > 3 else w)
    palabras = lambda t: [sin_s(w) for w in _re.findall(r"\w+", _ud.normalize("NFKD", t.lower()).encode("ascii", "ignore").decode())]
    dicho, junto = palabras(heard), palabras(f"{item.get('name', '')} {note}")
    if junto and any(dicho[k:k + len(junto)] == junto for k in range(len(dicho))):
        item = dict(item, note=None)
        # el nombre como lo dijo: buscamos las mismas palabras en lo dicho, con sus plurales
        crudo = _re.findall(r"\w+", heard)
        norm = [sin_s(_ud.normalize("NFKD", w.lower()).encode("ascii", "ignore").decode()) for w in crudo]
        for k in range(len(norm)):
            if norm[k:k + len(junto)] == junto:
                item["name"] = " ".join(crudo[k:k + len(junto)]).lower()
                break
    return item

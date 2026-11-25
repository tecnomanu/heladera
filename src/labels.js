/**
 * El vocabulario del núcleo es en inglés; la pantalla lo traduce.
 *
 * El modelo devuelve siempre la misma palabra ("butcher") hable quien hable el
 * idioma que hable, y recién al mostrarla se convierte en "carnicería" o
 * "butcher's". Si guardáramos lo que dijo la persona, la misma góndola quedaría
 * escrita de cinco maneras y agrupar dejaría de funcionar.
 */

export const CATEGORIES = [
  'produce', 'butcher', 'bakery', 'dairy', 'pantry', 'drinks',
  'cleaning', 'pharmacy', 'pets', 'hardware', 'stationery', 'other',
]

export const UNITS = ['kg', 'g', 'l', 'ml', 'u', 'pack', 'dozen']

const CAT_ES = {
  produce: 'verdulería', butcher: 'carnicería', bakery: 'panadería',
  dairy: 'lácteos', pantry: 'almacén', drinks: 'bebidas',
  cleaning: 'limpieza', pharmacy: 'farmacia', pets: 'mascotas',
  hardware: 'ferretería', stationery: 'librería', other: 'otros',
}

const CAT_EN = {
  produce: 'produce', butcher: 'butcher', bakery: 'bakery', dairy: 'dairy',
  pantry: 'pantry', drinks: 'drinks', cleaning: 'cleaning',
  pharmacy: 'pharmacy', pets: 'pets', hardware: 'hardware',
  stationery: 'stationery', other: 'other',
}

const CAT_PT = {
  produce: 'hortifrúti', butcher: 'açougue', bakery: 'padaria', dairy: 'laticínios',
  pantry: 'mercearia', drinks: 'bebidas', cleaning: 'limpeza', pharmacy: 'farmácia',
  pets: 'pets', hardware: 'ferragens', stationery: 'papelaria', other: 'outros',
}

const BY_LANG = { es: CAT_ES, en: CAT_EN, pt: CAT_PT }

/** Cómo se escribe la unidad en una lista de compras. */
const UNI_ES = { kg: 'kg', g: 'gr', l: 'lts', ml: 'ml', u: '', pack: 'paq', dozen: 'doc' }
const UNI_EN = { kg: 'kg', g: 'g', l: 'l', ml: 'ml', u: '', pack: 'pack', dozen: 'dz' }
const UNI_PT = { kg: 'kg', g: 'g', l: 'lt', ml: 'ml', u: '', pack: 'pct', dozen: 'dz' }
const UNITS_BY_LANG = { es: UNI_ES, en: UNI_EN, pt: UNI_PT }

const CAT_SCOPE = {
  es: {
    produce: 'frutas, verduras y hortalizas', butcher: 'carne, pollo, pescado y fiambres',
    bakery: 'pan, facturas y galletas', dairy: 'lácteos, huevos y congelados',
    pantry: 'secos, conservas, harina, arroz, fideos y aceite', drinks: 'agua, gaseosas, jugos, cerveza y vino',
    cleaning: 'limpieza y bolsas', pharmacy: 'higiene, cosmética y remedios',
    pets: 'mascotas', hardware: 'ferretería', stationery: 'papelería', other: 'sin categoría mejor',
  },
  en: {
    produce: 'fruit and vegetables', butcher: 'meat, chicken, fish and cold cuts',
    bakery: 'bread, pastries and biscuits', dairy: 'dairy, eggs and frozen food',
    pantry: 'dry goods, tins, flour, rice, pasta and oil', drinks: 'water, soda, juice, beer and wine',
    cleaning: 'cleaning and bags', pharmacy: 'toiletries, cosmetics and medicine',
    pets: 'pet supplies', hardware: 'hardware', stationery: 'stationery', other: 'use only when no category fits',
  },
  pt: {
    produce: 'frutas, verduras e legumes', butcher: 'carnes, frango, peixe e frios',
    bakery: 'pães, doces e biscoitos', dairy: 'laticínios, ovos e congelados',
    pantry: 'secos, enlatados, farinha, arroz, massas e óleo', drinks: 'água, refrigerantes, sucos, cerveja e vinho',
    cleaning: 'limpeza e sacos', pharmacy: 'higiene, cosméticos e remédios',
    pets: 'animais de estimação', hardware: 'ferragens', stationery: 'papelaria', other: 'só quando nenhuma categoria servir',
  },
}

const short = lang => String(lang || 'es').slice(0, 2).toLowerCase()

export function categoryLabel(cat, lang = 'es') {
  if (!cat) return ''
  const tabla = BY_LANG[short(lang)] || CAT_EN
  return tabla[cat] || cat
}

export function unitLabel(u, lang = 'es') {
  if (!u) return ''
  const tabla = UNITS_BY_LANG[short(lang)] || UNI_EN
  return tabla[u] !== undefined ? tabla[u] : u
}

/** Las traducciones que la pantalla necesita para armarse sola. */
export function dictionary(lang = 'es') {
  return {
    lang: short(lang),
    categories: BY_LANG[short(lang)] || CAT_EN,
    units: UNITS_BY_LANG[short(lang)] || UNI_EN,
  }
}

/** Contract used by the LLM and API clients to describe shopping fields. */
export function shoppingToolSpec(lang = 'es') {
  const language = short(lang)
  const labels = BY_LANG[language] || CAT_EN
  const scopes = CAT_SCOPE[language] || CAT_SCOPE.en
  const units = UNITS_BY_LANG[language] || UNI_EN
  return {
    language,
    add: {
      fields: ['name', 'quantity', 'unit', 'category', 'note'],
      quantity: 'positive number; use 1 when omitted',
      unit: 'required; use u for a countable item when no measure was said',
    },
    categories: CATEGORIES.map(id => ({ id, label: labels[id], scope: scopes[id] })),
    units: UNITS.map(id => ({ id, label: units[id] || id })),
  }
}

// Índice al revés: si el modelo contesta "panadería" en vez de "bakery", eso es
// la palabra correcta dicha en otro idioma, no basura. Tirarla sería perder una
// categoría buena por un detalle de forma.
const pelar = t => String(t || '').toLowerCase().normalize('NFD')
  .replace(/[\u0300-\u036f]/g, '').replace(/[^a-z]/g, '')

const INVERSO = new Map()
for (const tabla of [CAT_EN, CAT_ES, CAT_PT]) {
  for (const [canon, palabra] of Object.entries(tabla)) {
    if (!INVERSO.has(pelar(palabra))) INVERSO.set(pelar(palabra), canon)
  }
}
for (const c of CATEGORIES) INVERSO.set(pelar(c), c)
// Las que dice el modelo seguido y no están en ninguna tabla de pantalla.
for (const [alias, canon] of Object.entries({
  fruteria: 'produce', verduras: 'produce', frutas: 'produce', vegetables: 'produce',
  pescaderia: 'butcher', fiambreria: 'butcher', meat: 'butcher',
  lacteos: 'dairy', heladera: 'dairy', fridge: 'dairy', congelados: 'dairy',
  despensa: 'pantry', almacen: 'pantry', groceries: 'pantry', comida: 'pantry',
  bebida: 'drinks', perfumeria: 'pharmacy', higiene: 'pharmacy',
  mascota: 'pets', educacion: 'stationery', papeleria: 'stationery',
  varios: 'other', general: 'other',
})) INVERSO.set(pelar(alias), canon)

/** La categoría canónica de lo que haya dicho el modelo, o null. */
export function canonicalCategory(texto) {
  return INVERSO.get(pelar(texto)) || null
}

const UNITS_INVERSO = new Map()
for (const tabla of Object.values(UNITS_BY_LANG)) {
  for (const [canon, palabra] of Object.entries(tabla)) {
    if (palabra) UNITS_INVERSO.set(pelar(palabra), canon)
  }
}
for (const unidad of UNITS) UNITS_INVERSO.set(pelar(unidad), unidad)
for (const [alias, canon] of Object.entries({
  kilo: 'kg', kilos: 'kg', kilogramo: 'kg', kilogramos: 'kg',
  gramo: 'g', gramos: 'g', litro: 'l', litros: 'l', mililitro: 'ml', mililitros: 'ml',
  unidad: 'u', unidades: 'u', paquete: 'pack', paquetes: 'pack',
  docena: 'dozen', docenas: 'dozen', dozen: 'dozen',
})) UNITS_INVERSO.set(pelar(alias), canon)

/** Normalizes a localized unit label into a stable storage ID. */
export function canonicalUnit(texto) {
  return UNITS_INVERSO.get(pelar(texto)) || null
}

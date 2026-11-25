package ar.tecnomanu.heladera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * La pantalla de la heladera.
 *
 * Muestra siempre la lista pendiente y deja pedir cosas de tres maneras:
 * el botón Hablar (contesta en voz alta), el botón Escucharme (contesta solo por
 * pantalla, para no despertar a nadie de noche) y "manos libres", que escucha
 * la palabra de activación sin tocar nada.
 */
public class MainActivity extends Activity implements Recorder.Listener {

    private static final String PREFS = "heladera";
    private static final String K_SERVER = "server";
    private static final String K_TOKEN = "token";
    private static final String K_WAKE = "wake";
    private static final String K_MODO = "por_listas";
    private static final String K_SOLAPA = "solapa";
    private static final String K_HECHAS = "ver_hechas";
    // Sin servidor de fábrica: la primera vez se empareja con el QR de /pair (o a mano en Ajustes).
    private static final String DEFAULT_SERVER = "";
    private static final int POLL_MS = 3000;
    private static final int REQ_MIC = 7001;

    private TextView title, count, status, wakeToggle, btnTalk, btnQuiet, btnSend, btnMenu;
    private TextView segTodo, segListas, pickToggle, allToggle;
    // Sólo existen en la tablet (layout-w600dp): todo lo que los toca mira primero si son null.
    private TextView segGondola, enCarrito, oido, hecho, pista;
    private View anillo1, anillo2, giro, cara;
    private ImageView caraIcono;
    private Ondas caraOndas;
    private TextView caraTexto;
    private boolean porGondola = true, compacta = false;
    private final java.util.ArrayList<android.animation.Animator> latidos = new java.util.ArrayList<>();
    private final java.util.HashSet<Integer> nuevos = new java.util.HashSet<>();
    private static final String K_GONDOLA = "por_gondola";
    private static final String K_SUPER = "en_super";
    private boolean enSuper = false;
    private TextView superToggle;
    private View panelCharla, barraSuper;
    private LinearLayout tabs;
    private View tabsScroll;
    private boolean porListas = false, verHechas = false, chicosArriba = false;
    private boolean conversando = false;
    private int vueltas = 0, sinEntender = 0;
    private int destacado = 0;
    private long destacadoAt = 0;
    private View vistaDestacada = null;
    private String solapa = GENERAL;
    private DragList arrastre;
    private final java.util.HashSet<String> cerradas = new java.util.HashSet<>();
    private final java.util.HashSet<Integer> elegidos = new java.util.HashSet<>();
    private boolean eligiendo = false;
    private LinearLayout listContainer;
    private ScrollView scroll;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Api api;
    private Speaker speaker;
    private Recorder recorder;      // grabación puntual (botón)
    private Recorder waker;         // escucha continua (manos libres)

    private String lastRev = "";
    private boolean speakReply = true;
    private boolean wakeEnabled = false;
    private boolean online = false;
    private boolean recording = false;
    /** Número del turno en curso: las respuestas de turnos viejos se ignoran. */
    private int turnSeq = 0;
    /** true mientras la heladera espera un sí o un no. */
    private boolean asking = false;
    private boolean partialInFlight = false;
    private int pending = 0;

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            refresh();
            h.postDelayed(this, POLL_MS);
        }
    };

    // ------------------------------------------------------------- ciclo de vida

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);

        // La tablet vive enchufada en la heladera: la pantalla no se apaga.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        title = findViewById(R.id.title);
        count = findViewById(R.id.count);
        status = findViewById(R.id.status);
        wakeToggle = findViewById(R.id.wakeToggle);
        btnTalk = findViewById(R.id.btnTalk);
        btnQuiet = findViewById(R.id.btnQuiet);
        btnSend = findViewById(R.id.btnSend);
        btnMenu = findViewById(R.id.btnMenu);
        listContainer = findViewById(R.id.listContainer);
        scroll = findViewById(R.id.scroll);
        allToggle = findViewById(R.id.allToggle);
        allToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (todasElegidas()) elegidos.clear();
                else for (Integer id : seleccionables()) elegidos.add(id);
                lastRev = "";
                renderList(ultimos);
                pintarElegir();
                pintarTodas();
            }
        });
        pickToggle = findViewById(R.id.pickToggle);
        pickToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { modoElegir(!eligiendo); }
        });
        segTodo = findViewById(R.id.segTodo);
        segListas = findViewById(R.id.segListas);
        segGondola = findViewById(R.id.segGondola);
        enCarrito = findViewById(R.id.enCarrito);
        oido = findViewById(R.id.oido);
        hecho = findViewById(R.id.hecho);
        pista = findViewById(R.id.pista);
        anillo1 = findViewById(R.id.anillo1);
        anillo2 = findViewById(R.id.anillo2);
        giro = findViewById(R.id.giro);
        cara = findViewById(R.id.cara);
        caraIcono = findViewById(R.id.caraIcono);
        caraOndas = findViewById(R.id.caraOndas);
        caraTexto = findViewById(R.id.caraTexto);
        if (caraTexto != null) Fuentes.poner(caraTexto, Fuentes.titulo(this, 800));
        // Las letras: Bricolage para lo que se lee de lejos, Figtree para el resto.
        Fuentes.poner(title, Fuentes.titulo(this, 800));
        Fuentes.poner(count, Fuentes.titulo(this, 800));
        Fuentes.poner(btnTalk, Fuentes.titulo(this, 800));
        View hora = findViewById(R.id.hora);
        if (hora instanceof TextView) Fuentes.poner((TextView) hora, Fuentes.titulo(this, 700));
        Fuentes.poner(status, Fuentes.cuerpo(this, 600));
        Fuentes.aplicar(findViewById(android.R.id.content));
        superToggle = findViewById(R.id.superToggle);
        panelCharla = findViewById(R.id.panelCharla);
        barraSuper = findViewById(R.id.barraSuper);
        if (superToggle != null) {
            Iconos.poner(superToggle, R.drawable.ic_changuito, "#C7F536");
            superToggle.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setSuper(true); }
            });
            findViewById(R.id.superVolver).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setSuper(false); }
            });
            findViewById(R.id.superTerminar).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { terminarCompra(); }
            });
        }
        if (segGondola != null) segGondola.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setGondola(); }
        });
        tabs = findViewById(R.id.tabs);
        tabsScroll = findViewById(R.id.tabsScroll);
        arrastre = new DragList(listContainer, scroll, new DragList.OnDrop() {
            @Override public void soltado() { mandarOrden(); }
        });

        botonHablar(R.drawable.ic_mic, "#0B0F0A", "Hablar");
        Iconos.boton(btnQuiet, R.drawable.ic_oreja, "#6FD3FF", "Escucharme");
        chicosArriba = false;
        Iconos.boton(btnSend, R.drawable.ic_enviar, "#C7F536", "Mandar", chicosArriba);
        Iconos.boton(btnMenu, R.drawable.ic_menu, "#C7F536", "Más", chicosArriba);

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        api = new Api(p.getString(K_SERVER, DEFAULT_SERVER), p.getString(K_TOKEN, ""));
        handlePairLink(getIntent());
        wakeEnabled = p.getBoolean(K_WAKE, false);
        porListas = p.getBoolean(K_MODO, false);
        porGondola = p.getBoolean(K_GONDOLA, true);
        if (superToggle != null && p.getBoolean(K_SUPER, false)) h.post(new Runnable() {
            @Override public void run() { setSuper(true); }
        });
        solapa = p.getString(K_SOLAPA, GENERAL);
        verHechas = p.getBoolean(K_HECHAS, false);
        speaker = new Speaker(this);

        btnTalk.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (eligiendo) elegirLista();
                else if (asking) answer(true);
                else toggleRecording(true);
            }
        });
        btnQuiet.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (eligiendo) sobreElegidos("done", "Tachadas");
                else if (asking) answer(false);
                else toggleRecording(false);
            }
        });
        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (eligiendo) sobreElegidos("remove", "Borradas"); else sendList();
            }
        });
        btnMenu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (eligiendo) modoElegir(false); else menuAbajo();
            }
        });
        wakeToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setWake(!wakeEnabled); }
        });
        segTodo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setModo(false); }
        });
        segListas.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setModo(true); }
        });

        paintWakeToggle();
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).contains(K_SERVER)) {
            h.postDelayed(new Runnable() {
                @Override public void run() { showPairWelcome(); }
            }, 500);
        } else if (Tutorial.pendiente(p)) {
            h.postDelayed(new Runnable() {
                @Override public void run() { Tutorial.mostrar(MainActivity.this, getSharedPreferences(PREFS, MODE_PRIVATE)); }
            }, 400);
        }
        ensureMic();
        api.postJson("/api/warm", null, null);
        pedirPalabras();
    }

    @Override
    protected void onResume() {
        super.onResume();
        android.util.Log.d("heladera-life", "onResume");
        immersive();
        h.removeCallbacks(poller);
        h.post(poller);
        h.removeCallbacks(uiWatchdog);
        h.postDelayed(uiWatchdog, 1000);
        if (wakeEnabled) startWake();
    }

    @Override
    protected void onPause() {
        super.onPause();
        conversando = false;
        android.util.Log.d("heladera-life", "onPause");
        h.removeCallbacks(poller);
        h.removeCallbacks(uiWatchdog);
        stopWake();
        if (recorder != null) recorder.stop();
        if (charla != null) charla.cerrar();
        speaker.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        speaker.release();
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) immersive();
    }

    private void immersive() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable ignored) { }
    }

    private void ensureMic() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    private boolean hasMic() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_MIC && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED && wakeEnabled) {
            startWake();
        }
    }

    // ------------------------------------------------------------------ lista

    private void refresh() {
        api.get("/api/list", new Api.Cb() {
            @Override public void ok(JSONObject res) {
                online = true;
                String rev = res.optString("rev", "");
                pending = res.optInt("pending", 0);
                paintCount();
                String q = res.optString("question", "");
                if (!q.isEmpty() && !asking) setAskUi(q);
                else if (q.isEmpty() && asking) setAskUi("");
                if (!rev.equals(lastRev)) {
                    lastRev = rev;
                    renderList(res.optJSONArray("items"));
                }
            }
            @Override public void fail(String e) {
                online = false;
                paintCount();
                android.util.Log.w("heladera-life", "refresh falló: " + e);
                if (e != null && e.contains("token")) status.setText("Falta emparejar: menú → Ajustes.");
            }
        });
    }

    /**
     * "3 pendientes" no se entendía de un vistazo. Lo que uno quiere saber
     * parado frente a la heladera es cuánto falta comprar.
     */
    /** Hablar: el mismo ícono y palabra en el botón redondo de la tablet y en la barra del teléfono. */
    private void botonHablar(int icono, String color, String texto) {
        if (cara == null) { Iconos.boton(btnTalk, icono, color, texto); return; }
        caraBoton(icono == R.drawable.ic_mic ? R.drawable.ic_mic_grande : icono, 0, Color.parseColor(color), texto);
    }

    /**
     * Tablet: la cara del botón grande. icono 0 = sin ícono; ondas: 0 = no, 1 = siguen el micrófono, 2 = se mueven
     * solas (hablando). La palabra va siempre abajo, como en la propuesta.
     */
    private void caraBoton(int icono, int ondas, int color, String texto) {
        btnTalk.setText("");
        btnTalk.setCompoundDrawables(null, null, null, null);
        caraIcono.setVisibility(icono != 0 && ondas == 0 ? View.VISIBLE : View.GONE);
        if (icono != 0) {
            caraIcono.setImageResource(icono);
            caraIcono.setColorFilter(color);
        }
        caraOndas.setVisibility(ondas != 0 ? View.VISIBLE : View.GONE);
        caraOndas.color(color);
        caraOndas.hablar(ondas == 2);
        caraOndas.nivel(0);
        caraTexto.setText(texto);
        caraTexto.setTextColor(color);
    }

    /** "Faltan 9" con el número en lima: en la tablet es el título grande de la lista. */
    private boolean conteoGrande() { return "grande".equals(count.getTag()); }

    private void paintCount() {
        if (conteoGrande()) {
            String txt = !online ? "Sin conexión" : pending == 0 ? "Todo listo" : "Faltan " + pending;
            android.text.SpannableString sp = new android.text.SpannableString(txt);
            if (online && pending > 0)
                sp.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor("#C7F536")), 7, txt.length(), 0);
            count.setText(sp);
            count.setTextColor(Color.parseColor(online ? "#F3F6EE" : "#FF6B5A"));
            if (enCarrito != null) {
                int d = 0;
                if (ultimos != null) for (int i = 0; i < ultimos.length(); i++) {
                    JSONObject o = ultimos.optJSONObject(i);
                    if (o != null && o.optBoolean("done", false)) d++;
                }
                enCarrito.setText(!online || d == 0 ? "" : d + " ya en el changuito");
            }
            return;
        }
        if (!online) count.setText("sin conexión");
        else if (pending == 0) count.setText("todo listo");
        else count.setText("faltan " + pending);
        count.setTextColor(online ? Color.parseColor("#F2F5EE") : Color.parseColor("#FF6B5A"));
        Iconos.poner(count, R.drawable.ic_changuito, online ? "#C7F536" : "#FF6B5A");
    }

    private JSONArray ultimos = null;
    // El núcleo guarda palabras canónicas ("dairy", "l"); el server nos dice
    // cómo se escriben en el idioma de la casa.
    private JSONObject palabrasCat = new JSONObject(), palabrasUni = new JSONObject();

    private void pedirPalabras() {
        api.get("/api/labels", new Api.Cb() {
            @Override public void ok(JSONObject res) {
                JSONObject c = res.optJSONObject("categories");
                JSONObject u = res.optJSONObject("units");
                if (c != null) palabrasCat = c;
                if (u != null) palabrasUni = u;
                lastRev = "";
                renderList(ultimos);
            }
            @Override public void fail(String e) { /* sin traducción, se ve el canónico */ }
        });
    }

    private String gondolaDe(String cat) {
        if (cat == null || cat.isEmpty() || "null".equals(cat)) return "";
        return palabrasCat.optString(cat, cat);
    }
    private static final String GENERAL = "General";

    private static String grupo(JSONObject it) {
        String g = it.optString("group", "");
        return (g == null || g.isEmpty() || "null".equals(g)) ? null : g;
    }

    private String listaDe(JSONObject it) {
        String g = grupo(it);
        return g == null ? GENERAL : g;
    }

    /** Los nombres de las sublistas, en el orden en que aparecen. */
    private java.util.ArrayList<String> nombresDeListas() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        out.add(GENERAL);
        if (ultimos == null) return out;
        for (int i = 0; i < ultimos.length(); i++) {
            JSONObject o = ultimos.optJSONObject(i);
            String g = o == null ? null : grupo(o);
            if (g != null && !out.contains(g)) out.add(g);
        }
        return out;
    }

    private void renderList(JSONArray items) {
        if (items != null) ultimos = items;
        listContainer.removeAllViews();
        pintarSolapas();

        java.util.ArrayList<JSONObject> pend = new java.util.ArrayList<>();
        java.util.ArrayList<JSONObject> hechas = new java.util.ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) continue;
                if (porListas && !enSuper && !solapa.equals(listaDe(o))) continue;
                if (o.optBoolean("done", false)) hechas.add(o); else pend.add(o);
            }
        }

        if (enSuper) {
            renderSuper(pend, hechas);
        } else if (pend.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(hechas.isEmpty()
                    ? "La lista está vacía.\nTocá Hablar y decime qué falta."
                    : "No falta nada acá.");
            empty.setTextColor(Color.parseColor("#8A9280"));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(44), 0, dp(44));
            listContainer.addView(empty);
        } else if (tarjetas()) {
            renderTarjetas(pend, hechas);
        } else if (porListas) {
            listContainer.addView(cabecera(solapa, pend.size(), false));
            for (JSONObject o : pend) listContainer.addView(row(o));
        } else {
            String actual = "";
            boolean yaGeneral = false;
            for (JSONObject o : pend) {
                String g = grupo(o);
                if (g == null ? actual != null : !g.equals(actual)) {
                    if (!"".equals(actual) && actual != null) listContainer.addView(cierre(actual));
                    // El encabezado "General" va una sola vez: lo suelto que
                    // caiga después de una sublista sigue siendo lo mismo.
                    if (g != null || !yaGeneral) {
                        listContainer.addView(cabecera(g == null ? GENERAL : g, cuantas(pend, g), g != null));
                        if (g == null) yaGeneral = true;
                    }
                    actual = g;
                }
                if (g != null && cerradas.contains(g)) continue;
                listContainer.addView(row(o));
            }
            if (actual != null) listContainer.addView(cierre(actual));
        }
        if (!enSuper && (!tarjetas() || pend.isEmpty())) bloqueHechas(hechas);
        Fuentes.aplicar(listContainer);
        Fuentes.aplicar(tabs);
        // En la tablet la lista suelta va sobre un panel; las tarjetas ya traen el suyo.
        if (conteoGrande()) {
            boolean propias = enSuper || (tarjetas() && !pend.isEmpty());  // tarjetas con su propio fondo
            scroll.setBackgroundResource(propias ? 0 : R.drawable.panel);
            int pad = propias ? 0 : dp(8);
            scroll.setPadding(pad, pad == 0 ? 0 : dp(6), pad == 0 ? 0 : dp(10), pad == 0 ? 0 : dp(6));
            paintCount();
        }
        if (vistaDestacada != null) {
            final View v = vistaDestacada;
            vistaDestacada = null;
            scroll.post(new Runnable() {
                @Override public void run() { scroll.smoothScrollTo(0, Math.max(0, v.getTop() - dp(60))); }
            });
        }
    }

    /**
     * Tablet, vista por góndola: una tarjeta por góndola, repartidas en dos columnas para que queden parejas.
     * Se arrastra dentro de cada tarjeta (cambia el orden, no la góndola); para mover cosas entre sublistas está
     * "Todo junto" o Elegir → Agrupar.
     */
    private void renderTarjetas(java.util.ArrayList<JSONObject> pend, java.util.ArrayList<JSONObject> hechas) {
        java.util.LinkedHashMap<String, java.util.ArrayList<JSONObject>> por = new java.util.LinkedHashMap<>();
        for (JSONObject o : pend) {
            String c = o.optString("cat", "");
            if (c == null || c.isEmpty() || "null".equals(c)) c = "other";
            java.util.ArrayList<JSONObject> l = por.get(c);
            if (l == null) { l = new java.util.ArrayList<>(); por.put(c, l); }
            l.add(o);
        }
        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        fila.setClipChildren(false);
        final LinearLayout[] col = { columna(0), columna(dp(14)) };
        fila.addView(col[0]);
        fila.addView(col[1]);
        int[] peso = { 0, 0 };
        DragList principal = arrastre;
        compacta = true;
        try {
            for (java.util.Map.Entry<String, java.util.ArrayList<JSONObject>> e : por.entrySet()) {
                final LinearLayout card = tarjeta();
                card.addView(cabTarjeta(gondolaTitulo(e.getKey()), Iconos.deCategoria(e.getKey()),
                        String.valueOf(e.getValue().size()), null));
                arrastre = new DragList(card, scroll, new DragList.OnDrop() {
                    @Override public void soltado() { mandarOrdenDe(card); }
                });
                for (JSONObject o : e.getValue()) card.addView(row(o));
                int k = peso[0] <= peso[1] ? 0 : 1;
                col[k].addView(card);
                peso[k] += e.getValue().size() * 2 + 2;
            }
            if (!hechas.isEmpty()) {
                LinearLayout card = tarjeta();
                TextView vaciar = new TextView(this);
                vaciar.setText("Vaciar");
                vaciar.setTextColor(Color.parseColor("#8E977F"));
                vaciar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                vaciar.setBackgroundResource(R.drawable.pill);
                vaciar.setPadding(dp(12), dp(6), dp(12), dp(6));
                vaciar.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        post("/api/list/clear", json("scope", "done"), "Vacié las completadas");
                    }
                });
                View cab = cabTarjeta("Ya en el changuito", R.drawable.ic_changuito, String.valueOf(hechas.size()), vaciar);
                cab.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        verHechas = !verHechas;
                        prefs().edit().putBoolean(K_HECHAS, verHechas).apply();
                        lastRev = "";
                        renderList(ultimos);
                    }
                });
                card.addView(cab);
                if (verHechas) {
                    java.util.Collections.sort(hechas, new java.util.Comparator<JSONObject>() {
                        @Override public int compare(JSONObject a, JSONObject b) {
                            return b.optString("doneAt", "").compareTo(a.optString("doneAt", ""));
                        }
                    });
                    for (JSONObject o : hechas) card.addView(row(o));
                }
                col[1].addView(card, 0);  // fijo: siempre arriba a la derecha
            }
        } finally {
            arrastre = principal;
            compacta = false;
        }
        listContainer.addView(fila);
    }

    /**
     * "Estoy en el súper": a la izquierda lo que falta (por góndola), a la derecha el changuito. Tocar un renglón lo
     * pasa de un lado al otro; nada se borra hasta "Terminé la compra". Sin charla: en el súper no se habla.
     */
    private void setSuper(boolean on) {
        if (superToggle == null) return;
        if (on && eligiendo) modoElegir(false);
        if (on && charla != null && charla.viva()) charla.cerrar();
        enSuper = on;
        prefs().edit().putBoolean(K_SUPER, on).apply();
        panelCharla.setVisibility(on ? View.GONE : View.VISIBLE);
        barraSuper.setVisibility(on ? View.VISIBLE : View.GONE);
        int fuera = on ? View.GONE : View.VISIBLE;
        findViewById(R.id.seg).setVisibility(fuera);
        pickToggle.setVisibility(fuera);
        superToggle.setVisibility(fuera);
        wakeToggle.setVisibility(fuera);
        if (on) stopWake(); else resumeWake();
        lastRev = "";
        renderList(ultimos);
        scroll.post(new Runnable() { @Override public void run() { scroll.scrollTo(0, 0); } });
    }

    private void terminarCompra() {
        int n = 0;
        if (ultimos != null) for (int i = 0; i < ultimos.length(); i++) {
            JSONObject o = ultimos.optJSONObject(i);
            if (o != null && o.optBoolean("done", false)) n++;
        }
        if (n == 0) { toast("Todavía no hay nada en el changuito"); return; }
        post("/api/list/clear", json("scope", "done"), "Listo: saqué lo que compraste, queda lo que faltó");
        setSuper(false);
    }

    private void renderSuper(java.util.ArrayList<JSONObject> pend, java.util.ArrayList<JSONObject> hechas) {
        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout izq = columna(0), der = columna(dp(16));
        ((LinearLayout.LayoutParams) izq.getLayoutParams()).weight = 1.15f;
        fila.addView(izq);
        fila.addView(der);
        compacta = true;
        try {
            LinearLayout falta = tarjeta();
            falta.addView(cabTarjeta("Falta", R.drawable.ic_lista, String.valueOf(pend.size()), null));
            java.util.LinkedHashMap<String, java.util.ArrayList<JSONObject>> por = new java.util.LinkedHashMap<>();
            for (JSONObject o : pend) {
                String c = o.optString("cat", "");
                if (c == null || c.isEmpty() || "null".equals(c)) c = "other";
                java.util.ArrayList<JSONObject> l = por.get(c);
                if (l == null) { l = new java.util.ArrayList<>(); por.put(c, l); }
                l.add(o);
            }
            for (java.util.Map.Entry<String, java.util.ArrayList<JSONObject>> e : por.entrySet()) {
                TextView g = new TextView(this);
                g.setText(gondolaTitulo(e.getKey()));
                g.setTextColor(Color.parseColor("#C7F536"));
                g.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                g.setCompoundDrawablePadding(dp(8));
                g.setPadding(dp(12), dp(10), dp(12), dp(2));
                Fuentes.poner(g, Fuentes.cuerpo(this, 600));
                Iconos.poner(g, Iconos.deCategoria(e.getKey()), "#C7F536");
                falta.addView(g);
                for (JSONObject o : e.getValue()) falta.addView(row(o));
            }
            if (pend.isEmpty()) falta.addView(vacio("¡Ya está todo en el changuito!"));
            izq.addView(falta);

            LinearLayout carro = tarjeta();
            carro.addView(cabTarjeta("En el changuito", R.drawable.ic_changuito, String.valueOf(hechas.size()), null));
            java.util.Collections.sort(hechas, new java.util.Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) {
                    return b.optString("doneAt", "").compareTo(a.optString("doneAt", ""));
                }
            });
            for (JSONObject o : hechas) carro.addView(row(o));
            if (hechas.isEmpty()) carro.addView(vacio("Tocá lo que vas metiendo en el changuito. Si te arrepentís, lo tocás acá y vuelve."));
            der.addView(carro);
        } finally {
            compacta = false;
        }
        listContainer.addView(fila);
    }

    private TextView vacio(String txt) {
        TextView t = new TextView(this);
        t.setText(txt);
        t.setTextColor(Color.parseColor("#8E977F"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setPadding(dp(14), dp(10), dp(14), dp(14));
        return t;
    }

    private LinearLayout columna(int margenIzq) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = margenIzq;
        c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout tarjeta() {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackgroundResource(R.drawable.tarjeta);
        t.setPadding(dp(6), dp(14), dp(6), dp(8));
        t.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        t.setLayoutParams(lp);
        return t;
    }

    private View cabTarjeta(String titulo, int icono, String cuenta, View extra) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), 0, dp(12), dp(8));
        TextView t = new TextView(this);
        t.setText(titulo);
        t.setTextColor(Color.parseColor("#8E977F"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        Fuentes.poner(t, Fuentes.cuerpo(this, 600));
        t.setCompoundDrawablePadding(dp(8));
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(t);
        Iconos.poner(t, icono, "#C7F536");
        if (extra != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(10);
            extra.setLayoutParams(lp);
            r.addView(extra);
        }
        TextView c = new TextView(this);
        c.setText(cuenta);
        c.setTextColor(Color.parseColor("#5B6451"));
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        r.addView(c);
        return r;
    }

    private String gondolaTitulo(String cat) {
        String g = gondolaDe(cat);
        return cap(g.isEmpty() ? "Otros" : g);
    }

    /** Guarda el orden de una sola tarjeta: el servidor reacomoda sólo esos renglones, en sus mismos lugares. */
    private void mandarOrdenDe(LinearLayout card) {
        JSONArray ids = new JSONArray();
        for (int i = 0; i < card.getChildCount(); i++) {
            Object tag = card.getChildAt(i).getTag();
            if (tag instanceof Integer) ids.put(((Integer) tag).intValue());
        }
        JSONObject b = new JSONObject();
        try { b.put("ids", ids); } catch (Throwable ignored) { }
        api.postJson("/api/items/reorder", b, new Api.Cb() {
            @Override public void ok(JSONObject res) { lastRev = ""; applyState(res); }
            @Override public void fail(String e) { toast("No pude guardar el orden"); }
        });
    }

    private static int cuantas(java.util.ArrayList<JSONObject> lista, String g) {
        int n = 0;
        for (JSONObject o : lista) {
            String s = grupo(o);
            if (g == null ? s == null : g.equals(s)) n++;
        }
        return n;
    }

    private void pintarSolapas() {
        pintarSeg(segGondola, tarjetas());
        pintarSeg(segTodo, !porListas && !tarjetas());
        pintarSeg(segListas, porListas);
        tabsScroll.setVisibility(porListas ? View.VISIBLE : View.GONE);
        if (!porListas) return;

        java.util.ArrayList<String> nombres = nombresDeListas();
        if (!nombres.contains(solapa)) solapa = GENERAL;
        tabs.removeAllViews();
        for (final String n : nombres) {
            int falta = 0;
            if (ultimos != null) for (int i = 0; i < ultimos.length(); i++) {
                JSONObject o = ultimos.optJSONObject(i);
                if (o != null && !o.optBoolean("done", false) && n.equals(listaDe(o))) falta++;
            }
            TextView t = new TextView(this);
            t.setText(falta > 0 ? nombreDeLista(n) + "  " + falta : nombreDeLista(n));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            boolean sel = n.equals(solapa);
            t.setTextColor(sel ? colorElegido() : Color.parseColor("#8A9280"));
            t.setBackgroundResource(sel ? R.drawable.pill_sel : R.drawable.pill);
            t.setPadding(dp(16), dp(9), dp(16), dp(9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(7);
            t.setLayoutParams(lp);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    solapa = n;
                    prefs().edit().putString(K_SOLAPA, n).apply();
                    lastRev = "";
                    renderList(ultimos);
                    scroll.scrollTo(0, 0);
                }
            });
            tabs.addView(t);
        }
    }

    /**
     * Cierre invisible de una sublista.
     *
     * Sin esto no hay manera de saber si algo que quedó debajo del último
     * renglón del grupo entró al grupo o quedó afuera: esta marca es el borde.
     */
    private View cierre(String nombre) {
        View v = new View(this);
        v.setTag("F:" + nombre);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)));
        return v;
    }

    private View cabecera(final String nombre, int faltan, final boolean plegable) {
        final boolean cerrada = cerradas.contains(nombre);

        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        // El encabezado va arriba y al borde: con el aire de antes la tarjeta se
        // veía vacía aunque tuviera cosas.
        r.setPadding(dp(2), dp(6), dp(2), dp(2));
        if (plegable) r.setTag("H:" + nombre);

        ImageView agarre = new ImageView(this);
        agarre.setImageResource(R.drawable.ic_grip);
        agarre.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(24)));
        agarre.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        agarre.setVisibility(plegable ? View.VISIBLE : View.INVISIBLE);

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chev);
        chev.setColorFilter(Color.parseColor("#5C6552"));
        chev.setLayoutParams(new LinearLayout.LayoutParams(dp(26), dp(26)));
        chev.setRotation(cerrada ? 0 : 90);
        chev.setVisibility(plegable ? View.VISIBLE : View.INVISIBLE);

        TextView t = new TextView(this);
        t.setText(nombreDeLista(nombre));
        t.setTextColor(Color.parseColor("#C7F536"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setCompoundDrawablePadding(dp(8));
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setMaxLines(1);
        t.setPadding(dp(6), 0, 0, 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView c = new TextView(this);
        c.setText(faltan == 0 ? "completa" : faltan + " pendiente" + (faltan == 1 ? "" : "s"));
        c.setTextColor(Color.parseColor("#8A9280"));
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);

        TextView mas = new TextView(this);
        mas.setText("···");
        mas.setTextColor(Color.parseColor("#8A9280"));
        mas.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        mas.setPadding(dp(14), 0, dp(8), dp(2));
        mas.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { menuLista(nombre, plegable); }
        });

        r.addView(agarre);
        r.addView(chev);
        r.addView(t);
        if (palabrasCat.has(nombre)) Iconos.poner(t, Iconos.deCategoria(nombre), "#C7F536");
        r.addView(c);
        r.addView(mas);
        if (plegable) {
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (cerrada) cerradas.remove(nombre); else cerradas.add(nombre);
                    lastRev = "";
                    renderList(ultimos);
                }
            });
            arrastre.enganchar(r, agarre);
        }
        return r;
    }

    /** La card de completadas: plegada, con su contador y su "Vaciar". */
    private void bloqueHechas(java.util.ArrayList<JSONObject> hechas) {
        LinearLayout cab = new LinearLayout(this);
        cab.setOrientation(LinearLayout.HORIZONTAL);
        cab.setGravity(Gravity.CENTER_VERTICAL);
        cab.setPadding(dp(2), dp(12), dp(2), dp(4));

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chev);
        chev.setColorFilter(Color.parseColor("#5C6552"));
        chev.setLayoutParams(new LinearLayout.LayoutParams(dp(26), dp(26)));
        chev.setRotation(verHechas ? 90 : 0);

        TextView t = new TextView(this);
        t.setText("COMPLETADAS (" + hechas.size() + ")");
        t.setTextColor(Color.parseColor("#8A9280"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setLetterSpacing(0.07f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(dp(6), 0, 0, 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView vaciar = new TextView(this);
        vaciar.setText("Vaciar");
        vaciar.setTextColor(Color.parseColor("#8A9280"));
        vaciar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        vaciar.setBackgroundResource(R.drawable.pill);
        vaciar.setPadding(dp(14), dp(8), dp(14), dp(8));
        vaciar.setVisibility(hechas.isEmpty() ? View.GONE : View.VISIBLE);
        vaciar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                post("/api/list/clear", json("scope", "done"), "Vacié las completadas");
            }
        });

        View linea = new View(this);
        LinearLayout.LayoutParams lpl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lpl.topMargin = dp(10);
        linea.setLayoutParams(lpl);
        linea.setBackgroundColor(Color.parseColor("#242D1B"));
        listContainer.addView(linea);

        cab.addView(chev);
        cab.addView(t);
        cab.addView(vaciar);
        cab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                verHechas = !verHechas;
                prefs().edit().putBoolean(K_HECHAS, verHechas).apply();
                lastRev = "";
                renderList(ultimos);
            }
        });
        listContainer.addView(cab);

        if (!verHechas || hechas.isEmpty()) return;
        java.util.Collections.sort(hechas, new java.util.Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return b.optString("doneAt", "").compareTo(a.optString("doneAt", ""));
            }
        });
        for (JSONObject o : hechas) listContainer.addView(row(o));
    }

    private View row(final JSONObject it) {
        final int id = it.optInt("id");
        final boolean done = it.optBoolean("done", false);
        final boolean adentro = grupo(it) != null && !porListas && !done && !compacta;

        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(compacta ? 50 : 64));
        r.setPadding(adentro ? dp(24) : 0, compacta ? 0 : dp(3), compacta ? dp(8) : 0, compacta ? 0 : dp(3));
        r.setTag(Integer.valueOf(id));

        // Mientras elegís, la agarradera es la casilla cuadrada: el círculo
        // sigue siendo para tachar, que es otra cosa.
        final boolean marcada = eligiendo && elegidos.contains(Integer.valueOf(id));
        ImageView agarre = new ImageView(this);
        agarre.setImageResource(eligiendo
                ? (marcada ? R.drawable.ic_cuadro_ok : R.drawable.ic_cuadro)
                : R.drawable.ic_grip);
        if (eligiendo) agarre.setColorFilter(Color.parseColor(marcada ? "#6FD3FF" : "#4A5340"));
        agarre.setLayoutParams(new LinearLayout.LayoutParams(dp(compacta ? 22 : 32), dp(44)));
        if (compacta && !eligiendo) agarre.setAlpha(0.5f);
        agarre.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        agarre.setVisibility(done ? View.INVISIBLE : View.VISIBLE);
        if (eligiendo) agarre.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { elegir(id); }
        });

        // El tilde es un aro dibujado (lleno con tilde cuando ya está en el changuito); el toque es más grande que el dibujo.
        final TextView check = new TextView(this);
        check.setBackgroundResource(done ? R.drawable.tilde_hecho : R.drawable.tilde_vacio);
        LinearLayout.LayoutParams lpc = new LinearLayout.LayoutParams(dp(30), dp(30));
        lpc.leftMargin = dp(7);
        lpc.rightMargin = dp(7);
        check.setLayoutParams(lpc);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(8), 0, dp(6), 0);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        String qty = qtyLabel(it);
        boolean ancho = conteoGrande();  // tablet: la cantidad va en su propia columna, a la derecha
        name.setText(ancho || qty.isEmpty() ? cap(it.optString("name")) : qty + "  " + cap(it.optString("name")));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, ancho ? 22 : 21);
        Fuentes.poner(name, Fuentes.cuerpo(this, 600));
        name.setTextColor(Color.parseColor(done ? "#5C6552" : "#F2F5EE"));
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (done) name.setPaintFlags(name.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        mid.addView(name);

        // En las tarjetas, debajo del nombre sólo va lo que importa: la nota o para cuándo.
        TextView sub = compacta ? subtituloCorto(it) : subtitulo(it);
        if (sub != null) mid.addView(sub);

        final ImageView fav = new ImageView(this);
        fav.setImageResource(R.drawable.ic_estrella);
        fav.setColorFilter(Color.parseColor(it.optBoolean("star", false) ? "#C7F536" : "#333C28"));
        LinearLayout.LayoutParams lpf = new LinearLayout.LayoutParams(dp(38), dp(38));
        fav.setPadding(dp(8), dp(8), dp(8), dp(8));
        fav.setLayoutParams(lpf);
        fav.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                api.postJson("/api/items/" + id + "/star", null, new Api.Cb() {
                    @Override public void ok(JSONObject res) { lastRev = ""; applyState(res); }
                    @Override public void fail(String e) { toast("No pude destacarlo"); }
                });
            }
        });

        TextView del = new TextView(this);
        del.setText("×");
        del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        del.setTextColor(Color.parseColor("#333C28"));
        del.setPadding(dp(12), dp(14), dp(10), dp(14));
        del.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmDelete(id, cap(it.optString("name"))); }
        });

        r.addView(agarre);
        r.addView(check);
        r.addView(mid);
        if (conteoGrande() && !qty.isEmpty()) {
            TextView cant = new TextView(this);
            cant.setText(qty);
            cant.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            cant.setTextColor(Color.parseColor(done ? "#5B6451" : "#8E977F"));
            cant.setPadding(dp(8), 0, dp(4), 0);
            cant.setMaxLines(1);
            r.addView(cant);
        }
        if (!compacta) {  // en las tarjetas no entran: destacar y borrar están en el detalle (tocar el renglón)
            r.addView(fav);
            r.addView(del);
        }

        if (marcada) r.setBackgroundResource(R.drawable.row_drag);
        if (compacta && nuevos.contains(Integer.valueOf(id)) && System.currentTimeMillis() - destacadoAt < 6000) {
            r.setBackgroundResource(R.drawable.fila_nueva);
            final View rr = r;
            h.postDelayed(new Runnable() { @Override public void run() { rr.setBackgroundResource(0); } },
                    6000 - (System.currentTimeMillis() - destacadoAt));
        } else if (id == destacado && System.currentTimeMillis() - destacadoAt < 4000) {
            vistaDestacada = r;
            r.setBackgroundColor(Color.parseColor("#2A3A14"));
            final View rr = r;
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (Build.VERSION.SDK_INT >= 21) {
                        android.animation.ObjectAnimator.ofArgb(rr, "backgroundColor",
                                Color.parseColor("#2A3A14"), Color.TRANSPARENT).setDuration(500).start();
                    } else {
                        rr.setBackgroundColor(Color.TRANSPARENT);
                    }
                }
            }, 1100);
        }
        check.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (eligiendo) { elegir(id); return; }
                tachar(check, id, done);
            }
        });
        // El círculo tacha; el renglón abre el detalle, como en Tasks.
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (eligiendo) { elegir(id); return; }
                if (done || enSuper) { tachar(check, id, done); return; }
                detalle(it);
            }
        });
        if (enSuper) agarre.setVisibility(View.GONE);
        if (!done && !eligiendo && !enSuper) arrastre.enganchar(r, agarre, new DragList.OnLargo() {
            @Override public void apretado(View fila) {
                if (eligiendo) { elegir(id); return; }
                modoElegir(true);
                elegir(id);
            }
        });
        return r;
    }

    private void elegir(int id) {
        Integer k = Integer.valueOf(id);
        if (elegidos.contains(k)) elegidos.remove(k); else elegidos.add(k);
        lastRev = "";
        renderList(ultimos);
        pintarElegir();
        pintarTodas();
    }

    private void tachar(final TextView check, final int id, boolean done) {
        // Se tacha en el acto; si el server dice otra cosa, el próximo refresco
        // manda. Esperar la red para ver un tilde es horrible.
        check.setBackgroundResource(done ? R.drawable.tilde_vacio : R.drawable.tilde_hecho);
        if (!done) {
            check.setScaleX(0.6f);
            check.setScaleY(0.6f);
            check.animate().scaleX(1f).scaleY(1f).setDuration(220).start();
        }
        api.postJson("/api/items/" + id + "/toggle", null, new Api.Cb() {
            @Override public void ok(JSONObject res) { lastRev = ""; applyState(res); }
            @Override public void fail(String e) { toast("No pude tacharlo"); }
        });
    }

    private TextView subtituloCorto(JSONObject it) {
        String due = it.optString("due", "");
        String note = it.optString("note", "");
        boolean tieneDia = due != null && due.length() == 10;
        boolean tieneNota = note != null && !note.isEmpty() && !"null".equals(note);
        if (!tieneDia && !tieneNota) return null;
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTextColor(Color.parseColor(tieneDia && due.compareTo(hoyISO()) < 0 ? "#FF6B5A" : "#8E977F"));
        t.setText((tieneDia ? cap(dia(due)) : "") + (tieneDia && tieneNota ? " · " : "") + (tieneNota ? note : ""));
        return t;
    }

    /** Debajo del nombre: cuándo, la nota y en qué góndola se compra. */
    private TextView subtitulo(JSONObject it) {
        String due = it.optString("due", "");
        boolean tieneDia = due != null && due.length() == 10;
        String cuando = tieneDia ? cap(dia(due)) : dia(it.optString("createdAt", ""));

        StringBuilder resto = new StringBuilder();
        String note = it.optString("note", "");
        if (note != null && !note.isEmpty() && !"null".equals(note)) resto.append(note);
        // Debajo del título de su góndola, repetirla sobra.
        String catKey = it.optString("cat", "");
        String cat = catKey.equals(grupo(it)) ? "" : gondolaDe(catKey);
        if (!cat.isEmpty()) {
            if (resto.length() > 0) resto.append(" · ");
            resto.append(cat);
        }
        if (cuando.isEmpty() && resto.length() == 0) return null;

        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        String todo = cuando + (resto.length() > 0 ? (cuando.isEmpty() ? "" : " · ") + resto : "");
        if (tieneDia) {
            android.text.SpannableString sp = new android.text.SpannableString(todo);
            boolean tarde = due.compareTo(hoyISO()) < 0;
            sp.setSpan(new android.text.style.ForegroundColorSpan(
                    Color.parseColor(tarde ? "#FF6B5A" : "#C7F536")), 0, cuando.length(), 0);
            sp.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, cuando.length(), 0);
            t.setText(sp);
            t.setTextColor(Color.parseColor("#8A9280"));
        } else {
            t.setText(todo);
            t.setTextColor(Color.parseColor("#8A9280"));
        }
        return t;
    }

    private static String hoyISO() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
    }

    private static String dia(String iso) {
        if (iso == null || iso.length() < 10) return "";
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTime(f.parse(iso.substring(0, 10)));
            c.set(java.util.Calendar.HOUR_OF_DAY, 12);
            java.util.Calendar hoy = java.util.Calendar.getInstance();
            hoy.set(java.util.Calendar.HOUR_OF_DAY, 12);
            long dias = Math.round((hoy.getTimeInMillis() - c.getTimeInMillis()) / 86400000.0);
            if (dias == 0) return "hoy";
            if (dias == 1) return "ayer";
            if (dias == -1) return "mañana";
            if (dias > 0 && dias < 7) return new java.text.SimpleDateFormat("EEEE", new java.util.Locale("es")).format(c.getTime());
            if (dias < 0 && dias > -7) return "el " + new java.text.SimpleDateFormat("EEEE", new java.util.Locale("es")).format(c.getTime());
            return new java.text.SimpleDateFormat("d 'de' MMM", new java.util.Locale("es")).format(c.getTime());
        } catch (Throwable e) {
            return "";
        }
    }

    /** Manda el orden nuevo y en qué sublista quedó cada uno. */
    private void mandarOrden() {
        JSONArray ids = new JSONArray();
        JSONObject groups = new JSONObject();
        String cur = null;
        for (int i = 0; i < listContainer.getChildCount(); i++) {
            View v = listContainer.getChildAt(i);
            Object tag = v.getTag();
            if (tag instanceof String) {
                String t = (String) tag;
                if (t.startsWith("H:")) cur = t.substring(2);
                else if (t.startsWith("F:")) cur = null;
                continue;
            }
            if (!(tag instanceof Integer)) continue;
            ids.put(((Integer) tag).intValue());
            try { if (!porListas) groups.put(String.valueOf(tag), cur == null ? JSONObject.NULL : cur); }
            catch (Throwable ignored) { }
        }
        JSONObject b = new JSONObject();
        try { b.put("ids", ids); if (!porListas) b.put("groups", groups); } catch (Throwable ignored) { }
        api.postJson("/api/items/reorder", b, new Api.Cb() {
            @Override public void ok(JSONObject res) { lastRev = ""; applyState(res); }
            @Override public void fail(String e) { toast("No pude guardar el orden"); }
        });
    }

    /** El detalle de un renglón: nombre, nota, día y estrella. */
    private void detalle(final JSONObject it) {
        final int id = it.optInt("id");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(6), dp(22), 0);

        final EditText nombre = new EditText(this);
        nombre.setText(it.optString("name"));
        final EditText nota = new EditText(this);
        String n = it.optString("note", "");
        nota.setText(n == null || "null".equals(n) ? "" : n);
        nota.setHint("descremada, sin sal…");
        final EditText fecha = new EditText(this);
        String d = it.optString("due", "");
        fecha.setText(d == null || "null".equals(d) ? "" : d);
        fecha.setHint("hoy, mañana, sábado, 20/9");
        final android.widget.CheckBox star = new android.widget.CheckBox(this);
        star.setText("  Destacada");
        star.setTextColor(Color.parseColor("#F2F5EE"));
        star.setChecked(it.optBoolean("star", false));

        box.addView(label("Qué es"));
        box.addView(nombre);
        box.addView(label("Nota"));
        box.addView(nota);
        box.addView(label("Para cuándo"));
        box.addView(fecha);
        box.addView(star);

        new AlertDialog.Builder(this)
                .setTitle(cap(it.optString("name")))
                .setView(box)
                .setPositiveButton("Guardar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d2, int w) {
                        JSONObject b = new JSONObject();
                        try {
                            b.put("name", nombre.getText().toString().trim());
                            String no = nota.getText().toString().trim();
                            b.put("note", no.isEmpty() ? JSONObject.NULL : no);
                            String fe = fecha.getText().toString().trim();
                            b.put("due", fe.isEmpty() ? JSONObject.NULL : fe);
                            b.put("star", star.isChecked());
                        } catch (Throwable ignored) { }
                        post("/api/items/" + id, b, "Guardado");
                    }
                })
                .setNeutralButton("Borrar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d2, int w) {
                        post("/api/items/" + id + "/delete", null, "Lo saqué");
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private String qtyLabel(JSONObject it) {
        double q = it.optDouble("qty", Double.NaN);
        if (Double.isNaN(q) || q == 0) return "";
        String n = q == 0.5 ? "½" : ((q == Math.floor(q))
                ? String.valueOf((int) q) : String.valueOf(q).replace('.', ','));
        String u = it.optString("unit", "");
        // Guardamos la unidad canónica ("l") pero se escribe como se anota en
        // una lista: "1 lts". Y la "u" no se escribe, sobra.
        if (u == null || u.isEmpty() || "null".equals(u) || "u".equals(u)) return n;
        u = palabrasUni.optString(u, u);
        if (u.isEmpty()) return n;
        return n + " " + u;
    }

    /** Una sublista que se llama como una góndola ("produce") se muestra como la góndola ("Verdulería"). */
    private String nombreDeLista(String n) {
        return cap(palabrasCat.optString(n, n));
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private void applyState(JSONObject res) {
        lastRev = res.optString("rev", "");
        pending = res.optInt("pending", 0);
        online = true;
        paintCount();
        renderList(res.optJSONArray("items"));
    }

    private void confirmDelete(final int id, String name) {
        new AlertDialog.Builder(this)
                .setTitle("Borrar " + name + "?")
                .setPositiveButton("Borrar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        api.postJson("/api/items/" + id + "/delete", null, new Api.Cb() {
                            @Override public void ok(JSONObject res) { applyState(res); }
                            @Override public void fail(String e) { toast("No pude borrarlo"); }
                        });
                    }
                })
                .setNegativeButton("No", null)
                .show();
    }

    // ------------------------------------------------------------------- voz

    private void toggleRecording(boolean withVoice) {
        android.util.Log.d("heladera-life", "toggleRecording voice=" + withVoice
                + " running=" + (recorder != null && recorder.isRunning()));
        if (recorder != null && recorder.isRunning()) {
            if (recording) {
                conversando = false;   // apretaste Parar: se termina la charla
                recorder.finish();
                // El hilo del micrófono tarda un instante en enterarse; la
                // pantalla tiene que responder al toque, no al hilo.
                setRecordingUi(false, speakReply);
                status.setText("Listo, dejé de escuchar.");
                return;
            }
            conversando = false;
            // La pantalla dice "Hablar" pero quedó un grabador dando vueltas:
            // no es un "parar", es que se colgó. Lo matamos y arrancamos limpio,
            // que si no el botón no responde nunca más.
            android.util.Log.w("heladera-life", "grabador zombi: lo corto");
            recorder.stop();
            recorder = null;
        }
        // La tablet habla con la charla en vivo del servidor; el teléfono sigue grabando y mandando.
        if (conCharla()) { alternarCharla(!withVoice); return; }
        if (!hasMic()) { ensureMic(); return; }
        // Arrancar de nuevo cancela lo anterior: su respuesta, si llega, se descarta.
        turnSeq++;

        // Hablar abre una charla: sigue escuchando hasta que digas que ya está.
        // Escucharme es un solo turno, que para eso está el modo silencio.
        if (!conversando) { vueltas = 0; sinEntender = 0; }
        conversando = withVoice;
        speakReply = withVoice;
        speaker.stop();
        stopWake();
        // Que el modelo esté caliente para cuando llegue el audio.
        api.postJson("/api/warm", null, null);
        recorder = new Recorder(this, false);
        beep();
        recorder.start();
        setRecordingUi(true, withVoice);
        status.setText(withVoice ? "Hablame…  (tocá Parar cuando termines)"
                                 : "Hablame…  modo silencio, contesto por pantalla");
        if (oido != null) {
            globo("", false);
            if (hecho != null) hecho.setVisibility(View.GONE);
            status.setText(withVoice ? "Te escucho." : "Te escucho. Contesto por pantalla.");
        }
    }

    @Override
    public void onUtterance(Recorder source, byte[] wav, int ms) {
        android.util.Log.d("heladera-life", "onUtterance ms=" + ms
                + " deWaker=" + (source == waker) + " activo=" + (source == recorder));
        if (source == waker) {
            // Un "ok heladera" dura menos de dos segundos y termina en silencio.
            // Si el recorte llegó al tope, no fue una palabra: es la cocina
            // sonando sin parar, y mandarla a transcribir cada cuatro segundos
            // tenía a whisper ocupado justo cuando apretabas Hablar.
            // Ni el ruido largo ni el golpecito corto son "ok heladera":
            // dura cerca de un segundo y termina en silencio.
            if (ms < 600 || ms > 2500) return;
            checkWake(wav);
            return;
        }
        // Sobra de un grabador que ya cerramos: no es lo que la persona dijo.
        if (source != recorder) return;
        setRecordingUi(false, speakReply);
        status.setText("Pensando…");
        estado("piensa");
        // Un "mmm" apenas dejás de hablar. Guardado en la tablet, así suena en el
        // acto: la respuesta de verdad puede tardar dos segundos y sin esto
        // parece que no te escuchó.
        if (speakReply) playAck();
        final int seq = ++turnSeq;
        final long t0 = System.currentTimeMillis();
        // Lo que pidas cae en la lista que estás mirando; si estás en "Todo" o
        // en General, va suelto como siempre.
        final String destino = (porListas && !GENERAL.equals(solapa)) ? solapa : "";
        api.postAudio("/api/voice", wav,
                new String[]{"speak", speakReply ? "true" : "false", "source", "tablet",
                             "list", destino},
                new Api.Cb() {
                    @Override public void ok(JSONObject res) {
                        android.util.Log.d("heladera-life", "vuelta en "
                                + (System.currentTimeMillis() - t0) + " ms  server=" + res.optString("ms"));
                        if (seq != turnSeq) return;   // ya arrancó otro turno
                        handleReply(res);
                    }
                    @Override public void fail(String e) {
                        if (seq != turnSeq) return;
                        status.setText("No pude hablar con el servidor: " + e);
                        estado("reposo");
                        online = false;
                        paintCount();
                        resumeWake();
                    }
                });
    }

    /**
     * Cuando la heladera pregunta algo que no tiene vuelta ("¿borro toda la
     * lista?"), los dos botones grandes pasan a ser SÍ y NO. Contestar hablando
     * también anda, pero depende de que el micrófono te escuche justo en ese
     * momento, y si no te escucha quedás trabado sin entender por qué.
     */
    private void setAskUi(String question) {
        asking = question != null && !question.isEmpty();
        if (asking) {
            status.setText(question);
            botonHablar(0, "#0B0F0A", "Sí");
            Iconos.boton(btnQuiet, 0, "#F2F5EE", "No");
            btnTalk.setBackgroundResource(R.drawable.btn_talk);
            btnQuiet.setBackgroundResource(R.drawable.btn_ghost);
            btnTalk.setTextColor(Color.BLACK);
            btnQuiet.setTextColor(Color.parseColor("#F2F5EE"));
        } else {
            setRecordingUi(false, true);
        }
        verQuiet();
    }

    private void answer(final boolean yes) {
        asking = false;
        setRecordingUi(false, true);
        status.setText(yes ? "Dale…" : "Listo, no toco nada.");
        JSONObject b = new JSONObject();
        try { b.put("yes", yes); b.put("speak", true); } catch (Throwable ignored) { }
        api.postJson("/api/confirm", b, new Api.Cb() {
            @Override public void ok(JSONObject res) { handleReply(res); }
            @Override public void fail(String e) { status.setText("Error: " + e); }
        });
    }

    /**
     * Llevar la pantalla a donde pasó la cosa.
     *
     * Si estás mirando la solapa "General" y le pedís algo para el asado, la
     * heladera contesta "listo, anoté" y vos no ves cambiar nada: quedó en otra
     * solapa, o adentro de un grupo plegado. Entonces vamos hasta ahí y le
     * damos un destello al renglón nuevo.
     */
    private void irADondePaso(JSONObject res) {
        JSONObject r = res.optJSONObject("result");
        if (r == null) return;
        JSONObject nuevo = ultimoDe(r, "added");
        // Si no agregó nada, alcanza con ir a donde tachó o borró: el número de
        // la solapa cambia y si no te movés parece que no hizo nada.
        JSONObject donde = nuevo != null ? nuevo : ultimoDe(r, "marked");
        if (donde == null) donde = ultimoDe(r, "removed");
        if (donde == null) return;
        String g = grupo(donde);
        if (porListas) {
            String lista = g == null ? GENERAL : g;
            if (!lista.equals(solapa)) {
                solapa = lista;
                prefs().edit().putString(K_SOLAPA, lista).apply();
            }
        } else if (g != null) {
            cerradas.remove(g);
        }
        nuevos.clear();
        JSONArray agregados = r.optJSONArray("added");
        if (agregados != null) for (int i = 0; i < agregados.length(); i++) {
            JSONObject a = agregados.optJSONObject(i);
            if (a != null) nuevos.add(Integer.valueOf(a.optInt("id", 0)));
        }
        if (nuevo != null) {
            destacado = nuevo.optInt("id", 0);
            destacadoAt = System.currentTimeMillis();
        }
    }

    private static JSONObject ultimoDe(JSONObject r, String campo) {
        JSONArray a = r.optJSONArray(campo);
        return (a != null && a.length() > 0) ? a.optJSONObject(a.length() - 1) : null;
    }

    private CharSequence charla(String heard, String reply) {
        if (heard.isEmpty()) return reply;
        String a = "«" + heard + "»\n";
        android.text.SpannableString sp = new android.text.SpannableString(a + reply);
        sp.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor("#8E977F")), 0, a.length(), 0);
        sp.setSpan(new android.text.style.RelativeSizeSpan(0.8f), 0, a.length(), 0);
        return sp;
    }

    private void handleReply(JSONObject res) {
        irADondePaso(res);
        applyState(res);
        if (res.optBoolean("awaitingConfirmation", false)) {
            String q = res.optString("reply", "");
            setAskUi(q);
            if (speakReply) {
                String u = res.optString("audioUrl", "");
                estado("habla");
                speaker.say(api.audioUrl(u), q, new Speaker.Done() {
                    @Override public void onDone() { estado("reposo"); seguirCharla(res); }
                });
            } else {
                seguirCharla(res);
            }
            return;
        }
        asking = false;
        String heard = res.optString("transcript", "");
        String reply = res.optString("reply", "");
        if (oido != null) {
            globo(heard, true);
            chipHecho(res);
            status.setText(reply);
        } else {
            status.setText(charla(heard, reply));
        }
        if (!speakReply) estado("reposo");

        if (speakReply) {
            String url = res.optString("audioUrl", "");
            estado("habla");
            speaker.say(api.audioUrl(url), reply, new Speaker.Done() {
                @Override public void onDone() { estado("reposo"); seguirCharla(res); }
            });
        } else {
            seguirCharla(res);
        }
    }

    /**
     * Seguir la charla, o cortarla.
     *
     * Corta cuando el modelo entendió que ya está ("nada más"), cuando no te
     * entiende dos veces seguidas, o después de unas cuantas vueltas: la tablet
     * escucha una cocina entera y no queremos que se quede prendida sola.
     */
    private void seguirCharla(JSONObject res) {
        if (!conversando || !res.optBoolean("seguir", false)) {
            conversando = false;
            resumeWake();
            return;
        }
        if (res.optBoolean("entendido", true)) sinEntender = 0; else sinEntender++;
        if (sinEntender >= 2 || ++vueltas >= 8) {
            conversando = false;
            status.setText("Corté por las dudas. Tocá Hablar cuando quieras.");
            resumeWake();
            return;
        }
        h.postDelayed(new Runnable() {
            @Override public void run() { if (conversando) toggleRecording(true); }
        }, 250);
    }

    @Override
    public void onLevel(Recorder source, double level) {
        if (source != recorder || !recorder.isRunning()) return;
        if (speakReply && caraOndas != null) { caraOndas.nivel(Math.min(1.0, level * 1.6)); return; }
        int bars = (int) Math.round(Math.min(1.0, level) * 5);
        StringBuilder sb = new StringBuilder("■  Parar   ");
        for (int i = 0; i < 5; i++) sb.append(i < bars ? "●" : "·");
        (speakReply ? btnTalk : btnQuiet).setText(sb.toString());
    }

    /** Lo que va entendiendo mientras hablás, para que veas que te está escuchando. */
    @Override
    public void onPartial(Recorder source, byte[] wav, int ms) {
        if (source != recorder || partialInFlight) return;
        partialInFlight = true;
        api.postAudio("/api/partial", wav, null, new Api.Cb() {
            @Override public void ok(JSONObject res) {
                partialInFlight = false;
                String t = res.optString("text", "");
                if (!t.isEmpty() && recorder != null && recorder.isRunning()) {
                    if (oido != null) globo(t + "…", false);
                    else status.setText("« " + t + " …»");
                }
            }
            @Override public void fail(String e) { partialInFlight = false; }
        });
    }

    @Override
    public void onError(Recorder source, String msg) {
        if (source == waker) return;          // el de manos libres se reintenta solo
        if (source != recorder) return;
        setRecordingUi(false, speakReply);
        status.setText(msg);
    }

    /**
     * Tablet: cómo se ve el botón grande. escucha = anillos que laten; piensa = giro alrededor; habla = anillos lentos;
     * reposo = quieto. En el teléfono no hay anillos y esto no hace nada.
     */
    private void estado(String s) {
        if (anillo1 == null) return;
        for (android.animation.Animator a : latidos) a.cancel();
        latidos.clear();
        anillo1.setAlpha(0f);
        anillo2.setAlpha(0f);
        giro.setVisibility("piensa".equals(s) ? View.VISIBLE : View.INVISIBLE);
        boolean late = "escucha".equals(s) || "habla".equals(s);
        if ("piensa".equals(s)) {
            btnTalk.setBackgroundResource(R.drawable.btn_piensa);
            caraBoton(0, 0, Color.parseColor("#C7F536"), "Anotando…");
        } else if ("habla".equals(s)) {
            btnTalk.setBackgroundResource(R.drawable.btn_talk);
            caraBoton(0, 2, Color.parseColor("#0B0F0A"), "Hablando");
        } else if ("reposo".equals(s) && !eligiendo && !asking && !recording) {
            btnTalk.setBackgroundResource(R.drawable.btn_talk);
            caraBoton(R.drawable.ic_mic_grande, 0, Color.parseColor("#0B0F0A"), "Hablar");
        }
        if (late) {
            long ms = "escucha".equals(s) ? 1400 : 2200;
            latidos.add(latir(anillo1, ms, 0));
            latidos.add(latir(anillo2, ms, ms / 2));
        }
        boolean enVivo = charla != null && charla.viva();
        String p = "escucha".equals(s) ? (enVivo ? "tocá el botón para cortar la charla" : "tocá el botón cuando termines")
                : "habla".equals(s) ? (enVivo ? "hablale encima para cortarla" : "tocá el botón para interrumpirla")
                : "reposo".equals(s) && wakeEnabled && !eligiendo ? "o decí «ok tableta»" : "";
        pista.setText(p);
        pista.setVisibility(p.isEmpty() ? View.INVISIBLE : View.VISIBLE);
    }

    private android.animation.Animator latir(View v, long ms, long delay) {
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofPropertyValuesHolder(v,
                android.animation.PropertyValuesHolder.ofFloat("scaleX", 0.92f, 1.2f),
                android.animation.PropertyValuesHolder.ofFloat("scaleY", 0.92f, 1.2f),
                android.animation.PropertyValuesHolder.ofFloat("alpha", 0.8f, 0f));
        a.setDuration(ms);
        a.setStartDelay(delay);
        a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        a.start();
        return a;
    }

    /** Tablet: lo que dijiste va en un globo; mientras hablás, con borde punteado. */
    private void globo(String texto, boolean final_) {
        if (oido == null) return;
        if (texto == null || texto.isEmpty()) { oido.setVisibility(View.GONE); return; }
        oido.setVisibility(View.VISIBLE);
        oido.setText(texto);
        oido.setBackgroundResource(final_ ? R.drawable.burbuja_yo : R.drawable.burbuja_vivo);
        oido.setTextColor(Color.parseColor(final_ ? "#F3F6EE" : "#8E977F"));
    }

    /** Tablet: "✓ 3 anotados" arriba de la respuesta. */
    private void chipHecho(JSONObject res) {
        if (hecho == null) return;
        JSONObject r = res.optJSONObject("result");
        String txt = "";
        if (r != null) {
            int a = r.optJSONArray("added") == null ? 0 : r.optJSONArray("added").length();
            int m = r.optJSONArray("marked") == null ? 0 : r.optJSONArray("marked").length();
            int b = r.optJSONArray("removed") == null ? 0 : r.optJSONArray("removed").length();
            if (a > 0) txt = "✓ " + a + (a == 1 ? " anotado" : " anotados");
            else if (m > 0) txt = "✓ " + m + (m == 1 ? " tachado" : " tachados");
            else if (b > 0) txt = "✓ " + b + (b == 1 ? " borrado" : " borrados");
        }
        hecho.setText(txt);
        hecho.setVisibility(txt.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------ charla en vivo (tablet)

    private Charla charla;
    private String charlaDicho = "";
    private boolean charlaMuda = false;
    private static final long CHARLA_QUIETA_MS = 40000;
    private static final String K_VOZ_PUERTO = "voz_puerto";

    /** Si en un rato nadie le habla, corta: la tablet escucha una cocina entera y no puede quedar prendida sola. */
    private final Runnable charlaQuieta = new Runnable() {
        @Override public void run() {
            if (charla != null && charla.viva()) {
                charla.cerrar();
                status.setText("Corté porque no escuché nada. Tocá Hablar cuando quieras.");
            }
        }
    };

    private boolean conCharla() { return cara != null; }

    private void despierta() {
        h.removeCallbacks(charlaQuieta);
        h.postDelayed(charlaQuieta, CHARLA_QUIETA_MS);
    }

    /** El mismo servidor de la heladera, en el puerto de la charla, con el mismo token. */
    private String urlCharla(boolean muda) {
        android.net.Uri u = android.net.Uri.parse(api.base());
        int puerto = prefs().getInt(K_VOZ_PUERTO, 8796);
        String tok = api.token();
        try { tok = java.net.URLEncoder.encode(tok, "UTF-8"); } catch (Exception ignored) { }
        return "ws://" + u.getHost() + ":" + puerto + "/talk?token=" + tok + (muda ? "&mute=1" : "");
    }

    private void caraEscucha() {
        estado("escucha");
        btnTalk.setBackgroundResource(R.drawable.btn_rec);
        caraBoton(0, 1, Color.parseColor("#C7F536"), "Te escucho");
    }

    private void alternarCharla(boolean muda) {
        if (charla != null && charla.viva()) {
            charla.cerrar();
            return;
        }
        if (!hasMic()) { ensureMic(); return; }
        speaker.stop();
        stopWake();
        charlaMuda = muda;
        speakReply = !muda;
        charlaDicho = "";
        globo("", false);
        if (hecho != null) hecho.setVisibility(View.GONE);
        estado("piensa");
        caraBoton(0, 0, Color.parseColor("#C7F536"), "Conectando…");
        status.setText("");
        charla = new Charla(new Charla.Avisos() {
            @Override public void lista() {
                beep();
                caraEscucha();
                status.setText(charlaMuda ? "Te escucho. Contesto por pantalla." : "Te escucho.");
                despierta();
            }
            @Override public void aviso(JSONObject f) { avisoCharla(f); }
            @Override public void nivel(double n) {
                if (caraOndas != null && caraOndas.getVisibility() == View.VISIBLE && !"Hablando".contentEquals(caraTexto.getText()))
                    caraOndas.nivel(n);
            }
            @Override public void cortada(String porque) {
                h.removeCallbacks(charlaQuieta);
                charla = null;
                estado("reposo");
                if ("token".equals(porque)) status.setText("Falta emparejar la tablet: Más → Ajustes.");
                else if ("sin conexión".equals(porque)) status.setText("No llego a la heladera. ¿Está prendido el servidor de casa?");
                else if ("micrófono".equals(porque)) status.setText("Se cortó el micrófono. Tocá Hablar de nuevo.");
                else if (!porque.isEmpty()) status.setText("Se cortó la charla. Tocá Hablar de nuevo.");
                resumeWake();
                refresh();
            }
        });
        charla.empezar(urlCharla(muda));
    }

    private void avisoCharla(JSONObject f) {
        String tipo = f.optString("type");
        String txt = f.optString("text", "");
        switch (tipo) {
            case "partial":
                globo(txt.isEmpty() ? "" : txt + "…", false);
                despierta();
                break;
            case "heard":
                globo(txt, true);
                if (hecho != null) hecho.setVisibility(View.GONE);
                charlaDicho = "";
                despierta();
                break;
            case "state":
                String st = f.optString("state");
                if ("listening".equals(st)) caraEscucha();
                else if ("thinking".equals(st)) estado("piensa");
                else if ("speaking".equals(st)) estado("habla");
                break;
            case "saying":
                if (f.optBoolean("filler", false)) {
                    status.setText(txt);
                } else {
                    charlaDicho = charlaDicho.isEmpty() ? txt : charlaDicho + " " + txt;
                    status.setText(charlaDicho);
                }
                if (!charlaMuda) estado("habla");
                despierta();
                break;
            case "tool":
                chipTool(f.optString("name"), f.optJSONObject("args"), f.optBoolean("ok", true));
                refresh();
                break;
            case "interrupted":
                caraEscucha();
                break;
            case "end":
                if (charla != null) charla.despedir();
                break;
            default:
                break;
        }
    }

    /** "✓ 3 anotados" arriba de la respuesta, según lo que hizo la herramienta. */
    private void chipTool(String nombre, JSONObject args, boolean ok) {
        if (hecho == null || !ok || args == null) return;
        String txt = "";
        if ("add_items".equals(nombre)) {
            int n = args.optJSONArray("items") == null ? 0 : args.optJSONArray("items").length();
            if (n > 0) txt = "✓ " + n + (n == 1 ? " anotado" : " anotados");
        } else if ("check_off_items".equals(nombre) || "remove_items".equals(nombre)) {
            int n = args.optJSONArray("names") == null ? 1 : args.optJSONArray("names").length();
            boolean tacha = "check_off_items".equals(nombre);
            txt = "✓ " + n + (tacha ? (n == 1 ? " tachado" : " tachados") : (n == 1 ? " borrado" : " borrados"));
        } else if ("clear_list".equals(nombre)) {
            txt = args.optBoolean("only_done", false) ? "✓ saqué lo comprado" : "✓ lista vacía";
        } else if ("group_by_aisle".equals(nombre)) {
            txt = args.optBoolean("undo", false) ? "✓ lista junta de nuevo" : "✓ separada por góndola";
        } else if ("send_list".equals(nombre)) {
            txt = "✓ lista enviada";
        }
        hecho.setText(txt);
        hecho.setVisibility(txt.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Tablet: Escucharme vive en el menú Más; el botón aparece sólo para Tachar (elegir), No (pregunta) o Parar. */
    private void verQuiet() {
        if (cara == null) return;
        btnQuiet.setVisibility(eligiendo || asking || (recording && !speakReply) ? View.VISIBLE : View.GONE);
    }

    private void setRecordingUi(boolean rec, boolean voice) {
        recording = rec;
        TextView b = voice ? btnTalk : btnQuiet;
        TextView other = voice ? btnQuiet : btnTalk;
        if (rec) {
            b.setBackgroundResource(R.drawable.btn_rec);
            b.setTextColor(Color.WHITE);
            if (b == btnTalk && cara != null) caraBoton(0, 1, Color.parseColor("#C7F536"), "Te escucho");
            else Iconos.boton(b, 0, "#FFFFFF", "■  Parar");
            other.setAlpha(0.35f);
        } else {
            btnTalk.setBackgroundResource(R.drawable.btn_talk);
            btnTalk.setTextColor(Color.BLACK);
            botonHablar(R.drawable.ic_mic, "#0B0F0A", "Hablar");
            btnQuiet.setBackgroundResource(R.drawable.btn_quiet);
            btnQuiet.setTextColor(Color.parseColor("#6FD3FF"));
            Iconos.boton(btnQuiet, R.drawable.ic_oreja, "#6FD3FF", "Escucharme");
            btnTalk.setAlpha(1f);
            btnQuiet.setAlpha(1f);
        }
        estado(rec ? "escucha" : "reposo");
        verQuiet();
    }

    // ------------------------------------------------------------ manos libres

    private void setWake(boolean on) {
        wakeEnabled = on;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(K_WAKE, on).apply();
        paintWakeToggle();
        if (on) {
            if (!hasMic()) { ensureMic(); return; }
            startWake();
            status.setText("Manos libres activado. Decí «ok tableta» y te escucho.");
        } else {
            stopWake();
            status.setText("Manos libres apagado.");
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /** Tablet: tarjetas por góndola, en dos columnas. */
    private void setGondola() {
        porGondola = true;
        porListas = false;
        prefs().edit().putBoolean(K_GONDOLA, true).putBoolean(K_MODO, false).apply();
        lastRev = "";
        renderList(ultimos);
        scroll.post(new Runnable() { @Override public void run() { scroll.scrollTo(0, 0); } });
    }

    private boolean tarjetas() { return segGondola != null && porGondola && !porListas; }

    /** El texto de lo elegido: oscuro sobre lima en el teléfono, claro sobre gris en la tablet. */
    private int colorElegido() { return Color.parseColor(conteoGrande() ? "#F3F6EE" : "#0B0F0A"); }

    private void pintarSeg(TextView t, boolean sel) {
        if (t == null) return;
        t.setBackgroundResource(sel ? R.drawable.pill_sel : 0);
        t.setTextColor(sel ? colorElegido() : Color.parseColor("#8A9280"));
    }

    private void setModo(boolean listas) {
        porListas = listas;
        porGondola = false;
        prefs().edit().putBoolean(K_MODO, listas).putBoolean(K_GONDOLA, false).apply();
        lastRev = "";
        renderList(ultimos);
        scroll.post(new Runnable() { @Override public void run() { scroll.scrollTo(0, 0); } });
    }

    private void paintWakeToggle() {
        wakeToggle.setText("manos libres");
        wakeToggle.setTextColor(Color.parseColor(wakeEnabled ? "#C7F536" : "#8A9280"));
        wakeToggle.setBackgroundResource(wakeEnabled ? R.drawable.pill_on : R.drawable.pill);
        Iconos.poner(wakeToggle, R.drawable.ic_oreja, wakeEnabled ? "#C7F536" : "#6A7558");
    }

    private void startWake() {
        android.util.Log.d("heladera-life", "startWake enabled=" + wakeEnabled);
        if (!wakeEnabled || !hasMic()) return;
        if (recorder != null && recorder.isRunning()) return;
        if (waker != null && waker.isRunning()) return;
        waker = new Recorder(this, true);
        waker.start();
    }

    private void stopWake() {
        if (waker != null) {
            waker.stop();
            waker = null;
        }
    }

    /** Vuelve a escuchar la palabra de activación cuando terminó de contestar. */
    private void resumeWake() {
        h.postDelayed(new Runnable() {
            @Override public void run() { startWake(); }
        }, 400);
    }

    private long lastWakeCheck = 0;

    private void checkWake(byte[] wav) {
        // Un mínimo entre consultas: sin esto, una cocina ruidosa dispara una
        // transcripción por segundo y el servidor no hace otra cosa.
        long now = System.currentTimeMillis();
        if (now - lastWakeCheck < 1500) return;
        lastWakeCheck = now;
        api.postAudio("/api/wake", wav, null, new Api.Cb() {
            @Override public void ok(JSONObject res) {
                if (!res.optBoolean("awake", false)) return;
                stopWake();
                speakReply = true;
                // Let the wake recorder release the single microphone before the
                // command recorder acquires it. The beep is emitted at handoff.
                h.postDelayed(new Runnable() {
                    @Override public void run() {
                        startCommandAfterWake();
                        beep();
                    }
                }, Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1 ? 500 : 140);
            }
            @Override public void fail(String e) { /* red intermitente: seguimos escuchando */ }
        });
    }

    private void startCommandAfterWake() {
        android.util.Log.d("heladera-life", "startCommandAfterWake");
        api.postJson("/api/warm", null, null);
        if (recorder != null && recorder.isRunning()) return;
        conversando = true;
        vueltas = 0;
        sinEntender = 0;
        recorder = new Recorder(MainActivity.this, false);
        recorder.start();
        setRecordingUi(true, true);
        status.setText("Escuchando…");
    }

    // --------------------------------------------------------------- acciones

    private void sendList() {
        if (pending == 0) { toast("La lista está vacía"); return; }
        status.setText("Mandando la lista…");
        api.postJson("/api/send", json("channel", "telegram"), new Api.Cb() {
            @Override public void ok(JSONObject res) {
                JSONObject s = res.optJSONObject("sent");
                boolean ok = s != null && s.optBoolean("ok", false);
                status.setText(ok ? "Lista enviada por Telegram" : "No se pudo enviar por Telegram");
                applyState(res);
            }
            @Override public void fail(String e) { status.setText("Error al enviar: " + e); }
        });
    }

    // --------------------------------------------------------- elegir varias

    /**
     * Modo elegir: los cuatro botones cambian de oficio.
     *
     * Es para juntar cosas sueltas y mandarlas a una sublista de un saque, sin
     * tener que dictarlas de nuevo una por una.
     */
    private void modoElegir(boolean on) {
        eligiendo = on;
        elegidos.clear();
        pickToggle.setText(on ? "Listo" : "Elegir");
        pickToggle.setTextColor(on ? colorElegido() : Color.parseColor("#8A9280"));
        pickToggle.setBackgroundResource(on ? R.drawable.pill_sel : R.drawable.pill);
        allToggle.setVisibility(on ? View.VISIBLE : View.GONE);
        findViewById(R.id.seg).setVisibility(on ? View.GONE : View.VISIBLE);
        pintarTodas();
        lastRev = "";
        pintarElegir();
        renderList(ultimos);
    }

    private void pintarElegir() {
        if (eligiendo) {
            botonHablar(R.drawable.ic_lista, "#0B0F0A", "Agrupar");
            Iconos.boton(btnQuiet, R.drawable.ic_tachar, "#6FD3FF", "Tachar");
            Iconos.boton(btnSend, R.drawable.ic_papelera, "#C7F536", "Borrar", chicosArriba);
            Iconos.boton(btnMenu, R.drawable.ic_tachar, "#C7F536", "Listo", chicosArriba);
            status.setText(elegidos.isEmpty()
                    ? "Tocá las cosas que querés juntar."
                    : elegidos.size() + " elegida" + (elegidos.size() == 1 ? "" : "s") + ".");
            pickToggle.setText("Listo");
        } else {
            botonHablar(R.drawable.ic_mic, "#0B0F0A", "Hablar");
            Iconos.boton(btnQuiet, R.drawable.ic_oreja, "#6FD3FF", "Escucharme");
            Iconos.boton(btnSend, R.drawable.ic_enviar, "#C7F536", "Mandar", chicosArriba);
            Iconos.boton(btnMenu, R.drawable.ic_menu, "#C7F536", "Más", chicosArriba);
            setRecordingUi(false, true);
            status.setText("Tocá Hablar y decime qué falta.");
        }
        verQuiet();
    }

    /** Los que se pueden elegir: lo pendiente que está a la vista. */
    private java.util.ArrayList<Integer> seleccionables() {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        if (ultimos == null) return out;
        for (int i = 0; i < ultimos.length(); i++) {
            JSONObject o = ultimos.optJSONObject(i);
            if (o == null || o.optBoolean("done", false)) continue;
            if (porListas && !solapa.equals(listaDe(o))) continue;
            out.add(Integer.valueOf(o.optInt("id")));
        }
        return out;
    }

    private boolean todasElegidas() {
        java.util.ArrayList<Integer> v = seleccionables();
        return !v.isEmpty() && elegidos.containsAll(v);
    }

    private void pintarTodas() {
        if (!eligiendo) return;
        allToggle.setText(todasElegidas() ? "Ninguna" : "Todas");
    }

    private JSONArray idsElegidos() {
        JSONArray a = new JSONArray();
        for (Integer i : elegidos) a.put(i.intValue());
        return a;
    }

    private void sobreElegidos(String what, final String msg) {
        if (elegidos.isEmpty()) { toast("Elegí algo primero"); return; }
        JSONObject b = new JSONObject();
        try { b.put("ids", idsElegidos()); b.put("what", what); } catch (Throwable ignored) { }
        api.postJson("/api/items/bulk", b, new Api.Cb() {
            @Override public void ok(JSONObject res) { modoElegir(false); status.setText(msg); lastRev = ""; applyState(res); }
            @Override public void fail(String e) { toast("No pude: " + e); }
        });
    }

    /** A qué sublista mandarlas: las que ya existen, o una nueva. */
    private void elegirLista() {
        if (elegidos.isEmpty()) { toast("Elegí algo primero"); return; }
        Hoja h = new Hoja(this, "Mandar a una lista", elegidos.size() + " cosas elegidas");
        java.util.LinkedHashSet<String> vistas = new java.util.LinkedHashSet<>();
        if (ultimos != null) {
            for (int i = 0; i < ultimos.length(); i++) {
                JSONObject o = ultimos.optJSONObject(i);
                String g = o == null ? null : grupo(o);
                if (g != null) vistas.add(g);
            }
        }
        for (final String g : vistas) {
            h.opcion(R.drawable.ic_lista, g, new Hoja.Accion() {
                @Override public void correr() { mandarALista(g); }
            });
        }
        h.opcion(R.drawable.ic_menu, "Una lista nueva…", new Hoja.Accion() {
            @Override public void correr() { nuevaLista(); }
        });
        h.opcion(R.drawable.ic_volver, "Sacarlas de toda lista", new Hoja.Accion() {
            @Override public void correr() { mandarALista(""); }
        });
        h.mostrar();
    }

    private void nuevaLista() {
        final EditText in = new EditText(this);
        in.setHint("asado, cumple, desayuno…");
        new AlertDialog.Builder(this)
                .setTitle("¿Cómo se llama?")
                .setView(in)
                .setPositiveButton("Crear", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        mandarALista(in.getText().toString().trim());
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void mandarALista(final String nombre) {
        JSONObject b = new JSONObject();
        try { b.put("ids", idsElegidos()); b.put("group", nombre); } catch (Throwable ignored) { }
        api.postJson("/api/items/group", b, new Api.Cb() {
            @Override public void ok(JSONObject res) {
                modoElegir(false);
                status.setText(nombre.isEmpty() ? "Las saqué de la lista" : "A la lista de " + nombre);
                lastRev = "";
                applyState(res);
            }
            @Override public void fail(String e) { toast("No pude: " + e); }
        });
    }

    /**
     * El menú de abajo.
     *
     * Antes los ajustes vivían arriba, apretados entre las pastillas, y las
     * acciones de la lista en un diálogo blanco del sistema. Ahora todo sube
     * desde el mismo lugar y con los mismos colores.
     */
    private void menuAbajo() {
        Hoja hoja = new Hoja(this, "La lista", null);
        if (cara != null) hoja.opcion(R.drawable.ic_oreja, "Escucharme (contesta sólo en pantalla)", new Hoja.Accion() {
            @Override public void correr() { toggleRecording(false); }
        });
        hoja.opcion(R.drawable.ic_enviar, "Mandar por Telegram", new Hoja.Accion() {
                    @Override public void correr() { sendList(); }
                })
                .opcion(R.drawable.ic_enviar, "Mandar por WhatsApp", new Hoja.Accion() {
                    @Override public void correr() { post("/api/send", json("channel", "roby"), "Se manda por WhatsApp"); }
                })
                .opcion(R.drawable.ic_orden, "Ordenar la lista", new Hoja.Accion() {
                    @Override public void correr() { hojaOrden(); }
                })
                .opcion(R.drawable.ic_carpeta, "Separar en listas por góndola", new Hoja.Accion() {
                    @Override public void correr() { post("/api/list/organizar", null, "Separé por góndola"); }
                })
                .opcion(R.drawable.ic_volver, "Deshacer lo último", new Hoja.Accion() {
                    @Override public void correr() { post("/api/undo", null, "Volví atrás"); }
                })
                .opcion(R.drawable.ic_changuito, "Cerrar la compra", new Hoja.Accion() {
                    @Override public void correr() { post("/api/list/close", null, "Compra cerrada"); }
                })
                .opcion(R.drawable.ic_papelera, "Vaciar la lista", new Hoja.Accion() {
                    @Override public void correr() { post("/api/list/clear", json("scope", "all"), "Lista vacía"); }
                })
                .opcion(R.drawable.ic_ayuda, "Cómo se usa", new Hoja.Accion() {
                    @Override public void correr() { Tutorial.mostrar(MainActivity.this, getSharedPreferences(PREFS, MODE_PRIVATE)); }
                })
                .opcion(R.drawable.ic_ajustes, "Ajustes", new Hoja.Accion() {
                    @Override public void correr() { settingsDialog(); }
                })
                .mostrar();
    }

    private void ordenar(final String by, final String msg) {
        JSONObject b = json("by", by);
        api.postJson("/api/list/sort", b, new Api.Cb() {
            @Override public void ok(JSONObject res) { status.setText(msg); lastRev = ""; applyState(res); }
            @Override public void fail(String e) { toast("No pude ordenar"); }
        });
    }

    private void hojaOrden() {
        new Hoja(this, "Ordenar la lista",
                "La lista queda como vos la dejás. Esto la acomoda una vez y después no se mueve más sola.")
                .opcion(R.drawable.ic_reloj, "Fecha", new Hoja.Accion() {
                    @Override public void correr() { ordenar("fecha", "Ordenada por fecha"); }
                })
                .opcion(R.drawable.ic_estrella, "Destacadas recientemente", new Hoja.Accion() {
                    @Override public void correr() { ordenar("star", "Primero las destacadas"); }
                })
                .opcion(R.drawable.ic_lista, "Título", new Hoja.Accion() {
                    @Override public void correr() { ordenar("abc", "Ordenada de la A a la Z"); }
                })
                .opcion(R.drawable.ic_changuito, "Categoría", new Hoja.Accion() {
                    @Override public void correr() { ordenar("cat", "Ordenada por categoría"); }
                })
                .mostrar();
    }

    /** Lo que se puede hacer con una lista entera. */
    private void menuLista(final String nombre, final boolean propia) {
        final String base = "/api/groups/" + android.net.Uri.encode(nombre) + "/";
        Hoja h = new Hoja(this, nombre, null)
                .opcion(R.drawable.ic_enviar, "Mandar por Telegram", new Hoja.Accion() {
                    @Override public void correr() { mandarGrupo(propia ? nombre : null, "telegram"); }
                })
                .opcion(R.drawable.ic_chat, "Mandar por WhatsApp", new Hoja.Accion() {
                    @Override public void correr() { mandarGrupo(propia ? nombre : null, "roby"); }
                })
                .opcion(R.drawable.ic_orden, "Ordenar", new Hoja.Accion() {
                    @Override public void correr() { hojaOrden(); }
                });
        if (propia) {
            h.opcion(R.drawable.ic_tachar, "Tachar todo", new Hoja.Accion() {
                @Override public void correr() { post(base + "done", null, "Tildé " + nombre); }
            }).opcion(R.drawable.ic_volver, "Destachar todo", new Hoja.Accion() {
                @Override public void correr() { post(base + "undone", null, "Destildé " + nombre); }
            }).opcion(R.drawable.ic_lista, "Desarmar la lista", new Hoja.Accion() {
                @Override public void correr() { post(base + "ungroup", null, "Desarmé " + nombre); }
            }).opcion(R.drawable.ic_papelera, "Borrar la lista entera", new Hoja.Accion() {
                @Override public void correr() { post(base + "remove", null, "Borré " + nombre); }
            });
        }
        h.mostrar();
    }

    private void mandarGrupo(String nombre, String canal) {
        JSONObject b = new JSONObject();
        try {
            b.put("channel", canal);
            if (nombre != null) b.put("group", nombre);
        } catch (Throwable ignored) { }
        post("/api/send", b, nombre == null ? "Mandé la lista" : "Mandé " + nombre);
    }

    private void post(String path, JSONObject body, final String okMsg) {
        api.postJson(path, body, new Api.Cb() {
            @Override public void ok(JSONObject res) { status.setText(okMsg); lastRev = ""; applyState(res); }
            @Override public void fail(String e) { status.setText("Error: " + e); }
        });
    }

    private void settingsDialog() {
        final EditText inServer = new EditText(this);
        inServer.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        inServer.setText(api.base());
        inServer.setHint("http://192.168.1.10:8790");

        final EditText inToken = new EditText(this);
        inToken.setInputType(InputType.TYPE_CLASS_TEXT);
        inToken.setText(api.token());
        inToken.setHint("código de 8 caracteres, o vacío");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(12), dp(24), 0);
        box.addView(label("Servidor"));
        box.addView(inServer);
        box.addView(label("Código"));
        box.addView(inToken);
        box.addView(label("Más fácil: abrí " + api.base() + "/pair en esta tablet"));

        new AlertDialog.Builder(this)
                .setTitle("Ajustes")
                .setView(box)
                .setPositiveButton("Guardar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String url = inServer.getText().toString().trim();
                        String tok = inToken.getText().toString().trim();
                        if (url.isEmpty()) return;
                        saveServer(url, tok);
                        toast("Guardado");
                    }
                })
                .setNeutralButton("Probar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final String url = inServer.getText().toString().trim();
                        final String tok = inToken.getText().toString().trim();
                        new Api(url, tok).get("/api/health", new Api.Cb() {
                            @Override public void ok(JSONObject res) {
                                toast("Anda: " + res.optInt("pending") + " pendientes");
                            }
                            @Override public void fail(String e) { toast("No responde: " + e); }
                        });
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.parseColor("#8A9280"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    /**
     * El código que se pega en Ajustes son los primeros 8 caracteres del token.
     * Se lo cambiamos por el token entero contra /api/pair/claim, así nadie
     * tiene que tipear treinta y dos caracteres en una pantalla táctil.
     */
    private void saveServer(final String url, final String code) {
        if (code.isEmpty()) {
            applyServer(url, code);
            return;
        }
        JSONObject body = new JSONObject();
        try { body.put("code", code); } catch (Throwable ignored) { }
        new Api(url, "").postJson("/api/pair/claim", body, new Api.Cb() {
            @Override public void ok(JSONObject res) { applyServer(url, res.optString("token", code)); }
            @Override public void fail(String e) { applyServer(url, code); }
        });
    }

    private void applyServer(String url, String token) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(K_SERVER, url).putString(K_TOKEN, token).apply();
        api = new Api(url, token);
        lastRev = "";
        refresh();
        api.postJson("/api/warm", null, null);
    }

    // ---------------------------------------------------------------- emparejar

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePairLink(intent);
    }

    /**
     * La página /pair del servidor abre heladera://pair?server=…&token=… y con
     * eso la app queda configurada sola. Es para no tener que tipear una IP y un
     * token en una tablet pegada a la heladera.
     */
    private void handlePairLink(android.content.Intent intent) {
        if (intent == null || intent.getData() == null) return;
        handlePairUri(intent.getData());
    }

    private void handlePairUri(android.net.Uri u) {
        if (!"heladera".equals(u.getScheme())) return;
        String server = u.getQueryParameter("server");
        String token = u.getQueryParameter("token");
        String code = u.getQueryParameter("code");
        if (server == null || server.trim().isEmpty()) return;
        if ((token == null || token.trim().isEmpty()) && code != null && !code.trim().isEmpty()) {
            if (status != null) status.setText("Vinculando Heladera…");
            saveServer(server.trim(), code.trim());
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(K_SERVER, server.trim())
                .putString(K_TOKEN, token == null ? "" : token.trim())
                .apply();
        api = new Api(server.trim(), token == null ? "" : token.trim());
        lastRev = "";
        refresh();
        api.postJson("/api/warm", null, null);
        toast("Conectada a " + server.trim());
        if (status != null) status.setText("Conectada. Tocá Hablar y decime qué falta.");
    }

    private void showPairWelcome() {
        new AlertDialog.Builder(this)
                .setTitle("Conectar Heladera")
                .setMessage("Escaneá QR mostrado en Ajustes web. La app queda vinculada sola.")
                .setPositiveButton("Escanear QR", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { scanPairQr(); }
                })
                .setNegativeButton("Más tarde", null)
                .show();
    }

    private void scanPairQr() {
        new IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Escaneá QR de Heladera")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        String content = result.getContents();
        if (content == null) return;
        try {
            android.net.Uri uri = android.net.Uri.parse(content);
            if (!"heladera".equals(uri.getScheme())) throw new IllegalArgumentException();
            handlePairUri(uri);
        } catch (Throwable ignored) {
            toast("Ese QR no es de Heladera");
        }
    }

    // -------------------------------------------------------------- utilidades

    private static JSONObject json(String k, String v) {
        JSONObject o = new JSONObject();
        try { o.put(k, v); } catch (Throwable ignored) { }
        return o;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** Confirms the utterance ended without repeating words from the reply. */
    private void playAck() {
        try {
            android.media.ToneGenerator tg = new android.media.ToneGenerator(
                    android.media.AudioManager.STREAM_MUSIC, 45);
            tg.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 70);
            h.postDelayed(new Runnable() { @Override public void run() { tg.release(); } }, 250);
        } catch (Throwable ignored) { }
    }

    /** Un bip corto avisa que ya podés hablar, sin esperar a que cargue una voz. */
    private void beep() {
        try {
            android.media.ToneGenerator tg = new android.media.ToneGenerator(
                    android.media.AudioManager.STREAM_MUSIC, 70);
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120);
            h.postDelayed(new Runnable() { @Override public void run() { tg.release(); } }, 400);
        } catch (Throwable ignored) { }
    }

    /**
     * Cada segundo revisa que el botón diga lo que realmente está pasando.
     * Sin esto, cualquier camino raro (una respuesta que falla, el micrófono que
     * se corta) deja el botón en PARAR para siempre y hay que reiniciar la app.
     */
    private int zombi = 0;

    /**
     * Reconcilia la pantalla con el micrófono, en los dos sentidos.
     *
     * Que el grabador siga vivo con la pantalla en "Hablar" es el caso que
     * duele: cada toque se interpreta como "parar" y la tablet queda muda.
     */
    private final Runnable uiWatchdog = new Runnable() {
        @Override public void run() {
            boolean rec = recorder != null && recorder.isRunning();
            if (!rec && recording && !asking) setRecordingUi(false, speakReply);
            if (rec && !recording) {
                if (++zombi >= 3) {
                    android.util.Log.w("heladera-life", "grabador zombi: lo corto (watchdog)");
                    recorder.stop();
                    recorder = null;
                    zombi = 0;
                    resumeWake();
                }
            } else {
                zombi = 0;
            }
            h.postDelayed(this, 1000);
        }
    };
}

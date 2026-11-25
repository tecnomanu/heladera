package ar.tecnomanu.heladera;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * El paseo de la primera vez.
 *
 * Los botones de la heladera no se explican solos: HABLAR contesta en voz alta
 * y ESCUCHARME no, y eso hay que contarlo una vez. Se muestra solo el primer
 * arranque y después queda en el menú, en "Cómo se usa".
 */
public class Tutorial {

    private static final String VISTO = "tutorial_visto";

    private static final String[][] PASOS = {
            {"mic", "Tocá Hablar y hablá",
             "Apretá Hablar, decime qué falta y listo. Te contesta en voz alta.\n\n"
             + "Mientras hablás vas viendo abajo lo que entendí. Si te trabaste, "
             + "el mismo botón dice Parar."},
            {"oreja", "Escucharme es igual, pero callada",
             "Escucha y anota, pero no contesta en voz alta.\n\n"
             + "Para la noche, o cuando hay alguien durmiendo en la casa."},
            {"changuito", "Decile lo que se te ocurra",
             "\"anotame dos litros de leche descremada\"\n"
             + "\"ya compré el pan\"\n"
             + "\"borrá la zanahoria\"\n"
             + "\"qué falta comprar\"\n\n"
             + "Entiende como hablás: metele, guardame, acordate de, traeme."},
            {"lista", "Sublistas para una ocasión",
             "\"armame una lista para el asado con chorizo, vacío y carbón\"\n\n"
             + "Queda agrupado adentro de la misma lista. Tocando el título "
             + "podés tacharla entera, mandarla sola o desarmarla."},
            {"grip", "La lista queda como vos la dejás",
             "Tachá tocando el círculo: el renglón no se mueve de lugar.\n\n"
             + "Para reordenar, mantené apretado un renglón y arrastralo. "
             + "O usá los puntitos de la izquierda."},
            {"mic", "Sin tocar nada",
             "Prendé \"manos libres\" arriba y decí \"che heladera\" o "
             + "\"ok tableta\" para que te escuche sola.\n\n"
             + "Tenela enchufada y no se apaga nunca."},
    };

    public static boolean pendiente(SharedPreferences p) {
        return !p.getBoolean(VISTO, false);
    }

    public static void mostrar(final Activity a, final SharedPreferences p) {
        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final int[] paso = {0};

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B0F0A"));
        root.setPadding(dp(a, 34), dp(a, 30), dp(a, 34), dp(a, 26));

        final TextView saltear = new TextView(a);
        saltear.setText("saltear");
        saltear.setTextColor(Color.parseColor("#8A9280"));
        saltear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        saltear.setGravity(Gravity.RIGHT);
        saltear.setPadding(0, 0, 0, dp(a, 10));
        root.addView(saltear);

        final ImageView dibujo = new ImageView(a);
        LinearLayout.LayoutParams lpd = new LinearLayout.LayoutParams(dp(a, 92), dp(a, 92));
        lpd.gravity = Gravity.CENTER_HORIZONTAL;
        lpd.bottomMargin = dp(a, 24);
        dibujo.setLayoutParams(lpd);
        root.addView(dibujo);

        final TextView titulo = new TextView(a);
        titulo.setTextColor(Color.parseColor("#C7F536"));
        titulo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 27);
        titulo.setTypeface(null, android.graphics.Typeface.BOLD);
        titulo.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(titulo);

        final TextView cuerpo = new TextView(a);
        cuerpo.setTextColor(Color.parseColor("#8A9280"));
        cuerpo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        cuerpo.setLineSpacing(dp(a, 4), 1f);
        cuerpo.setGravity(Gravity.CENTER_HORIZONTAL);
        cuerpo.setPadding(0, dp(a, 16), 0, 0);
        LinearLayout.LayoutParams lpc = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        cuerpo.setLayoutParams(lpc);
        root.addView(cuerpo);

        final TextView puntos = new TextView(a);
        puntos.setGravity(Gravity.CENTER);
        puntos.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        puntos.setPadding(0, 0, 0, dp(a, 16));
        root.addView(puntos);

        final TextView boton = new TextView(a);
        boton.setBackgroundResource(R.drawable.btn_talk);
        boton.setTextColor(Color.parseColor("#0B0F0A"));
        boton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        boton.setTypeface(null, android.graphics.Typeface.BOLD);
        boton.setGravity(Gravity.CENTER);
        boton.setPadding(0, dp(a, 20), 0, dp(a, 20));
        root.addView(boton);

        final Runnable pintar = new Runnable() {
            @Override public void run() {
                String[] s = PASOS[paso[0]];
                dibujo.setImageResource(a.getResources().getIdentifier(
                        "ic_" + s[0], "drawable", a.getPackageName()));
                dibujo.setColorFilter(Color.parseColor("#C7F536"));
                titulo.setText(s[1]);
                cuerpo.setText(s[2]);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < PASOS.length; i++) sb.append(i == paso[0] ? "●  " : "○  ");
                puntos.setText(sb.toString().trim());
                puntos.setTextColor(Color.parseColor("#4A5340"));
                boton.setText(paso[0] == PASOS.length - 1 ? "DALE, ENTENDÍ" : "SIGUIENTE");
                saltear.setVisibility(paso[0] == PASOS.length - 1 ? View.INVISIBLE : View.VISIBLE);
            }
        };

        final Runnable cerrar = new Runnable() {
            @Override public void run() {
                p.edit().putBoolean(VISTO, true).apply();
                d.dismiss();
            }
        };

        boton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (paso[0] >= PASOS.length - 1) cerrar.run();
                else { paso[0]++; pintar.run(); }
            }
        });
        saltear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cerrar.run(); }
        });

        // Deslizar también pasa de pantalla: es lo que la mano espera hacer.
        final GestureDetector g = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null || Math.abs(vx) < 400) return false;
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) < dp(a, 50)) return false;
                if (dx < 0 && paso[0] < PASOS.length - 1) paso[0]++;
                else if (dx > 0 && paso[0] > 0) paso[0]--;
                else return false;
                pintar.run();
                return true;
            }
        });
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return g.onTouchEvent(e); }
        });

        pintar.run();
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        d.setCanceledOnTouchOutside(false);
        d.show();
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}

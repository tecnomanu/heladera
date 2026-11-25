package ar.tecnomanu.heladera;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.HashMap;

/**
 * Las letras de la app, empaquetadas en assets/fonts (ver LEEME.md ahí).
 *
 * Android 7 no elige el grosor de una fuente variable, por eso van archivos estáticos, uno por grosor.
 * Si alguno falta, queda la letra del sistema: la app nunca se rompe por una fuente.
 */
public final class Fuentes {
    private static final HashMap<String, Typeface> cache = new HashMap<>();

    private Fuentes() { }

    private static Typeface de(Context c, String archivo) {
        if (cache.containsKey(archivo)) return cache.get(archivo);
        Typeface t = null;
        try {
            t = Typeface.createFromAsset(c.getAssets(), "fonts/" + archivo + ".ttf");
        } catch (Throwable ignored) { }
        cache.put(archivo, t);
        return t;
    }

    /** Bricolage Grotesque: la marca, "Faltan N", la hora y el botón Hablar. */
    public static Typeface titulo(Context c, int peso) {
        return de(c, "bricolage-" + (peso >= 800 ? 800 : 700));
    }

    /** Figtree, de 400 a 700. */
    public static Typeface cuerpo(Context c, int peso) {
        int p = peso <= 400 ? 400 : peso <= 500 ? 500 : peso <= 600 ? 600 : 700;
        return de(c, "figtree-" + p);
    }

    public static void poner(TextView t, Typeface f) {
        if (t != null && f != null) t.setTypeface(f);
    }

    /**
     * Pasa a Figtree todo lo que todavía tenga la letra del sistema: negrita a 700, el resto a 500.
     * Lo que ya tiene una de nuestras letras (porque se eligió a propósito) no se toca.
     */
    public static void aplicar(View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            Typeface actual = t.getTypeface();
            if (actual == null || !cache.containsValue(actual)) {
                boolean negrita = actual != null && actual.isBold();
                poner(t, cuerpo(v.getContext(), negrita ? 700 : 500));
            }
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) aplicar(g.getChildAt(i));
        }
    }
}

package ar.tecnomanu.heladera;

import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.List;

/**
 * Arrastrar para reordenar, sin RecyclerView ni AndroidX.
 *
 * La lista es un LinearLayout adentro de un ScrollView. Mientras arrastrás no
 * tocamos el árbol de vistas: al bloque lo movemos con translationY y a los
 * vecinos los corremos su alto. Recién al soltar cambiamos el orden de verdad;
 * si lo hiciéramos al revés parpadearía en cada cruce.
 *
 * Un bloque es un renglón suelto, o una sublista entera: el encabezado (tag
 * "H:nombre") arrastra todo lo suyo hasta su cierre (tag "F:nombre").
 *
 * Dos maneras de empezar, como en Google Tasks: mantener apretado el renglón,
 * o tocar la agarradera de puntitos, que arranca al toque.
 */
public class DragList {

    public interface OnDrop { void soltado(); }

    /** Mantener apretado un renglón: en vez de arrastrar, se elige. */
    public interface OnLargo { void apretado(View fila); }

    private static final int LONG_MS = 320;

    private final LinearLayout cont;
    private final ScrollView scroll;
    private final OnDrop onDrop;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final int slop;
    private final float dens;

    private final List<View> bloque = new ArrayList<>();
    private final List<View> fuera = new ArrayList<>();
    private boolean dragging, desdeAgarradera;
    private int i0, ins, alto, top0, scroll0;
    private float downRawY, ultimaRawY;
    private Runnable largo;

    public DragList(LinearLayout cont, ScrollView scroll, OnDrop onDrop) {
        this.cont = cont;
        this.scroll = scroll;
        this.onDrop = onDrop;
        this.slop = ViewConfiguration.get(cont.getContext()).getScaledTouchSlop();
        this.dens = cont.getResources().getDisplayMetrics().density;
        // Sin esto el renglón levantado se corta contra el borde de la lista.
        cont.setClipChildren(false);
        scroll.setClipChildren(false);
    }

    public boolean arrastrando() { return dragging; }

    public void enganchar(View fila, View agarradera) {
        enganchar(fila, agarradera, null);
    }

    /**
     * Arrastrar es de la agarradera; mantener apretado es para elegir.
     *
     * Los dos gestos no pueden ser el mismo: los puntitos están a la vista y no
     * se confunden, y mantener apretado para seleccionar es lo que la mano
     * espera en Android.
     */
    public void enganchar(final View fila, View agarradera, final OnLargo alLargo) {
        fila.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return tocar(fila, e, false, alLargo); }
        });
        if (agarradera != null) agarradera.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return tocar(fila, e, true, null); }
        });
    }

    private boolean tocar(final View fila, MotionEvent e, boolean agarradera, final OnLargo alLargo) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawY = ultimaRawY = e.getRawY();
                desdeAgarradera = agarradera;
                if (agarradera) {
                    arrancar(fila);
                } else {
                    largo = new Runnable() {
                        @Override public void run() {
                            largo = null;
                            if (alLargo != null) {
                                fila.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                                alLargo.apretado(fila);
                            } else {
                                arrancar(fila);
                            }
                        }
                    };
                    h.postDelayed(largo, LONG_MS);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                ultimaRawY = e.getRawY();
                // Si movió antes de tiempo quería scrollear: soltamos el gesto
                // y el ScrollView lo toma con su propio intercept.
                if (!dragging) {
                    if (Math.abs(e.getRawY() - downRawY) > slop) cancelarLargo();
                    return true;
                }
                mover();
                return true;

            case MotionEvent.ACTION_UP:
                cancelarLargo();
                if (dragging) { soltar(); return true; }
                if (!desdeAgarradera) fila.performClick();
                return true;

            case MotionEvent.ACTION_CANCEL:
                cancelarLargo();
                if (dragging) soltar();
                return true;
        }
        return false;
    }

    private void cancelarLargo() {
        if (largo != null) { h.removeCallbacks(largo); largo = null; }
    }

    private static boolean esCabecera(View v) {
        Object t = v.getTag();
        return t instanceof String && ((String) t).startsWith("H:");
    }

    private static boolean esCierre(View v) {
        Object t = v.getTag();
        return t instanceof String && ((String) t).startsWith("F:");
    }

    private void arrancar(View fila) {
        int n = cont.getChildCount();
        int inicio = cont.indexOfChild(fila);
        if (inicio < 0 || n < 2) return;

        int fin = inicio;
        if (esCabecera(fila)) {
            for (int k = inicio; k < n; k++) {
                fin = k;
                if (k > inicio && esCierre(cont.getChildAt(k))) break;
            }
        }
        bloque.clear();
        fuera.clear();
        for (int k = 0; k < n; k++) {
            View v = cont.getChildAt(k);
            if (k >= inicio && k <= fin) bloque.add(v); else fuera.add(v);
        }
        if (fuera.isEmpty()) return;

        i0 = inicio;
        ins = inicio;
        alto = 0;
        for (View v : bloque) alto += v.getHeight();
        top0 = bloque.get(0).getTop();
        scroll0 = scroll.getScrollY();
        dragging = true;
        scroll.requestDisallowInterceptTouchEvent(true);
        for (View v : bloque) {
            v.setBackgroundResource(R.drawable.row_drag);
            if (Build.VERSION.SDK_INT >= 21) v.setTranslationZ(10 * dens);
        }
        fila.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        h.postDelayed(bordes, 16);
    }

    private void mover() {
        if (bloque.isEmpty()) return;
        float dy = (ultimaRawY - downRawY) + (scroll.getScrollY() - scroll0);
        for (View v : bloque) v.setTranslationY(dy);

        float medio = top0 + dy + alto / 2f;
        int k = 0;
        for (View v : fuera) {
            if (v.getTop() + v.getHeight() / 2f < medio) k++; else break;
        }
        if (k == ins) return;
        ins = k;
        for (int j = 0; j < fuera.size(); j++) {
            float s = 0;
            if (ins > i0 && j >= i0 && j < ins) s = -alto;
            if (ins < i0 && j >= ins && j < i0) s = alto;
            fuera.get(j).animate().translationY(s).setDuration(140).start();
        }
    }

    /** Si llegás al borde de la lista, la lista sigue sola. */
    private final Runnable bordes = new Runnable() {
        @Override public void run() {
            if (!dragging) return;
            int[] loc = new int[2];
            scroll.getLocationOnScreen(loc);
            float y = ultimaRawY - loc[1];
            int borde = (int) (64 * dens), paso = (int) (9 * dens);
            if (y < borde && scroll.getScrollY() > 0) { scroll.scrollBy(0, -paso); mover(); }
            else if (y > scroll.getHeight() - borde) { scroll.scrollBy(0, paso); mover(); }
            h.postDelayed(this, 16);
        }
    };

    private void soltar() {
        dragging = false;
        h.removeCallbacks(bordes);
        scroll.requestDisallowInterceptTouchEvent(false);
        if (bloque.isEmpty()) return;

        for (View v : fuera) { v.animate().cancel(); v.setTranslationY(0); }
        for (View v : bloque) {
            v.setTranslationY(0);
            if (Build.VERSION.SDK_INT >= 21) v.setTranslationZ(0);
            v.setBackgroundColor(Color.TRANSPARENT);
        }
        if (ins != i0) {
            View ref = ins < fuera.size() ? fuera.get(ins) : null;
            for (View v : bloque) cont.removeView(v);
            int at = ref == null ? cont.getChildCount() : cont.indexOfChild(ref);
            for (int j = 0; j < bloque.size(); j++) cont.addView(bloque.get(j), at + j);
            onDrop.soltado();
        }
        bloque.clear();
        fuera.clear();
    }
}

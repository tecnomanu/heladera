package ar.tecnomanu.heladera;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ImageSpan;
import android.widget.TextView;

/**
 * Los íconos son vectores de un solo trazo gris; los teñimos donde hagan falta.
 *
 * Se generan desde tools/iconos.json, así el mismo dibujo va a la web (sprite
 * SVG) y al APK (VectorDrawable) y no se despintan entre sí.
 */
public final class Iconos {

    private Iconos() { }

    public static int deCategoria(String cat) {
        if (cat == null) return R.drawable.ic_otros;
        switch (cat) {
            // las góndolas canónicas del núcleo (en inglés) y los nombres viejos
            case "produce": case "verduleria": return R.drawable.ic_verduleria;
            case "butcher": return R.drawable.ic_carniceria;
            case "bakery": return R.drawable.ic_panaderia;
            case "dairy": return R.drawable.ic_heladera;
            case "pantry": return R.drawable.ic_almacen;
            case "drinks": return R.drawable.ic_bebidas;
            case "cleaning": return R.drawable.ic_limpieza;
            case "pharmacy": return R.drawable.ic_farmacia;
            case "pets": return R.drawable.ic_mascotas;
            case "carniceria": return R.drawable.ic_carniceria;
            case "panaderia": return R.drawable.ic_panaderia;
            case "heladera": return R.drawable.ic_heladera;
            case "almacen": return R.drawable.ic_almacen;
            case "bebidas": return R.drawable.ic_bebidas;
            case "limpieza": return R.drawable.ic_limpieza;
            case "farmacia": return R.drawable.ic_farmacia;
            case "mascotas": return R.drawable.ic_mascotas;
            default: return R.drawable.ic_otros;
        }
    }

    /**
     * Pone un ícono a la izquierda del texto y lo tiñe.
     *
     * El `mutate()` no es opcional: los VectorDrawable comparten estado entre
     * todas las vistas que usan el mismo recurso, así que sin copiarlo el
     * primero que se pinta le cambia el color a los demás (la oreja de "manos
     * libres" salía negra hasta que la tocabas).
     */
    public static void poner(TextView t, int icono, String color) {
        poner(t, icono, color, false);
    }

    public static void poner(TextView t, int icono, String color, boolean arriba) {
        boton(t, icono, color, t.getText().toString(), arriba);
    }

    /**
     * Ícono y palabra, juntos y centrados.
     *
     * Con `setCompoundDrawables` el dibujo se pega al borde del botón y la
     * palabra queda centrada aparte: en un botón ancho se ven a media cuadra uno
     * del otro. Metiéndolo en el texto como ImageSpan viaja pegado a la palabra
     * y los dos se centran juntos.
     */
    public static void boton(TextView t, int icono, String color, String texto, boolean arriba) {
        Drawable d = icono == 0 ? null : t.getResources().getDrawable(icono);
        if (d == null) {
            t.setCompoundDrawables(null, null, null, null);
            t.setText(texto);
            return;
        }
        d = d.mutate();
        d.setColorFilter(Color.parseColor(color), PorterDuff.Mode.SRC_IN);
        d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        if (arriba) {
            t.setCompoundDrawables(null, d, null, null);
            t.setText(texto);
            return;
        }
        t.setCompoundDrawables(null, null, null, null);
        SpannableStringBuilder sb = new SpannableStringBuilder("  " + texto);
        sb.setSpan(new CenterImageSpan(d), 0, 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setText(sb);
    }

    private static final class CenterImageSpan extends ImageSpan {
        CenterImageSpan(Drawable drawable) {
            super(drawable);
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                                   float x, int top, int y, int bottom, Paint paint) {
            Drawable drawable = getDrawable();
            int offsetY = Math.round(y + (paint.ascent() + paint.descent()
                    - drawable.getBounds().height()) / 2f);
            canvas.save();
            canvas.translate(x, offsetY);
            drawable.draw(canvas);
            canvas.restore();
        }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end,
                                     Paint.FontMetricsInt fontMetrics) {
            return getDrawable().getBounds().width();
        }
    }

    public static void boton(TextView t, int icono, String color, String texto) {
        boton(t, icono, color, texto, false);
    }

    /** Pinta los dibujos que ya tiene puestos el TextView. */
    public static void tenir(TextView t, String color) {
        for (Drawable d : t.getCompoundDrawables()) {
            if (d != null) d.setColorFilter(Color.parseColor(color), PorterDuff.Mode.SRC_IN);
        }
    }
}

package ar.tecnomanu.heladera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Cinco barritas redondeadas adentro del botón grande.
 *
 * Escuchando siguen el nivel del micrófono (nivel()); hablando suben y bajan solas (hablar(true)).
 * Se dibujan a mano con un Canvas: liviano para la tablet vieja y sin depender del texto del botón.
 */
public class Ondas extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float[] alto = new float[5];
    private float nivel = 0f;
    private boolean solas = false;
    private long t0 = System.currentTimeMillis();

    public Ondas(Context c) { this(c, null); }

    public Ondas(Context c, AttributeSet a) {
        super(c, a);
        p.setColor(0xFF0B0F0A);
        for (int i = 0; i < alto.length; i++) alto[i] = 0.3f;
    }

    public void color(int argb) { p.setColor(argb); invalidate(); }

    /** 0..1, del micrófono. */
    public void nivel(double n) {
        nivel = (float) Math.max(0, Math.min(1, n));
        invalidate();
    }

    public void hablar(boolean on) {
        solas = on;
        t0 = System.currentTimeMillis();
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        int n = alto.length;
        float ancho = w / (n * 2 - 1);
        long ms = System.currentTimeMillis() - t0;
        for (int i = 0; i < n; i++) {
            float objetivo;
            if (solas) {
                objetivo = 0.35f + 0.65f * (float) (0.5 + 0.5 * Math.sin(ms / 160.0 + i * 0.9));
            } else {
                // el centro sube más que los bordes, como una onda de voz
                float forma = 1f - Math.abs(i - (n - 1) / 2f) / n;
                objetivo = 0.25f + 0.75f * nivel * forma * (0.8f + 0.4f * (float) Math.sin(ms / 90.0 + i));
            }
            alto[i] += (objetivo - alto[i]) * 0.35f;
            float bh = Math.max(ancho, h * alto[i]);
            float x = i * ancho * 2;
            r.set(x, (h - bh) / 2, x + ancho, (h + bh) / 2);
            c.drawRoundRect(r, ancho / 2, ancho / 2, p);
        }
        if (getVisibility() == VISIBLE) postInvalidateOnAnimation();
    }
}

package ar.tecnomanu.heladera;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * La hoja que sube desde abajo, con nuestra pinta.
 *
 * Reemplaza a los AlertDialog con lista: aquellos salen con el tema del sistema
 * (blanco) y en una pantalla pegada a la heladera eso encandila de noche. Acá
 * el menú, el orden y las acciones de una sublista usan todos la misma.
 */
public class Hoja {

    public interface Accion { void correr(); }

    private final Activity a;
    private final LinearLayout box;
    private AlertDialog d;

    public Hoja(Activity a, String titulo, String nota) {
        this.a = a;
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.sheet_bg);
        box.setPadding(0, dp(10), 0, dp(16));

        View agarre = new View(a);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(4));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(12);
        agarre.setLayoutParams(lp);
        agarre.setBackgroundColor(Color.parseColor("#242D1B"));
        box.addView(agarre);

        TextView t = new TextView(a);
        t.setText(titulo.toUpperCase());
        t.setTextColor(Color.parseColor("#8A9280"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setLetterSpacing(0.07f);
        t.setPadding(dp(22), 0, dp(22), dp(4));
        box.addView(t);

        if (nota != null && !nota.isEmpty()) {
            TextView n = new TextView(a);
            n.setText(nota);
            n.setTextColor(Color.parseColor("#8A9280"));
            n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            n.setPadding(dp(22), dp(4), dp(22), dp(8));
            box.addView(n);
        }
    }

    public Hoja opcion(int icono, String label, final Accion fn) {
        TextView o = new TextView(a);
        o.setText(label);
        o.setTextColor(Color.parseColor("#F2F5EE"));
        o.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        o.setPadding(dp(22), dp(17), dp(22), dp(17));
        o.setGravity(Gravity.CENTER_VERTICAL);
        o.setCompoundDrawablePadding(dp(18));
        if (icono != 0) {
            o.setCompoundDrawablesWithIntrinsicBounds(icono, 0, 0, 0);
            Iconos.tenir(o, "#C7F536");
        }
        o.setBackgroundColor(Color.TRANSPARENT);
        o.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (d != null) d.dismiss();
                if (fn != null) fn.correr();
            }
        });
        box.addView(o);
        return this;
    }

    public void mostrar() {
        ScrollView sc = new ScrollView(a);
        sc.addView(box);
        d = new AlertDialog.Builder(a).setView(sc).create();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
        }
        d.show();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private int dp(int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }
}

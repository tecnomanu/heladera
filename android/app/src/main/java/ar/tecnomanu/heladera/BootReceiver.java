package ar.tecnomanu.heladera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Si se corta la luz o se reinicia la tablet, volvemos a mostrar la lista. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            Intent i = new Intent(ctx, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(i);
        } catch (Throwable ignored) {
            // Android 10+ bloquea abrir pantallas desde atrás; en la tablet vieja anda.
        }
    }
}

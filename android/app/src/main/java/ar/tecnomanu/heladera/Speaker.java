package ar.tecnomanu.heladera;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/**
 * Reproduce lo que contesta la heladera.
 *
 * Primero el audio que generó el servidor (voz argentina de piper). Si el server
 * no está o no mandó audio, cae a la voz del propio Android, que es fea pero
 * offline: la heladera nunca se queda muda.
 */
public class Speaker {

    public interface Done { void onDone(); }

    private final Context ctx;
    private MediaPlayer player;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    public Speaker(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        try {
            tts = new TextToSpeech(this.ctx, new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS) {
                        try {
                            tts.setLanguage(new Locale("es", "AR"));
                        } catch (Throwable t) {
                            try { tts.setLanguage(new Locale("es", "ES")); } catch (Throwable ignored) { }
                        }
                        ttsReady = true;
                    }
                }
            });
        } catch (Throwable ignored) { }
    }

    public void stop() {
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        if (tts != null) { try { tts.stop(); } catch (Throwable ignored) { } }
    }

    /** url puede ser null: ahí habla el Android. */
    public void say(final String url, final String fallbackText, final Done done) {
        stop();
        if (url == null || url.isEmpty()) {
            device(fallbackText, done);
            return;
        }
        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(url);
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) {
                    stop();
                    if (done != null) done.onDone();
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                    stop();
                    device(fallbackText, done);
                    return true;
                }
            });
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer mp) { mp.start(); }
            });
            player.prepareAsync();
        } catch (Throwable t) {
            device(fallbackText, done);
        }
    }

    @SuppressWarnings("deprecation")
    private void device(String text, Done done) {
        if (ttsReady && text != null && !text.isEmpty()) {
            try {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            } catch (Throwable ignored) { }
        }
        if (done != null) done.onDone();
    }

    public void release() {
        stop();
        if (tts != null) {
            try { tts.shutdown(); } catch (Throwable ignored) { }
            tts = null;
        }
    }
}

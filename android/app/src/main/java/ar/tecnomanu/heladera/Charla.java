package ar.tecnomanu.heladera;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * La charla en vivo con la heladera del servidor (servicio heladera-voz, /talk).
 *
 * Sube el micrófono tal cual (PCM 16 bits, 16 kHz, de a 32 ms) y reproduce lo que baja mientras llega. Quién habla,
 * cuándo terminaste, qué se entendió y cuándo cortar la voz lo decide el servidor; acá solo se escucha y se suena.
 * El micrófono no se apaga mientras la heladera habla: así se le puede hablar encima (el servidor la calla).
 */
public class Charla {
    public interface Avisos {
        void lista();
        /** Un aviso del servidor: partial, heard, state, saying, tool, interrupted, timing, end. */
        void aviso(JSONObject f);
        /** 0..1, para las ondas del botón. */
        void nivel(double n);
        void cortada(String porque);
    }

    private static final int SR_MIC = 16000, CHUNK = 512;

    private final Avisos avisos;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<byte[]> parlante = new LinkedBlockingQueue<>();
    private final WsCliente ws;
    private volatile boolean viva;
    private volatile AudioTrack track;
    private AudioRecord mic;
    private AcousticEchoCanceler eco;

    public Charla(Avisos a) {
        avisos = a;
        ws = new WsCliente(new WsCliente.Oyente() {
            @Override public void texto(String s) { recibido(s); }
            @Override public void binario(byte[] b) { if (track != null) parlante.offer(b); }
            @Override public void cerrado(final String porque) { terminar(porque); }
        });
    }

    public boolean viva() { return viva; }

    /** url: ws://casa:8796/talk?token=…  — conecta en otro hilo y avisa con lista() o cortada(). */
    public void empezar(final String url) {
        viva = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ws.conectar(url);
                } catch (Exception e) {
                    terminar("token".equals(e.getMessage()) ? "token" : "sin conexión");
                }
            }
        }, "charla-conectar").start();
    }

    private void recibido(String s) {
        final JSONObject f;
        try { f = new JSONObject(s); } catch (Exception e) { return; }
        String tipo = f.optString("type");
        if ("ready".equals(tipo)) {
            arrancarAudio(f.optInt("sr", 22050));
            ui.post(new Runnable() { @Override public void run() { if (viva) avisos.lista(); } });
            return;
        }
        if ("interrupted".equals(tipo)) callar();
        ui.post(new Runnable() { @Override public void run() { if (viva) avisos.aviso(f); } });
    }

    private void arrancarAudio(int srVoz) {
        int min = AudioTrack.getMinBufferSize(srVoz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack(AudioManager.STREAM_MUSIC, srVoz, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, srVoz), AudioTrack.MODE_STREAM);
        track.play();
        Thread p = new Thread(new Runnable() {
            @Override public void run() {
                AudioTrack t = track;
                while (true) {
                    byte[] b;
                    try { b = parlante.take(); } catch (InterruptedException e) { break; }
                    if (b.length == 0) break;  // fin: lo que quedaba ya sonó
                    t.write(b, 0, b.length);
                }
                try { t.stop(); t.release(); } catch (Throwable ignored) { }
            }
        }, "charla-parlante");
        p.setDaemon(true);
        p.start();

        int minMic = AudioRecord.getMinBufferSize(SR_MIC, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        // MIC y no VOICE_RECOGNITION: en esta tablet la supresión de ruido de ese modo se come la voz.
        mic = new AudioRecord(MediaRecorder.AudioSource.MIC, SR_MIC, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(minMic, CHUNK * 2 * 8));
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                eco = AcousticEchoCanceler.create(mic.getAudioSessionId());
                if (eco != null) eco.setEnabled(true);
            } catch (Throwable ignored) { }
        }
        mic.startRecording();
        Thread m = new Thread(new Runnable() {
            @Override public void run() { grabar(); }
        }, "charla-mic");
        m.setDaemon(true);
        m.start();
    }

    private void grabar() {
        byte[] buf = new byte[CHUNK * 2];
        int vacios = 0;
        while (viva && ws.abierto()) {
            int n = mic.read(buf, 0, buf.length);
            // read() no bloquea cuando el micrófono murió: sin este corte el hilo gira para siempre (ver Recorder).
            if (n < 0) { terminar("micrófono"); return; }
            if (n == 0) { if (++vacios > 100) { terminar("micrófono"); return; } continue; }
            vacios = 0;
            ws.binario(buf, n);
            long sum = 0;
            for (int i = 0; i + 1 < n; i += 2) {
                short v = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
                sum += (long) v * v;
            }
            final double rms = Math.sqrt(sum / Math.max(1.0, n / 2.0));
            ui.post(new Runnable() { @Override public void run() { if (viva) avisos.nivel(Math.min(1.0, rms / 2500.0)); } });
        }
    }

    /** Corta lo que está sonando (el servidor avisó que le hablaron encima). */
    private void callar() {
        parlante.clear();
        AudioTrack t = track;
        if (t != null) {
            try { t.pause(); t.flush(); t.play(); } catch (IllegalStateException ignored) { }
        }
    }

    /** Lo escrito en vez de dicho (por si algún día la pantalla tiene teclado). */
    public void escribir(String texto) {
        try {
            ws.texto(new JSONObject().put("type", "text").put("text", texto).toString());
        } catch (Exception ignored) { }
    }

    /** Cortar ya (tocaste el botón): se calla en el acto. */
    public void cerrar() { terminar("", false); }

    /** La heladera se despidió: deja terminar de sonar lo último ("Dale, cualquier cosa me avisás."). */
    public void despedir() { terminar("", true); }

    private void terminar(String porque) { terminar(porque, false); }

    private synchronized void terminar(final String porque, boolean suave) {
        if (!viva) return;
        viva = false;
        ws.cerrar();
        if (!suave) callar();
        parlante.offer(new byte[0]);  // el parlante suelta el audio cuando llega a esta marca
        try { if (mic != null) { mic.stop(); mic.release(); } } catch (Throwable ignored) { }
        try { if (eco != null) eco.release(); } catch (Throwable ignored) { }
        ui.post(new Runnable() { @Override public void run() { avisos.cortada(porque); } });
    }
}

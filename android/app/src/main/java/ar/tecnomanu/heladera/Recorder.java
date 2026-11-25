package ar.tecnomanu.heladera;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;

/**
 * Graba del micrófono a WAV 16 kHz mono y corta solo cuando dejás de hablar.
 *
 * Se usa para dos cosas: el botón de hablar (una grabación puntual) y el modo
 * manos libres (escucha continua que va entregando cada frase suelta).
 * El umbral de ruido se calibra con el ambiente real de la cocina, así no hay
 * que tocar constantes según dónde esté la tablet.
 */
public class Recorder {

    /**
     * Todos los avisos dicen de qué grabador vienen.
     *
     * Hace falta porque los avisos viajan al hilo de la pantalla y pueden llegar
     * después de que ese grabador se cerró: al tocar HABLAR se apaga el de manos
     * libres, y si no distinguimos quién habló, su audio viejo (ruido de la
     * cocina) se manda como si fuera el pedido.
     */
    public interface Listener {
        /** nivel 0..1 para el feedback visual */
        void onLevel(Recorder source, double level);
        /** lo que va grabado hasta ahora, para ir mostrando la transcripción */
        void onPartial(Recorder source, byte[] wav, int ms);
        /** una frase completa lista para mandar */
        void onUtterance(Recorder source, byte[] wav, int ms);
        void onError(Recorder source, String msg);
    }

    public static final int RATE = 16000;

    private static final int MIN_SPEECH_MS = 700;    // menos que esto es un ruido
    // Dictar una lista tiene pausas largas ("un kilo de fideos… dos latas de tomate").
    // Cortar a los 1,15 s mandaba media frase y la respuesta era "no te entendí".
    private static final int TRAIL_SILENCE_MS = 1200;
    private static final int MAX_UTTERANCE_MS = 15000;
    // A noisy room can keep the silence detector open forever. A wake phrase is
    // short, so send a bounded clip instead of waiting for quiet that may never come.
    public static final int MAX_WAKE_MS = 1800;
    // "che heladera" dura un segundo: esperar 1,6 s de silencio antes de siquiera
    // consultar hace que la heladera parezca sorda. Dictando una lista sí hacen
    // falta esas pausas largas, por eso son dos valores distintos.
    private static final int WAKE_SILENCE_MS = 450;
    private static final int MIN_WAKE_MS = 350;
    // Si tocaste HABLAR y no se escucha nada, cortamos en vez de quedarnos en rojo
    // para siempre esperando una voz que no llega.
    private static final int NO_SPEECH_MS = 12000;
    private static final int LEAD_MS = 300;          // audio previo que conservamos
    private static final int PARTIAL_MS = 1200;      // cada cuánto mandamos un adelanto
    // El umbral se calcula sobre el ruido medido. Un mínimo fijo alto deja sorda a
    // cualquier tablet vieja: la SM-T350 capta cinco veces menos que un celular
    // moderno, y con un piso de 380 había que gritarle.
    private static final double MIN_THRESHOLD = 80;
    private static final double MARGIN = 55;          // cuánto por encima del ruido alcanza
    private static final double MAX_THRESHOLD = 5000;

    static final String TAG = "heladera-rec";
    public static boolean DEBUG = true;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final boolean continuous;
    /** Los adelantos solo sirven cuando alguien los está mirando. */
    private final boolean partials;

    private volatile boolean running = false;
    private volatile boolean forceStop = false;
    private Thread thread;

    public Recorder(Listener listener, boolean continuous) {
        this(listener, continuous, !continuous);
    }

    public Recorder(Listener listener, boolean continuous, boolean partials) {
        this.listener = listener;
        this.continuous = continuous;
        this.partials = partials;
    }

    public boolean isRunning() { return running; }

    /** Corta la frase actual y la entrega (modo botón). */
    public void finish() { forceStop = true; }

    public void stop() {
        running = false;
        forceStop = true;
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    public void start() {
        if (running) return;
        running = true;
        forceStop = false;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "heladera-rec");
        thread.start();
    }

    // ------------------------------------------------------------------ loop

    private void loop() {
        int minBuf = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = RATE;
        int bufSize = Math.max(minBuf * 2, RATE);   // ~0.5s de colchón

        AudioRecord rec = null;
        try {
            // A released AudioRecord can still own the hardware briefly. Validate
            // recording state too: initialization alone is not enough on old tablets.
            for (int intento = 0; intento < 8 && running; intento++) {
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    rec.release();
                    rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
                }
                if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                    try { rec.startRecording(); } catch (IllegalStateException ignored) { }
                    if (rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) break;
                }
                if (rec != null) rec.release();
                rec = null;
                android.util.Log.d(TAG, "micrófono ocupado, reintento " + (intento + 1));
                Thread.sleep(90);
            }
            if (rec == null || rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                post("No pude abrir el micrófono. Probá de nuevo.");
                running = false;
                return;
            }

            final int chunk = RATE / 50;             // 20 ms
            short[] buf = new short[chunk];
            ByteArrayOutputStream speech = new ByteArrayOutputStream();
            ByteArrayOutputStream lead = new ByteArrayOutputStream();

            double floor = -1;
            boolean inSpeech = false;
            int speechMs = 0, silenceMs = 0, lastPartialMs = 0, waitingMs = 0;
            long lastLevel = 0, lastLog = 0;

            long fallaDesde = 0;

            while (running) {
                int n = rec.read(buf, 0, chunk);
                // Si el micrófono devuelve error (se lo llevó otra app, o el
                // AudioRecord quedó muerto), `read` contesta al instante y para
                // siempre. Con un `continue` pelado el bucle gira eternamente:
                // `running` sigue en true, `finish()` no lo toca porque nunca
                // llega a mirar forceStop, y el botón Hablar no vuelve nunca más.
                if (n <= 0) {
                    if (n < 0 || forceStop) {
                        if (n < 0) post("Se cortó el micrófono. Probá de nuevo.");
                        break;
                    }
                    if (fallaDesde == 0) {
                        fallaDesde = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - fallaDesde > 2000) {
                        post("El micrófono no está entregando audio. Probá de nuevo.");
                        break;
                    }
                    try { Thread.sleep(10); } catch (InterruptedException ignored) { }
                    continue;
                }
                fallaDesde = 0;

                double sum = 0;
                for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
                double rms = Math.sqrt(sum / n);

                // El piso de ruido se sigue solo: baja rápido cuando hay silencio y
                // sube muy despacio, así hablar apenas tocás el botón no lo envenena
                // (antes calibrábamos 600 ms fijos y si arrancabas a hablar ahí, sordera).
                if (floor < 0) {
                    floor = rms;
                } else if (rms < floor) {
                    floor = floor * 0.70 + rms * 0.30;
                } else if (!inSpeech) {
                    floor = floor * 0.995 + rms * 0.005;
                }
                // Histéresis: empezar a hablar tiene que costar más que seguir hablando.
                // Con un solo umbral, las sílabas flojas del medio de una frase quedan
                // por debajo y la grabación se corta en "anotame un kilo de fideos" —
                // justo antes de lo que faltaba.
                // Para entrar en "está hablando" el escucha de manos libres
                // pide más que el botón: cada recorte suyo es una transcripción,
                // y la cocina sola no tiene que pagarlas.
                double entrar = continuous ? floor * 4.0 : floor * 3.0;
                double threshold = Math.min(
                        Math.max(Math.max(entrar, floor + MARGIN * (continuous ? 1.6 : 1.0)), MIN_THRESHOLD),
                        MAX_THRESHOLD);
                // Un poco por debajo del de entrada, no muchísimo: si queda demasiado
                // bajo, el ruido de fondo alcanza para "seguir hablando" y la
                // grabación no termina nunca.
                // El escucha de manos libres es más exigente para "seguir
                // hablando": si no, el ruido de fondo lo mantiene adentro para
                // siempre y recorta 4 segundos de cocina una y otra vez.
                double keep = continuous
                        ? Math.max(Math.max(floor * 2.6, floor + MARGIN), MIN_THRESHOLD)
                        : Math.max(Math.max(floor * 2.0, floor + MARGIN * 0.6), MIN_THRESHOLD * 0.75);
                boolean loud = rms > (inSpeech ? keep : threshold);

                long now = System.currentTimeMillis();
                if (now - lastLevel > 90) {
                    lastLevel = now;
                    postLevel(Math.min(1.0, rms / Math.max(threshold * 3, 1)));
                }
                if (DEBUG && now - lastLog > 400) {
                    lastLog = now;
                    android.util.Log.d(TAG, String.format(java.util.Locale.US,
                            "rms=%.0f floor=%.0f thr=%.0f keep=%.0f loud=%b speech=%b sp=%d sil=%d",
                            rms, floor, threshold, keep, loud, inSpeech, speechMs, silenceMs));
                }

                byte[] pcm = toBytes(buf, n);
                int ms = n * 1000 / RATE;

                if (!inSpeech) {
                    // guardamos un poquito de audio previo para no comernos la primera sílaba
                    lead.write(pcm, 0, pcm.length);
                    int maxLead = LEAD_MS * RATE / 1000 * 2;
                    if (lead.size() > maxLead) {
                        byte[] all = lead.toByteArray();
                        lead.reset();
                        lead.write(all, all.length - maxLead, maxLead);
                    }
                    if (loud) {
                        inSpeech = true;
                        speechMs = 0;
                        silenceMs = 0;
                        lastPartialMs = 0;
                        waitingMs = 0;
                        byte[] l = lead.toByteArray();
                        speech.write(l, 0, l.length);
                        lead.reset();
                    } else if (forceStop) {
                        break;   // apretó parar sin decir nada
                    } else if (!continuous && (waitingMs += ms) >= NO_SPEECH_MS) {
                        post("No te escuché. Tocá Hablar y hablá cerca de la pantalla.");
                        break;
                    }
                } else {
                    speech.write(pcm, 0, pcm.length);
                    speechMs += ms;
                    silenceMs = loud ? 0 : silenceMs + ms;

                    // Apenas se hace silencio dejamos de pedir parciales: la
                    // transcripción de verdad viene en un segundo y no queremos
                    // que espere atrás de un parcial que ya no le importa a nadie.
                    if (partials && silenceMs < 400 && speechMs - lastPartialMs >= PARTIAL_MS) {
                        lastPartialMs = speechMs;
                        postPartial(wav(speech.toByteArray()), speechMs);
                    }

                    boolean enough = speechMs >= (continuous ? MIN_WAKE_MS : MIN_SPEECH_MS);
                    boolean quiet = silenceMs >= (continuous ? WAKE_SILENCE_MS : TRAIL_SILENCE_MS);
                    boolean tooLong = speechMs >= (continuous ? MAX_WAKE_MS : MAX_UTTERANCE_MS);

                    if (tooLong || (enough && quiet) || (forceStop && enough)) {
                        postUtterance(wav(speech.toByteArray()), speechMs);
                        speech.reset();
                        inSpeech = false;
                        speechMs = 0;
                        silenceMs = 0;
                        lastPartialMs = 0;
                        if (!continuous) break;
                    } else if (forceStop && !enough) {
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            post("Micrófono: " + t.getMessage());
        } finally {
            running = false;
            if (rec != null) {
                try { rec.stop(); } catch (Throwable ignored) { }
                try { rec.release(); } catch (Throwable ignored) { }
            }
            postLevel(0);
        }
    }

    // ------------------------------------------------------------- utilidades

    private static byte[] toBytes(short[] src, int n) {
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            out[i * 2] = (byte) (src[i] & 0xff);
            out[i * 2 + 1] = (byte) ((src[i] >> 8) & 0xff);
        }
        return out;
    }

    /**
     * Sube el volumen de lo grabado hasta un nivel razonable.
     *
     * En un micrófono flojo la voz entra usando el 1% del rango de 16 bits, y
     * whisper transcribe bastante peor. Escalamos al 50% del máximo, con tope
     * para no convertir el silencio en ruido blanco.
     */
    static byte[] normalize(byte[] pcm) {
        // Sobre el RMS y no sobre el pico: un solo golpe (una puerta, un plato)
        // dispara el pico y amplificar por eso convierte el silencio en ruido
        // blanco, con el que whisper alucina frases enteras.
        long sum = 0;
        int n = 0, peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int v = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            sum += (long) v * v;
            peak = Math.max(peak, Math.abs(v));
            n++;
        }
        if (n == 0) return pcm;
        double rms = Math.sqrt((double) sum / n);
        if (rms < 60) return pcm;                   // no hay voz que levantar
        double gain = 2200.0 / rms;
        if (gain <= 1.2) return pcm;                // ya venía bien
        gain = Math.min(gain, 8.0);
        if (peak * gain > 32000) gain = 32000.0 / peak;   // que no sature
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int v = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            int out = (int) Math.round(v * gain);
            if (out > 32767) out = 32767;
            if (out < -32768) out = -32768;
            pcm[i] = (byte) (out & 0xff);
            pcm[i + 1] = (byte) ((out >> 8) & 0xff);
        }
        return pcm;
    }

    /** Envuelve el PCM crudo en un WAV que el server pueda abrir. */
    public static byte[] wav(byte[] pcm) {
        pcm = normalize(pcm);
        int dataLen = pcm.length;
        int total = 36 + dataLen;
        byte[] out = new byte[44 + dataLen];
        writeAscii(out, 0, "RIFF");
        writeInt(out, 4, total);
        writeAscii(out, 8, "WAVE");
        writeAscii(out, 12, "fmt ");
        writeInt(out, 16, 16);
        writeShort(out, 20, (short) 1);      // PCM
        writeShort(out, 22, (short) 1);      // mono
        writeInt(out, 24, RATE);
        writeInt(out, 28, RATE * 2);
        writeShort(out, 32, (short) 2);
        writeShort(out, 34, (short) 16);
        writeAscii(out, 36, "data");
        writeInt(out, 40, dataLen);
        System.arraycopy(pcm, 0, out, 44, dataLen);
        return out;
    }

    private static void writeAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    private static void writeShort(byte[] b, int off, short v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
    }

    private void post(final String msg) {
        final Recorder self = this;
        ui.post(new Runnable() { @Override public void run() { listener.onError(self, msg); } });
    }

    private void postLevel(final double l) {
        final Recorder self = this;
        ui.post(new Runnable() { @Override public void run() { listener.onLevel(self, l); } });
    }

    private void postPartial(final byte[] wav, final int ms) {
        final Recorder self = this;
        ui.post(new Runnable() { @Override public void run() { listener.onPartial(self, wav, ms); } });
    }

    private void postUtterance(final byte[] wav, final int ms) {
        final Recorder self = this;
        ui.post(new Runnable() { @Override public void run() { listener.onUtterance(self, wav, ms); } });
    }
}

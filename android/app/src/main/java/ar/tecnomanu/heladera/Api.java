package ar.tecnomanu.heladera;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Cliente HTTP mínimo contra el servicio de la heladera.
 * A propósito sin librerías: HttpURLConnection anda igual en Android 5 que en 16.
 */
public class Api {

    public interface Cb {
        void ok(JSONObject res);
        void fail(String error);
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private final String base;
    private final String token;

    public Api(String base) { this(base, ""); }

    public Api(String base, String token) {
        String b = base == null ? "" : base.trim();
        // Una barra o una contrabarra de más al final convierten cada pedido en
        // un 404 silencioso ("//api/list"). Vienen de copiar y pegar la URL.
        while (b.endsWith("/") || b.endsWith("\\")) b = b.substring(0, b.length() - 1);
        this.base = b;
        this.token = token == null ? "" : token.trim();
    }

    public String base() { return base; }
    public String token() { return token; }

    /** URL de audio lista para MediaPlayer, con el token si hace falta. */
    public String audioUrl(String path) {
        if (path == null || path.isEmpty()) return null;
        return base + path + (token.isEmpty() ? "" : (path.contains("?") ? "&" : "?") + "token=" + token);
    }

    private void auth(HttpURLConnection c) {
        if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
    }

    // ---------------------------------------------------------------- helpers

    private static String readAll(HttpURLConnection c) throws Exception {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        BufferedInputStream bin = new BufferedInputStream(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = bin.read(buf)) > 0) out.write(buf, 0, n);
        bin.close();
        return out.toString("UTF-8");
    }

    /**
     * Un 4xx del server viene con cuerpo JSON y `readAll` lo lee igual, así que
     * sin esto un "falta el token" llegaba como respuesta buena: la pantalla
     * mostraba la lista vacía y "no falta nada" en vez de avisar el problema.
     */
    private static JSONObject revisar(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        if (o.has("error") && !o.has("items")) throw new Exception(o.optString("error"));
        return o;
    }

    private void deliver(final Cb cb, final JSONObject res, final String err) {
        if (cb == null) return;
        UI.post(new Runnable() {
            @Override public void run() {
                if (err != null) cb.fail(err); else cb.ok(res);
            }
        });
    }

    private void async(final Runnable r) { new Thread(r).start(); }

    // ---------------------------------------------------------------- verbos

    public void get(final String path, final Cb cb) {
        async(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(base + path).openConnection();
                    auth(c);
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(15000);
                    String body = readAll(c);
                    deliver(cb, revisar(body), null);
                } catch (Exception e) {
                    deliver(cb, null, String.valueOf(e.getMessage()));
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        });
    }

    public void postJson(final String path, final JSONObject body, final Cb cb) {
        async(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(base + path).openConnection();
                    c.setRequestMethod("POST");
                    auth(c);
                    c.setDoOutput(true);
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(60000);
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    byte[] out = (body == null ? new JSONObject() : body).toString().getBytes("UTF-8");
                    c.setFixedLengthStreamingMode(out.length);
                    OutputStream os = c.getOutputStream();
                    os.write(out);
                    os.flush();
                    os.close();
                    deliver(cb, revisar(readAll(c)), null);
                } catch (Exception e) {
                    deliver(cb, null, String.valueOf(e.getMessage()));
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        });
    }

    /** Baja un archivo del servidor y lo deja en disco. Corre en segundo plano. */
    public void download(final String path, final java.io.File dest, final Runnable done) {
        async(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(base + path).openConnection();
                    auth(c);
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(30000);
                    java.io.File tmp = new java.io.File(dest.getPath() + ".part");
                    InputStream in = new BufferedInputStream(c.getInputStream());
                    java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.close();
                    in.close();
                    if (tmp.length() > 1000) tmp.renameTo(dest); else tmp.delete();
                } catch (Exception ignored) {
                    // sin ack local seguimos igual, solo se nota un poco más la espera
                } finally {
                    if (c != null) c.disconnect();
                }
                if (done != null) UI.post(done);
            }
        });
    }

    /** Sube un WAV como multipart/form-data. */
    public void postAudio(final String path, final byte[] wav, final String[] fields, final Cb cb) {
        async(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
                try {
                    String boundary = "----heladera" + System.currentTimeMillis();
                    c = (HttpURLConnection) new URL(base + path).openConnection();
                    c.setRequestMethod("POST");
                    auth(c);
                    c.setDoOutput(true);
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(35000);
                    c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                    c.setChunkedStreamingMode(0);

                    DataOutputStream os = new DataOutputStream(c.getOutputStream());
                    if (fields != null) {
                        for (int i = 0; i + 1 < fields.length; i += 2) {
                            os.writeBytes("--" + boundary + "\r\n");
                            os.writeBytes("Content-Disposition: form-data; name=\"" + fields[i] + "\"\r\n\r\n");
                            os.write(fields[i + 1].getBytes("UTF-8"));
                            os.writeBytes("\r\n");
                        }
                    }
                    os.writeBytes("--" + boundary + "\r\n");
                    os.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\n");
                    os.writeBytes("Content-Type: audio/wav\r\n\r\n");
                    os.write(wav);
                    os.writeBytes("\r\n--" + boundary + "--\r\n");
                    os.flush();
                    os.close();

                    deliver(cb, revisar(readAll(c)), null);
                } catch (Exception e) {
                    deliver(cb, null, String.valueOf(e.getMessage()));
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        });
    }
}

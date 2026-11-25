package ar.tecnomanu.heladera;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Un cliente WebSocket mínimo (RFC 6455) sobre un Socket, sin librerías: la app no tiene AndroidX ni OkHttp y para
 * una sola conexión con la charla no vale la pena sumarlos. Solo ws:// (la heladera vive en la red de la casa).
 *
 * Los avisos llegan en el hilo lector; quien los use los pasa al hilo de la pantalla.
 */
public class WsCliente {
    public interface Oyente {
        void texto(String s);
        void binario(byte[] b);
        void cerrado(String porque);
    }

    private final Oyente oyente;
    private final SecureRandom rnd = new SecureRandom();
    private Socket sock;
    private OutputStream out;
    private volatile boolean abierto;

    public WsCliente(Oyente o) { this.oyente = o; }

    public boolean abierto() { return abierto; }

    /** Conecta y hace el saludo. Bloquea: llamarlo fuera del hilo de la pantalla. */
    public void conectar(String url) throws IOException {
        URI u = URI.create(url);
        int port = u.getPort() > 0 ? u.getPort() : 80;
        String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
                + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
        sock = new Socket();
        sock.setTcpNoDelay(true);
        sock.connect(new InetSocketAddress(u.getHost(), port), 5000);
        out = sock.getOutputStream();
        byte[] k = new byte[16];
        rnd.nextBytes(k);
        String key = Base64.encodeToString(k, Base64.NO_WRAP);
        String hola = "GET " + path + " HTTP/1.1\r\nHost: " + u.getHost() + ":" + port
                + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key
                + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        out.write(hola.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        final DataInputStream in = new DataInputStream(sock.getInputStream());
        String status = linea(in);
        if (!status.contains(" 101")) {
            cerrarSocket();
            throw new IOException(status.contains(" 403") ? "token" : "respuesta " + status);
        }
        while (!linea(in).isEmpty()) { /* encabezados: no hacen falta */ }
        abierto = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { leer(in); }
        }, "ws-lector");
        t.setDaemon(true);
        t.start();
    }

    private static String linea(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        if (c == -1 && sb.length() == 0) throw new IOException("se cortó el saludo");
        return sb.toString();
    }

    private void leer(DataInputStream in) {
        String porque = "";
        ByteArrayOutputStream partes = new ByteArrayOutputStream();
        int opPartes = 0;
        try {
            while (abierto) {
                int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0F;
                long len = b1 & 0x7F;
                if (len == 126) len = in.readUnsignedShort();
                else if (len == 127) len = in.readLong();
                if (len > (16 << 20)) throw new IOException("mensaje demasiado grande");
                byte[] mask = null;
                if ((b1 & 0x80) != 0) { mask = new byte[4]; in.readFully(mask); }
                byte[] data = new byte[(int) len];
                in.readFully(data);
                if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
                if (op == 8) { porque = "cerrado"; break; }
                if (op == 9) { mandar(10, data); continue; }
                if (op == 10) continue;
                if (op != 0) opPartes = op;
                partes.write(data);
                if (!fin) continue;
                byte[] todo = partes.toByteArray();
                partes.reset();
                if (opPartes == 1) oyente.texto(new String(todo, StandardCharsets.UTF_8));
                else if (opPartes == 2) oyente.binario(todo);
            }
        } catch (IOException e) {
            if (abierto) porque = "red: " + e.getMessage();
        }
        boolean avisar = abierto;
        abierto = false;
        cerrarSocket();
        if (avisar || !porque.isEmpty()) oyente.cerrado(porque);
    }

    public void texto(String s) { mandar(1, s.getBytes(StandardCharsets.UTF_8)); }

    public void binario(byte[] b, int n) {
        byte[] c = new byte[n];
        System.arraycopy(b, 0, c, 0, n);
        mandar(2, c);
    }

    /** Un frame enmascarado (el cliente siempre enmascara). Sincronizado: el micrófono y el lector mandan a la vez. */
    private synchronized void mandar(int op, byte[] data) {
        if (out == null) return;
        try {
            ByteArrayOutputStream f = new ByteArrayOutputStream(data.length + 14);
            f.write(0x80 | op);
            if (data.length < 126) f.write(0x80 | data.length);
            else if (data.length < 65536) { f.write(0x80 | 126); f.write(data.length >> 8); f.write(data.length & 0xFF); }
            else {
                f.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) f.write((int) (((long) data.length >> (8 * i)) & 0xFF));
            }
            byte[] m = new byte[4];
            rnd.nextBytes(m);
            f.write(m);
            for (int i = 0; i < data.length; i++) f.write(data[i] ^ m[i & 3]);
            out.write(f.toByteArray());
            out.flush();
        } catch (IOException e) {
            abierto = false;
            cerrarSocket();
        }
    }

    public void cerrar() {
        if (!abierto) { cerrarSocket(); return; }
        mandar(8, new byte[] { 0x03, (byte) 0xE8 });  // 1000: normal
        abierto = false;
        cerrarSocket();
    }

    private void cerrarSocket() {
        try { if (sock != null) sock.close(); } catch (IOException ignored) { }
    }
}

package com.kinoki.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Runs on the TV: answers the phone's cast requests on the local network and drives the player.
 *
 * <p>There is no Google Cast involved. The phone already knows the stream URL and the position the user is at, so it
 * sends both here and the TV simply opens its own player there — no receiver app, no internet round trip.
 * {@link CastService} keeps this alive after the user leaves the app.
 */
final class CastHost {

    static final int PORT = 8477;
    static final int DISCOVERY_PORT = 8478;

    /** Discovery strings: the phone broadcasts the first, every TV answers with the second plus name and port. */
    static final String DISCOVER = "KINOKI_DISCOVER";
    static final String HERE = "KINOKI_HERE";

    private static final String TAG = "CastHost";
    private static final int MAX_LINE_BYTES = 8 * 1024;
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 5000;

    /** Turns HTTP commands into player calls; implemented by {@link PlayerActivity}. */
    interface Remote {
        void onRemotePause();

        void onRemoteResume();

        void onRemoteSeek(long positionMs);

        void onRemoteStop();
    }

    /** What the TV is playing right now, as seen by the phone. */
    static final class Status {
        static final Status IDLE = new Status(null, null, null, 0, 0, false);

        final String url;
        final String title;
        final String watchKey;
        final long positionMs;
        final long durationMs;
        final boolean playing;

        Status(String url, String title, String watchKey, long positionMs, long durationMs, boolean playing) {
            this.url = url;
            this.title = title;
            this.watchKey = watchKey;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.playing = playing;
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("url", url == null ? "" : url);
                json.put("title", title == null ? "" : title);
                json.put("watchKey", watchKey == null ? "" : watchKey);
                json.put("positionMs", positionMs);
                json.put("durationMs", durationMs);
                json.put("playing", playing);
                json.put("idle", url == null);
            } catch (JSONException ignored) {
                // Only strings and numbers are put, so this cannot happen.
            }
            return json;
        }
    }

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Context context;
    private static volatile Status status = Status.IDLE;
    private static volatile Remote remote;
    private static volatile boolean running;
    private static volatile ServerSocket server;
    private static volatile DatagramSocket discovery;

    private CastHost() {
    }

    static boolean isTv(Context context) {
        PackageManager manager = context.getPackageManager();
        return manager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || manager.hasSystemFeature(PackageManager.FEATURE_TELEVISION);
    }

    /** Starts the control server once; later calls are ignored. */
    static void start(Context source) {
        synchronized (LOCK) {
            if (running) {
                return;
            }
            running = true;
            context = source.getApplicationContext();
            startThread("cast-server", CastHost::serveLoop);
            startThread("cast-discovery", CastHost::discoveryLoop);
        }
    }

    static void stop() {
        running = false;
        close(server);
        close(discovery);
        server = null;
        discovery = null;
        status = Status.IDLE;
        remote = null;
    }

    static void attach(Remote listener) {
        remote = listener;
    }

    static void detach(Remote listener) {
        if (remote == listener) {
            remote = null;
            status = Status.IDLE;
        }
    }

    static void publish(String url, String title, String watchKey, long positionMs, long durationMs,
                        boolean playing) {
        status = new Status(url, title, watchKey, positionMs, durationMs, playing);
    }

    // ---- HTTP server --------------------------------------------------------------------------------------------

    private static void startThread(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static void serveLoop() {
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(PORT));
            server = socket;
            Log.i(TAG, "cast server listening on " + PORT);
            while (running) {
                Socket client = socket.accept();
                serve(client);
            }
        } catch (IOException e) {
            if (running) {
                Log.w(TAG, "cast server stopped", e);
            }
        } finally {
            close(server);
        }
    }

    private static void serve(Socket socket) {
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String path = parts[1];
            int query = path.indexOf('?');
            if (query >= 0) {
                path = path.substring(0, query);
            }

            int length = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    try {
                        length = Integer.parseInt(line.substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {
                        length = 0;
                    }
                }
            }
            length = Math.max(0, Math.min(length, MAX_BODY_BYTES));
            String body = length == 0 ? "" : new String(readBody(in, length), StandardCharsets.UTF_8);

            write(out, handle(parts[0], path, body));
        } catch (Exception e) {
            Log.w(TAG, "cast request failed", e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Nothing useful to do.
            }
        }
    }

    private static String handle(String method, String path, String body) {
        try {
            switch (path) {
                case "/info":
                    return info();
                case "/status": {
                    JSONObject json = status.toJson();
                    putVolume(json);
                    return json.toString();
                }
                case "/play":
                    return play(new JSONObject(body));
                case "/volume":
                    return "GET".equalsIgnoreCase(method) ? volumeState() : changeVolume(new JSONObject(body));
                case "/pause":
                    return command(Remote::onRemotePause);
                case "/resume":
                    return command(Remote::onRemoteResume);
                case "/stop":
                    return command(Remote::onRemoteStop);
                case "/seek":
                    return seek(new JSONObject(body));
                default:
                    return error("unknown path");
            }
        } catch (Exception e) {
            return error(String.valueOf(e.getMessage()));
        }
    }

    private static AudioManager audio() {
        return (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    private static String volumeState() {
        JSONObject json = new JSONObject();
        putVolume(json);
        return json.toString();
    }

    /** Adds the TV's media volume to a reply so the phone can show and adjust it. */
    private static void putVolume(JSONObject json) {
        AudioManager audio = audio();
        if (audio == null) {
            return;
        }
        try {
            json.put("level", audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            json.put("max", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            json.put("muted", audio.isStreamMute(AudioManager.STREAM_MUSIC));
        } catch (JSONException ignored) {
            // Only numbers are put, so this cannot happen.
        }
    }

    /** Volume keys from the phone's remote: up, down or mute on the TV's media stream. */
    private static String changeVolume(JSONObject body) throws JSONException {
        AudioManager audio = audio();
        if (audio == null) {
            return error("no audio");
        }
        String action = body.optString("action", "up");
        int direction = "down".equals(action) ? AudioManager.ADJUST_LOWER
                : "mute".equals(action) ? AudioManager.ADJUST_TOGGLE_MUTE
                : AudioManager.ADJUST_RAISE;
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        return volumeState();
    }

    private static String info() {
        JSONObject json = new JSONObject();
        try {
            json.put("app", "kinoki");
            json.put("name", deviceName());
            json.put("port", PORT);
            json.put("ok", true);
        } catch (JSONException ignored) {
            // Cannot happen.
        }
        return json.toString();
    }

    /**
     * Starts the TV player at the position the phone is at. If the same stream is already open the player is just
     * moved there, so casting twice in a row does not reload the video.
     */
    private static String play(JSONObject body) throws JSONException {
        final String url = body.getString("url");
        final String title = body.optString("title", "");
        final String watchKey = body.optString("watchKey", url);
        final long positionMs = body.optLong("positionMs", 0);

        Remote listener = remote;
        Status current = status;
        if (listener != null && url.equals(current.url)) {
            MAIN.post(() -> {
                listener.onRemoteSeek(positionMs);
                listener.onRemoteResume();
            });
            return ok();
        }

        Intent intent = new Intent(context, PlayerActivity.class)
                .putExtra(PlayerActivity.EXTRA_URL, url)
                .putExtra(PlayerActivity.EXTRA_TITLE, title)
                .putExtra(PlayerActivity.EXTRA_WATCH_KEY, watchKey)
                .putExtra(PlayerActivity.EXTRA_START_MS, positionMs)
                .putExtra(PlayerActivity.EXTRA_CAST, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
        return ok();
    }

    private static String command(java.util.function.Consumer<Remote> action) {
        Remote listener = remote;
        if (listener == null) {
            return error("idle");
        }
        MAIN.post(() -> action.accept(listener));
        return ok();
    }

    private static String seek(JSONObject body) throws JSONException {
        Remote listener = remote;
        if (listener == null) {
            return error("idle");
        }
        long positionMs = body.optLong("positionMs", 0);
        MAIN.post(() -> listener.onRemoteSeek(positionMs));
        return ok();
    }

    private static String ok() {
        return "{\"ok\":true}";
    }

    private static String error(String message) {
        JSONObject json = new JSONObject();
        try {
            json.put("ok", false);
            json.put("error", message);
        } catch (JSONException ignored) {
            // Cannot happen.
        }
        return json.toString();
    }

    private static String deviceName() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        if (model.isEmpty()) {
            return "Android TV";
        }
        if (manufacturer.isEmpty() || model.toLowerCase().contains(manufacturer.toLowerCase())) {
            return model;
        }
        return manufacturer + " " + model;
    }

    private static void write(OutputStream out, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buffer.write(b);
            }
            if (buffer.size() > MAX_LINE_BYTES) {
                throw new IOException("request line too long");
            }
        }
        if (b == -1 && buffer.size() == 0) {
            return null;
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] readBody(InputStream in, int length) throws IOException {
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int count = in.read(body, read, length - read);
            if (count < 0) {
                break;
            }
            read += count;
        }
        return read == length ? body : Arrays.copyOf(body, read);
    }

    // ---- discovery ----------------------------------------------------------------------------------------------

    /** Answers UDP probes so the phone can find this TV without typing an address. */
    private static void discoveryLoop() {
        try {
            DatagramSocket socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(DISCOVERY_PORT));
            discovery = socket;
            byte[] buffer = new byte[256];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                if (!DISCOVER.equals(text)) {
                    continue;
                }
                String reply = HERE + "|" + deviceName() + "|" + PORT;
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(bytes, bytes.length, packet.getAddress(), packet.getPort()));
            }
        } catch (IOException e) {
            if (running) {
                Log.w(TAG, "cast discovery stopped", e);
            }
        } finally {
            close(discovery);
        }
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Nothing useful to do.
        }
    }
}

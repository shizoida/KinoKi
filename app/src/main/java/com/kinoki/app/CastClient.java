package com.kinoki.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs on the phone: finds a TV running {@link CastHost} on the local network and sends it playback commands.
 *
 * <p>Everything here blocks, so callers on the UI thread hand the work to a background thread (discovery does it by
 * itself and reports back on the main thread).
 */
final class CastClient {

    private static final String TAG = "CastClient";
    private static final int DISCOVERY_WINDOW_MS = 3500;
    private static final int DISCOVERY_TIMEOUT_MS = 400;
    private static final int REQUEST_TIMEOUT_MS = 3000;
    private static final int STATUS_TIMEOUT_MS = 1500;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** A TV we can cast to. */
    static final class Device {
        final String host;
        final String name;

        Device(String host, String name) {
            this.host = host;
            this.name = name;
        }
    }

    /** The cast currently running, kept in memory so returning to the player shows the remote again. */
    static final class Session {
        final Device device;
        final String url;
        final String title;
        final String watchKey;

        Session(Device device, String url, String title, String watchKey) {
            this.device = device;
            this.url = url;
            this.title = title;
            this.watchKey = watchKey;
        }
    }

    interface Callback {
        void onDevices(List<Device> devices);
    }

    static volatile Session session;

    static final String VOLUME_UP = "up";
    static final String VOLUME_DOWN = "down";
    static final String VOLUME_MUTE = "mute";

    private CastClient() {
    }

    /** Broadcasts a discovery probe and collects the TVs that answer. The callback runs on the main thread. */
    static void discover(Callback callback) {
        Thread thread = new Thread(() -> {
            List<Device> devices = find(DISCOVERY_WINDOW_MS);
            MAIN.post(() -> callback.onDevices(devices));
        }, "cast-discover");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * One blocking discovery round, used both for the button's "is the TV app around?" check and by
     * {@link #discover(Callback)}. Call from a background thread.
     */
    static List<Device> find(int windowMs) {
        Map<String, Device> found = new LinkedHashMap<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(DISCOVERY_TIMEOUT_MS);
            byte[] probe = CastHost.DISCOVER.getBytes(StandardCharsets.UTF_8);
            for (InetAddress address : broadcastAddresses()) {
                try {
                    socket.send(new DatagramPacket(probe, probe.length, address, CastHost.DISCOVERY_PORT));
                } catch (Exception e) {
                    Log.d(TAG, "probe to " + address + " failed");
                }
            }
            long deadline = System.currentTimeMillis() + windowMs;
            byte[] buffer = new byte[512];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (Exception e) {
                    continue;
                }
                String reply = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                String[] parts = reply.split("\\|", 3);
                if (parts.length < 3 || !CastHost.HERE.equals(parts[0])) {
                    continue;
                }
                String host = packet.getAddress().getHostAddress();
                if (!found.containsKey(host)) {
                    found.put(host, new Device(host, parts[1]));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "discovery failed", e);
        }
        return new ArrayList<>(found.values());
    }

    /** Asks the TV to open a stream at the position the phone is at. */
    static boolean play(Device device, String url, String title, String watchKey, long positionMs) {
        JSONObject body = new JSONObject();
        try {
            body.put("url", url);
            body.put("title", title == null ? "" : title);
            body.put("watchKey", watchKey == null ? url : watchKey);
            body.put("positionMs", positionMs);
        } catch (JSONException e) {
            return false;
        }
        return isOk(request(device, "/play", body, REQUEST_TIMEOUT_MS));
    }

    /** What the TV is playing, or null if it did not answer. */
    static JSONObject status(Device device) {
        return request(device, "/status", null, STATUS_TIMEOUT_MS);
    }

    static void pause(Device device) {
        request(device, "/pause", new JSONObject(), REQUEST_TIMEOUT_MS);
    }

    static void resume(Device device) {
        request(device, "/resume", new JSONObject(), REQUEST_TIMEOUT_MS);
    }

    static void seek(Device device, long positionMs) {
        JSONObject body = new JSONObject();
        try {
            body.put("positionMs", positionMs);
        } catch (JSONException e) {
            return;
        }
        request(device, "/seek", body, REQUEST_TIMEOUT_MS);
    }

    static void stop(Device device) {
        request(device, "/stop", new JSONObject(), REQUEST_TIMEOUT_MS);
    }

    /** Volume up, down or mute on the TV; returns its new volume, or null if the TV did not answer. */
    static JSONObject volume(Device device, String action) {
        JSONObject body = new JSONObject();
        try {
            body.put("action", action);
        } catch (JSONException e) {
            return null;
        }
        return request(device, "/volume", body, REQUEST_TIMEOUT_MS);
    }

    private static JSONObject request(Device device, String path, JSONObject body, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://" + device.host + ":" + CastHost.PORT + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                return new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.d(TAG, path + " to " + device.host + " failed: " + e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isOk(JSONObject response) {
        return response != null && response.optBoolean("ok", false);
    }

    /**
     * Every address a broadcast probe should be sent to: one per network interface, plus the global broadcast as a
     * safety net for networks where the interface address is unusual.
     */
    private static List<InetAddress> broadcastAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    InetAddress broadcast = address.getBroadcast();
                    if (broadcast != null) {
                        addresses.add(broadcast);
                    }
                }
            }
            addresses.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception e) {
            Log.w(TAG, "could not list broadcast addresses", e);
        }
        return addresses;
    }
}

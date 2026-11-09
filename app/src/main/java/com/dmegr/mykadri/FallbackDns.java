package com.dmegr.mykadri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Uses the system resolver, but adds addresses from DNS-over-HTTPS when that fails or a connection to the host has
 * failed. Some networks block mykadri.tv at their DNS (by returning nothing, or an address that goes nowhere), and
 * IPv6 networks hand out AAAA records that may not be reachable, so both A and AAAA records are asked for.
 */
final class FallbackDns implements Dns {

    /**
     * DNS-over-HTTPS endpoints, addressed by IP literal so no DNS is needed to reach them. Both IPv4 and IPv6 are
     * listed so one of them is reachable on any network. The first two speak Cloudflare's JSON API, the others Google's.
     */
    private static final String[] DOH_URLS = {
            "https://1.1.1.1/dns-query",
            "https://[2606:4700:4700::1111]/dns-query",
            "https://8.8.8.8/resolve",
            "https://[2001:4860:4860::8888]/resolve",
    };
    private static final int TYPE_A = 1;
    private static final int TYPE_AAAA = 28;
    private static final String[] QUERY_TYPES = {"A", "AAAA"};
    private static final int CONNECT_TIMEOUT_MS = 4_000;
    private static final int READ_TIMEOUT_MS = 6_000;

    private final OkHttpClient bootstrap = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build();
    /** Hosts resolved with the help of DoH, remembered for the session. */
    private final Map<String, List<InetAddress>> resolved = new ConcurrentHashMap<>();
    /** Hosts the system resolver gave addresses for that then could not be connected to. */
    private final Set<String> unreachable = ConcurrentHashMap.newKeySet();

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        List<InetAddress> cached = resolved.get(hostname);
        if (cached != null) {
            return cached;
        }
        List<InetAddress> system = null;
        UnknownHostException failure = null;
        try {
            system = Dns.SYSTEM.lookup(hostname);
        } catch (UnknownHostException e) {
            failure = e;
        }
        if (system != null && !unreachable.contains(hostname)) {
            return system;
        }

        List<InetAddress> secure = resolveOverHttps(hostname);
        if (secure.isEmpty()) {
            if (system != null) {
                return system;
            }
            throw failure;
        }
        // Try the DoH addresses first; the system's own may be the ones that don't work.
        List<InetAddress> merged = new ArrayList<>(secure);
        if (system != null) {
            for (InetAddress address : system) {
                if (!merged.contains(address)) {
                    merged.add(address);
                }
            }
        }
        resolved.put(hostname, merged);
        return merged;
    }

    /** Called when connecting to a host failed, so the next lookup also consults DoH. */
    void markUnreachable(String hostname) {
        unreachable.add(hostname);
        resolved.remove(hostname);
    }

    private List<InetAddress> resolveOverHttps(String hostname) {
        for (String endpoint : DOH_URLS) {
            List<InetAddress> addresses = new ArrayList<>();
            for (String type : QUERY_TYPES) {
                addresses.addAll(query(endpoint, hostname, type));
            }
            if (!addresses.isEmpty()) {
                return addresses;
            }
        }
        return new ArrayList<>();
    }

    private List<InetAddress> query(String endpoint, String hostname, String type) {
        Request request = new Request.Builder()
                .url(endpoint + "?name=" + hostname + "&type=" + type)
                .header("Accept", "application/dns-json")
                .build();
        List<InetAddress> addresses = new ArrayList<>();
        try (Response response = bootstrap.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return addresses;
            }
            JSONArray answers = new JSONObject(response.body().string()).optJSONArray("Answer");
            for (int i = 0; answers != null && i < answers.length(); i++) {
                JSONObject answer = answers.getJSONObject(i);
                int answerType = answer.optInt("type");
                if (answerType == TYPE_A || answerType == TYPE_AAAA) {
                    // getByName on a literal IP does not do a lookup; re-wrap it so it carries the hostname.
                    byte[] ip = InetAddress.getByName(answer.getString("data")).getAddress();
                    addresses.add(InetAddress.getByAddress(hostname, ip));
                }
            }
        } catch (IOException | JSONException e) {
            addresses.clear();
        }
        return addresses;
    }
}

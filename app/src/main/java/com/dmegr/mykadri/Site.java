package com.dmegr.mykadri;

import android.text.Html;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;

/** Fetches and scrapes mykadri.tv: category listings and the player data embedded in each title's page. */
final class Site {

    static final String BASE_URL = "https://mykadri.tv";
    static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0 Safari/537.36";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final String DOH_URL = "https://1.1.1.1/dns-query?type=A&name=";
    private static final int DNS_TYPE_A = 1;

    /** Shared by page fetches and video playback, so both get the DNS fallback. */
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .dns(new FallbackDns())
            .build();

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern CARD = Pattern.compile(
            "<a class=\"card-info\" href=\"([^\"]+)\"[^>]*>\\s*<h3 class=\"card-primary-title\">(.*?)</h3>",
            Pattern.DOTALL);
    private static final Pattern CARD_BLOCK = Pattern.compile(
            "<article class=\"card poster-card\">(.*?)</article>", Pattern.DOTALL);
    private static final Pattern SRCSET = Pattern.compile("srcset=\"([^\"]+)\"");
    private static final Pattern IMG_SRC = Pattern.compile("<img\\s[^>]*?src=\"([^\"]+)\"");
    private static final Pattern CARD_YEAR = Pattern.compile("card-poster-year\">([^<]*)<");
    private static final Pattern CARD_RATING = Pattern.compile("card-poster-rating\">([^<]*)<");
    private static final Pattern META_DESCRIPTION = Pattern.compile(
            "<meta name=\"description\" content=\"([^\"]*)\"");
    private static final Pattern OG_IMAGE = Pattern.compile("<meta property=\"og:image\" content=\"([^\"]+)\"");
    private static final Pattern POSTER_IMG = Pattern.compile(
            "class=\"fullstory-poster\">.*?<img\\s[^>]*?src=\"([^\"]+)\"", Pattern.DOTALL);
    /** The synopsis is in the page head and the poster a little further down, so only the start of the page is read. */
    private static final int HEAD_BYTES = 48 * 1024;
    private static final Pattern PANEL = Pattern.compile(
            "language-player-panel\"\\s+data-player-id=\"(movie|series)-(geo|rus|eng)\"");
    private static final Pattern MOVIE_JSON = Pattern.compile("\\bmovie:\\s*\\[");
    private static final Pattern SERIES_JSON = Pattern.compile("\\bplaylist:\\s*\\{");
    private static final Pattern GENRE_SELECT = Pattern.compile(
            "<select name=\"cat\"[^>]*>(.*?)</select>", Pattern.DOTALL);
    private static final Pattern GENRE_OPTION = Pattern.compile(
            "<option value=\"([^\"]+)\">([^<]*)</option>");
    private static final String FILTER_SUFFIX = "/sort=date/order=desc/";
    /** The first option of a genre list is "all movies / all anime", which is just the unfiltered category. */
    private static final String ALL_PREFIX = "ყველა";

    /** Panel languages in order of preference: Georgian first. */
    private static final String[] LANGUAGES = {"geo", "rus", "eng"};
    private static final String GEORGIAN_LABEL = "ქართ";

    private Site() {
    }

    static final class Title {
        final String name;
        final String url;
        /** Poster image, release year and rating as shown on the listing card; null when the card has none. */
        final String poster;
        final String year;
        final String rating;

        Title(String name, String url, String poster, String year, String rating) {
            this.name = name;
            this.url = url;
            this.poster = poster;
            this.year = year;
            this.rating = rating;
        }
    }

    static final class Episode {
        final String title;
        final String url;

        Episode(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    static final class Season {
        final String name;
        final List<Episode> episodes;

        Season(String name, List<Episode> episodes) {
            this.name = name;
            this.episodes = episodes;
        }
    }

    /** A genre filter: {@code url} is a listing page (ending in '/') that paginates like a category. */
    static final class Genre {
        final String name;
        final String url;

        Genre(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    /** What a title's page contains: either one movie stream or a list of seasons. Both empty if nothing playable. */
    static final class Item {
        String movieUrl;
        final List<Season> seasons = new ArrayList<>();
    }

    /**
     * Genres from the filter on a category page. Movie and series pages identify genres by numeric category id
     * (served through the filter plugin), anime pages by a plain sub-category path.
     */
    static List<Genre> parseGenres(String html) {
        List<Genre> genres = new ArrayList<>();
        Matcher select = GENRE_SELECT.matcher(COMMENT.matcher(html).replaceAll(""));
        if (!select.find()) {
            return genres;
        }
        Matcher option = GENRE_OPTION.matcher(select.group(1));
        while (option.find()) {
            String value = option.group(1);
            String name = Html.fromHtml(option.group(2), Html.FROM_HTML_MODE_LEGACY).toString().trim();
            if (name.startsWith(ALL_PREFIX)) {
                continue;
            }
            String url = value.startsWith("/") ? BASE_URL + value : BASE_URL + "/f/cat=" + value + FILTER_SUFFIX;
            genres.add(new Genre(name, url));
        }
        return genres;
    }

    static String searchUrl(String query) throws IOException {
        return BASE_URL + "/?do=search&subaction=search&story=" + URLEncoder.encode(query, "UTF-8");
    }

    static OkHttpClient client() {
        return CLIENT;
    }

    static String fetch(String url) throws IOException {
        Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            return response.body().string();
        }
    }

    /** Extras from a title's own page: the synopsis and the poster. Either may be empty. */
    static final class Details {
        final String description;
        final String poster;

        Details(String description, String poster) {
            this.description = description;
            this.poster = poster;
        }
    }

    /** Reads the synopsis and poster from a title's page, downloading only the first part of it. */
    static Details fetchDetails(String url) throws IOException {
        Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            BufferedSource source = response.body().source();
            source.request(HEAD_BYTES);
            String head = source.getBuffer().readUtf8(Math.min(source.getBuffer().size(), HEAD_BYTES));

            String description = firstGroup(META_DESCRIPTION, head);
            if (description != null) {
                description = Html.fromHtml(description, Html.FROM_HTML_MODE_LEGACY).toString().trim();
            }
            String poster = firstGroup(POSTER_IMG, head);
            if (poster == null) {
                // The social-sharing image is a wide backdrop; ask TMDB for a thumbnail-sized version.
                poster = firstGroup(OG_IMAGE, head);
                if (poster != null) {
                    poster = poster.replace("/w1280/", "/w342/");
                }
            }
            return new Details(description == null ? "" : description, poster);
        }
    }

    /**
     * Uses the system resolver, but falls back to Cloudflare's DNS-over-HTTPS when it can't resolve a host. Some home
     * networks block mykadri.tv at the router's DNS, which otherwise makes the app look offline.
     */
    private static final class FallbackDns implements Dns {

        private final OkHttpClient bootstrap = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
        private final Map<String, List<InetAddress>> resolved = new ConcurrentHashMap<>();

        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            List<InetAddress> cached = resolved.get(hostname);
            if (cached != null) {
                return cached;
            }
            try {
                return Dns.SYSTEM.lookup(hostname);
            } catch (UnknownHostException e) {
                List<InetAddress> addresses = resolveOverHttps(hostname);
                if (addresses.isEmpty()) {
                    throw e;
                }
                resolved.put(hostname, addresses);
                return addresses;
            }
        }

        private List<InetAddress> resolveOverHttps(String hostname) {
            Request request = new Request.Builder()
                    .url(DOH_URL + hostname)
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
                    if (answer.optInt("type") == DNS_TYPE_A) {
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

    /**
     * Titles listed on a category or search page, with the poster, year and rating shown on each card. The site also
     * keeps old cards in HTML comments; those are ignored.
     */
    static List<Title> parseTitles(String html) {
        List<Title> titles = new ArrayList<>();
        String page = COMMENT.matcher(html).replaceAll("");
        Matcher blocks = CARD_BLOCK.matcher(page);
        while (blocks.find()) {
            String block = blocks.group(1);
            Matcher card = CARD.matcher(block);
            if (!card.find()) {
                continue;
            }
            String name = Html.fromHtml(card.group(2), Html.FROM_HTML_MODE_LEGACY).toString().trim();
            String poster = firstGroup(SRCSET, block);
            if (poster == null) {
                poster = firstGroup(IMG_SRC, block);
            }
            if (poster != null && poster.startsWith("/")) {
                poster = BASE_URL + poster;
            }
            titles.add(new Title(name, card.group(1), poster, firstGroup(CARD_YEAR, block),
                    firstGroup(CARD_RATING, block)));
        }
        if (titles.isEmpty()) {
            // Unfamiliar markup: still list the titles, just without the extras.
            Matcher matcher = CARD.matcher(page);
            while (matcher.find()) {
                String name = Html.fromHtml(matcher.group(2), Html.FROM_HTML_MODE_LEGACY).toString().trim();
                titles.add(new Title(name, matcher.group(1), null, null, null));
            }
        }
        return titles;
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).trim();
        return value.isEmpty() ? null : value;
    }

    static Item parseItem(String html) throws JSONException {
        Item item = new Item();
        String page = COMMENT.matcher(html).replaceAll("");

        // Split the page into player panels (movie-geo, series-rus, ...) and take the preferred one that has data.
        List<int[]> panels = new ArrayList<>();
        List<String> panelLanguages = new ArrayList<>();
        Matcher panel = PANEL.matcher(page);
        while (panel.find()) {
            panels.add(new int[]{panel.end(), page.length()});
            panelLanguages.add(panel.group(2));
            if (panels.size() > 1) {
                panels.get(panels.size() - 2)[1] = panel.start();
            }
        }
        for (String language : LANGUAGES) {
            for (int i = 0; i < panels.size(); i++) {
                if (!panelLanguages.get(i).equals(language)) {
                    continue;
                }
                String segment = page.substring(panels.get(i)[0], panels.get(i)[1]);
                if (fillItem(item, segment)) {
                    return item;
                }
            }
        }
        return item;
    }

    private static boolean fillItem(Item item, String segment) throws JSONException {
        Matcher movie = MOVIE_JSON.matcher(segment);
        if (movie.find()) {
            String json = extractBalanced(segment, movie.end() - 1);
            String url = firstSourceUrl(new JSONArray(json));
            if (url != null) {
                item.movieUrl = url;
                return true;
            }
        }
        Matcher series = SERIES_JSON.matcher(segment);
        if (series.find()) {
            JSONObject playlist = new JSONObject(extractBalanced(segment, series.end() - 1));
            List<String> keys = new ArrayList<>();
            for (Iterator<String> it = playlist.keys(); it.hasNext(); ) {
                keys.add(it.next());
            }
            Collections.sort(keys, (a, b) -> {
                try {
                    return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
                } catch (NumberFormatException e) {
                    return a.compareTo(b);
                }
            });
            for (String key : keys) {
                JSONArray episodesJson = playlist.getJSONArray(key);
                List<Episode> episodes = new ArrayList<>();
                for (int i = 0; i < episodesJson.length(); i++) {
                    JSONObject episode = episodesJson.getJSONObject(i);
                    String url = firstSourceUrl(episode.getJSONArray("languages"));
                    if (url != null) {
                        episodes.add(new Episode(episode.optString("title", String.valueOf(i + 1)), url));
                    }
                }
                if (!episodes.isEmpty()) {
                    item.seasons.add(new Season(key, episodes));
                }
            }
            return !item.seasons.isEmpty();
        }
        return false;
    }

    /** First stream URL, preferring the Georgian-labelled entry when the list has several languages. */
    private static String firstSourceUrl(JSONArray languages) throws JSONException {
        JSONObject chosen = null;
        for (int i = 0; i < languages.length(); i++) {
            JSONObject language = languages.getJSONObject(i);
            JSONArray sources = language.optJSONArray("sources");
            if (sources == null || sources.length() == 0) {
                continue;
            }
            if (chosen == null) {
                chosen = language;
            }
            if (language.optString("label").contains(GEORGIAN_LABEL)) {
                chosen = language;
                break;
            }
        }
        return chosen == null ? null : chosen.getJSONArray("sources").getJSONObject(0).getString("file");
    }

    /** Returns the JSON array/object that starts at {@code start}, skipping brackets inside string literals. */
    private static String extractBalanced(String text, int start) {
        char open = text.charAt(start);
        char close = open == '[' ? ']' : '}';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        return text.substring(start);
    }
}

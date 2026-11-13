package com.kinoki.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plain-text browser: a category tab row and a list of titles. OK on a movie plays it; OK on a series expands it into
 * seasons and episodes in place.
 */
public class MainActivity extends Activity {

    /** Name, listing path, and whether the category has a genre filter ("true"/"false"). */
    private static final String[][] CATEGORIES = {
            {"ფავორიტები", "", "false"},
            {"ბოლოს ნანახი", "", "false"},
            {"ფილმები", "/filmebiqartulad/", "true"},
            {"სერიალები", "/serialebi_qartulad/", "true"},
            {"თურქული სერიალები", "/turquli-serialebi-qartulad/", "false"},
            {"ანიმე", "/anime/", "true"},
    };
    /** The favorites and recently-watched tabs list locally saved titles instead of a category on the site. */
    private static final int FAVORITES_TAB = 0;
    private static final int RECENT_TAB = 1;
    private static final int MOVIES_TAB = 2;
    private static final int MIN_SEARCH_LENGTH = 3;
    private static final int PREFETCH_ROWS = 6;
    private static final int INDENT_DP = 32;
    private static final int BASE_PADDING_DP = 24;
    private static final String FAVORITE_MARK = "★ ";
    private static final int FAVORITE_COLOR = 0xFFFFC107;
    private static final String WATCHED_MARK = "✓ ";
    private static final int WATCHED_COLOR = 0xFF4CAF50;

    private enum Kind {TITLE, SEASON, EPISODE}

    private static final class Row {
        final Kind kind;
        final int depth;
        final String label;
        final String url;
        /** The listing entry (with poster, year, rating) for title rows; null for seasons and episodes. */
        final Site.Title title;
        /** For seasons and episodes, the title they belong to; null on title rows. */
        Row owner;
        boolean loading;
        boolean expanded;
        /** Seasons or episodes under this row; null until a series has been opened. */
        List<Row> children;

        Row(Kind kind, int depth, String label, String url) {
            this.kind = kind;
            this.depth = depth;
            this.label = label;
            this.url = url;
            this.title = null;
        }

        Row(Site.Title title) {
            this.kind = Kind.TITLE;
            this.depth = 0;
            this.label = title.name;
            this.url = title.url;
            this.title = title;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Set<String> seenUrls = new HashSet<>();
    private final List<TextView> tabViews = new ArrayList<>();
    /** Genres per category tab, filled in from each category's first page; null until loaded. */
    private final List<List<Site.Genre>> genresByTab = new ArrayList<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    private RowAdapter adapter;
    private ListView list;
    private TextView status;
    private EditText search;
    private TextView genreButton;

    /** Index into CATEGORIES of the tab being shown, or -1 while showing search results. */
    private int currentTab = -1;
    private Site.Genre activeGenre;
    /** Listing URL being paged through (category or genre), or null while showing search results. */
    private String categoryUrl;
    private String searchQuery;
    private int nextPage;
    private boolean hasMore;
    private boolean loading;
    /** Bumped on every list reset so late responses from an earlier category are dropped. */
    private int generation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        list = findViewById(R.id.list);
        search = findViewById(R.id.search);
        LinearLayout tabs = findViewById(R.id.tabs);
        LinearLayout filters = findViewById(R.id.filters);

        for (int i = 0; i < CATEGORIES.length; i++) {
            int index = i;
            TextView tab = (TextView) getLayoutInflater().inflate(R.layout.item_tab, tabs, false);
            tab.setText(CATEGORIES[i][0]);
            tab.setOnClickListener(v -> showCategory(index));
            tabs.addView(tab);
            tabViews.add(tab);
            genresByTab.add(null);
        }

        genreButton = (TextView) getLayoutInflater().inflate(R.layout.item_tab, filters, false);
        genreButton.setId(View.generateViewId());
        genreButton.setOnClickListener(v -> showGenreDialog());
        filters.addView(genreButton, 0);

        search.setOnEditorActionListener((v, actionId, event) -> {
            String query = v.getText().toString().trim();
            if (query.length() < MIN_SEARCH_LENGTH) {
                Toast.makeText(this, R.string.search_too_short, Toast.LENGTH_SHORT).show();
                return true;
            }
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            showSearch(query);
            return true;
        });

        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> onRowClicked(position));
        list.setOnItemLongClickListener((parent, view, position, id) -> onRowLongClicked(position));
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (first + visible >= total - PREFETCH_ROWS) {
                    loadMore();
                }
            }
        });

        // Start on the favorites if there are any, otherwise on the movies.
        TitleStore.FAVORITES.load(this);
        TitleStore.RECENT.load(this);
        showCategory(TitleStore.FAVORITES.all().isEmpty() ? MOVIES_TAB : FAVORITES_TAB);

        // On a TV, stay reachable from the phone even after the user goes back to the launcher.
        if (CastHost.isTv(this)) {
            CastService.start(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the player: what was just played moves to the top of the recently watched list.
        if (currentTab == RECENT_TAB) {
            showCategory(RECENT_TAB);
        } else {
            // Episodes and movies finished in the player turn green.
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ---- list loading -------------------------------------------------------------------------------------------

    private void showCategory(int index) {
        for (int i = 0; i < tabViews.size(); i++) {
            tabViews.get(i).setSelected(i == index);
        }
        resetList();
        currentTab = index;
        activeGenre = null;
        updateGenreButton();
        if (index == FAVORITES_TAB) {
            showStored(TitleStore.FAVORITES, R.string.no_favorites);
            return;
        }
        if (index == RECENT_TAB) {
            showStored(TitleStore.RECENT, R.string.no_recent);
            return;
        }
        categoryUrl = Site.BASE_URL + CATEGORIES[index][1];
        loadMore();
    }

    /** Lists a locally saved set of titles (favorites, recently watched); nothing is fetched from the site. */
    private void showStored(TitleStore store, int emptyMessage) {
        hasMore = false;
        for (Site.Title title : store.all()) {
            rows.add(new Row(title));
        }
        adapter.notifyDataSetChanged();
        setStatus(rows.isEmpty() ? getString(emptyMessage) : null);
        if (!rows.isEmpty()) {
            focusListUnlessTyping();
        }
    }

    /**
     * Moves focus to the list when new content arrives, so the remote works right away. Focus is left alone while the
     * user is typing a search, and wherever it already is inside the list.
     */
    private void focusListUnlessTyping() {
        if (!search.hasFocus() && !list.hasFocus()) {
            list.requestFocus();
        }
    }

    private void showGenre(Site.Genre genre) {
        resetList();
        activeGenre = genre;
        updateGenreButton();
        categoryUrl = genre.url;
        loadMore();
    }

    private void showSearch(String query) {
        for (TextView tab : tabViews) {
            tab.setSelected(false);
        }
        resetList();
        currentTab = -1;
        activeGenre = null;
        updateGenreButton();
        searchQuery = query;
        loadMore();
    }

    private void showGenreDialog() {
        List<Site.Genre> genres = currentTab < 0 ? null : genresByTab.get(currentTab);
        if (genres == null || genres.isEmpty()) {
            Toast.makeText(this, R.string.genres_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        int tab = currentTab;
        String[] names = new String[genres.size() + 1];
        names[0] = getString(R.string.all_genres);
        for (int i = 0; i < genres.size(); i++) {
            names[i + 1] = genres.get(i).name;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.genre)
                .setItems(names, (dialog, which) -> {
                    if (which == 0) {
                        showCategory(tab);
                    } else {
                        showGenre(genres.get(which - 1));
                    }
                })
                .show();
    }

    /** The genre button only exists for tabs that have genres; it shows the active genre and stays highlighted. */
    private void updateGenreButton() {
        boolean available = currentTab >= 0 && Boolean.parseBoolean(CATEGORIES[currentTab][2]);
        genreButton.setVisibility(available ? View.VISIBLE : View.GONE);
        // Geometry-based focus search skips this row when moving up from the list, so point it there explicitly.
        list.setNextFocusUpId(available ? genreButton.getId() : R.id.search);
        genreButton.setText(activeGenre == null ? getString(R.string.genre)
                : getString(R.string.genre_selected, activeGenre.name));
        genreButton.setSelected(activeGenre != null);
    }

    private void resetList() {
        generation++;
        rows.clear();
        seenUrls.clear();
        adapter.notifyDataSetChanged();
        categoryUrl = null;
        searchQuery = null;
        nextPage = 1;
        hasMore = true;
        loading = false;
    }

    private void loadMore() {
        if (loading || !hasMore) {
            return;
        }
        loading = true;
        int gen = generation;
        int page = nextPage;
        String category = categoryUrl;
        String query = searchQuery;
        // The first unfiltered page of a category also carries that category's genre list.
        int tab = currentTab;
        boolean wantGenres = category != null && page == 1 && activeGenre == null && tab >= 0
                && Boolean.parseBoolean(CATEGORIES[tab][2]);
        setStatus(getString(R.string.loading));
        executor.execute(() -> {
            try {
                String url = category == null ? Site.searchUrl(query)
                        : page == 1 ? category : category + "page/" + page + "/";
                String html = Site.fetch(url);
                List<Site.Title> titles = Site.parseTitles(html);
                List<Site.Genre> genres = wantGenres ? Site.parseGenres(html) : null;
                runOnUiThread(() -> onPageLoaded(gen, titles, tab, genres));
            } catch (Exception e) {
                runOnUiThread(() -> onPageFailed(gen));
            }
        });
    }

    private void onPageLoaded(int gen, List<Site.Title> titles, int tab, List<Site.Genre> genres) {
        if (gen != generation) {
            return;
        }
        if (genres != null) {
            genresByTab.set(tab, genres);
        }
        boolean firstPage = rows.isEmpty();
        int added = 0;
        for (Site.Title title : titles) {
            if (seenUrls.add(title.url)) {
                rows.add(new Row(title));
                added++;
            }
        }
        loading = false;
        nextPage++;
        hasMore = categoryUrl != null && added > 0;
        adapter.notifyDataSetChanged();
        setStatus(rows.isEmpty() ? getString(R.string.nothing_found) : null);
        if (firstPage && !rows.isEmpty()) {
            focusListUnlessTyping();
        }
    }

    private void onPageFailed(int gen) {
        if (gen != generation) {
            return;
        }
        loading = false;
        hasMore = false;
        setStatus(rows.isEmpty() ? getString(R.string.load_failed) : null);
    }

    private void setStatus(String text) {
        status.setText(text);
        status.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    // ---- row actions --------------------------------------------------------------------------------------------

    private void onRowClicked(int position) {
        Row row = rows.get(position);
        switch (row.kind) {
            case TITLE:
                onTitleClicked(row);
                break;
            case SEASON:
                toggle(row);
                break;
            case EPISODE:
                play(row.owner, row.url, row.label, row.url);
                break;
        }
    }

    private void onTitleClicked(Row row) {
        if (row.children != null) {
            toggle(row);
            return;
        }
        if (row.loading) {
            return;
        }
        row.loading = true;
        setStatus(getString(R.string.loading));
        executor.execute(() -> {
            try {
                Site.Item item = Site.parseItem(Site.fetch(row.url));
                runOnUiThread(() -> onItemLoaded(row, item));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    row.loading = false;
                    setStatus(null);
                    Toast.makeText(this, R.string.load_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void onItemLoaded(Row title, Site.Item item) {
        title.loading = false;
        setStatus(rows.isEmpty() ? getString(R.string.nothing_found) : null);
        if (item.movieUrl != null) {
            play(title, item.movieUrl, title.label, title.url);
            return;
        }
        if (item.seasons.isEmpty()) {
            Toast.makeText(this, R.string.nothing_playable, Toast.LENGTH_LONG).show();
            return;
        }
        title.children = new ArrayList<>();
        boolean single = item.seasons.size() == 1;
        for (Site.Season season : item.seasons) {
            Row parent = title;
            if (!single) {
                Row seasonRow = new Row(Kind.SEASON, title.depth + 1, getString(R.string.season, season.name), null);
                seasonRow.owner = title;
                seasonRow.children = new ArrayList<>();
                title.children.add(seasonRow);
                parent = seasonRow;
            }
            for (Site.Episode episode : season.episodes) {
                Row episodeRow = new Row(Kind.EPISODE, parent.depth + 1, episode.title, episode.url);
                episodeRow.owner = title;
                parent.children.add(episodeRow);
            }
        }
        if (rows.contains(title)) {
            toggle(title);
        }
    }

    /** Expands a row to show its children, or collapses it (removing all descendants from the visible list). */
    private void toggle(Row row) {
        int position = rows.indexOf(row);
        if (position < 0) {
            return;
        }
        if (row.expanded) {
            int end = position + 1;
            while (end < rows.size() && rows.get(end).depth > row.depth) {
                end++;
            }
            rows.subList(position + 1, end).clear();
            row.expanded = false;
        } else {
            List<Row> visible = new ArrayList<>();
            collectVisible(row, visible);
            rows.addAll(position + 1, visible);
            row.expanded = true;
        }
        adapter.notifyDataSetChanged();
    }

    private static void collectVisible(Row row, List<Row> out) {
        for (Row child : row.children) {
            out.add(child);
            if (child.expanded && child.children != null) {
                collectVisible(child, out);
            }
        }
    }

    /** Long press (held OK on a remote) on a title adds it to the favorites, or removes it if it is already there. */
    private boolean onRowLongClicked(int position) {
        Row row = rows.get(position);
        if (row.kind != Kind.TITLE) {
            return false;
        }
        boolean added = TitleStore.FAVORITES.toggle(this, row.title);
        Toast.makeText(this, added ? R.string.favorite_added : R.string.favorite_removed, Toast.LENGTH_SHORT).show();
        if (!added && currentTab == FAVORITES_TAB) {
            if (row.expanded) {
                toggle(row);
            }
            rows.remove(row);
            setStatus(rows.isEmpty() ? getString(R.string.no_favorites) : null);
        }
        adapter.notifyDataSetChanged();
        return true;
    }

    /** Plays a stream and remembers its title (the movie, or the series an episode belongs to) as recently watched. */
    private void play(Row title, String url, String label, String watchKey) {
        TitleStore.RECENT.addFirst(this, title.title);
        startActivity(new Intent(this, PlayerActivity.class)
                .putExtra(PlayerActivity.EXTRA_URL, url)
                .putExtra(PlayerActivity.EXTRA_TITLE, label)
                .putExtra(PlayerActivity.EXTRA_WATCH_KEY, watchKey));
    }

    // ---- adapter ------------------------------------------------------------------------------------------------

    private final class RowAdapter extends BaseAdapter {

        private static final int TYPE_TITLE = 0;
        private static final int TYPE_TEXT = 1;

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).kind == Kind.TITLE ? TYPE_TITLE : TYPE_TEXT;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = rows.get(position);
            return row.kind == Kind.TITLE ? bindTitle(row, convertView, parent) : bindText(row, convertView, parent);
        }

        /** A title: poster, name (starred if a favorite, green if watched), year and rating, and the synopsis. */
        private View bindTitle(Row row, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.item_title, parent, false);
            Site.Title title = row.title;

            TextView name = view.findViewById(R.id.name);
            if (TitleStore.FAVORITES.contains(row.url)) {
                SpannableString starred = new SpannableString(FAVORITE_MARK + row.label);
                starred.setSpan(new ForegroundColorSpan(FAVORITE_COLOR), 0, FAVORITE_MARK.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                name.setText(starred);
            } else {
                name.setText(row.label);
            }
            name.setTextColor(Progress.isWatched(MainActivity.this, row.url) ? WATCHED_COLOR : 0xFFFFFFFF);

            StringBuilder meta = new StringBuilder();
            if (title.year != null) {
                meta.append(title.year);
            }
            if (title.rating != null) {
                meta.append(meta.length() > 0 ? "  ·  " : "").append(getString(R.string.imdb_rating, title.rating));
            }
            TextView metaView = view.findViewById(R.id.meta);
            metaView.setText(meta);
            metaView.setVisibility(meta.length() > 0 ? View.VISIBLE : View.GONE);

            Site.Details details = Descriptions.get(row.url);
            if (details == null) {
                Descriptions.request(row.url, this::notifyDataSetChanged);
            }
            // Titles saved before posters were kept have none of their own; borrow the one from the title's page.
            Images.load(view.findViewById(R.id.poster),
                    title.poster != null ? title.poster : details != null ? details.poster : null);
            String description = details == null ? "" : details.description;
            TextView descriptionView = view.findViewById(R.id.description);
            descriptionView.setText(description);
            descriptionView.setVisibility(description.isEmpty() ? View.GONE : View.VISIBLE);
            return view;
        }

        /** A season or episode: plain text, with watched episodes in green. */
        private View bindText(Row row, View convertView, ViewGroup parent) {
            TextView view = (TextView) convertView;
            if (view == null) {
                view = (TextView) getLayoutInflater().inflate(R.layout.item_row, parent, false);
            }
            float density = getResources().getDisplayMetrics().density;
            view.setPadding(Math.round((BASE_PADDING_DP + row.depth * INDENT_DP) * density),
                    view.getPaddingTop(), view.getPaddingRight(), view.getPaddingBottom());
            boolean watched = row.kind == Kind.EPISODE && Progress.isWatched(MainActivity.this, row.url);
            view.setText(watched ? WATCHED_MARK + row.label : row.label);
            view.setTextSize(19);
            view.setTextColor(watched ? WATCHED_COLOR : row.kind == Kind.SEASON ? 0xFFE0C068 : 0xFFC8C8D8);
            return view;
        }
    }
}

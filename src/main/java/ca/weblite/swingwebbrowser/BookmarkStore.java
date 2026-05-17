package ca.weblite.swingwebbrowser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistent bookmark list.  Backed by {@code ~/.swingwebbrowser/bookmarks.tsv}
 * with {@code title\turl} per line.  Order is preserved.
 */
public final class BookmarkStore {

    public static final class Bookmark {
        public final String title;
        public final String url;

        public Bookmark(String title, String url) {
            this.title = title;
            this.url = url;
        }

        @Override public String toString() {
            // The JComboBox in the toolbar shows this; keep it brief.
            return title;
        }
    }

    private final Path file;
    private final List<Bookmark> bookmarks = new ArrayList<>();

    public BookmarkStore() {
        this.file = Paths.get(System.getProperty("user.home"),
                              ".swingwebbrowser", "bookmarks.tsv");
        load();
        if (bookmarks.isEmpty()) seedDefaults();
    }

    public List<Bookmark> bookmarks() {
        return Collections.unmodifiableList(bookmarks);
    }

    public boolean contains(String url) {
        for (Bookmark b : bookmarks) if (b.url.equals(url)) return true;
        return false;
    }

    public void add(String title, String url) {
        if (url == null || url.isEmpty()) return;
        if (contains(url)) return;
        bookmarks.add(new Bookmark(title == null || title.isEmpty() ? url : title, url));
        rewrite();
    }

    public void remove(String url) {
        if (bookmarks.removeIf(b -> b.url.equals(url))) {
            rewrite();
        }
    }

    private void seedDefaults() {
        bookmarks.add(new Bookmark("Example", "https://example.com"));
        bookmarks.add(new Bookmark("Wikipedia", "https://en.wikipedia.org"));
        bookmarks.add(new Bookmark("Hacker News", "https://news.ycombinator.com"));
        bookmarks.add(new Bookmark("DuckDuckGo", "https://duckduckgo.com"));
        bookmarks.add(new Bookmark("OpenJDK", "https://openjdk.org"));
        rewrite();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", 2);
                if (parts.length != 2) continue;
                bookmarks.add(new Bookmark(parts[0], parts[1]));
            }
        } catch (IOException e) {
            System.err.println("[swingwebbrowser] failed to load bookmarks: " + e);
        }
    }

    private void rewrite() {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            for (Bookmark b : bookmarks) {
                String safeTitle = b.title.replace('\t', ' ').replace('\n', ' ');
                sb.append(safeTitle).append('\t').append(b.url).append('\n');
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                              StandardOpenOption.CREATE,
                              StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[swingwebbrowser] failed to write bookmarks: " + e);
        }
    }
}

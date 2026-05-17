package ca.weblite.swingwebbrowser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Append-only browser history, persisted to
 * {@code ~/.swingwebbrowser/history.tsv}.  Each entry is
 * {@code timestamp\ttitle\turl} -- titles default to the URL when the
 * page never told us its real title.
 *
 * <p>This class is not thread-safe; callers stick to the EDT.
 */
public final class HistoryStore {

    /** Cap so the file doesn't grow without bound across long-lived
     *  installs.  When we hit the cap we keep the most-recent half. */
    private static final int MAX_ENTRIES = 5000;

    public static final class Entry {
        public final Instant when;
        public String title;
        public final String url;

        public Entry(Instant when, String title, String url) {
            this.when = when;
            this.title = title;
            this.url = url;
        }
    }

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    public HistoryStore() {
        this.file = Paths.get(System.getProperty("user.home"),
                              ".swingwebbrowser", "history.tsv");
        load();
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * Record a visit.  Coalesces with the immediately previous entry if
     * the URL is identical (the page reloading itself or fragment
     * navigation shouldn't spam the list).
     */
    public void record(String url, String title) {
        if (url == null || url.isEmpty()) return;
        if (url.startsWith("about:") || url.startsWith("data:")) return;
        if (!entries.isEmpty()) {
            Entry tail = entries.get(entries.size() - 1);
            if (tail.url.equals(url)) {
                if (title != null && !title.isEmpty()) tail.title = title;
                return;
            }
        }
        entries.add(new Entry(Instant.now(),
                              title == null ? url : title, url));
        if (entries.size() > MAX_ENTRIES) {
            entries.subList(0, entries.size() - MAX_ENTRIES / 2).clear();
            rewrite();
            return;
        }
        save();
    }

    public void clear() {
        entries.clear();
        rewrite();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", 3);
                if (parts.length != 3) continue;
                try {
                    entries.add(new Entry(Instant.parse(parts[0]),
                                          parts[1], parts[2]));
                } catch (Exception ignored) {
                    // Skip malformed lines.
                }
            }
        } catch (IOException e) {
            System.err.println("[swingwebbrowser] failed to load history: " + e);
        }
    }

    private void save() {
        Entry last = entries.get(entries.size() - 1);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, format(last) + "\n",
                              StandardCharsets.UTF_8,
                              StandardOpenOption.CREATE,
                              StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[swingwebbrowser] failed to write history: " + e);
        }
    }

    private void rewrite() {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            for (Entry e : entries) sb.append(format(e)).append('\n');
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                              StandardOpenOption.CREATE,
                              StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[swingwebbrowser] failed to rewrite history: " + e);
        }
    }

    private static String format(Entry e) {
        // Tabs and newlines in titles would corrupt the on-disk format.
        String safeTitle = e.title.replace('\t', ' ').replace('\n', ' ');
        return e.when.toString() + '\t' + safeTitle + '\t' + e.url;
    }
}

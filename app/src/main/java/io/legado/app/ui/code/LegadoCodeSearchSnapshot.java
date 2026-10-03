package io.github.rosemoe.sora.widget;

import androidx.annotation.Nullable;
import io.github.rosemoe.sora.util.IntPair;
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only compatibility adapter for pinned Sora 0.24.6.
 *
 * <p>Sora exposes no public accepted-result snapshot or replace-all progress factory. Its own
 * CodeEditor reads these protected fields from this package. This adapter copies only accepted
 * search metadata; it never changes library state or uses reflection. The app applies the original
 * replacement algorithms and renders progress in Compose instead of Sora's ProgressDialog.
 */
public final class LegadoCodeSearchSnapshot {
    private LegadoCodeSearchSnapshot() {}

    @Nullable
    public static Snapshot capture(CodeEditor editor) {
        EditorSearcher searcher = editor.getSearcher();
        // A worker can exit before its Main publication runs. isResultValid() alone then permits
        // old positions with a new pattern. Only the accepted Main publication clears this field.
        if (!searcher.hasQuery()
                || searcher.currentThread != null
                || searcher.lastResults == null
                || searcher.searchOptions == null) {
            return null;
        }
        List<Region> regions = new ArrayList<>(searcher.lastResults.size());
        for (int index = 0; index < searcher.lastResults.size(); index++) {
            long region = searcher.lastResults.get(index);
            regions.add(new Region(IntPair.getFirst(region), IntPair.getSecond(region)));
        }
        return new Snapshot(
                editor.getText().toString(),
                searcher.currentPattern,
                searcher.searchOptions.type,
                searcher.searchOptions.caseInsensitive,
                searcher.searchOptions.regexBackrefGrammar,
                searcher.getReplaceOptions().preserveCase,
                regions);
    }

    public static final class Snapshot {
        public final String source;
        public final String pattern;
        public final int type;
        public final boolean caseInsensitive;
        @Nullable public final RegexBackrefGrammar grammar;
        public final boolean preserveCase;
        public final List<Region> regions;

        private Snapshot(
                String source,
                String pattern,
                int type,
                boolean caseInsensitive,
                @Nullable RegexBackrefGrammar grammar,
                boolean preserveCase,
                List<Region> regions) {
            this.source = source;
            this.pattern = pattern;
            this.type = type;
            this.caseInsensitive = caseInsensitive;
            this.grammar = grammar;
            this.preserveCase = preserveCase;
            this.regions = Collections.unmodifiableList(new ArrayList<>(regions));
        }
    }

    public static final class Region {
        public final int start;
        public final int end;

        private Region(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}

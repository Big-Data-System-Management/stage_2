package bdsm.indexing.events;

import bdsm.common.model.BookMetadata;
import bdsm.indexing.index.Datamart;
import bdsm.indexing.index.IndexStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

class InMemoryDatamart implements Datamart {

    final Map<Integer, BookMetadata> published = new TreeMap<>();
    final Map<Integer, String> paths = new TreeMap<>();
    final List<Integer> indexCalls = new ArrayList<>();

    @Override
    public boolean isPublished(int bookId) {
        return published.containsKey(bookId);
    }

    @Override
    public void index(BookMetadata metadata, String path, String body) {
        indexCalls.add(metadata.bookId());
        published.put(metadata.bookId(), metadata);
        paths.put(metadata.bookId(), path);
    }

    @Override
    public void rebuild(Loader loader) throws Exception {
        InMemoryDatamart staging = new InMemoryDatamart();
        loader.load(staging);
        published.clear();
        published.putAll(staging.published);
        paths.clear();
        paths.putAll(staging.paths);
        indexCalls.addAll(staging.indexCalls);
    }

    @Override
    public IndexStats stats() {
        return new IndexStats(published.size(), null, 0);
    }
}

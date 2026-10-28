package bdsm.indexing.index;

import java.util.Set;

public interface InvertedIndex {
    void indexBook(int bookId, String body);

    Set<Integer> search(String term);
}

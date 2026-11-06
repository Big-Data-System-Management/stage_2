package bdsm.ingestion.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookIndexTest {

    private static final BookFiles COMPLETE = new BookFiles(true, true);

    private final BookIndex index = new BookIndex();

    @Test
    void keepsTheFirstCompleteCopyWhenABookAppearsInTwoFolders() {
        index.replaceFolder("20261028/10", 1L, Map.of(5, COMPLETE));
        index.replaceFolder("20261028/11", 2L, Map.of(5, COMPLETE));

        assertEquals("20261028/10", index.completeEntry(5).orElseThrow().folder());
    }

    @Test
    void replacesAnIncompleteCopyWithACompleteOne() {
        index.replaceFolder("20261028/10", 1L, Map.of(5, BookFiles.HEADER_ONLY));
        index.replaceFolder("20261028/11", 2L, Map.of(5, COMPLETE));

        assertEquals("20261028/11", index.completeEntry(5).orElseThrow().folder());
    }

    @Test
    void doesNotListIncompleteBooks() {
        index.replaceFolder("20261028/10", 1L, Map.of(1, COMPLETE, 2, BookFiles.HEADER_ONLY, 3, BookFiles.BODY_ONLY));

        assertEquals(List.of(1), index.completeIds());
        assertTrue(index.completeEntry(2).isEmpty());
    }

    @Test
    void removesTheBooksThatDisappearedFromARescannedFolder() {
        index.replaceFolder("20261028/10", 1L, Map.of(1, COMPLETE, 2, COMPLETE));

        index.replaceFolder("20261028/10", 2L, Map.of(1, COMPLETE));

        assertEquals(List.of(1), index.completeIds());
    }

    @Test
    void removingAFolderForgetsItsBooksAndKeepsTheOthers() {
        index.replaceFolder("20261028/10", 1L, Map.of(1, COMPLETE));
        index.replaceFolder("20261028/11", 2L, Map.of(2, COMPLETE));

        index.removeFolder("20261028/10");

        assertEquals(List.of(2), index.completeIds());
        assertTrue(index.folder("20261028/10").isEmpty());
    }

    @Test
    void discardingABookMarksItsFolderAsStale() {
        index.replaceFolder("20261028/10", 7L, Map.of(1, COMPLETE));

        index.discard(index.completeEntry(1).orElseThrow());

        assertTrue(index.completeEntry(1).isEmpty());
        assertFalse(index.folder("20261028/10").orElseThrow().isUpToDate(7L));
    }
}

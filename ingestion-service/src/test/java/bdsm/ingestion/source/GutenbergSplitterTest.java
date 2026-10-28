package bdsm.ingestion.source;

import bdsm.common.model.Book;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GutenbergSplitterTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "*** START OF THE PROJECT GUTENBERG EBOOK PI ***",
            "*** START OF THIS PROJECT GUTENBERG EBOOK PI ***",
            "***START OF THE PROJECT GUTENBERG EBOOK PI***",
            "*** Start of the Project Gutenberg eBook Pi ***",
            "*** START OF THIS PROJECT GUTENBERG E-BOOK PI ***"})
    void acceptsTheStartMarkerVariants(String startMarker) {
        String text = "Title: Pi\r\n\r\n" + startMarker + "\r\n\r\n3.14159\r\n\r\n*** END OF THIS PROJECT GUTENBERG EBOOK PI ***\r\nfooter";

        Book book = GutenbergSplitter.split(1, text).orElseThrow();

        assertEquals("Title: Pi", book.header());
        assertEquals("3.14159", book.body());
    }

    @Test
    void ignoresAnEndMarkerBeforeTheStartMarker() {
        String text = "*** END OF THE PROJECT GUTENBERG EBOOK X ***\nheader\n"
                + "*** START OF THE PROJECT GUTENBERG EBOOK X ***\nbody\n"
                + "*** END OF THE PROJECT GUTENBERG EBOOK X ***\n";

        assertEquals("body", GutenbergSplitter.split(1, text).orElseThrow().body());
    }

    @Test
    void rejectsTextWithoutMarkers() {
        assertTrue(GutenbergSplitter.split(1, "plain text without markers").isEmpty());
    }
}

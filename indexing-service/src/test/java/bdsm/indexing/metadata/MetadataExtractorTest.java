package bdsm.indexing.metadata;

import bdsm.common.model.Book;
import bdsm.common.model.BookMetadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetadataExtractorTest {

    @Test
    void extractsFieldsJoiningContinuationLines() {
        String header = """
                Title: Peter Pan
                        [Peter and Wendy]
                Editor: Eric S. Raymond
                        Guy L. Steele
                Language: English
                """;

        BookMetadata metadata = MetadataExtractor.extract(new Book(16, header, "body"));

        assertEquals("Peter Pan [Peter and Wendy]", metadata.title());
        assertEquals("Eric S. Raymond; Guy L. Steele", metadata.author());
        assertEquals("English", metadata.language());
    }

    @Test
    void usesUnknownWhenAFieldIsMissing() {
        BookMetadata metadata = MetadataExtractor.extract(new Book(7, "Title: The Mayflower Compact\n", "body"));

        assertEquals("Unknown", metadata.author());
        assertEquals("Unknown", metadata.language());
    }
}

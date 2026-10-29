package bdsm.indexing.metadata;

import bdsm.common.model.Book;
import bdsm.common.model.BookMetadata;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        assertEquals("en", metadata.language());
    }

    @Test
    void usesUnknownOrNullWhenAFieldIsMissing() {
        BookMetadata metadata = MetadataExtractor.extract(new Book(7, "Title: The Mayflower Compact\n", "body"));

        assertEquals("Unknown", metadata.author());
        assertNull(metadata.language());
        assertNull(metadata.year());
    }

    @Test
    void takesTheYearFromTheOriginalPublicationOnly() {
        String header = """
                Title: Jane Eyre
                Author: Charlotte Brontë
                Release date: March 1, 1998 [eBook #1260]
                Language: English
                Original publication: London: Smith, Elder & Co., 1847
                """;

        assertEquals(1847, MetadataExtractor.extract(new Book(1260, header, "body")).year());
        assertNull(MetadataExtractor.extract(new Book(11, "Release date: June 27, 2008 [eBook #11]\n", "body")).year());
    }

    @Test
    void acceptsOnlyAYearAfterTheLastCommaOfTheField() {
        assertEquals(Optional.of(1928), MetadataExtractor.publicationYear("Garden City, NY: Doubleday, Doran & Compnay, Inc., 1928"));
        assertEquals(Optional.of(1924), MetadataExtractor.publicationYear("LONDON: KEGAN PAUL, TRENCH, TRUBNER & CO., LTD, 1924"));
        assertEquals(Optional.of(1903), MetadataExtractor.publicationYear("London: Oxford University Press, 1903."));
        assertEquals(Optional.empty(), MetadataExtractor.publicationYear("New York: Harper, 1903. Reprinted 1910"));
        assertEquals(Optional.empty(), MetadataExtractor.publicationYear("London: 1234 Strand Press"));
        assertEquals(Optional.empty(), MetadataExtractor.publicationYear("1897-1898"));
    }

    @Test
    void mapsLanguageNamesToIsoCodes() {
        assertEquals(Optional.of("fr"), MetadataExtractor.languageCode("French"));
        assertEquals(Optional.of("es"), MetadataExtractor.languageCode("Spanish, English"));
        assertEquals(Optional.of("de"), MetadataExtractor.languageCode("german"));
        assertEquals(Optional.empty(), MetadataExtractor.languageCode("Klingon"));
    }
}

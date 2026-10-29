package bdsm.indexing.index;

import bdsm.common.model.BookMetadata;
import bdsm.common.text.Tokenizer;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MongoDatamartTest {

    private static final String URI = "mongodb://localhost:27017/?serverSelectionTimeoutMS=1000";

    private final String databaseName = "indexing_test_" + UUID.randomUUID().toString().substring(0, 8);
    private MongoClient client;
    private MongoDatabase database;
    private MongoDatamart datamart;

    @BeforeEach
    void connect() {
        client = MongoClients.create(URI);
        try {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
        } catch (RuntimeException e) {
            client.close();
            assumeTrue(false, "MongoDB is not running on localhost:27017");
        }
        database = client.getDatabase(databaseName);
        datamart = new MongoDatamart(URI, databaseName, new Tokenizer());
    }

    @AfterEach
    void dropDatabase() {
        if (datamart == null) return;
        database.drop();
        datamart.close();
        client.close();
    }

    @Test
    void publishesMetadataAndPostings() {
        datamart.index(new BookMetadata(11, "Alice", "Lewis Carroll", "en", 1865), "20261028/10", "Rabbit hole, rabbit!");

        Document book = database.getCollection("books").find(eq("_id", 11)).first();
        assertEquals("published", book.getString("status"));
        assertEquals("Lewis Carroll", book.getString("author"));
        assertEquals(1865, book.getInteger("year"));
        assertNotNull(book.getDate("indexed_at"));
        assertEquals(List.of(11), postings("rabbit"));
        assertTrue(datamart.isPublished(11));
        assertFalse(datamart.isPublished(12));
    }

    @Test
    void reindexingABookDoesNotDuplicateAnything() {
        BookMetadata metadata = new BookMetadata(11, "Alice", "Lewis Carroll", "en", null);
        datamart.index(metadata, "20261028/10", "rabbit");
        datamart.index(metadata, "20261028/10", "rabbit");
        datamart.index(new BookMetadata(12, "Snark", "Lewis Carroll", "en", null), "20261028/10", "rabbit");

        assertEquals(List.of(11, 12), postings("rabbit"));
        assertEquals(2, database.getCollection("books").countDocuments());
        assertEquals(1, database.getCollection("inverted_index").countDocuments());
        assertNull(database.getCollection("books").find(eq("_id", 11)).first().get("year"));
    }

    @Test
    void rebuildReplacesTheWholeIndexAtOnce() throws Exception {
        datamart.index(new BookMetadata(1, "Old", "A", "en", null), "p", "obsolete");

        datamart.rebuild(staging -> {
            assertFalse(staging.isPublished(1));
            assertTrue(datamart.isPublished(1));
            staging.index(new BookMetadata(2, "New", "B", "en", null), "p", "fresh");
        });

        assertFalse(datamart.isPublished(1));
        assertTrue(datamart.isPublished(2));
        assertNull(database.getCollection("inverted_index").find(eq("term", "obsolete")).first());
        assertEquals(List.of(2), postings("fresh"));
        assertFalse(database.listCollectionNames().into(new ArrayList<>()).contains("books_rebuild"));
    }

    @Test
    void reportsStatistics() {
        assertEquals(0, datamart.stats().booksIndexed());
        assertNull(datamart.stats().lastUpdate());

        datamart.index(new BookMetadata(5, "Robinson Crusoe", "Daniel Defoe", "en", 1719), "p", "island");

        IndexStats stats = datamart.stats();
        assertEquals(1, stats.booksIndexed());
        assertNotNull(stats.lastUpdate());
        assertTrue(stats.indexSizeMb() > 0);
    }

    private List<Integer> postings(String term) {
        return database.getCollection("inverted_index").find(eq("term", term)).first().getList("postings", Integer.class);
    }
}

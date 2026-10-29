package bdsm.search.repository;

import bdsm.common.config.Config;
import bdsm.common.model.BookMetadata;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

import java.util.Date;
import java.util.UUID;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MongoBookRepositoryTest extends BookRepositoryContractTest {

    private static MongoClient client;
    private MongoDatabase database;

    @BeforeAll
    static void connect() {
        String uri = Config.get("MONGO_URI", "mongodb://localhost:27017");
        String separator = uri.contains("?") ? "&" : "/?";
        client = MongoClients.create(uri + separator + "serverSelectionTimeoutMS=1000");
        assumeTrue(isAvailable(), "MongoDB is not available, skipping MongoBookRepositoryTest");
    }

    private static boolean isAvailable() {
        try {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            return true;
        } catch (MongoException e) {
            return false;
        }
    }

    @AfterAll
    static void disconnect() {
        if (client != null) client.close();
    }

    @AfterEach
    void dropDatabase() {
        if (database != null) database.drop();
    }

    @Override
    protected BookRepository createRepository() {
        database = client.getDatabase("search_engine_test_" + UUID.randomUUID().toString().replace("-", ""));
        return new MongoBookRepository(database);
    }

    @Override
    protected void store(BookMetadata book, boolean published, String... terms) {
        database.getCollection("books").insertOne(new Document("_id", book.bookId())
                .append("title", book.title())
                .append("author", book.author())
                .append("language", book.language())
                .append("year", book.year())
                .append("path", "datalake/20261029/10/" + book.bookId())
                .append("status", published ? "published" : "indexing")
                .append("indexed_at", new Date()));
        for (String term : terms) {
            database.getCollection("inverted_index").updateOne(eq("term", term),
                    Updates.addToSet("postings", book.bookId()), new UpdateOptions().upsert(true));
        }
    }
}

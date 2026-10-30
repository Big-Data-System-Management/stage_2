package bdsm.integration.support;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.util.Collections;
import java.util.List;

import static com.mongodb.client.model.Filters.eq;

public final class Datamart implements AutoCloseable {

    private final MongoClient client = MongoClients.create(Settings.MONGO_URI);
    private final MongoDatabase database = client.getDatabase(Settings.MONGO_DATABASE);
    private final MongoCollection<Document> books = database.getCollection("books");
    private final MongoCollection<Document> index = database.getCollection("inverted_index");

    public void clear() {
        books.deleteMany(new Document());
        index.deleteMany(new Document());
    }

    public long bookRecords(int bookId) {
        return books.countDocuments(eq("_id", bookId));
    }

    public long termsOf(int bookId) {
        return index.countDocuments(eq("postings", bookId));
    }

    public long termRecords(String term) {
        return index.countDocuments(eq("term", term));
    }

    public int occurrencesInPostings(String term, int bookId) {
        Document document = index.find(eq("term", term)).first();
        if (document == null) return 0;
        return Collections.frequency(document.getList("postings", Integer.class), bookId);
    }

    public long publishedBooks() {
        return books.countDocuments(eq("status", "published"));
    }

    public void insertUnpublishedBook(int bookId, String term) {
        books.insertOne(new Document("_id", bookId)
                .append("title", "Being indexed")
                .append("author", "Nobody")
                .append("language", "en")
                .append("status", "indexing"));
        index.insertOne(new Document("term", term).append("postings", List.of(bookId)));
    }

    @Override
    public void close() {
        client.close();
    }
}

package bdsm.indexing.index;

import bdsm.common.model.BookMetadata;
import bdsm.common.text.Tokenizer;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.RenameCollectionOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

public class MongoDatamart implements Datamart, AutoCloseable {

    public static final String INDEX_COLLECTION = "inverted_index";
    public static final String BOOKS_COLLECTION = "books";
    public static final String INDEXING = "indexing";
    public static final String PUBLISHED = "published";
    private static final String STAGING_SUFFIX = "_rebuild";
    private static final double BYTES_PER_MB = 1024.0 * 1024.0;

    private final MongoClient client;
    private final MongoDatabase database;
    private final Tokenizer tokenizer;
    private final MongoCollection<Document> index;
    private final MongoCollection<Document> books;

    public MongoDatamart(String connectionUri, String databaseName, Tokenizer tokenizer) {
        this(MongoClients.create(connectionUri), databaseName, tokenizer, "");
    }

    private MongoDatamart(MongoClient client, String databaseName, Tokenizer tokenizer, String suffix) {
        this.client = client;
        this.database = client.getDatabase(databaseName);
        this.tokenizer = tokenizer;
        this.index = database.getCollection(INDEX_COLLECTION + suffix);
        this.books = database.getCollection(BOOKS_COLLECTION + suffix);
        createIndexes();
    }

    @Override
    public boolean isPublished(int bookId) {
        return books.find(and(eq("_id", bookId), eq("status", PUBLISHED))).first() != null;
    }

    @Override
    public void index(BookMetadata metadata, String path, String body) {
        int bookId = metadata.bookId();
        books.updateOne(eq("_id", bookId), Updates.combine(
                Updates.set("title", metadata.title()),
                Updates.set("author", metadata.author()),
                Updates.set("language", metadata.language()),
                Updates.set("year", metadata.year()),
                Updates.set("path", path),
                Updates.setOnInsert("status", INDEXING)), new UpdateOptions().upsert(true));
        addPostings(bookId, body);
        books.updateOne(eq("_id", bookId), Updates.combine(
                Updates.set("status", PUBLISHED),
                Updates.set("indexed_at", new Date())));
    }

    @Override
    public void rebuild(Loader loader) throws Exception {
        MongoDatamart staging = new MongoDatamart(client, database.getName(), tokenizer, STAGING_SUFFIX);
        staging.index.drop();
        staging.books.drop();
        staging.createIndexes();
        loader.load(staging);
        RenameCollectionOptions replace = new RenameCollectionOptions().dropTarget(true);
        staging.index.renameCollection(index.getNamespace(), replace);
        staging.books.renameCollection(books.getNamespace(), replace);
    }

    @Override
    public IndexStats stats() {
        long booksIndexed = books.countDocuments(eq("status", PUBLISHED));
        Document latest = books.find(eq("status", PUBLISHED)).sort(Sorts.descending("indexed_at")).first();
        String lastUpdate = latest == null ? null : latest.getDate("indexed_at").toInstant().toString();
        return new IndexStats(booksIndexed, lastUpdate, dataBytes(index) / BYTES_PER_MB);
    }

    @Override
    public void close() {
        client.close();
    }

    private void addPostings(int bookId, String body) {
        Set<String> terms = new HashSet<>(tokenizer.tokenize(body));
        if (terms.isEmpty()) return;
        List<UpdateOneModel<Document>> updates = terms.stream()
                .map(term -> new UpdateOneModel<Document>(
                        eq("term", term),
                        Updates.addToSet("postings", bookId),
                        new UpdateOptions().upsert(true)))
                .toList();
        index.bulkWrite(updates, new BulkWriteOptions().ordered(false));
    }

    private void createIndexes() {
        index.createIndex(Indexes.ascending("term"), new IndexOptions().unique(true));
        books.createIndex(Indexes.ascending("status"));
    }

    private static long dataBytes(MongoCollection<Document> collection) {
        Document stats = collection.aggregate(List.of(
                new Document("$collStats", new Document("storageStats", new Document())))).first();
        if (stats == null) return 0;
        return ((Number) stats.get("storageStats", Document.class).get("size")).longValue();
    }
}

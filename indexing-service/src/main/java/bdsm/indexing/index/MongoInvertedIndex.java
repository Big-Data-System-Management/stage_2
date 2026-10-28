package bdsm.indexing.index;

import bdsm.common.text.Tokenizer;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.mongodb.client.model.Filters.eq;

public class MongoInvertedIndex implements InvertedIndex, AutoCloseable {

    private static final String COLLECTION = "inverted_index";
    private static final String TERM_FIELD = "term";
    private static final String POSTINGS_FIELD = "postings";

    private final MongoClient client;
    private final MongoCollection<Document> collection;
    private final Tokenizer tokenizer;

    public MongoInvertedIndex(String connectionUri, String databaseName, Tokenizer tokenizer) {
        this.client = MongoClients.create(connectionUri);
        this.collection = client.getDatabase(databaseName).getCollection(COLLECTION);
        this.tokenizer = tokenizer;
        this.collection.createIndex(Indexes.ascending(TERM_FIELD), new IndexOptions().unique(true));
    }

    @Override
    public void indexBook(int bookId, String body) {
        Set<String> terms = new HashSet<>(tokenizer.tokenize(body));
        if (terms.isEmpty()) return;
        List<UpdateOneModel<Document>> updates = terms.stream()
                .map(term -> new UpdateOneModel<Document>(
                        eq(TERM_FIELD, term),
                        Updates.addToSet(POSTINGS_FIELD, bookId),
                        new UpdateOptions().upsert(true)))
                .toList();
        collection.bulkWrite(updates, new BulkWriteOptions().ordered(false));
    }

    @Override
    public Set<Integer> search(String term) {
        List<String> tokens = tokenizer.tokenize(term);
        if (tokens.size() != 1) return Set.of();
        Document document = collection.find(eq(TERM_FIELD, tokens.get(0))).first();
        if (document == null) return Set.of();
        return new TreeSet<>(document.getList(POSTINGS_FIELD, Integer.class));
    }

    @Override
    public void close() {
        client.close();
    }
}

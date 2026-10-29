package bdsm.search.repository;

import bdsm.common.model.BookMetadata;
import bdsm.search.query.BookFilter;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Filters.regex;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.Sorts.ascending;

public class MongoBookRepository implements BookRepository {

    private static final String BOOKS = "books";
    private static final String INVERTED_INDEX = "inverted_index";
    private static final String PUBLISHED = "published";
    private static final Pattern REGEX_SPECIAL_CHARACTERS = Pattern.compile("[\\\\^$.|?*+()\\[\\]{}]");

    private final MongoCollection<Document> books;
    private final MongoCollection<Document> invertedIndex;

    public MongoBookRepository(MongoDatabase database) {
        this.books = database.getCollection(BOOKS);
        this.invertedIndex = database.getCollection(INVERTED_INDEX);
    }

    @Override
    public Map<String, Set<Integer>> findPostings(Set<String> terms) {
        return execute(() -> {
            Map<String, Set<Integer>> postings = new HashMap<>();
            for (Document document : invertedIndex.find(in("term", terms)).projection(include("term", "postings"))) {
                postings.put(document.getString("term"), toIds(document.getList("postings", Number.class)));
            }
            return postings;
        });
    }

    @Override
    public BookPage findPublished(Set<Integer> bookIds, BookFilter filter, int offset, int limit) {
        return execute(() -> {
            Bson query = publishedBooksMatching(bookIds, filter);
            long total = books.countDocuments(query);
            if (offset >= total) return new BookPage(total, List.of());
            List<BookMetadata> page = books.find(query)
                    .sort(ascending("_id"))
                    .skip(offset)
                    .limit(limit)
                    .map(MongoBookRepository::toMetadata)
                    .into(new ArrayList<>());
            return new BookPage(total, page);
        });
    }

    @Override
    public Optional<BookMetadata> findPublishedById(int bookId) {
        return execute(() -> Optional.ofNullable(books.find(and(eq("_id", bookId), eq("status", PUBLISHED))).first())
                .map(MongoBookRepository::toMetadata));
    }

    @Override
    public FilterValues findFilterValues() {
        return execute(() -> {
            List<String> languages = books.distinct("language", and(eq("status", PUBLISHED), ne("language", null)), String.class)
                    .into(new ArrayList<>());
            List<Integer> years = books.distinct("year", and(eq("status", PUBLISHED), ne("year", null)), Integer.class)
                    .into(new ArrayList<>());
            languages.sort(null);
            years.sort(null);
            return new FilterValues(languages, years);
        });
    }

    private static Bson publishedBooksMatching(Set<Integer> bookIds, BookFilter filter) {
        List<Bson> conditions = new ArrayList<>();
        conditions.add(in("_id", bookIds));
        conditions.add(eq("status", PUBLISHED));
        if (filter.author() != null) conditions.add(regex("author", Pattern.compile(escapeRegex(filter.author()), Pattern.CASE_INSENSITIVE)));
        if (filter.language() != null) conditions.add(eq("language", filter.language()));
        if (filter.year() != null) conditions.add(eq("year", filter.year()));
        return and(conditions);
    }

    private static String escapeRegex(String text) {
        return REGEX_SPECIAL_CHARACTERS.matcher(text).replaceAll("\\\\$0");
    }

    private static Set<Integer> toIds(List<Number> values) {
        Set<Integer> ids = new HashSet<>();
        if (values != null) values.forEach(value -> ids.add(value.intValue()));
        return ids;
    }

    private static BookMetadata toMetadata(Document document) {
        Object year = document.get("year");
        return new BookMetadata(
                document.get("_id", Number.class).intValue(),
                document.getString("title"),
                document.getString("author"),
                document.getString("language"),
                year instanceof Number number ? number.intValue() : null);
    }

    private static <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (MongoException e) {
            throw new RepositoryUnavailableException("MongoDB request failed: " + e.getMessage(), e);
        }
    }
}

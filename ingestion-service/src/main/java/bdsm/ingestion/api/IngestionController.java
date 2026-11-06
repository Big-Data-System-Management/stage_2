package bdsm.ingestion.api;

import bdsm.ingestion.storage.BookLocator;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class IngestionController {

    private static final String ROUTE_STATUS = "/ingest/status/{book_id}";
    private static final String ROUTE_LIST = "/ingest/list";

    private static final String PATH_PARAM_BOOK_ID = "book_id";

    private static final String JSON_KEY_BOOK_ID = "book_id";
    private static final String JSON_KEY_STATUS = "status";
    private static final String JSON_KEY_COUNT = "count";
    private static final String JSON_KEY_BOOKS = "books";

    private static final String BOOK_STATUS_AVAILABLE = "available";
    private static final String BOOK_STATUS_MISSING = "missing";

    private static final String ERROR_INVALID_BOOK_ID = "book_id must be a positive integer";

    private final BookLocator locator;

    public IngestionController(BookLocator locator) {
        this.locator = locator;
    }

    public void register(Javalin app) {
        app.get(ROUTE_STATUS, this::status);
        app.get(ROUTE_LIST, this::list);
    }

    private void status(Context context) throws IOException {
        int bookId = extractAndValidateBookId(context);
        String bookStatus = getBookStatus(bookId);
        setContextResult(context, JSON_KEY_BOOK_ID, bookId, JSON_KEY_STATUS, bookStatus);
    }

    private int extractAndValidateBookId(Context context) {
        String rawBookIdValue = context.pathParam(PATH_PARAM_BOOK_ID);
        return parseBookId(rawBookIdValue);
    }

    private String getBookStatus(int bookId) throws IOException {
        return locator.find(bookId).isPresent()
                ? BOOK_STATUS_AVAILABLE
                : BOOK_STATUS_MISSING;
    }

    private void list(Context context) throws IOException {
        List<Integer> books = locator.list();
        setContextResult(context, JSON_KEY_COUNT, books.size(), JSON_KEY_BOOKS, books);
    }

    private static void setContextResult(Context context, Object key1, Object value1, Object key2, Object value2) {
        context.json(Map.of(
                key1, value1,
                key2, value2
        ));
    }

    private static int parseBookId(String value) {
        try {
            int bookId = Integer.parseInt(value);
            if (bookId > 0) {
                return bookId;
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(ERROR_INVALID_BOOK_ID, e);
        }
        throw new IllegalArgumentException(ERROR_INVALID_BOOK_ID);
    }
}
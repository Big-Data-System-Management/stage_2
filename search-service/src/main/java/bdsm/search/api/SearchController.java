package bdsm.search.api;

import bdsm.common.http.ApiError;
import bdsm.search.query.SearchQuery;
import bdsm.search.query.SearchService;
import bdsm.search.repository.RepositoryUnavailableException;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SearchController {

    private static final Logger LOG = LoggerFactory.getLogger(SearchController.class);

    private final SearchService service;

    public SearchController(SearchService service) {
        this.service = service;
    }

    public void register(Javalin app) {
        app.get("/search", this::search);
        app.get("/search/filters", ctx -> ctx.json(service.filterValues()));
        app.get("/books/{book_id}", this::book);
        app.exception(RepositoryUnavailableException.class, (e, ctx) -> {
            LOG.error(e.getMessage());
            ctx.status(503).json(new ApiError("service_unavailable", "Search storage is temporarily unavailable"));
        });
    }

    private void search(Context ctx) {
        SearchQuery query = SearchQuery.from(
                ctx.queryParam("q"),
                ctx.queryParam("author"),
                ctx.queryParam("language"),
                ctx.queryParam("year"),
                ctx.queryParam("offset"),
                ctx.queryParam("limit"));
        ctx.json(service.search(query));
    }

    private void book(Context ctx) {
        int bookId = parseBookId(ctx.pathParam("book_id"));
        service.findBook(bookId).ifPresentOrElse(ctx::json,
                () -> ctx.status(404).json(new ApiError("not_found", "Book " + bookId + " was not found")));
    }

    private static int parseBookId(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Path parameter book_id must be an integer, got: " + value);
        }
    }
}

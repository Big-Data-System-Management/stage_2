package bdsm.search.api;

import bdsm.common.http.ServiceApp;
import io.javalin.Javalin;

public class SearchController {

    public void register(Javalin app) {
        app.get("/search", ServiceApp::notImplemented);
        app.get("/search/filters", ServiceApp::notImplemented);
        app.get("/books/{book_id}", ServiceApp::notImplemented);
    }
}

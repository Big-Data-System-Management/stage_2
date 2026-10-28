package bdsm.indexing.api;

import bdsm.common.http.ServiceApp;
import bdsm.indexing.index.InvertedIndex;
import io.javalin.Javalin;

public class IndexingController {

    private final InvertedIndex index;

    public IndexingController(InvertedIndex index) {
        this.index = index;
    }

    public void register(Javalin app) {
        app.get("/index/status", ServiceApp::notImplemented);
    }
}

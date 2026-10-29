package bdsm.indexing.api;

import bdsm.common.http.ServiceApp;
import bdsm.indexing.index.Datamart;
import io.javalin.Javalin;

public class IndexingController {

    private final Datamart datamart;

    public IndexingController(Datamart datamart) {
        this.datamart = datamart;
    }

    public void register(Javalin app) {
        app.get("/index/status", ServiceApp::notImplemented);
    }
}

package bdsm.ingestion.api;

import bdsm.common.datalake.Datalake;
import bdsm.common.http.ServiceApp;
import io.javalin.Javalin;

public class IngestionController {

    private final Datalake datalake;

    public IngestionController(Datalake datalake) {
        this.datalake = datalake;
    }

    public void register(Javalin app) {
        app.get("/ingest/status/{book_id}", ServiceApp::notImplemented);
        app.get("/ingest/list", ServiceApp::notImplemented);
    }
}

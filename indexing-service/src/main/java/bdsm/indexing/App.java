package bdsm.indexing;

import bdsm.common.config.Config;
import bdsm.common.datalake.TimeHierarchyDatalake;
import bdsm.common.events.BookDownloaded;
import bdsm.common.events.EventBus;
import bdsm.common.events.IndexRebuildRequested;
import bdsm.common.http.ServiceApp;
import bdsm.common.text.Tokenizer;
import bdsm.indexing.api.IndexingController;
import bdsm.indexing.events.IndexingHandlers;
import bdsm.indexing.index.MongoInvertedIndex;
import io.javalin.Javalin;

public class App {

    public static void main(String[] args) throws Exception {
        int port = Config.getInt("INDEXING_PORT", 7002);
        int threads = Config.getInt("INDEXING_HTTP_THREADS", 8);
        int workers = Config.getInt("INDEXING_WORKERS", 2);
        TimeHierarchyDatalake datalake = new TimeHierarchyDatalake(Config.getPath("DATALAKE_PATH", "datalake"));
        MongoInvertedIndex index = new MongoInvertedIndex(
                Config.get("MONGO_URI", "mongodb://localhost:27017"),
                Config.get("MONGO_DATABASE", "search_engine"),
                new Tokenizer());

        IndexingHandlers handlers = new IndexingHandlers(datalake, index);
        EventBus events = EventBus.fromConfig();
        events.subscribe(BookDownloaded.class, "indexing", workers, handlers::onBookDownloaded);
        events.subscribe(IndexRebuildRequested.class, "indexing", 1, handlers::onRebuildRequested);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            events.close();
            index.close();
        }));

        Javalin app = ServiceApp.create("indexing-service", threads);
        new IndexingController(index).register(app);
        app.start(port);
    }
}

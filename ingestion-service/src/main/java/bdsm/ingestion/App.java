package bdsm.ingestion;

import bdsm.common.config.Config;
import bdsm.common.datalake.TimeHierarchyDatalake;
import bdsm.common.events.BookRequested;
import bdsm.common.events.EventBus;
import bdsm.common.http.ServiceApp;
import bdsm.ingestion.api.IngestionController;
import bdsm.ingestion.events.IngestionHandler;
import bdsm.ingestion.source.BookSource;
import bdsm.ingestion.source.GutenbergSource;
import bdsm.ingestion.source.LocalSource;
import bdsm.ingestion.storage.TimeHierarchyBookLocator;
import io.javalin.Javalin;

import java.io.IOException;
import java.nio.file.Path;

public class App {

    public static void main(String[] args) throws Exception {
        int port = Config.getInt("INGESTION_PORT", 7001);
        int threads = Config.getInt("INGESTION_HTTP_THREADS", 8);
        int workers = Config.getInt("INGESTION_WORKERS", 1);
        TimeHierarchyDatalake datalake = new TimeHierarchyDatalake(Config.getPath("DATALAKE_PATH", "datalake"));
        TimeHierarchyBookLocator locator = new TimeHierarchyBookLocator(datalake.root());
        String source = Config.get("BOOK_SOURCE", "gutenberg");
        BookSource books = source.equals("gutenberg") ? new GutenbergSource() : new LocalSource(Path.of(source));
        EventBus events = EventBus.fromConfig();
        events.subscribe(BookRequested.class, "ingestion", workers, new IngestionHandler(datalake, locator, books));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(events, locator)));
        Javalin app = ServiceApp.create("ingestion-service", threads);
        new IngestionController(locator).register(app);
        app.start(port);
    }

    private static void shutdown(EventBus events, TimeHierarchyBookLocator locator) {
        events.close();
        try {
            locator.close();
        } catch (IOException e) {
            System.err.println("[Ingestion] Could not save the book index: " + e.getMessage());
        }
    }
}
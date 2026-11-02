package bdsm.search;

import bdsm.common.config.Config;
import bdsm.common.http.ServiceApp;
import bdsm.common.text.Tokenizer;
import bdsm.search.api.Cors;
import bdsm.search.api.SearchController;
import bdsm.search.query.SearchService;
import bdsm.search.repository.MongoBookRepository;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.javalin.Javalin;

public class App {

    public static void main(String[] args) {
        int port = Config.getInt("SEARCH_PORT", 7003);
        int threads = Config.getInt("SEARCH_HTTP_THREADS", 32);
        String corsOrigin = Config.get("SEARCH_CORS_ORIGIN", "*");
        String mongoUri = Config.get("MONGO_URI", "mongodb://localhost:27017");
        String databaseName = Config.get("MONGO_DATABASE", "search_engine");

        MongoClient client = MongoClients.create(mongoUri);
        SearchService service = new SearchService(new MongoBookRepository(client.getDatabase(databaseName)), new Tokenizer());
        Javalin app = createServer(service, threads, corsOrigin);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            client.close();
        }));
        app.start(port);
    }

    public static Javalin createServer(SearchService service, int threads, String corsOrigin) {
        Javalin app = ServiceApp.create("search-service", threads);
        Cors.enable(app, corsOrigin);
        new SearchController(service).register(app);
        return app;
    }
}

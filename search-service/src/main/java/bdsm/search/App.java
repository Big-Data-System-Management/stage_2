package bdsm.search;

import bdsm.common.config.Config;
import bdsm.common.http.ServiceApp;
import bdsm.search.api.SearchController;
import io.javalin.Javalin;

public class App {

    public static void main(String[] args) {
        int port = Config.getInt("SEARCH_PORT", 7003);
        int threads = Config.getInt("SEARCH_HTTP_THREADS", 32);

        Javalin app = ServiceApp.create("search-service", threads);
        new SearchController().register(app);
        app.start(port);
    }
}

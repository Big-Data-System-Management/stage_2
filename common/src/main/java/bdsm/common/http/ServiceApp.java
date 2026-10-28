package bdsm.common.http;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.util.Map;

public final class ServiceApp {

    private ServiceApp() {
    }

    public static Javalin create(String serviceName, int maxThreads) {
        Javalin app = Javalin.create(config -> {
            config.jsonMapper(new GsonMapper());
            config.showJavalinBanner = false;
            config.jetty.threadPool = new QueuedThreadPool(maxThreads);
        });
        app.get("/status", ctx -> ctx.json(Map.of("service", serviceName, "status", "running")));
        app.exception(IllegalArgumentException.class,
                (e, ctx) -> ctx.status(400).json(new ApiError("bad_request", e.getMessage())));
        return app;
    }

    public static void notImplemented(Context ctx) {
        ctx.status(501).json(new ApiError("not_implemented", ctx.method() + " " + ctx.path() + " is not implemented yet"));
    }
}

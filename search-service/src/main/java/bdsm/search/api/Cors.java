package bdsm.search.api;

import io.javalin.Javalin;

public final class Cors {

    private Cors() {
    }

    public static void enable(Javalin app, String allowedOrigin) {
        app.before(ctx -> {
            ctx.header("Access-Control-Allow-Origin", allowedOrigin);
            ctx.header("Access-Control-Allow-Methods", "GET, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type");
            if (!"*".equals(allowedOrigin)) ctx.header("Vary", "Origin");
        });
        app.options("/*", ctx -> ctx.status(204));
    }
}

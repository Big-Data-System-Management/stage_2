package bdsm.common.http;

import bdsm.common.json.Json;
import io.javalin.json.JsonMapper;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Type;

public class GsonMapper implements JsonMapper {

    @NotNull
    @Override
    public String toJsonString(@NotNull Object obj, @NotNull Type type) {
        return Json.GSON.toJson(obj, type);
    }

    @NotNull
    @Override
    public <T> T fromJsonString(@NotNull String json, @NotNull Type targetType) {
        return Json.GSON.fromJson(json, targetType);
    }
}

package bdsm.common.http;

import bdsm.common.json.Json;
import io.javalin.json.JsonMapper;

import java.lang.reflect.Type;

public class GsonMapper implements JsonMapper {

    @Override
    public String toJsonString(Object obj, Type type) {
        return Json.GSON.toJson(obj, type);
    }

    @Override
    public <T> T fromJsonString(String json, Type targetType) {
        return Json.GSON.fromJson(json, targetType);
    }
}

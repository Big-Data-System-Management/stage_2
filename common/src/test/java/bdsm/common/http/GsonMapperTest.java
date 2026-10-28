package bdsm.common.http;

import bdsm.common.model.BookMetadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GsonMapperTest {

    @Test
    void writesFieldsInSnakeCase() {
        String json = new GsonMapper().toJsonString(new BookMetadata(5, "Robinson Crusoe", "Daniel Defoe", "en", 1719), BookMetadata.class);

        assertEquals("{\"book_id\":5,\"title\":\"Robinson Crusoe\",\"author\":\"Daniel Defoe\",\"language\":\"en\",\"year\":1719}", json);
    }
}

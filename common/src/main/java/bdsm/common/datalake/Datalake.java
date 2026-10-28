package bdsm.common.datalake;

import bdsm.common.model.Book;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public interface Datalake {
    Path store(Book book) throws IOException;

    boolean exists(int bookId);

    Optional<Book> read(int bookId) throws IOException;
}

package bdsm.search.repository;

import bdsm.common.model.BookMetadata;

import java.util.List;

public record BookPage(long total, List<BookMetadata> books) {

    public static final BookPage EMPTY = new BookPage(0, List.of());
}

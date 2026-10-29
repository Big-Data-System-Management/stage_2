package bdsm.search.repository;

import bdsm.common.model.BookMetadata;

class InMemoryBookRepositoryTest extends BookRepositoryContractTest {

    private final InMemoryBookRepository repository = new InMemoryBookRepository();

    @Override
    protected BookRepository createRepository() {
        return repository;
    }

    @Override
    protected void store(BookMetadata book, boolean published, String... terms) {
        repository.add(book, published, terms);
    }
}

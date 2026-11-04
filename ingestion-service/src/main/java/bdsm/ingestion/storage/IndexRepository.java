package bdsm.ingestion.storage;

import java.io.IOException;
import java.util.Optional;

interface IndexRepository {

    Optional<IndexSnapshot> load();

    void save(IndexSnapshot snapshot) throws IOException;
}

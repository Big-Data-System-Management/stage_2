package bdsm.search.repository;

public class RepositoryUnavailableException extends RuntimeException {

    public RepositoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

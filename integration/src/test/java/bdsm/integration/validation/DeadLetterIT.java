package bdsm.integration.validation;

import bdsm.common.events.BookDownloaded;
import bdsm.integration.support.Api;
import bdsm.integration.support.Await;
import bdsm.integration.support.Broker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DeadLetterIT {

    private static final Duration ALL_RETRIES = Duration.ofSeconds(60);

    @BeforeEach
    void emptyTheDeadLetterQueue() throws Exception {
        Broker.drain(Broker.DEAD_LETTER_QUEUE);
    }

    @Test
    void anEventThatAlwaysFailsEndsUpInTheDeadLetterQueue() throws Exception {
        int book = 999001;

        Broker.publish(new BookDownloaded(book, "folder/that/does/not/exist"));

        Await.until("the event is in " + Broker.DEAD_LETTER_QUEUE, ALL_RETRIES, () -> deadLetters().stream()
                .anyMatch(message -> message.contains("\"book_id\":" + book)));
        assertFalse(Api.isSearchable(book));
    }

    @Test
    void aMalformedMessageEndsUpInTheDeadLetterQueue() throws Exception {
        String text = "this is not json";

        Broker.publishText("VirtualTopic.BookDownloaded", text);

        Await.until("the message is in " + Broker.DEAD_LETTER_QUEUE, ALL_RETRIES, () -> deadLetters().contains(text));
    }

    private static List<String> deadLetters() {
        try {
            return Broker.browse(Broker.DEAD_LETTER_QUEUE);
        } catch (Exception e) {
            throw new AssertionError("The dead-letter queue could not be read: " + e, e);
        }
    }
}

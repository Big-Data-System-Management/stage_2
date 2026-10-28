package bdsm.feeder;

import bdsm.common.events.BookRequested;
import bdsm.common.events.EventBus;
import bdsm.common.events.IndexRebuildRequested;

public class App {

    private static final String USAGE = "Usage: feeder request <first_id> [last_id] | feeder rebuild";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println(USAGE);
            System.exit(1);
        }
        try (EventBus events = EventBus.fromConfig()) {
            switch (args[0]) {
                case "request" -> requestBooks(events, args);
                case "rebuild" -> {
                    events.publish(new IndexRebuildRequested());
                    System.out.println("[Feeder] Published IndexRebuildRequested");
                }
                default -> {
                    System.err.println(USAGE);
                    System.exit(1);
                }
            }
        }
    }

    private static void requestBooks(EventBus events, String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println(USAGE);
            System.exit(1);
        }
        int first = Integer.parseInt(args[1]);
        int last = args.length > 2 ? Integer.parseInt(args[2]) : first;
        for (int bookId = first; bookId <= last; bookId++) events.publish(new BookRequested(bookId));
        System.out.printf("[Feeder] Published BookRequested for books %d to %d%n", first, last);
    }
}

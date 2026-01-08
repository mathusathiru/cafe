package helpers;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class StateLogger {
    private static final String LOG_FILE = "cafe_log.json";
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static {
        try {
            if (!Files.exists(Paths.get(LOG_FILE))) {
                Files.write(Paths.get(LOG_FILE), "[]".getBytes());
            }
        } catch (IOException ignored) {
        }
    }

    public static class LogEntry {
        final String timestamp;
        final State state;

        LogEntry(State state) {
            this.timestamp = LocalDateTime.now().format(formatter);
            this.state = state;
        }
    }

    public static class State {
        final int totalCustomers;
        final int waitingCustomers;
        final DrinkCount waitingArea;
        final DrinkCount brewingArea;
        final DrinkCount trayArea;

        public State(AtomicInteger totalCustomers, AtomicInteger waitingCustomers,
                     DrinkCount waitingArea, DrinkCount brewingArea, DrinkCount trayArea) {
            this.totalCustomers = totalCustomers.get();
            this.waitingCustomers = waitingCustomers.get();
            this.waitingArea = waitingArea;
            this.brewingArea = brewingArea;
            this.trayArea = trayArea;
        }
    }

    public static class DrinkCount {
        final int teas;
        final int coffees;

        public DrinkCount(AtomicInteger teas, AtomicInteger coffees) {
            this.teas = teas.get();
            this.coffees = coffees.get();
        }
    }

    public static void log(State state) {
        try {
            synchronized (StateLogger.class) {
                List<LogEntry> entries;
                Path path = Paths.get(LOG_FILE);

                try (Reader reader = Files.newBufferedReader(path)) {
                    entries = new ArrayList<>(List.of(gson.fromJson(reader, LogEntry[].class)));
                }

                entries.add(new LogEntry(state));

                try (Writer writer = Files.newBufferedWriter(path)) {
                    gson.toJson(entries, writer);
                }
            }
        } catch (IOException ignored) {
        }
    }
}
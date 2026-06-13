package com.spirit.koil.api.console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class ConsoleRepository {
    public interface Listener {
        void onRecord(ConsoleRecord record);
    }

    private static final int MAX_RECORDS_PER_CHANNEL = 4_000;
    private static final ConsoleRepository INSTANCE = new ConsoleRepository();

    private final Map<ConsoleChannel, List<ConsoleRecord>> records = new EnumMap<>(ConsoleChannel.class);
    private final Map<ConsoleChannel, CopyOnWriteArrayList<Listener>> listeners = new EnumMap<>(ConsoleChannel.class);
    private final AtomicLong sequence = new AtomicLong();

    private ConsoleRepository() {
        for (ConsoleChannel channel : ConsoleChannel.values()) {
            this.records.put(channel, new ArrayList<>());
            this.listeners.put(channel, new CopyOnWriteArrayList<>());
        }
    }

    public static ConsoleRepository getInstance() {
        return INSTANCE;
    }

    public synchronized ConsoleRecord publish(ConsoleChannel channel, ConsoleLevel level, String timestamp, String thread, String category, String message, String rawLine) {
        ConsoleRecord record = new ConsoleRecord(
                this.sequence.incrementAndGet(),
                channel,
                level,
                timestamp,
                thread == null ? "" : thread,
                category == null ? "" : category,
                message == null ? "" : message,
                rawLine == null ? "" : rawLine
        );
        List<ConsoleRecord> channelRecords = this.records.get(channel);
        channelRecords.add(record);
        if (channelRecords.size() > MAX_RECORDS_PER_CHANNEL) {
            channelRecords.remove(0);
        }
        for (Listener listener : this.listeners.get(channel)) {
            listener.onRecord(record);
        }
        return record;
    }

    public synchronized List<ConsoleRecord> snapshot(ConsoleChannel channel) {
        return List.copyOf(this.records.getOrDefault(channel, Collections.emptyList()));
    }

    public void subscribe(ConsoleChannel channel, Listener listener) {
        this.listeners.get(channel).add(listener);
    }

    public void unsubscribe(ConsoleChannel channel, Listener listener) {
        this.listeners.get(channel).remove(listener);
    }
}

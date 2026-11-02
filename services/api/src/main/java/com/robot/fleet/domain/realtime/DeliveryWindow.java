package com.robot.fleet.domain.realtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

public final class DeliveryWindow {
    private final int maximum;
    private final long ttlMillis;
    private final LongSupplier now;
    private final LinkedHashMap<String, Long> delivered = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> terminal = new LinkedHashMap<>();

    public DeliveryWindow(int maximum, long ttlMillis, LongSupplier now) {
        if (maximum < 1 || ttlMillis < 1) throw new IllegalArgumentException("Invalid delivery window");
        this.maximum = maximum;
        this.ttlMillis = ttlMillis;
        this.now = now;
    }

    public synchronized boolean accept(String id, String cmdId, String status) {
        long timestamp = now.getAsLong();
        prune(delivered, timestamp);
        prune(terminal, timestamp);
        if (delivered.containsKey(id) || (cmdId != null && terminal.containsKey(cmdId))) return false;
        delivered.put(id, timestamp);
        if (cmdId != null && (status.equals("done") || status.equals("failed"))) terminal.put(cmdId, timestamp);
        trim(delivered); trim(terminal);
        return true;
    }

    private void prune(Map<String, Long> map, long timestamp) {
        var entries = map.entrySet().iterator();
        while (entries.hasNext()) {
            if (timestamp - entries.next().getValue() < ttlMillis) break;
            entries.remove();
        }
    }
    private void trim(LinkedHashMap<String, Long> map) {
        while (map.size() > maximum) map.remove(map.keySet().iterator().next());
    }
}

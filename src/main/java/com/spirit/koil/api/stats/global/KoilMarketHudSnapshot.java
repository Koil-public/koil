package com.spirit.koil.api.stats.global;

import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

public record KoilMarketHudSnapshot(String mode, boolean watch, long createdAt, String watchQuery, String title, String subtitle, String reserveLine, String sourceLine, List<Entry> entries, List<String> notes) {
    public KoilMarketHudSnapshot {
        mode = safe(mode, "quote");
        watchQuery = safe(watchQuery, "");
        title = safe(title, "Market");
        subtitle = safe(subtitle, "");
        reserveLine = safe(reserveLine, "");
        sourceLine = safe(sourceLine, "");
        entries = entries == null ? List.of() : List.copyOf(entries);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public KoilMarketHudSnapshot(String mode, boolean watch, long createdAt, String watchQuery, String title, String subtitle, String reserveLine, String sourceLine, List<Entry> entries) {
        this(mode, watch, createdAt, watchQuery, title, subtitle, reserveLine, sourceLine, entries, List.of());
    }

    public void write(PacketByteBuf buf) {
        buf.writeString(this.mode, 32);
        buf.writeBoolean(this.watch);
        buf.writeLong(this.createdAt);
        buf.writeString(this.watchQuery, 192);
        buf.writeString(this.title, 96);
        buf.writeString(this.subtitle, 160);
        buf.writeString(this.reserveLine, 180);
        buf.writeString(this.sourceLine, 120);
        buf.writeVarInt(Math.min(2, this.entries.size()));

        for (int i = 0; i < this.entries.size() && i < 2; i++) {
            this.entries.get(i).write(buf);
        }

        buf.writeVarInt(Math.min(12, this.notes.size()));

        for (int i = 0; i < this.notes.size() && i < 12; i++) {
            buf.writeString(this.notes.get(i) == null ? "" : this.notes.get(i), 160);
        }
    }

    public static KoilMarketHudSnapshot read(PacketByteBuf buf) {
        String mode = buf.readString(32);
        boolean watch = buf.readBoolean();
        long createdAt = buf.readLong();
        String watchQuery = buf.readString(192);
        String title = buf.readString(96);
        String subtitle = buf.readString(160);
        String reserveLine = buf.readString(180);
        String sourceLine = buf.readString(120);
        int size = Math.min(2, Math.max(0, buf.readVarInt()));
        List<Entry> entries = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            entries.add(Entry.read(buf));
        }

        List<String> notes = new ArrayList<>();

        try {
            int noteCount = Math.min(12, Math.max(0, buf.readVarInt()));

            for (int i = 0; i < noteCount; i++) {
                notes.add(buf.readString(160));
            }
        } catch (Exception ignored) {
            notes.clear();
        }

        return new KoilMarketHudSnapshot(mode, watch, createdAt, watchQuery, title, subtitle, reserveLine, sourceLine, entries, notes);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record Entry(String id, String title, String subject, String value, String exchange, String reserve, int confidence, boolean authoritative, double demand, double supply, double scarcity, double trend, int[] series, String valueDelta, String demandDelta, String supplyDelta, String scarcityDelta, String trendDelta, String chartDelta) {
        public Entry {
            id = safe(id, "");
            title = safe(title, "Market");
            subject = safe(subject, "unit");
            value = safe(value, "ƒ0");
            exchange = safe(exchange, "");
            reserve = safe(reserve, "");
            confidence = Math.max(0, Math.min(100, confidence));
            series = series == null ? new int[0] : series.clone();
            valueDelta = safe(valueDelta, "");
            demandDelta = safe(demandDelta, "");
            supplyDelta = safe(supplyDelta, "");
            scarcityDelta = safe(scarcityDelta, "");
            trendDelta = safe(trendDelta, "");
            chartDelta = safe(chartDelta, "");
        }

        public Entry(String id, String title, String subject, String value, String exchange, String reserve, int confidence, boolean authoritative, double demand, double supply, double scarcity, double trend, int[] series) {
            this(id, title, subject, value, exchange, reserve, confidence, authoritative, demand, supply, scarcity, trend, series, "", "", "", "", "", "");
        }

        private void write(PacketByteBuf buf) {
            buf.writeString(this.id, 160);
            buf.writeString(this.title, 96);
            buf.writeString(this.subject, 64);
            buf.writeString(this.value, 32);
            buf.writeString(this.exchange, 120);
            buf.writeString(this.reserve, 120);
            buf.writeVarInt(this.confidence);
            buf.writeBoolean(this.authoritative);
            buf.writeDouble(this.demand);
            buf.writeDouble(this.supply);
            buf.writeDouble(this.scarcity);
            buf.writeDouble(this.trend);
            int length = Math.min(KoilMarketSeriesWindow.historySampleLimit(), this.series.length);
            buf.writeVarInt(length);

            for (int i = 0; i < length; i++) {
                buf.writeVarInt(Math.max(0, this.series[i]));
            }

            buf.writeString(this.valueDelta, 48);
            buf.writeString(this.demandDelta, 48);
            buf.writeString(this.supplyDelta, 48);
            buf.writeString(this.scarcityDelta, 48);
            buf.writeString(this.trendDelta, 48);
            buf.writeString(this.chartDelta, 48);
        }

        private static Entry read(PacketByteBuf buf) {
            String id = buf.readString(160);
            String title = buf.readString(96);
            String subject = buf.readString(64);
            String value = buf.readString(32);
            String exchange = buf.readString(120);
            String reserve = buf.readString(120);
            int confidence = buf.readVarInt();
            boolean authoritative = buf.readBoolean();
            double demand = buf.readDouble();
            double supply = buf.readDouble();
            double scarcity = buf.readDouble();
            double trend = buf.readDouble();
            int length = Math.min(KoilMarketSeriesWindow.historySampleLimit(), Math.max(0, buf.readVarInt()));
            int[] series = new int[length];

            for (int i = 0; i < length; i++) {
                series[i] = buf.readVarInt();
            }

            String valueDelta = "";
            String demandDelta = "";
            String supplyDelta = "";
            String scarcityDelta = "";
            String trendDelta = "";
            String chartDelta = "";

            try {
                valueDelta = buf.readString(48);
                demandDelta = buf.readString(48);
                supplyDelta = buf.readString(48);
                scarcityDelta = buf.readString(48);
                trendDelta = buf.readString(48);
                chartDelta = buf.readString(48);
            } catch (Exception ignored) {
                valueDelta = "";
                demandDelta = "";
                supplyDelta = "";
                scarcityDelta = "";
                trendDelta = "";
                chartDelta = "";
            }

            return new Entry(id, title, subject, value, exchange, reserve, confidence, authoritative, demand, supply, scarcity, trend, series, valueDelta, demandDelta, supplyDelta, scarcityDelta, trendDelta, chartDelta);
        }
    }
}

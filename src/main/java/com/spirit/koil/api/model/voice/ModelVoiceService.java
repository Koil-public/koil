package com.spirit.koil.api.model.voice;

import com.spirit.koil.api.chat.RichChatSectionFormatting;
import com.spirit.koil.api.model.LocalModelRuntimeLog;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ModelVoiceService {
    private static final Path CACHE_DIRECTORY = Path.of("koil/sys/model/voice-cache");
    private static final Pattern PRONOUNCEABLE_WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}'’\\-]{0,48}");
    private static final long FIRST_PHRASE_LATENCY_MILLIS = 60L;
    private static final long FOLLOWING_PHRASE_LATENCY_MILLIS = 220L;
    private static final ThreadPoolExecutor SYNTHESIS = new ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64),
            new VoiceThreadFactory("koil-model-voice-synthesis"),
            (task, executor) -> reject("voice synthesis queue is full; dropped a generated phrase")
    );
    private static final ThreadPoolExecutor PLAYBACK = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128),
            new VoiceThreadFactory("koil-model-voice-playback"),
            (task, executor) -> reject("voice playback queue is full; dropped a generated phrase")
    );
    private static final ScheduledThreadPoolExecutor FLUSH_TIMER = flushTimer();
    private static final AtomicLong SPEECH_EPOCH = new AtomicLong();
    private static final AtomicReference<Clip> ACTIVE_CLIP = new AtomicReference<>();
    private static final java.util.Set<CompletableFuture<Path>> ACTIVE_AUDIO =
            ConcurrentHashMap.newKeySet();
    private static volatile ModelVoiceSettings settings = ModelVoiceSettingsStore.load();

    private ModelVoiceService() {
    }

    public static ModelVoiceSettings settings() {
        return settings;
    }

    public static List<ModelVoiceDefinition> voices() {
        return ModelVoiceRegistry.voices();
    }

    public static synchronized ModelVoiceSettings setEnabled(boolean enabled) {
        settings = new ModelVoiceSettings(enabled, settings.voiceId());
        ModelVoiceSettingsStore.save(settings);
        LocalModelRuntimeLog.write("voice_state", enabled ? "enabled" : "disabled");
        return settings;
    }

    public static synchronized boolean setVoice(String voiceId) {
        ModelVoiceDefinition voice = ModelVoiceRegistry.find(voiceId).orElse(null);
        if (voice == null) {
            return false;
        }
        settings = new ModelVoiceSettings(settings.enabled(), voice.id());
        ModelVoiceSettingsStore.save(settings);
        LocalModelRuntimeLog.write("voice_selected", voice.id());
        return true;
    }

    public static String selectedVoiceLabel() {
        return ModelVoiceRegistry.find(settings.voiceId())
                .map(ModelVoiceDefinition::displayName)
                .orElse(settings.voiceId());
    }

    /**
     * Creates one response-scoped phrase assembler. The first short phrase is
     * dispatched quickly, while later phrases are large enough to avoid one
     * provider request per word.
     */
    public static StreamingSpeech beginStreaming() {
        return new StreamingSpeech(SPEECH_EPOCH.get());
    }

    /** Speaks only a finalized, user-facing model answer. */
    public static void speakFinalAnswer(String answer) {
        if (answer == null || answer.isBlank() || !settings.enabled()) {
            return;
        }
        StreamingSpeech speech = beginStreaming();
        speech.accept(answer);
        speech.finish();
    }

    /**
     * Immediately invalidates speech from the prior response without touching
     * universal media playback.
     */
    public static void stopSpeaking(String reason) {
        SPEECH_EPOCH.incrementAndGet();
        Clip clip = ACTIVE_CLIP.getAndSet(null);
        closeClip(clip);
        CancellationException cancelled = new CancellationException(
                reason == null || reason.isBlank() ? "model speech superseded" : reason
        );
        for (CompletableFuture<Path> audio : List.copyOf(ACTIVE_AUDIO)) {
            Path ready = completedPath(audio);
            audio.completeExceptionally(cancelled);
            deleteQuietly(ready);
            ACTIVE_AUDIO.remove(audio);
        }
        SYNTHESIS.getQueue().clear();
        PLAYBACK.getQueue().clear();
        SYNTHESIS.purge();
        PLAYBACK.purge();
        LocalModelRuntimeLog.write("voice_cancelled", cancelled.getMessage());
    }

    public static void speakWord(String generatedWord) {
        ModelVoiceSettings snapshot = settings;
        if (!snapshot.enabled()) {
            return;
        }
        String word = finalPronounceableWord(generatedWord);
        if (word.isBlank()) {
            return;
        }
        enqueuePhrase(snapshot, new ModelVoicePhrase(word, ModelVoiceExpression.NEUTRAL), SPEECH_EPOCH.get());
    }

    public static String finalPronounceableWord(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = RichChatSectionFormatting.speechSafeText(text)
                .replaceAll("https?://\\S+", " ")
                .replaceAll("[`*_#|\\[\\]{}<>]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        Matcher matcher = PRONOUNCEABLE_WORD.matcher(normalized);
        String last = "";
        while (matcher.find()) {
            last = matcher.group();
        }
        if (last.length() == 1 && !Character.isLetterOrDigit(last.charAt(0))) {
            return "";
        }
        return last;
    }

    private static void enqueuePhrase(ModelVoiceSettings snapshot, ModelVoicePhrase phrase, long epoch) {
        if (!snapshot.enabled() || phrase == null || phrase.text().isBlank() || epoch != SPEECH_EPOCH.get()) {
            return;
        }
        String spoken = RichChatSectionFormatting.speechSafeText(phrase.text())
                .replaceAll("\\s+", " ")
                .strip();
        if (spoken.isBlank()) {
            return;
        }
        ModelVoicePhrase safePhrase = new ModelVoicePhrase(spoken, phrase.expression());
        CompletableFuture<Path> audio = new CompletableFuture<>();
        ACTIVE_AUDIO.add(audio);
        try {
            SYNTHESIS.execute(() -> synthesize(snapshot.voiceId(), safePhrase, audio, epoch));
        } catch (RejectedExecutionException rejected) {
            audio.completeExceptionally(rejected);
            ACTIVE_AUDIO.remove(audio);
            return;
        }
        try {
            PLAYBACK.execute(() -> playQueued(snapshot.voiceId(), safePhrase, audio, epoch));
        } catch (RejectedExecutionException rejected) {
            audio.whenComplete((path, failure) -> deleteQuietly(path));
            audio.completeExceptionally(rejected);
            ACTIVE_AUDIO.remove(audio);
        }
    }

    private static void synthesize(
            String voiceId,
            ModelVoicePhrase phrase,
            CompletableFuture<Path> audio,
            long epoch
    ) {
        Path created = null;
        try {
            if (epoch != SPEECH_EPOCH.get()) {
                throw new CancellationException("model speech superseded");
            }
            ModelVoiceDefinition voice = ModelVoiceRegistry.find(voiceId)
                    .orElseThrow(() -> new IllegalStateException("selected voice is unavailable: " + voiceId));
            ModelVoiceProvider provider = ModelVoiceRegistry.providerFor(voice)
                    .orElseThrow(() -> new IllegalStateException("voice provider is unavailable: " + voice.providerId()));
            ModelVoiceExpression expression = provider.supportedExpressions().contains(phrase.expression())
                    ? phrase.expression()
                    : ModelVoiceExpression.NEUTRAL;
            created = provider.synthesize(voice.id(), phrase.text(), expression, CACHE_DIRECTORY);
            if (epoch != SPEECH_EPOCH.get()) {
                deleteQuietly(created);
                audio.completeExceptionally(new CancellationException("model speech superseded"));
                return;
            }
            if (!audio.complete(created)) {
                deleteQuietly(created);
            }
        } catch (Exception failure) {
            deleteQuietly(created);
            audio.completeExceptionally(failure);
        }
    }

    private static void playQueued(
            String voiceId,
            ModelVoicePhrase phrase,
            CompletableFuture<Path> audio,
            long epoch
    ) {
        Path path = null;
        try {
            path = audio.get(25L, TimeUnit.SECONDS);
            if (epoch != SPEECH_EPOCH.get()) {
                return;
            }
            LocalModelRuntimeLog.write(
                    "voice_playback_started",
                    voiceId + " | " + phrase.expression().name().toLowerCase(Locale.ROOT)
            );
            playIsolated(path, epoch);
            if (epoch != SPEECH_EPOCH.get()) {
                return;
            }
            LocalModelRuntimeLog.write(
                    "voice_spoken",
                    voiceId + " | " + phrase.expression().name().toLowerCase(Locale.ROOT)
                            + " | " + phrase.text().toLowerCase(Locale.ROOT)
            );
        } catch (Exception failure) {
            if (epoch == SPEECH_EPOCH.get() && !(failure instanceof CancellationException)) {
                LocalModelRuntimeLog.write("voice_failed", failureMessage(failure));
            }
        } finally {
            ACTIVE_AUDIO.remove(audio);
            deleteQuietly(path);
            if (path == null) {
                audio.whenComplete((latePath, failure) -> deleteQuietly(latePath));
            }
        }
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
            }
        }
    }

    private static String failureMessage(Exception failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static void reject(String reason) {
        LocalModelRuntimeLog.write("voice_dropped", reason);
        throw new RejectedExecutionException(reason);
    }

    private static ScheduledThreadPoolExecutor flushTimer() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1,
                new VoiceThreadFactory("koil-model-voice-flush")
        );
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static void enqueuePhrases(List<ModelVoicePhrase> phrases, long epoch) {
        if (phrases == null || phrases.isEmpty() || epoch != SPEECH_EPOCH.get()) {
            return;
        }
        ModelVoiceSettings snapshot = settings;
        if (!snapshot.enabled()) {
            return;
        }
        for (ModelVoicePhrase phrase : phrases) {
            enqueuePhrase(snapshot, phrase, epoch);
        }
    }

    private static void playIsolated(Path audio, long epoch) throws Exception {
        try (AudioInputStream input = AudioSystem.getAudioInputStream(audio.toFile())) {
            Clip clip = AudioSystem.getClip();
            CountDownLatch completed = new CountDownLatch(1);
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP || event.getType() == LineEvent.Type.CLOSE) {
                    completed.countDown();
                }
            });
            try {
                clip.open(input);
                if (epoch != SPEECH_EPOCH.get()) {
                    return;
                }
                ACTIVE_CLIP.set(clip);
                if (epoch != SPEECH_EPOCH.get()) {
                    ACTIVE_CLIP.compareAndSet(clip, null);
                    return;
                }
                clip.start();
                long maximumWaitMillis = Math.max(1_000L, Math.min(20_000L, clip.getMicrosecondLength() / 1_000L + 2_000L));
                completed.await(maximumWaitMillis, TimeUnit.MILLISECONDS);
            } finally {
                ACTIVE_CLIP.compareAndSet(clip, null);
                closeClip(clip);
            }
        }
    }

    private static Path completedPath(CompletableFuture<Path> audio) {
        if (audio == null || !audio.isDone() || audio.isCompletedExceptionally() || audio.isCancelled()) {
            return null;
        }
        try {
            return audio.getNow(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void closeClip(Clip clip) {
        if (clip == null) {
            return;
        }
        try {
            clip.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            clip.close();
        } catch (RuntimeException ignored) {
        }
    }

    private static final class VoiceThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger();

        private VoiceThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, this.prefix + "-" + this.sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    public static final class StreamingSpeech {
        private final ModelVoicePhrasePlanner planner = new ModelVoicePhrasePlanner();
        private final long epoch;
        private ScheduledFuture<?> pendingFlush;
        private boolean finished;

        private StreamingSpeech(long epoch) {
            this.epoch = epoch;
        }

        public synchronized void accept(String delta) {
            if (cancelled()) {
                stopWithoutFlush();
                return;
            }
            if (this.finished || delta == null || delta.isEmpty()) {
                return;
            }
            enqueuePhrases(this.planner.accept(delta), this.epoch);
            scheduleLatencyFlush();
        }

        public synchronized void finish() {
            if (this.finished) {
                return;
            }
            this.finished = true;
            cancelPendingFlush();
            enqueuePhrases(this.planner.finish(), this.epoch);
        }

        /** Drops an incomplete provider round without invalidating other model speech. */
        public synchronized void discard() {
            if (this.finished) {
                return;
            }
            stopWithoutFlush();
        }

        public boolean cancelled() {
            return this.epoch != SPEECH_EPOCH.get();
        }

        private synchronized void flushForLatency() {
            this.pendingFlush = null;
            if (cancelled()) {
                stopWithoutFlush();
                return;
            }
            if (this.finished) {
                return;
            }
            enqueuePhrases(this.planner.flushForLatency(), this.epoch);
            scheduleLatencyFlush();
        }

        private void scheduleLatencyFlush() {
            if (this.finished || !this.planner.hasCompletedWords()
                    || this.pendingFlush != null && !this.pendingFlush.isDone()) {
                return;
            }
            long delay = this.planner.emittedFirstPhrase()
                    ? FOLLOWING_PHRASE_LATENCY_MILLIS
                    : FIRST_PHRASE_LATENCY_MILLIS;
            this.pendingFlush = FLUSH_TIMER.schedule(this::flushForLatency, delay, TimeUnit.MILLISECONDS);
        }

        private void cancelPendingFlush() {
            if (this.pendingFlush != null) {
                this.pendingFlush.cancel(false);
                this.pendingFlush = null;
            }
        }

        private void stopWithoutFlush() {
            this.finished = true;
            cancelPendingFlush();
        }
    }
}

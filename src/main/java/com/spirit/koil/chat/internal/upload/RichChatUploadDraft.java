package com.spirit.koil.chat.internal.upload;

import com.spirit.koil.api.chat.RichChatAttachment;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class RichChatUploadDraft {
    private static PendingAttachment pending;
    private static String status = "";
    private static long statusUntil;

    private RichChatUploadDraft() {
    }

    public static synchronized void stage(Path path) {
        if (path == null) {
            setStatus("No file selected.", 1800L);
            return;
        }
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            setStatus("Only files can be attached right now.", 2200L);
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        UUID uploaderUuid = client != null && client.player != null ? client.player.getUuid() : null;
        String uploaderName = client != null && client.player != null ? client.player.getGameProfile().getName() : "";
        PendingAttachment next = new PendingAttachment(path.toAbsolutePath().normalize(), RichChatUploadStorage.stage(path, uploaderUuid, uploaderName));
        pending = next;
        setStatus("Preparing " + fileName(path) + "...", 2200L);
        next.future.whenComplete((stored, throwable) -> {
            synchronized (RichChatUploadDraft.class) {
                if (pending != next) {
                    return;
                }
                if (throwable != null) {
                    setStatus("Upload blocked: " + throwable.getMessage(), 5000L);
                } else if (stored == null || !stored.ready()) {
                    setStatus("Upload blocked: " + (stored == null ? "unknown error" : stored.error()), 5000L);
                } else {
                    setStatus("Attached " + stored.attachment().fileName(), 3000L);
                }
            }
        });
    }

    public static synchronized void clear() {
        pending = null;
        setStatus("Attachment removed.", 1500L);
    }

    public static synchronized boolean hasPending() {
        return pending != null;
    }

    public static synchronized boolean isReady() {
        return pending != null && pending.stored() != null && pending.stored().ready();
    }

    public static synchronized boolean isProcessing() {
        return pending != null && !pending.future.isDone();
    }

    public static synchronized RichChatAttachment attachmentForMessage(UUID messageId) {
        if (!isReady()) {
            return null;
        }
        return RichChatUploadStorage.withMessageId(pending.stored().attachment(), messageId);
    }

    public static synchronized String fallbackLabel() {
        if (pending == null) {
            return "";
        }
        RichChatUploadStorage.StoredAttachment stored = pending.stored();
        if (stored != null && stored.ready()) {
            return "[" + stored.attachment().type().name().toLowerCase() + ": " + stored.attachment().fileName() + "]";
        }
        return "[attachment: " + fileName(pending.source()) + "]";
    }

    public static synchronized String statusText() {
        if (pending != null) {
            RichChatUploadStorage.StoredAttachment stored = pending.stored();
            if (stored != null && stored.ready()) {
                return stored.attachment().fileName() + " | " + RichChatUploadStorage.attachmentDescription(stored.attachment());
            }
            if (stored != null && stored.error() != null) {
                return "Blocked | " + stored.error();
            }
            return "Preparing " + fileName(pending.source()) + "...";
        }
        if (!status.isBlank() && System.currentTimeMillis() < statusUntil) {
            return status;
        }
        return "";
    }

    public static synchronized int reservedHeight() {
        return 0;
    }

    public static synchronized List<String> tooltipLines() {
        if (pending == null) {
            return List.of("Attach a file to this chat message.");
        }
        RichChatUploadStorage.StoredAttachment stored = pending.stored();
        if (stored == null) {
            return List.of("Preparing attachment", fileName(pending.source()));
        }
        if (!stored.ready()) {
            return List.of("Attachment blocked", stored.error());
        }
        RichChatAttachment attachment = stored.attachment();
        return List.of(
                attachment.fileName(),
                RichChatUploadStorage.attachmentDescription(attachment),
                "SHA-256: " + attachment.sha256().substring(0, Math.min(16, attachment.sha256().length())) + "..."
        );
    }

    private static void setStatus(String value, long millis) {
        status = value == null ? "" : value;
        statusUntil = System.currentTimeMillis() + millis;
    }

    private static String fileName(Path path) {
        return path == null || path.getFileName() == null ? "attachment" : path.getFileName().toString();
    }

    private record PendingAttachment(Path source, CompletableFuture<RichChatUploadStorage.StoredAttachment> future) {
        private RichChatUploadStorage.StoredAttachment stored() {
            if (!future.isDone()) {
                return null;
            }
            try {
                return future.getNow(null);
            } catch (Exception ignored) {
                return RichChatUploadStorage.StoredAttachment.failed(source, "Upload failed.");
            }
        }
    }
}

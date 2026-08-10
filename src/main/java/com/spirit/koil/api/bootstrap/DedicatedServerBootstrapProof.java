package com.spirit.koil.api.bootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Fresh-directory proof for consent deferral, asynchronous failure, and retry recovery. */
public final class DedicatedServerBootstrapProof {
    private DedicatedServerBootstrapProof() {
    }

    public static void main(String[] args) throws Exception {
        Path config = Path.of("koil", "sys", "config.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                {
                  "firstLaunch": true,
                  "termsVersion": "",
                  "termsAcceptedAt": ""
                }
                """, StandardCharsets.UTF_8);

        AtomicInteger attempts = new AtomicInteger();
        DedicatedServerBootstrapService.initialize(() -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("intentional proof failure");
        }, (message, failure) -> { });
        require(attempts.get() == 0, "bootstrap ran before consent");
        require(DedicatedServerBootstrapService.snapshot().state()
                == DedicatedServerBootstrapService.State.AWAITING_CONSENT, "fresh state is not awaiting consent");

        require(DedicatedServerBootstrapService.acceptFromPhysicalConsole(), "console acceptance failed");
        await(DedicatedServerBootstrapService.State.DEGRADED);
        require(attempts.get() == 1, "accepted bootstrap did not run exactly once");
        require(DedicatedServerBootstrapService.termsAccepted(), "accepted terms were not persisted");

        require(DedicatedServerBootstrapService.retryFromPhysicalConsole(), "degraded bootstrap did not retry");
        await(DedicatedServerBootstrapService.State.READY);
        require(attempts.get() == 2, "retry did not run exactly once");
        System.out.println("Dedicated server bootstrap proof passed");
    }

    private static void await(DedicatedServerBootstrapService.State expected) throws InterruptedException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (DedicatedServerBootstrapService.snapshot().state() == expected) return;
            Thread.sleep(10L);
        }
        throw new IllegalStateException("timed out waiting for " + expected + ": "
                + DedicatedServerBootstrapService.snapshot().statusLine());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

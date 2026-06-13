package com.spirit.koil.api.console;

import com.spirit.client.gui.UiSoundHelper;
import com.spirit.koil.api.automation.AutomationRouter;
import net.minecraft.client.MinecraftClient;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ConsoleRequestBridge {
    private static final AtomicBoolean HOST_RUNNING = new AtomicBoolean();
    private static volatile ServerSocket serverSocket;
    private static volatile String hostToken;
    private static volatile int hostPort = -1;

    private ConsoleRequestBridge() {
    }

    public static void initializeHost() {
        try {
            startSocketHost();
        } catch (IOException ignored) {
        }
    }

    public static void submit(ConsoleChannel channel, String input) {
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        String safeChannel = channel == null ? ConsoleChannel.KOIL.id() : channel.id();
        if (sendSocketPayload("request", safeChannel, trimmed)) {
            return;
        }
    }

    public static void submitUiSound(String soundId) {
        String trimmed = soundId == null ? "" : soundId.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (sendSocketPayload("sound", trimmed, "")) {
            return;
        }
    }

    public static int hostPort() {
        return hostPort;
    }

    public static String hostToken() {
        return hostToken == null ? "" : hostToken;
    }

    public static boolean hostReady() {
        return HOST_RUNNING.get() && hostPort > 0 && hostToken != null && !hostToken.isBlank();
    }

    private static void startSocketHost() throws IOException {
        if (HOST_RUNNING.get()) {
            return;
        }
        hostToken = UUID.randomUUID().toString();
        ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        socket.setSoTimeout(1000);
        serverSocket = socket;
        hostPort = socket.getLocalPort();
        HOST_RUNNING.set(true);
        Thread thread = new Thread(ConsoleRequestBridge::acceptLoop, "koil-console-socket-bridge");
        thread.setDaemon(true);
        thread.start();
    }

    private static void acceptLoop() {
        while (HOST_RUNNING.get()) {
            ServerSocket socket = serverSocket;
            if (socket == null || socket.isClosed()) {
                HOST_RUNNING.set(false);
                break;
            }
            try {
                Socket client = socket.accept();
                handleSocketClient(client);
            } catch (SocketTimeoutException ignored) {
            } catch (IOException ignored) {
            }
        }
    }

    private static void handleSocketClient(Socket client) {
        try (Socket socket = client;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            String token = reader.readLine();
            String type = reader.readLine();
            String subject = reader.readLine();
            String encodedPayload = reader.readLine();
            if (hostToken == null || !hostToken.equals(token)) {
                writer.write("ERR\n");
                writer.flush();
                return;
            }
            String payload = decode(encodedPayload);
            MinecraftClient minecraft = MinecraftClient.getInstance();
            if (minecraft == null) {
                writer.write("ERR\n");
                writer.flush();
                return;
            }
            minecraft.execute(() -> {
                if ("sound".equals(type)) {
                    dispatchSound(subject);
                } else if ("request".equals(type)) {
                    dispatch(ConsoleChannel.fromId(subject), payload);
                }
            });
            writer.write("OK\n");
            writer.flush();
        } catch (IOException ignored) {
        }
    }

    private static boolean sendSocketPayload(String type, String subject, String payload) {
        Endpoint endpoint = hostReady() ? new Endpoint(hostPort, hostToken) : ExternalEndpointHolder.endpoint();
        if (endpoint == null) {
            return false;
        }
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), endpoint.port());
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(600);
            writer.write(endpoint.token());
            writer.write('\n');
            writer.write(type);
            writer.write('\n');
            writer.write(subject == null ? "" : subject);
            writer.write('\n');
            writer.write(encode(payload));
            writer.write('\n');
            writer.flush();
            return "OK".equals(reader.readLine());
        } catch (IOException ignored) {
            return false;
        }
    }

    public static void configureExternalEndpoint(int port, String token) {
        if (port <= 0 || token == null || token.isBlank()) {
            ExternalEndpointHolder.set(null);
            return;
        }
        ExternalEndpointHolder.set(new Endpoint(port, token));
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static void dispatch(ConsoleChannel channel, String input) {
        if (input.isBlank()) {
            return;
        }
        if (channel == ConsoleChannel.CLI) {
            AutomationRouter.handleConsoleInput(input);
            return;
        }
        if (input.startsWith("/")) {
            AutomationRouter.sendRawCommand(input.substring(1));
            return;
        }
        AutomationRouter.sendChatMessage(input);
    }

    private static void dispatchSound(String soundId) {
        if ("focus".equals(soundId) || "click".equals(soundId)) {
            UiSoundHelper.playButtonClick();
        }
    }

    private record Endpoint(int port, String token) {
    }

    private static final class ExternalEndpointHolder {
        private static volatile Endpoint endpoint;

        private static Endpoint endpoint() {
            return endpoint;
        }

        private static void set(Endpoint endpoint) {
            ExternalEndpointHolder.endpoint = endpoint;
        }
    }
}

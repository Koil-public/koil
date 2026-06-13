package com.spirit.koil.api.util.application;

import com.spirit.koil.api.console.ConsoleChannel;
import com.spirit.koil.api.console.ConsoleRequestBridge;

import javax.swing.*;

public final class ExternalWindowConsole {
    private ExternalWindowConsole() {
    }

    public static void main(String[] args) {
        ConsoleChannel channel = args.length > 0 ? ConsoleChannel.fromId(args[0]) : ConsoleChannel.KOIL;
        if (args.length > 2) {
            try {
                ConsoleRequestBridge.configureExternalEndpoint(Integer.parseInt(args[1]), args[2]);
            } catch (NumberFormatException ignored) {
            }
        }
        SwingUtilities.invokeLater(() -> {
            ExternalConsoleWindow window = new ExternalConsoleWindow(channel, true);
            window.setVisible(true);
        });
    }
}

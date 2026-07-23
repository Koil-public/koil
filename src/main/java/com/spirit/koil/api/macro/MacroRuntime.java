package com.spirit.koil.api.macro;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class MacroRuntime {
    private static final int MAX_CONCURRENT_MACROS = 8;
    private static final Map<String, Boolean> PREVIOUS_TRIGGER_STATE = new HashMap<>();
    private static final Deque<String> REQUESTED_RUNS = new ArrayDeque<>();
    private static final List<RunningMacro> RUNNING = new ArrayList<>();
    private static long ticks;

    private MacroRuntime() {
    }

    public static synchronized void runNow(String macroId) {
        if (macroId != null && !macroId.isBlank() && REQUESTED_RUNS.size() < 32) {
            REQUESTED_RUNS.addLast(macroId);
        }
    }

    public static void tick(MinecraftClient client) {
        if (client == null || client.getWindow() == null) {
            return;
        }
        ticks++;
        if (client.world == null || client.player == null) {
            cancelAll();
            return;
        }
        collectRequestedRuns();
        pollTriggers(client);
        advance(client);
    }

    private static synchronized void cancelAll() {
        REQUESTED_RUNS.clear();
        PREVIOUS_TRIGGER_STATE.clear();
        for (RunningMacro running : RUNNING) {
            running.cancel();
        }
        RUNNING.clear();
    }

    private static synchronized void collectRequestedRuns() {
        while (!REQUESTED_RUNS.isEmpty() && RUNNING.size() < MAX_CONCURRENT_MACROS) {
            String id = REQUESTED_RUNS.removeFirst();
            MacroRegistry.find(id).ifPresent(macro -> RUNNING.add(new RunningMacro(macro)));
        }
    }

    private static void pollTriggers(MinecraftClient client) {
        long window = client.getWindow().getHandle();
        boolean gameplayInput = client.currentScreen == null && client.world != null && client.player != null;
        for (MacroDefinition macro : MacroRegistry.all()) {
            boolean pressed = gameplayInput && macro.enabled() && isPressed(window, macro.triggerType(), macro.triggerCode());
            boolean previous = PREVIOUS_TRIGGER_STATE.getOrDefault(macro.id(), false);
            if (pressed && !previous && RUNNING.size() < MAX_CONCURRENT_MACROS) {
                RUNNING.add(new RunningMacro(macro));
            }
            PREVIOUS_TRIGGER_STATE.put(macro.id(), pressed);
        }
        PREVIOUS_TRIGGER_STATE.keySet().removeIf(id -> MacroRegistry.find(id).isEmpty());
    }

    private static boolean isPressed(long window, MacroTriggerType type, int code) {
        if (type == null || type == MacroTriggerType.NONE || code < 0) {
            return false;
        }
        return type == MacroTriggerType.MOUSE
                ? GLFW.glfwGetMouseButton(window, code) == GLFW.GLFW_PRESS
                : GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS;
    }

    private static void advance(MinecraftClient client) {
        Iterator<RunningMacro> iterator = RUNNING.iterator();
        while (iterator.hasNext()) {
            RunningMacro running = iterator.next();
            if (running.advance(client)) {
                iterator.remove();
            }
        }
    }

    private static final class RunningMacro {
        private final MacroDefinition macro;
        private int index;
        private long resumeTick;
        private InputUtil.Key heldKey;

        private RunningMacro(MacroDefinition macro) {
            this.macro = macro;
        }

        private void cancel() {
            if (heldKey != null) {
                KeyBinding.setKeyPressed(heldKey, false);
                heldKey = null;
            }
        }

        private boolean advance(MinecraftClient client) {
            if (heldKey != null) {
                if (ticks < resumeTick) {
                    return false;
                }
                KeyBinding.setKeyPressed(heldKey, false);
                heldKey = null;
                index++;
            }
            if (ticks < resumeTick) {
                return false;
            }
            if (index >= macro.actions().size()) {
                return true;
            }
            MacroAction action = macro.actions().get(index);
            switch (action.type()) {
                case COMMAND -> {
                    if (client.getNetworkHandler() != null && client.player != null) {
                        String command = action.text().strip();
                        while (command.startsWith("/")) {
                            command = command.substring(1);
                        }
                        if (!command.isBlank()) {
                            client.getNetworkHandler().sendChatCommand(command);
                        }
                    }
                    index++;
                }
                case KEY -> {
                    if (action.code() < 0) {
                        index++;
                        break;
                    }
                    heldKey = InputUtil.Type.KEYSYM.createFromCode(action.code());
                    KeyBinding.setKeyPressed(heldKey, true);
                    KeyBinding.onKeyPressed(heldKey);
                    resumeTick = ticks + action.durationTicks();
                }
                case MOUSE_BUTTON -> {
                    if (action.code() < 0) {
                        index++;
                        break;
                    }
                    heldKey = InputUtil.Type.MOUSE.createFromCode(action.code());
                    KeyBinding.setKeyPressed(heldKey, true);
                    KeyBinding.onKeyPressed(heldKey);
                    resumeTick = ticks + action.durationTicks();
                }
                case MOUSE_MOVE -> {
                    if (client.player != null) {
                        client.player.changeLookDirection(action.x(), action.y());
                    } else {
                        double[] cursorX = new double[1];
                        double[] cursorY = new double[1];
                        GLFW.glfwGetCursorPos(client.getWindow().getHandle(), cursorX, cursorY);
                        GLFW.glfwSetCursorPos(
                                client.getWindow().getHandle(),
                                cursorX[0] + action.x(),
                                cursorY[0] + action.y()
                        );
                    }
                    index++;
                }
                case WAIT -> {
                    index++;
                    resumeTick = ticks + action.durationTicks();
                }
            }
            return index >= macro.actions().size() && heldKey == null && ticks >= resumeTick;
        }
    }
}

package com.spirit.koil.api.util.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.automation.AutomationRouter;
import com.spirit.koil.api.automation.cli.AutomationCliRenderer;
import com.spirit.koil.api.automation.cli.AutomationCliSnapshot;
import com.spirit.koil.api.automation.cli.AutomationCliSnapshotStore;
import com.spirit.koil.api.automation.cli.AutomationCliViewModel;
import com.spirit.koil.api.console.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.time.LocalDate;

public final class ExternalConsoleWindow extends JFrame implements ConsoleRepository.Listener {
    private static final Color PANEL_OVERLAY = new Color(18, 22, 30, 108);
    private static final Color STRONG_PANEL_OVERLAY = new Color(14, 18, 26, 132);
    private static final Color TOP_BAR_OVERLAY = new Color(20, 24, 34, 168);
    private static final Color FIELD_OVERLAY = new Color(8, 10, 16, 126);
    private static final Color FIELD_FOCUS_OVERLAY = new Color(14, 20, 30, 160);
    private static final Color OUTPUT_OVERLAY = new Color(10, 12, 18, 116);
    private static final int[] WINDOW_ICON_SIZES = new int[]{256, 128, 64, 48, 32, 24, 20, 16};

    private ConsoleChannel channel;
    private final boolean standalone;
    private final DefaultStyledDocument document = new DefaultStyledDocument();
    private final JTextPane outputPane = new AlphaTextPane(this.document, OUTPUT_OVERLAY);
    private final JTextField searchField = new JTextField();
    private final JTextField inputField = new JTextField();
    private final List<ConsoleStyledLine> lines = new ArrayList<>();
    private final List<ConsoleInputSuggestionService.ConsoleInputSuggestion> inputSuggestions = new ArrayList<>();
    private final JLabel titleLabel = new JLabel();
    private final JLabel subtitleLabel = new JLabel("Manager Menu - Console");
    private final JLabel statusLabel = new JLabel();
    private final float uiScaleFactor;
    private JScrollPane outputScrollPane;
    private SuggestionPanel suggestionPanel;
    private JLayeredPane layeredPane;
    private Timer filePollTimer;
    private long lastFileStamp = Long.MIN_VALUE;
    private long lastFileOffset;
    private long lastFileSequence;
    private String lastFilter = "";
    private int selectedInputSuggestionIndex;

    public ExternalConsoleWindow(ConsoleChannel channel) {
        this(channel, false);
    }

    public ExternalConsoleWindow(ConsoleChannel channel, boolean standalone) {
        super("Koil Console :: " + channel.id().toUpperCase());
        this.channel = channel;
        this.standalone = standalone;
        this.uiScaleFactor = readUiScaleFactor();
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        int width = Math.round(960 * this.uiScaleFactor);
        int height = Math.round(680 * this.uiScaleFactor);
        setSize(Math.max(900, width), Math.max(620, height));
        restoreWindowBounds();
        List<Image> windowIcons = loadWindowIcons();
        Image icon = windowIcons.isEmpty() ? null : windowIcons.get(0);
        applyWindowIcons(windowIcons);
        applyComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        getRootPane().setBorder(new MatteBorder(1, 1, 1, 1, ConsoleTheme.awt(ConsoleTheme.border())));
        getRootPane().setOpaque(true);
        getRootPane().setBackground(ConsoleTheme.awt(ConsoleTheme.background()));
        getRootPane().applyComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        setContentPane(buildUi(icon));
        this.layeredPane = getLayeredPane();
        this.suggestionPanel = new SuggestionPanel();
        this.suggestionPanel.setVisible(false);
        this.layeredPane.add(this.suggestionPanel, JLayeredPane.POPUP_LAYER);
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                refreshSuggestionPanel();
                saveWindowBounds();
            }

            @Override
            public void componentMoved(java.awt.event.ComponentEvent event) {
                refreshSuggestionPanel();
                saveWindowBounds();
            }
        });
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                saveWindowBounds();
            }

            @Override
            public void windowClosed(java.awt.event.WindowEvent event) {
                saveWindowBounds();
            }
        });
        reload();
        if (this.standalone) {
            startFilePolling();
        } else {
            ConsoleRepository.getInstance().subscribe(channel, this);
        }
    }

    private JPanel buildUi(Image icon) {
        JPanel root = createBackgroundPanel();
        root.setLayout(new BorderLayout());
        root.setOpaque(false);
        root.applyComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);

        JPanel titleStrip = new JPanel(new BorderLayout());
        titleStrip.setOpaque(true);
        titleStrip.setBackground(TOP_BAR_OVERLAY);
        titleStrip.setPreferredSize(new Dimension(10, 58));
        titleStrip.setBorder(new EmptyBorder(12, 10, 10, 10));

        this.titleLabel.setForeground(ConsoleTheme.awt(ConsoleTheme.primaryText()));
        this.titleLabel.setFont(resolveFont().deriveFont(Font.BOLD, scaledFontSize(14f)));
        this.subtitleLabel.setForeground(ConsoleTheme.awt(ConsoleTheme.secondaryText()));
        this.subtitleLabel.setFont(resolveFont().deriveFont(scaledFontSize(10f)));
        updateTitle();

        JPanel leftTitlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftTitlePanel.setOpaque(false);
        leftTitlePanel.applyComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        JLabel iconLabel = new JLabel();
        if (icon != null) {
            int titleIconSize = Math.round(20 * Math.max(1.0f, Math.min(1.7f, this.uiScaleFactor)));
            if (icon instanceof BufferedImage) {
                iconLabel.setIcon(new ImageIcon(scaleIconTexture((BufferedImage) icon, titleIconSize)));
            } else {
                iconLabel.setIcon(new ImageIcon(icon.getScaledInstance(titleIconSize, titleIconSize, Image.SCALE_SMOOTH)));
            }
        }
        JPanel titleTextPanel = new JPanel(new BorderLayout());
        titleTextPanel.setOpaque(false);
        titleTextPanel.applyComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        titleTextPanel.add(this.titleLabel, BorderLayout.NORTH);
        titleTextPanel.add(this.subtitleLabel, BorderLayout.SOUTH);
        leftTitlePanel.add(iconLabel);
        leftTitlePanel.add(titleTextPanel);
        titleStrip.add(leftTitlePanel, BorderLayout.WEST);

        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttonBar.setOpaque(false);
        buttonBar.applyComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        for (ConsoleChannel consoleChannel : new ConsoleChannel[]{ConsoleChannel.KOIL, ConsoleChannel.PACKAGE, ConsoleChannel.MINECRAFT, ConsoleChannel.CLI}) {
            JButton button = createTopButton(channelLabel(consoleChannel));
            button.addActionListener(event -> {
                DesktopUiSoundHelper.playClick();
                switchChannel(consoleChannel);
            });
            buttonBar.add(button);
        }
        titleStrip.add(buttonBar, BorderLayout.EAST);

        JPanel stripe = new JPanel();
        stripe.setPreferredSize(new Dimension(10, 2));
        stripe.setBackground(ConsoleTheme.awt(ConsoleTheme.headerStripe()));

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(true);
        north.setBackground(TOP_BAR_OVERLAY);
        north.applyComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        north.add(titleStrip, BorderLayout.NORTH);
        north.add(stripe, BorderLayout.SOUTH);
        root.add(north, BorderLayout.NORTH);

        JPanel centerPanel = new AlphaPanel(STRONG_PANEL_OVERLAY, new BorderLayout());
        centerPanel.applyComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        centerPanel.setBorder(BorderFactory.createCompoundBorder(
                new EmptyBorder(10, 10, 0, 10),
                BorderFactory.createLineBorder(ConsoleTheme.awt(ConsoleTheme.border()))
        ));

        this.searchField.setFont(resolveFont().deriveFont(scaledFontSize(13f)));
        this.searchField.setOpaque(true);
        this.searchField.setBackground(PANEL_OVERLAY);
        this.searchField.setForeground(ConsoleTheme.awt(ConsoleTheme.primaryText()));
        this.searchField.setCaretColor(ConsoleTheme.awt(ConsoleTheme.primaryText()));
        this.searchField.setSelectionColor(new Color(64, 156, 214, 180));
        this.searchField.setSelectedTextColor(ConsoleTheme.awt(ConsoleTheme.primaryText()));
        this.searchField.setBorder(new EmptyBorder(0, 10, 0, 10));
        this.searchField.setMargin(new Insets(6, 10, 6, 10));
        this.searchField.setHorizontalAlignment(JTextField.LEFT);
        this.searchField.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        ((AbstractDocument) this.searchField.getDocument()).putProperty("i18n", Boolean.FALSE);
        this.searchField.setToolTipText("Search visible log history");
        this.searchField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                DesktopUiSoundHelper.playFocus();
                searchField.setCaretPosition(searchField.getDocument().getLength());
                searchField.repaint();
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                searchField.repaint();
            }
        });
        this.searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                ensureAppendCaret(searchField);
                renderDocument();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                renderDocument();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                renderDocument();
            }
        });
        installPopupFieldBehavior(this.searchField);

        JPanel searchWrap = new FieldChromePanel();
        searchWrap.setBorder(new EmptyBorder(8, 8, 8, 8));
        searchWrap.applyComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        searchWrap.add(this.searchField, BorderLayout.CENTER);
        centerPanel.add(searchWrap, BorderLayout.NORTH);

        this.outputPane.setEditable(false);
        this.outputPane.setOpaque(false);
        this.outputPane.setBackground(new Color(0, 0, 0, 0));
        this.outputPane.setForeground(ConsoleTheme.awt(ConsoleTheme.secondaryText()));
        this.outputPane.setFont(resolveFont().deriveFont(scaledFontSize(13f)));
        this.outputPane.setBorder(new EmptyBorder(10, 10, 10, 10));
        this.outputPane.setFocusable(false);

        JScrollPane scrollPane = new JScrollPane(this.outputPane);
        this.outputScrollPane = scrollPane;
        scrollPane.setBackground(new Color(10, 12, 18, 150));
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.getViewport().setBackground(new Color(0, 0, 0, 0));
        scrollPane.setBorder(new EmptyBorder(0, 0, 0, 0));
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getVerticalScrollBar().setUI(new ConsoleScrollBarUi());
        scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));
        scrollPane.getVerticalScrollBar().setBackground(new Color(8, 10, 16, 220));
        scrollPane.getHorizontalScrollBar().setUI(new ConsoleScrollBarUi());
        scrollPane.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 10));
        scrollPane.getHorizontalScrollBar().setBackground(new Color(8, 10, 16, 220));
        centerPanel.add(scrollPane, BorderLayout.CENTER);

        this.inputField.setFont(resolveFont().deriveFont(scaledFontSize(13f)));
        this.inputField.setOpaque(true);
        this.inputField.setBackground(PANEL_OVERLAY);
        this.inputField.setForeground(ConsoleTheme.awt(ConsoleTheme.primaryText()));
        this.inputField.setCaretColor(ConsoleTheme.awt(ConsoleTheme.primaryText()));
        this.inputField.setSelectionColor(new Color(64, 156, 214, 180));
        this.inputField.setSelectedTextColor(ConsoleTheme.awt(ConsoleTheme.primaryText()));
        this.inputField.setBorder(new EmptyBorder(0, 10, 0, 10));
        this.inputField.setMargin(new Insets(6, 10, 6, 10));
        this.inputField.setHorizontalAlignment(JTextField.LEFT);
        this.inputField.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        ((AbstractDocument) this.inputField.getDocument()).putProperty("i18n", Boolean.FALSE);
        this.inputField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                DesktopUiSoundHelper.playFocus();
                inputField.setCaretPosition(inputField.getDocument().getLength());
                updateInputSuggestions();
                inputField.repaint();
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                clearInputSuggestions();
                inputField.repaint();
            }
        });
        this.inputField.addActionListener(event -> {
            String text = this.inputField.getText();
            if (!text.isBlank()) {
                DesktopUiSoundHelper.playClick();
                submit(text);
                this.inputField.setText("");
            }
        });
        this.inputField.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent event) {
                if (hasInputSuggestions()) {
                    if (event.isControlDown() && event.getKeyCode() == java.awt.event.KeyEvent.VK_UP) {
                        moveInputSuggestionSelection(-1);
                        event.consume();
                        return;
                    }
                    if (event.isControlDown() && event.getKeyCode() == java.awt.event.KeyEvent.VK_DOWN) {
                        moveInputSuggestionSelection(1);
                        event.consume();
                        return;
                    }
                    if (event.getKeyCode() == java.awt.event.KeyEvent.VK_TAB) {
                        acceptSelectedInputSuggestion();
                        event.consume();
                        return;
                    }
                }
                if (event.getKeyCode() == java.awt.event.KeyEvent.VK_UP) {
                    String previous = ConsoleCommandHistory.previous();
                    if (previous != null) {
                        inputField.setText(previous);
                        inputField.setCaretPosition(inputField.getText().length());
                    }
                    event.consume();
                } else if (event.getKeyCode() == java.awt.event.KeyEvent.VK_DOWN) {
                    String next = ConsoleCommandHistory.next();
                    inputField.setText(next == null ? "" : next);
                    inputField.setCaretPosition(inputField.getText().length());
                    event.consume();
                }
            }

            @Override
            public void keyReleased(java.awt.event.KeyEvent event) {
                int code = event.getKeyCode();
                if (code == java.awt.event.KeyEvent.VK_UP
                        || code == java.awt.event.KeyEvent.VK_DOWN
                        || code == java.awt.event.KeyEvent.VK_LEFT
                        || code == java.awt.event.KeyEvent.VK_RIGHT
                        || code == java.awt.event.KeyEvent.VK_SHIFT
                        || code == java.awt.event.KeyEvent.VK_CONTROL
                        || code == java.awt.event.KeyEvent.VK_ALT) {
                    return;
                }
                updateInputSuggestions();
            }
        });
        installPopupFieldBehavior(this.inputField);

        JPanel inputPanel = new FieldChromePanel();
        inputPanel.setBorder(new EmptyBorder(0, 1, 1, 1));
        inputPanel.applyComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        JPanel inputDivider = new JPanel();
        inputDivider.setPreferredSize(new Dimension(10, 1));
        inputDivider.setBackground(ConsoleTheme.awt(ConsoleTheme.border()));
        inputPanel.add(inputDivider, BorderLayout.NORTH);
        inputPanel.add(this.inputField, BorderLayout.CENTER);
        centerPanel.add(inputPanel, BorderLayout.SOUTH);

        root.add(centerPanel, BorderLayout.CENTER);

        JPanel statusPanel = new AlphaPanel(TOP_BAR_OVERLAY, new BorderLayout());
        statusPanel.setBorder(new MatteBorder(1, 0, 0, 0, ConsoleTheme.awt(ConsoleTheme.border())));
        this.statusLabel.setBorder(new EmptyBorder(6, 10, 6, 10));
        this.statusLabel.setForeground(ConsoleTheme.awt(ConsoleTheme.secondaryText()));
        this.statusLabel.setFont(resolveFont().deriveFont(scaledFontSize(11f)));
        statusPanel.add(this.statusLabel, BorderLayout.WEST);
        root.add(statusPanel, BorderLayout.SOUTH);

        return root;
    }

    private void installPopupFieldBehavior(JTextField field) {
        field.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent event) {
                DesktopUiSoundHelper.playClick();
            }
        });
    }

    private Font resolveFont() {
        Font installed = firstAvailableFont("Minecraft", "Minecraftia", "Minecrafter", "Monocraft");
        if (installed != null) {
            return installed.deriveFont(Font.PLAIN, 13f);
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, 13);
    }

    private float scaledFontSize(float base) {
        return base * Math.max(1.0f, Math.min(1.7f, this.uiScaleFactor));
    }

    private float readUiScaleFactor() {
        int guiScale = readGuiScale();
        if (guiScale <= 0) {
            return 1.0f;
        }
        return 1.0f + Math.min(0.7f, (guiScale - 1) * 0.12f);
    }

    private int readGuiScale() {
        for (Path candidate : List.of(Path.of("run/options.txt"), Path.of("options.txt"))) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(candidate)) {
                    if (line.startsWith("guiScale:")) {
                        return Integer.parseInt(line.substring("guiScale:".length()).trim());
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private Font firstAvailableFont(String... names) {
        String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        for (String candidate : names) {
            for (String existing : available) {
                if (existing.equalsIgnoreCase(candidate)) {
                    return new Font(existing, Font.PLAIN, 13);
                }
            }
        }
        return null;
    }

    private JButton createTopButton(String label) {
        JButton button = new JButton(label);
        button.setFont(resolveFont().deriveFont(12f));
        button.setFocusable(false);
        button.setForeground(ConsoleTheme.awt(ConsoleTheme.primaryText()));
        button.setBackground(new Color(0, 0, 0, 0));
        button.setBorder(buttonBorder());
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setUI(new PixelButtonUi());
        return button;
    }

    private Border buttonBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ConsoleTheme.awt(ConsoleTheme.border())),
                new EmptyBorder(4, 8, 4, 8)
        );
    }

    private JPanel createBackgroundPanel() {
        BackgroundImage background = loadBackgroundImage();
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(ConsoleTheme.awt(ConsoleTheme.background()));
                g2.fillRect(0, 0, getWidth(), getHeight());
                if (background != null && background.image() != null) {
                    drawBackground(g2, background, getWidth(), getHeight());
                }
                g2.setColor(new Color(10, 12, 16, background != null && background.tiled() ? 28 : 52));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(TOP_BAR_OVERLAY);
                g2.fillRect(0, 0, getWidth(), 54);
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        return panel;
    }

    private void drawBackground(Graphics2D graphics, BackgroundImage background, int width, int height) {
        if (background.tiled()) {
            drawTiledBackground(graphics, background.image(), width, height);
            return;
        }
        drawCoverBackground(graphics, background.image(), width, height);
    }

    private void drawCoverBackground(Graphics2D graphics, BufferedImage image, int width, int height) {
        double scale = Math.max((double) width / image.getWidth(), (double) height / image.getHeight());
        int drawWidth = (int) Math.ceil(image.getWidth() * scale);
        int drawHeight = (int) Math.ceil(image.getHeight() * scale);
        int drawX = (width - drawWidth) / 2;
        int drawY = (height - drawHeight) / 2;
        Object oldInterpolation = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(image, drawX, drawY, drawWidth, drawHeight, null);
        if (oldInterpolation != null) {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
        } else {
            graphics.getRenderingHints().remove(RenderingHints.KEY_INTERPOLATION);
        }
    }

    private void drawTiledBackground(Graphics2D graphics, BufferedImage image, int width, int height) {
        TexturePaint paint = new TexturePaint(image, new Rectangle(0, 0, image.getWidth(), image.getHeight()));
        Paint oldPaint = graphics.getPaint();
        graphics.setPaint(paint);
        graphics.fillRect(0, 0, width, height);
        graphics.setPaint(oldPaint);
    }

    private BackgroundImage loadBackgroundImage() {
        BufferedImage runtimeBackground = loadRuntimeBackgroundImage();
        if (runtimeBackground != null) {
            return new BackgroundImage(runtimeBackground, false);
        }
        try (InputStream stream = ExternalConsoleWindow.class.getResourceAsStream("/assets/koil/textures/gui/menus/options_background_fallback.png")) {
            if (stream == null) {
                return null;
            }
            BufferedImage fallback = ImageIO.read(stream);
            return fallback == null ? null : new BackgroundImage(fallback, true);
        } catch (IOException ignored) {
            return null;
        }
    }

    private BufferedImage loadRuntimeBackgroundImage() {
        Path configured = resolveConfiguredLoadingScreenPath();
        if (configured != null) {
            BufferedImage configuredImage = readBackgroundImage(configured);
            if (configuredImage != null) {
                return configuredImage;
            }
        }
        for (Path root : List.of(Path.of("koil/sys/design"), Path.of("koil/design"), Path.of("run/koil/sys/design"))) {
            BufferedImage discovered = readFirstDiscoveredLoadingScreen(root);
            if (discovered != null) {
                return discovered;
            }
        }
        return null;
    }

    private BufferedImage readFirstDiscoveredLoadingScreen(Path root) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        try {
            List<Path> candidates = Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).matches("loading_screen.*\\.png"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path candidate : candidates) {
                BufferedImage image = readBackgroundImage(candidate);
                if (image != null) {
                    return image;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private BufferedImage readBackgroundImage(Path candidate) {
        try (InputStream stream = Files.newInputStream(candidate)) {
            return ImageIO.read(stream);
        } catch (IOException ignored) {
            return null;
        }
    }

    private Path resolveConfiguredLoadingScreenPath() {
        try {
            Path configPath = Path.of("koil/sys/config.json");
            if (!Files.isRegularFile(configPath)) {
                return null;
            }
            JsonObject config = readJsonObject(configPath);
            String designBase = stringValue(config, "uiDesignDirectory", "./koil/sys/design");
            String requestedTheme = stringValue(config, "uiTheme", "default");
            Path themePath = Path.of(designBase, requestedTheme);
            Path designPath = themePath.resolve("design.json");
            if (!Files.isRegularFile(designPath)) {
                themePath = Path.of(designBase, "default");
                designPath = themePath.resolve("design.json");
            }
            if (!Files.isRegularFile(designPath)) {
                return null;
            }
            JsonObject design = readJsonObject(designPath);
            Path uiFilesDirectory = resolveThemeRelativePath(themePath, stringValue(design, "uiFilesDirectory", "./files"));
            Path uiImageDirectory = resolveThemeRelativePath(themePath, stringValue(design, "uiImageDirectory", "./images"));
            String loadingTexture = createLoadingTextureName(uiFilesDirectory);
            Path candidate = uiImageDirectory.resolve(loadingTexture);
            return Files.isRegularFile(candidate) ? candidate : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String createLoadingTextureName(Path uiFilesDirectory) {
        String baseName = "loading_screen";
        Path backgroundFile = uiFilesDirectory.resolve("background.json");
        if (!Files.isRegularFile(backgroundFile)) {
            return baseName + ".png";
        }
        try {
            JsonObject config = readJsonObject(Path.of("koil/sys/config.json"));
            boolean holidayDesign = booleanValue(config, "holidayDesign", false);
            String background = stringValue(config, "background", "");
            JsonObject backgroundConfig = readJsonObject(backgroundFile);
            JsonArray mappings = backgroundConfig.getAsJsonArray("background");
            if (mappings == null) {
                return baseName + ".png";
            }
            int currentMonth = LocalDate.now().getMonthValue();
            for (JsonElement element : mappings) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                String fileSuffix = stringValue(entry, "fileSuffix", "");
                boolean holidayCheck = booleanValue(entry, "holidayCheck", false);
                int month = intValue(entry, "month", -1);
                if (holidayCheck && holidayDesign && month == currentMonth) {
                    return baseName + fileSuffix + ".png";
                }
                JsonArray tags = entry.getAsJsonArray("tags");
                if (tags == null) {
                    continue;
                }
                for (JsonElement tag : tags) {
                    if (tag.isJsonPrimitive() && background.equalsIgnoreCase(tag.getAsString())) {
                        return baseName + fileSuffix + ".png";
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return baseName + ".png";
    }

    private JsonObject readJsonObject(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement element = JsonParser.parseReader(reader);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        }
    }

    private Path resolveThemeRelativePath(Path themePath, String value) {
        if (value == null || value.isBlank()) {
            return themePath;
        }
        String normalized = value.replace("\\", "/");
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return themePath.resolve(normalized).normalize();
    }

    private String stringValue(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean booleanValue(JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int intValue(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void applyWindowIcons(List<Image> icons) {
        if (icons == null || icons.isEmpty()) {
            return;
        }
        setIconImages(icons);
        setIconImage(icons.get(0));
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                Taskbar.getTaskbar().setIconImage(icons.get(0));
            }
        } catch (Exception ignored) {
        }
    }

    private List<Image> loadWindowIcons() {
        BufferedImage source = loadKoilLogoImage();
        if (source == null) {
            return List.of();
        }
        BufferedImage prepared = prepareIconTexture(source);
        List<Image> icons = new ArrayList<>();
        for (int size : WINDOW_ICON_SIZES) {
            icons.add(scaleIconTexture(prepared, size));
        }
        return icons;
    }

    private BufferedImage loadKoilLogoImage() {
        for (Path candidate : runtimeIconCandidates()) {
            BufferedImage image = readIconImage(candidate);
            if (image != null) {
                return image;
            }
        }
        for (String resource : List.of(
                "/assets/koil/textures/gui/icons/koil_logo.png",
                "/assets/koil/textures/gui/icons/window_icon.png",
                "/assets/koil/textures/gui/icons/icon.png",
                "/assets/koil/textures/gui/koil_logo.png",
                "/assets/koil/textures/gui/logo.png",
                "/assets/koil/icon.png",
                "/koil_icon.png",
                "/icon.png"
        )) {
            BufferedImage image = readResourceIconImage(resource);
            if (image != null) {
                return image;
            }
        }
        return null;
    }

    private List<Path> runtimeIconCandidates() {
        List<Path> candidates = new ArrayList<>();
        candidates.addAll(resolveConfiguredIconCandidates());
        for (Path candidate : List.of(
                Path.of("koil/sys/design/default/images/koil_logo.png"),
                Path.of("koil/sys/design/default/images/window_icon.png"),
                Path.of("koil/sys/design/default/images/icon.png"),
                Path.of("koil/sys/design/default/images/logo.png"),
                Path.of("koil/design/default/images/koil_logo.png"),
                Path.of("koil/design/default/images/window_icon.png"),
                Path.of("koil/design/default/images/icon.png"),
                Path.of("run/koil/sys/design/default/images/koil_logo.png"),
                Path.of("run/koil/sys/design/default/images/window_icon.png"),
                Path.of("run/koil/sys/design/default/images/icon.png"),
                Path.of("src/main/resources/assets/koil/textures/gui/icons/koil_logo.png"),
                Path.of("src/main/resources/assets/koil/textures/gui/icons/window_icon.png"),
                Path.of("src/main/resources/assets/koil/textures/gui/icons/icon.png")
        )) {
            candidates.add(candidate);
        }
        for (Path root : List.of(Path.of("koil/sys/design"), Path.of("koil/design"), Path.of("run/koil/sys/design"), Path.of("src/main/resources/assets/koil"))) {
            candidates.addAll(discoveredIconCandidates(root));
        }
        return candidates;
    }

    private List<Path> resolveConfiguredIconCandidates() {
        List<Path> candidates = new ArrayList<>();
        try {
            Path configPath = Path.of("koil/sys/config.json");
            if (!Files.isRegularFile(configPath)) {
                return candidates;
            }
            JsonObject config = readJsonObject(configPath);
            String designBase = stringValue(config, "uiDesignDirectory", "./koil/sys/design");
            String requestedTheme = stringValue(config, "uiTheme", "default");
            Path themePath = Path.of(designBase, requestedTheme);
            Path designPath = themePath.resolve("design.json");
            if (!Files.isRegularFile(designPath)) {
                themePath = Path.of(designBase, "default");
                designPath = themePath.resolve("design.json");
            }
            if (!Files.isRegularFile(designPath)) {
                return candidates;
            }
            JsonObject design = readJsonObject(designPath);
            Path uiImageDirectory = resolveThemeRelativePath(themePath, stringValue(design, "uiImageDirectory", "./images"));
            for (String name : List.of("koil_logo.png", "window_icon.png", "icon.png", "logo.png", "koil.png", "title.png", "koil_title.png")) {
                candidates.add(uiImageDirectory.resolve(name));
            }
        } catch (Exception ignored) {
        }
        return candidates;
    }

    private List<Path> discoveredIconCandidates(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try {
            return Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .filter(path -> iconCandidateScore(path) < 1_000)
                    .sorted(Comparator.comparingInt(this::iconCandidateScore).thenComparing(Path::toString))
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private int iconCandidateScore(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT) + "structure.png";
        if (name.equals("icon.png")) {
            return 0;
        }
        if (name.equals("structure.png")) {
            return 1;
        }
        return 1_000;
    }

    private BufferedImage readIconImage(Path candidate) {
        if (candidate == null || !Files.isRegularFile(candidate)) {
            return null;
        }
        try (InputStream stream = Files.newInputStream(candidate)) {
            return ImageIO.read(stream);
        } catch (IOException ignored) {
            return null;
        }
    }

    private BufferedImage readResourceIconImage(String resource) {
        try (InputStream stream = ExternalConsoleWindow.class.getResourceAsStream(resource)) {
            if (stream == null) {
                return null;
            }
            return ImageIO.read(stream);
        } catch (IOException ignored) {
            return null;
        }
    }

    private BufferedImage prepareIconTexture(BufferedImage source) {
        BufferedImage transparent = removeBlackIconBackground(source);
        Rectangle bounds = visibleIconBounds(transparent);
        if (bounds == null) {
            bounds = new Rectangle(0, 0, transparent.getWidth(), transparent.getHeight());
        }
        int side = Math.max(bounds.width, bounds.height);
        int margin = Math.max(2, side / 10);
        int canvasSize = side + margin + margin;
        BufferedImage output = new BufferedImage(canvasSize, canvasSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = output.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        int x = (canvasSize - bounds.width) / 2;
        int y = (canvasSize - bounds.height) / 2;
        g2.drawImage(transparent, x, y, x + bounds.width, y + bounds.height, bounds.x, bounds.y, bounds.x + bounds.width, bounds.y + bounds.height, null);
        g2.dispose();
        return output;
    }

    private BufferedImage removeBlackIconBackground(BufferedImage source) {
        BufferedImage output = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                int alpha = (argb >>> 24) & 255;
                int red = (argb >>> 16) & 255;
                int green = (argb >>> 8) & 255;
                int blue = argb & 255;
                if (alpha > 0 && red <= 4 && green <= 4 && blue <= 4) {
                    output.setRGB(x, y, 0);
                } else {
                    output.setRGB(x, y, argb);
                }
            }
        }
        return output;
    }

    private Rectangle visibleIconBounds(BufferedImage image) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = (image.getRGB(x, y) >>> 24) & 255;
                if (alpha > 8) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return null;
        }
        return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private BufferedImage scaleIconTexture(BufferedImage source, int size) {
        BufferedImage output = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = output.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.drawImage(source, 0, 0, size, size, null);
        g2.dispose();
        return output;
    }

    private void reload() {
        this.lines.clear();
        if (this.channel == ConsoleChannel.CLI) {
            AutomationCliSnapshot snapshot = this.standalone ? AutomationCliSnapshotStore.load() : AutomationCliViewModel.snapshot();
            this.lines.addAll(AutomationCliRenderer.render(snapshot, ""));
            this.lastFileStamp = cliSnapshotStamp();
            this.lastFileOffset = 0L;
            this.lastFileSequence = this.lines.size();
            renderDocument();
            return;
        }
        if (this.standalone) {
            this.lines.addAll(ConsoleDisplayService.readStyledLog(channelLogPath(this.channel), this.channel));
            try {
                this.lastFileOffset = Files.exists(channelLogPath(this.channel)) ? Files.size(channelLogPath(this.channel)) : 0L;
            } catch (IOException ignored) {
                this.lastFileOffset = 0L;
            }
            this.lastFileSequence = this.lines.size();
        } else {
            this.lines.addAll(ConsoleDisplayService.snapshot(this.channel));
        }
        renderDocument();
    }

    private void renderDocument() {
        boolean keepBottom = isNearBottom();
        try {
            this.document.remove(0, this.document.getLength());
        } catch (BadLocationException ignored) {
        }
        List<ConsoleStyledLine> visibleLines = filteredLines();
        for (ConsoleStyledLine line : visibleLines) {
            appendLineToDocument(line);
        }
        this.lastFilter = normalizedFilter();
        this.statusLabel.setText("channel: " + channelLabel(this.channel) + "  rows: " + visibleLines.size());
        refreshSuggestionPanel();
        if (keepBottom) {
            SwingUtilities.invokeLater(() -> this.outputPane.setCaretPosition(this.document.getLength()));
        }
    }

    private void appendLineToDocument(ConsoleStyledLine line) {
        for (ConsoleStyledSpan span : line.spans()) {
            appendSpan(span.text(), span.color());
        }
        try {
            this.document.insertString(this.document.getLength(), "\n", new SimpleAttributeSet());
        } catch (BadLocationException ignored) {
        }
    }

    private List<ConsoleStyledLine> filteredLines() {
        return ConsoleDisplayService.filter(this.lines, this.searchField.getText());
    }

    private void append(ConsoleStyledLine line) {
        this.lines.add(line);
        if (this.lines.size() > 4_000) {
            this.lines.remove(0);
            renderDocument();
            return;
        }
        String filter = normalizedFilter();
        if (!filter.isEmpty()) {
            renderDocument();
            return;
        }
        boolean keepBottom = isNearBottom();
        appendLineToDocument(line);
        this.statusLabel.setText("channel: " + channelLabel(this.channel) + "  rows: " + this.lines.size());
        this.lastFilter = "";
        if (keepBottom) {
            SwingUtilities.invokeLater(() -> this.outputPane.setCaretPosition(this.document.getLength()));
        }
    }

    @Override
    public void onRecord(ConsoleRecord record) {
        if (record.channel() != this.channel) {
            return;
        }
        if (this.channel == ConsoleChannel.CLI) {
            SwingUtilities.invokeLater(this::reload);
            return;
        }
        SwingUtilities.invokeLater(() -> append(ConsoleFormatter.style(record)));
    }

    @Override
    public void dispose() {
        if (this.filePollTimer != null) {
            this.filePollTimer.stop();
            this.filePollTimer = null;
        }
        if (!this.standalone) {
            ConsoleRepository.getInstance().unsubscribe(this.channel, this);
        }
        super.dispose();
    }

    private void appendSpan(String text, int color) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setForeground(attributes, ConsoleTheme.awt(color));
        StyleConstants.setFontFamily(attributes, resolveFont().getFamily());
        StyleConstants.setFontSize(attributes, 13);
        try {
            this.document.insertString(this.document.getLength(), text, attributes);
        } catch (BadLocationException ignored) {
        }
    }

    private void switchChannel(ConsoleChannel channel) {
        if (this.channel == channel) {
            return;
        }
        if (!this.standalone) {
            ConsoleRepository.getInstance().unsubscribe(this.channel, this);
        }
        this.channel = channel;
        if (!this.standalone) {
            ConsoleRepository.getInstance().subscribe(this.channel, this);
        }
        this.lastFileStamp = Long.MIN_VALUE;
        updateTitle();
        reload();
    }

    private void updateTitle() {
        this.titleLabel.setText("KOIL CONSOLE  |  " + channelLabel(this.channel));
        setTitle("Koil Console :: " + this.channel.id().toUpperCase());
    }

    private void submit(String text) {
        ConsoleCommandHistory.push(text);
        clearInputSuggestions();
        if (this.standalone) {
            ConsoleRequestBridge.submit(this.channel, text);
            return;
        }
        if (this.channel == ConsoleChannel.CLI) {
            AutomationRouter.handleConsoleInput(text);
            return;
        }
        if (text.startsWith("/")) {
            AutomationRouter.sendRawCommand(text.substring(1));
            return;
        }
        AutomationRouter.sendChatMessage(text);
    }

    private void startFilePolling() {
        this.filePollTimer = new Timer(180, event -> pollFileState());
        this.filePollTimer.setCoalesce(true);
        this.filePollTimer.start();
    }

    private void pollFileState() {
        if (this.channel == ConsoleChannel.CLI) {
            long stamp = cliSnapshotStamp();
            if (stamp != this.lastFileStamp) {
                this.lastFileStamp = stamp;
                reload();
            }
            return;
        }
        Path path = channelLogPath(this.channel);
        try {
            long stamp = Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : Long.MIN_VALUE;
            if (stamp != this.lastFileStamp) {
                this.lastFileStamp = stamp;
                String filter = normalizedFilter();
                long size = Files.exists(path) ? Files.size(path) : 0L;
                if (filter.isEmpty() && size >= this.lastFileOffset && this.lastFileOffset > 0L) {
                    ConsoleDisplayService.ConsoleLogReadResult result = ConsoleDisplayService.readStyledLogDelta(path, this.channel, this.lastFileOffset, this.lastFileSequence);
                    this.lastFileOffset = result.offset();
                    this.lastFileSequence = result.sequence();
                    if (!result.lines().isEmpty()) {
                        for (ConsoleStyledLine line : result.lines()) {
                            append(line);
                        }
                        return;
                    }
                }
                reload();
            }
        } catch (IOException ignored) {
        }
    }

    private long cliSnapshotStamp() {
        try {
            Path path = AutomationCliSnapshotStore.path();
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : Long.MIN_VALUE;
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private Path channelLogPath(ConsoleChannel channel) {
        return switch (channel) {
            case KOIL -> Path.of("koil/logs/latest.log");
            case PACKAGE -> Path.of("koil/logs/package/latest.log");
            case MINECRAFT -> Path.of("logs/latest.log");
            case CLI -> Path.of("koil/logs/cli/latest.log");
        };
    }

    private String channelLabel(ConsoleChannel channel) {
        return switch (channel) {
            case KOIL -> "Koil Logs";
            case PACKAGE -> "Package Logs";
            case MINECRAFT -> "Minecraft Logs";
            case CLI -> "Automation CLI";
        };
    }

    private void updateInputSuggestions() {
        this.inputSuggestions.clear();
        if (!this.inputField.isFocusOwner()) {
            refreshSuggestionPanel();
            return;
        }
        this.inputSuggestions.addAll(ConsoleInputSuggestionService.suggestions(this.inputField.getText(), this.channel, this.channel == ConsoleChannel.CLI));
        this.selectedInputSuggestionIndex = Math.max(0, Math.min(this.selectedInputSuggestionIndex, this.inputSuggestions.size() - 1));
        refreshSuggestionPanel();
    }

    private void clearInputSuggestions() {
        this.inputSuggestions.clear();
        this.selectedInputSuggestionIndex = 0;
        setInputGhostText("");
        refreshSuggestionPanel();
    }

    private boolean hasInputSuggestions() {
        return !this.inputSuggestions.isEmpty();
    }

    private void moveInputSuggestionSelection(int delta) {
        if (!hasInputSuggestions()) {
            return;
        }
        this.selectedInputSuggestionIndex = Math.max(0, Math.min(this.selectedInputSuggestionIndex + delta, this.inputSuggestions.size() - 1));
        DesktopUiSoundHelper.playClick();
        refreshSuggestionPanel();
    }

    private void acceptSelectedInputSuggestion() {
        if (!hasInputSuggestions()) {
            return;
        }
        this.inputField.setText(this.inputSuggestions.get(this.selectedInputSuggestionIndex).value());
        this.inputField.setCaretPosition(this.inputField.getText().length());
        DesktopUiSoundHelper.playClick();
        updateInputSuggestions();
    }

    private void refreshSuggestionPanel() {
        if (this.suggestionPanel == null) {
            return;
        }
        String ghost = "";
        if (hasInputSuggestions()) {
            String current = this.inputField.getText();
            String value = this.inputSuggestions.get(this.selectedInputSuggestionIndex).value();
            if (current != null && !current.isBlank() && value.startsWith(current)) {
                ghost = value.substring(current.length());
            }
        }
        setInputGhostText(ghost);
        boolean visible = hasInputSuggestions();
        this.suggestionPanel.setVisible(visible);
        if (visible) {
            positionSuggestionPanel();
        }
        this.suggestionPanel.revalidate();
        this.suggestionPanel.repaint();
    }

    private void positionSuggestionPanel() {
        if (this.layeredPane == null || this.suggestionPanel == null || !hasInputSuggestions()) {
            return;
        }
        int width = suggestionPopupWidth();
        int visibleRows = Math.min(ConsoleSuggestionPresentation.MAX_VISIBLE_ROWS, this.inputSuggestions.size());
        int height = 6 + (visibleRows * ConsoleSuggestionPresentation.ROW_HEIGHT) + 6;
        Point origin = SwingUtilities.convertPoint(this.inputField.getParent(), this.inputField.getLocation(), this.layeredPane);
        int x = Math.max(8, Math.min(origin.x, this.layeredPane.getWidth() - width - 8));
        int y = Math.max(8, origin.y - height - 4);
        this.suggestionPanel.setBounds(x, y, width, height);
    }

    private int suggestionPopupWidth() {
        FontMetrics metrics = getFontMetrics(resolveFont().deriveFont(12f));
        int valueWidth = 0;
        int detailWidth = 0;
        int visibleRows = Math.min(ConsoleSuggestionPresentation.MAX_VISIBLE_ROWS, this.inputSuggestions.size());
        for (int i = 0; i < visibleRows; i++) {
            ConsoleInputSuggestionService.ConsoleInputSuggestion suggestion = this.inputSuggestions.get(i);
            valueWidth = Math.max(valueWidth, metrics.stringWidth(suggestion.value()));
            detailWidth = Math.max(detailWidth, metrics.stringWidth(suggestion.detail()));
        }
        return Math.max(
                ConsoleSuggestionPresentation.MIN_WIDTH,
                Math.min(
                        ConsoleSuggestionPresentation.MAX_WIDTH,
                        18 + 58 + valueWidth + (detailWidth > 0 ? Math.min(ConsoleSuggestionPresentation.DETAIL_WIDTH, detailWidth) + 10 : 0)
                )
        );
    }

    private void setInputGhostText(String ghostText) {
    }

    private void ensureAppendCaret(JTextField field) {
        SwingUtilities.invokeLater(() -> {
            if (!field.isFocusOwner()) {
                return;
            }
            if (field.getSelectionStart() != field.getSelectionEnd()) {
                return;
            }
            int length = field.getDocument().getLength();
            if (length > 0 && field.getCaretPosition() == 0) {
                field.setCaretPosition(length);
            }
        });
    }

    private Rectangle combinedScreenBounds() {
        Rectangle result = null;
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration configuration = device.getDefaultConfiguration();
            if (configuration == null) {
                continue;
            }
            Rectangle bounds = configuration.getBounds();
            result = result == null ? new Rectangle(bounds) : result.union(bounds);
        }
        return result == null ? GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds() : result;
    }

    private static final class AlphaPanel extends JPanel {
        private final Color fill;

        private AlphaPanel(Color fill, LayoutManager layout) {
            super(layout);
            this.fill = fill;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(this.fill);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static final class FieldChromePanel extends JPanel {
        private FieldChromePanel() {
            super(new BorderLayout());
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(PANEL_OVERLAY);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(new Color(255, 255, 255, 12));
            g2.fillRect(1, 1, Math.max(0, getWidth() - 2), 1);
            g2.setColor(ConsoleTheme.awt(ConsoleTheme.border()));
            g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static final class AlphaTextPane extends JTextPane {
        private final Color fill;

        private AlphaTextPane(DefaultStyledDocument document, Color fill) {
            super(document);
            this.fill = fill;
            setOpaque(false);
            setBackground(new Color(0, 0, 0, 0));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(this.fill);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(new Color(255, 255, 255, 12));
            g2.fillRect(0, 0, getWidth(), 1);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private final class PixelButtonUi extends BasicButtonUI {
        @Override
        public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            Graphics2D g2 = (Graphics2D) graphics.create();
            ButtonModel model = button.getModel();
            Color fill = model.isPressed() ? FIELD_FOCUS_OVERLAY : PANEL_OVERLAY;
            g2.setColor(fill);
            g2.fillRect(0, 0, component.getWidth(), component.getHeight());
            g2.setColor(new Color(255, 255, 255, model.isRollover() ? 36 : 18));
            g2.fillRect(1, 1, Math.max(0, component.getWidth() - 2), 1);
            g2.setColor(ConsoleTheme.awt(ConsoleTheme.border()));
            g2.drawRect(0, 0, component.getWidth() - 1, component.getHeight() - 1);
            FontMetrics metrics = g2.getFontMetrics(button.getFont());
            int textX = Math.max(4, (component.getWidth() - metrics.stringWidth(button.getText())) / 2);
            int textY = (component.getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.setFont(button.getFont());
            g2.setColor(button.getForeground());
            g2.drawString(button.getText(), textX, textY);
            g2.dispose();
        }
    }

    private boolean isNearBottom() {
        if (this.outputScrollPane == null) {
            return true;
        }
        JScrollBar scrollBar = this.outputScrollPane.getVerticalScrollBar();
        int remaining = scrollBar.getMaximum() - (scrollBar.getValue() + scrollBar.getVisibleAmount());
        return remaining < 20;
    }

    private String normalizedFilter() {
        String value = this.searchField.getText();
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private void restoreWindowBounds() {
        try {
            Path statePath = windowStatePath();
            if (!Files.isRegularFile(statePath)) {
                return;
            }
            JsonObject state = readJsonObject(statePath);
            int x = intValue(state, "x", Integer.MIN_VALUE);
            int y = intValue(state, "y", Integer.MIN_VALUE);
            int width = intValue(state, "width", getWidth());
            int height = intValue(state, "height", getHeight());
            if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE) {
                return;
            }
            Rectangle bounds = new Rectangle(x, y, Math.max(900, width), Math.max(620, height));
            Rectangle screen = combinedScreenBounds();
            if (!screen.intersects(bounds)) {
                return;
            }
            setBounds(bounds);
        } catch (Exception ignored) {
        }
    }

    private void saveWindowBounds() {
        try {
            Path statePath = windowStatePath();
            Files.createDirectories(statePath.getParent());
            Rectangle bounds = getBounds();
            JsonObject state = new JsonObject();
            state.addProperty("x", bounds.x);
            state.addProperty("y", bounds.y);
            state.addProperty("width", bounds.width);
            state.addProperty("height", bounds.height);
            Files.writeString(statePath, state.toString());
        } catch (Exception ignored) {
        }
    }

    private Path windowStatePath() {
        return Path.of("koil/sys/cache/external-console-window-" + this.channel.id() + ".json");
    }

    private static final class ConsoleScrollBarUi extends BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
            this.trackColor = new Color(10, 12, 18, 236);
            this.thumbColor = new Color(24, 28, 38, 238);
            this.thumbDarkShadowColor = new Color(18, 22, 30, 240);
            this.thumbHighlightColor = new Color(42, 48, 62, 236);
            this.thumbLightShadowColor = new Color(18, 22, 30, 240);
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return invisibleButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return invisibleButton();
        }

        private JButton invisibleButton() {
            JButton button = new JButton();
            button.setPreferredSize(new Dimension(0, 0));
            button.setMinimumSize(new Dimension(0, 0));
            button.setMaximumSize(new Dimension(0, 0));
            return button;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(this.trackColor);
            g2.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);
            g2.dispose();
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
            if (thumbBounds.isEmpty() || !this.scrollbar.isEnabled()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(this.thumbColor);
            g2.fillRect(thumbBounds.x + 1, thumbBounds.y + 1, Math.max(6, thumbBounds.width - 2), Math.max(12, thumbBounds.height - 2));
            g2.setColor(new Color(52, 58, 74, 236));
            g2.drawRect(thumbBounds.x + 1, thumbBounds.y + 1, Math.max(6, thumbBounds.width - 3), Math.max(12, thumbBounds.height - 3));
            g2.dispose();
        }
    }

    private final class SuggestionPanel extends JComponent {
        private SuggestionPanel() {
            setOpaque(false);
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                    int index = suggestionIndexAt(e.getY());
                    if (index >= 0) {
                        selectedInputSuggestionIndex = index;
                        acceptSelectedInputSuggestion();
                    }
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent e) {
                    repaint();
                }
            });
            addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                @Override
                public void mouseMoved(java.awt.event.MouseEvent e) {
                    repaint();
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            if (!hasInputSuggestions()) {
                return new Dimension(0, 0);
            }
            return new Dimension(suggestionPopupWidth(), 6 + (Math.min(ConsoleSuggestionPresentation.MAX_VISIBLE_ROWS, inputSuggestions.size()) * ConsoleSuggestionPresentation.ROW_HEIGHT) + 6);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (!hasInputSuggestions()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            Font font = resolveFont().deriveFont(12f);
            FontMetrics metrics = g2.getFontMetrics(font);
            Point mouse = getMousePosition();
            g2.setFont(font);
            g2.setColor(new Color(14, 18, 26, 234));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(ConsoleTheme.awt(ConsoleTheme.border()));
            g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            int visibleRows = Math.min(ConsoleSuggestionPresentation.MAX_VISIBLE_ROWS, inputSuggestions.size());
            for (int i = 0; i < visibleRows; i++) {
                int rowY = 4 + (i * ConsoleSuggestionPresentation.ROW_HEIGHT);
                boolean hovered = mouse != null && mouse.x >= 1 && mouse.x <= getWidth() - 1 && mouse.y >= rowY && mouse.y <= rowY + ConsoleSuggestionPresentation.ROW_HEIGHT - 1;
                if (i == selectedInputSuggestionIndex) {
                    g2.setColor(new Color(73, 92, 128, 144));
                    g2.fillRect(1, rowY, getWidth() - 2, ConsoleSuggestionPresentation.ROW_HEIGHT - 1);
                } else if (hovered) {
                    g2.setColor(new Color(73, 92, 128, 96));
                    g2.fillRect(1, rowY, getWidth() - 2, ConsoleSuggestionPresentation.ROW_HEIGHT - 1);
                }
                ConsoleInputSuggestionService.ConsoleInputSuggestion suggestion = inputSuggestions.get(i);
                ConsoleSuggestionPresentation.Presentation presentation = ConsoleSuggestionPresentation.present(suggestion.kind());
                int kindX = ConsoleSuggestionPresentation.PADDING;
                int valueX = 58;
                int detailWidth = Math.min(ConsoleSuggestionPresentation.DETAIL_WIDTH, metrics.stringWidth(suggestion.detail()));
                int detailX = getWidth() - 8 - detailWidth;
                g2.setColor(new Color(presentation.color(), true));
                g2.drawString(presentation.label(), kindX, rowY + 12);
                g2.setColor(ConsoleTheme.awt(ConsoleTheme.primaryText()));
                g2.drawString(fitSwingText(suggestion.value(), Math.max(56, detailX - valueX - 8)), valueX, rowY + 12);
                if (!suggestion.detail().isBlank()) {
                    String detail = fitSwingText(suggestion.detail(), 116);
                    g2.setColor(ConsoleTheme.awt(ConsoleTheme.secondaryText()));
                    g2.drawString(detail, getWidth() - Math.min(ConsoleSuggestionPresentation.DETAIL_WIDTH, metrics.stringWidth(detail)) - 8, rowY + 12);
                }
            }
            g2.dispose();
        }

        private int suggestionIndexAt(int y) {
            int index = (y - 4) / ConsoleSuggestionPresentation.ROW_HEIGHT;
            return index >= 0 && index < Math.min(ConsoleSuggestionPresentation.MAX_VISIBLE_ROWS, inputSuggestions.size()) ? index : -1;
        }
    }

    private String fitSwingText(String text, int maxWidth) {
        FontMetrics metrics = getFontMetrics(resolveFont().deriveFont(12f));
        if (text == null || metrics.stringWidth(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        String ellipsis = "...";
        String candidate = text;
        while (!candidate.isEmpty() && metrics.stringWidth(candidate + ellipsis) > maxWidth) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate + ellipsis;
    }

    private record BackgroundImage(BufferedImage image, boolean tiled) {
    }
}
package wavefront.mc.ui;

import com.redsmods.wavefront.config.Config;
import com.redsmods.wavefront.config.QualityPreset;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * Wavefront's own settings screen: a Quality switch and one row per topic. Each row has its
 * main control and a button that opens the row to show its detailed controls and what they
 * do to realism and performance. Only one row is open at a time, and the others shrink to a
 * single line so everything fits without scrolling. The full option list stays reachable
 * through "Expert tuning".
 */
public class WavefrontScreen extends Screen {
    private static final int ACCENT = 0xFF8FC0E8;
    private static final int PANEL = 0xB0101216;
    private static final int BORDER = 0xFF3A3F48;
    private static final int TEXT = 0xFFECEEF1;
    private static final int MUTED = 0xFFAEB5BF;
    private static final int NOTE = 0xFFC9CFD7;

    private static final int TOP = 12;
    private static final int ROW_FULL = 30;
    private static final int ROW_COMPACT = 20;
    private static final int GAP = 4;
    private static final int CONTROL_H = 16;
    private static final int LINE = CONTROL_H + 2;

    private static final String[] TOPICS = {"walls", "diffraction", "reverb", "voice"};
    private static final int WALLS = 0, DIFFRACTION = 1, REVERB = 2, VOICE = 3;

    private final Screen parent;
    private int expanded = -1;
    private int left, columnWidth;
    private final List<RowBox> rows = new ArrayList<>();
    private final int[][] qualityBounds = new int[QualityPreset.values().length][];

    /** Where one row was laid out, kept so the text can be drawn over it each frame. */
    private record RowBox(int topic, int y, int rowHeight, boolean compact, boolean open,
                          int textWidth, int notesX, int notesY, int notesWidth) {}

    public WavefrontScreen(Screen parent) {
        super(Component.translatable("wavefront.screen.title"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        rows.clear();
        Config cfg = Config.getInstance();
        columnWidth = Math.min(400, this.width - 32);
        left = (this.width - columnWidth) / 2;

        // Quality switch, top right.
        QualityPreset[] presets = QualityPreset.values();
        int quality = Math.max(40, Math.min(64, (columnWidth - 70) / presets.length));
        int qualityX = left + columnWidth - quality * presets.length;
        for (int i = 0; i < presets.length; i++) {
            QualityPreset preset = presets[i];
            String key = "wavefront.screen.quality." + preset.name().toLowerCase(Locale.ROOT);
            int x = qualityX + i * quality;
            qualityBounds[i] = new int[]{x, TOP, quality, CONTROL_H};
            addRenderableWidget(Button.builder(Component.translatable(key), b -> selectPreset(preset))
                    .bounds(x, TOP, quality, CONTROL_H)
                    .tooltip(Tooltip.create(Component.translatable(key + ".tip")))
                    .build());
        }

        // One row per topic.
        int y = TOP + CONTROL_H + 10;
        for (int topic = 0; topic < TOPICS.length; topic++) {
            boolean open = expanded == topic;
            boolean compact = expanded != -1 && !open;
            int head = compact ? ROW_COMPACT : ROW_FULL;
            int controlY = y + (head - CONTROL_H) / 2;
            int expandX = left + columnWidth - 6 - CONTROL_H;

            final int index = topic;
            addRenderableWidget(Button.builder(Component.literal(open ? "-" : "+"), b -> {
                        expanded = expanded == index ? -1 : index;
                        rebuildWidgets();
                    })
                    .bounds(expandX, controlY, CONTROL_H, CONTROL_H)
                    .tooltip(Tooltip.create(Component.translatable(open ? "wavefront.screen.less" : "wavefront.screen.more")))
                    .build());
            int controlsLeft = addMainControl(cfg, topic, expandX - 4, controlY);

            int rowHeight = head;
            int notesX = 0, notesY = 0, notesWidth = 0;
            if (open) {
                int half = columnWidth / 2;
                int bodyY = y + head + 2;
                int controlsBottom = addDetailControls(cfg, topic, left + 8, bodyY, half - 16);
                notesX = left + half + 4;
                notesY = bodyY;
                notesWidth = columnWidth - half - 12;
                int notesBottom = bodyY + this.font.wordWrapHeight(note(topic, true), notesWidth) + 4
                        + this.font.wordWrapHeight(note(topic, false), notesWidth);
                rowHeight = Math.max(controlsBottom, notesBottom) + 6 - y;
            }
            rows.add(new RowBox(topic, y, rowHeight, compact, open, controlsLeft - left - 16, notesX, notesY, notesWidth));
            y += rowHeight + GAP;
        }

        // Footer.
        int footerY = this.height - 26;
        int footerWidth = Math.min(110, (columnWidth - 8) / 2);
        addRenderableWidget(Button.builder(Component.translatable("wavefront.screen.expert"),
                        b -> this.minecraft.gui.setScreen(Config.configScreen(this)))
                .bounds(left, footerY, footerWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("wavefront.screen.expert.tip")))
                .build());
        addRenderableWidget(Button.builder(Component.translatable("wavefront.screen.done"), b -> onClose())
                .bounds(left + columnWidth - footerWidth, footerY, footerWidth, 20)
                .build());
    }

    /** Adds the control shown on a row's right-hand side. Returns the x where the controls start. */
    private int addMainControl(Config cfg, int topic, int rightX, int y) {
        switch (topic) {
            case WALLS -> {
                int w = Math.min(110, columnWidth / 3);
                addRenderableWidget(new ValueSlider(rightX - w, y, w, "wavefront.screen.strength",
                        0, 2, 0.05, cfg.wallStrength, v -> cfg.wallStrength = (float) v, WavefrontScreen::times));
                return rightX - w;
            }
            case DIFFRACTION -> {
                return addToggle(rightX, y, () -> cfg.diffraction, v -> cfg.diffraction = v);
            }
            case REVERB -> {
                return addToggle(rightX, y, () -> cfg.reverb, v -> cfg.reverb = v);
            }
            default -> {
                return addToggle(rightX, y, () -> cfg.voicechatMuffle, v -> cfg.voicechatMuffle = v);
            }
        }
    }

    /** Adds the controls inside an open row. Returns the y just below the last one. */
    private int addDetailControls(Config cfg, int topic, int x, int y, int w) {
        switch (topic) {
            case WALLS -> {
                addLabelToggle(x, y, w, "wavefront.screen.physics", () -> cfg.wavePhysics, v -> cfg.wavePhysics = v);
                return y + LINE;
            }
            case DIFFRACTION -> {
                addRenderableWidget(new ValueSlider(x, y, w, "wavefront.screen.loss",
                        0, 2, 0.05, cfg.diffractionStrength, v -> cfg.diffractionStrength = (float) v, WavefrontScreen::times));
                // Routes and range belong to the Quality preset; changing them switches Quality to Custom.
                addRenderableWidget(new ValueSlider(x, y + LINE, w, "wavefront.screen.routes",
                        1, 4, 1, cfg.routes(), v -> { cfg.switchToCustom(); cfg.diffractionRoutes = (int) v; }, WavefrontScreen::whole));
                addRenderableWidget(new ValueSlider(x, y + 2 * LINE, w, "wavefront.screen.range",
                        8, 64, 4, cfg.bendRange(), v -> { cfg.switchToCustom(); cfg.diffractionRange = (int) v; }, WavefrontScreen::whole));
                return y + 3 * LINE;
            }
            case REVERB -> {
                addRenderableWidget(new ValueSlider(x, y, w, "wavefront.screen.rays",
                        50, 1000, 50, cfg.rays(), v -> { cfg.switchToCustom(); cfg.raysCast = (int) v; }, WavefrontScreen::whole));
                addRenderableWidget(new ValueSlider(x, y + LINE, w, "wavefront.screen.bounces",
                        1, 8, 1, cfg.bounces(), v -> { cfg.switchToCustom(); cfg.raysBounced = (int) v; }, WavefrontScreen::whole));
                return y + 2 * LINE;
            }
            default -> {
                addLabelToggle(x, y, w, "wavefront.screen.voice_volume", () -> cfg.voicechatMuffleVolume, v -> cfg.voicechatMuffleVolume = v);
                addLabelToggle(x, y + LINE, w, "wavefront.screen.voice_reverb", () -> cfg.voicechatReverb, v -> cfg.voicechatReverb = v);
                addLabelToggle(x, y + 2 * LINE, w, "wavefront.screen.voice_self", () -> cfg.voicechatReverbSelf, v -> cfg.voicechatReverbSelf = v);
                return y + 3 * LINE;
            }
        }
    }

    /** A bare ON/OFF button, right-aligned at {@code rightX}. Returns its left edge. */
    private int addToggle(int rightX, int y, BooleanSupplier get, Consumer<Boolean> set) {
        int w = 44;
        addRenderableWidget(Button.builder(onOff(get.getAsBoolean()), b -> {
            set.accept(!get.getAsBoolean());
            b.setMessage(onOff(get.getAsBoolean()));
        }).bounds(rightX - w, y, w, CONTROL_H).build());
        return rightX - w;
    }

    /** A wide button that reads "Label: ON". */
    private void addLabelToggle(int x, int y, int w, String key, BooleanSupplier get, Consumer<Boolean> set) {
        addRenderableWidget(Button.builder(Component.translatable(key, onOff(get.getAsBoolean())), b -> {
            set.accept(!get.getAsBoolean());
            b.setMessage(Component.translatable(key, onOff(get.getAsBoolean())));
        }).bounds(x, y, w, CONTROL_H).build());
    }

    private void selectPreset(QualityPreset preset) {
        Config cfg = Config.getInstance();
        if (preset == QualityPreset.CUSTOM) cfg.switchToCustom();
        else cfg.qualityPreset = preset;
        rebuildWidgets(); // sliders must show the new preset's values
    }

    // ------------------------------------------------------------------ text helpers

    private static MutableComponent onOff(boolean on) {
        return Component.translatable(on ? "wavefront.screen.on" : "wavefront.screen.off")
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private static MutableComponent note(int topic, boolean realism) {
        String kind = realism ? "realism" : "performance";
        return Component.translatable("wavefront.screen." + kind)
                .withStyle(realism ? ChatFormatting.AQUA : ChatFormatting.GOLD)
                .append(Component.translatable("wavefront.screen.topic." + TOPICS[topic] + "." + kind)
                        .withStyle(ChatFormatting.GRAY));
    }

    private static boolean topicOn(Config cfg, int topic) {
        return switch (topic) {
            case WALLS -> cfg.wavePhysics;
            case DIFFRACTION -> cfg.diffraction;
            case REVERB -> cfg.reverb;
            default -> cfg.voicechatMuffle;
        };
    }

    private static String times(double v) {
        return String.format(Locale.ROOT, "%.2fx", v);
    }

    private static String whole(double v) {
        return Integer.toString((int) Math.round(v));
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Config cfg = Config.getInstance();

        // Row panels go down first so the buttons and sliders draw on top of them.
        for (RowBox row : rows) {
            g.fill(left, row.y(), left + columnWidth, row.y() + row.rowHeight(), PANEL);
            g.outline(left, row.y(), columnWidth, row.rowHeight(), row.open() ? ACCENT : BORDER);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.text(this.font, this.title, left, TOP + (CONTROL_H - this.font.lineHeight) / 2 + 1, TEXT, true);
        int[] selected = qualityBounds[cfg.qualityPreset.ordinal()];
        if (selected != null) {
            g.outline(selected[0] - 1, selected[1] - 1, selected[2] + 2, selected[3] + 2, ACCENT);
        }

        for (RowBox row : rows) {
            String base = "wavefront.screen.topic." + TOPICS[row.topic()];
            int textX = left + 8;
            if (row.compact()) {
                g.text(this.font, Component.translatable(base + ".title"), textX,
                        row.y() + (ROW_COMPACT - this.font.lineHeight) / 2 + 1, TEXT, true);
            } else {
                g.text(this.font, Component.translatable(base + ".title"), textX, row.y() + 5, TEXT, true);
                String summary = Component.translatable(base + (topicOn(cfg, row.topic()) ? ".summary" : ".summary_off")).getString();
                g.text(this.font, this.font.plainSubstrByWidth(summary, Math.max(20, row.textWidth())),
                        textX, row.y() + 17, MUTED, false);
            }
            if (row.open()) {
                MutableComponent realism = note(row.topic(), true);
                g.textWithWordWrap(this.font, realism, row.notesX(), row.notesY(), row.notesWidth(), NOTE);
                int next = row.notesY() + this.font.wordWrapHeight(realism, row.notesWidth()) + 4;
                g.textWithWordWrap(this.font, note(row.topic(), false), row.notesX(), next, row.notesWidth(), NOTE);
            }
        }
    }

    @Override
    public void onClose() {
        Config.CONFIG.save();
        this.minecraft.gui.setScreen(parent);
    }

    // ------------------------------------------------------------------ slider

    /** A slider over a real range with a step, labelled "Name: value". */
    private static final class ValueSlider extends AbstractSliderButton {
        private final String key;
        private final double min, max, step;
        private final DoubleConsumer setter;
        private final DoubleFunction<String> format;

        ValueSlider(int x, int y, int w, String key, double min, double max, double step, double current,
                    DoubleConsumer setter, DoubleFunction<String> format) {
            super(x, y, w, CONTROL_H, Component.empty(), Math.max(0, Math.min(1, (current - min) / (max - min))));
            this.key = key;
            this.min = min;
            this.max = max;
            this.step = step;
            this.setter = setter;
            this.format = format;
            updateMessage();
        }

        private double real() {
            double raw = min + this.value * (max - min);
            return Math.max(min, Math.min(max, Math.round(raw / step) * step));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(key, format.apply(real())));
        }

        @Override
        protected void applyValue() {
            setter.accept(real());
        }
    }
}

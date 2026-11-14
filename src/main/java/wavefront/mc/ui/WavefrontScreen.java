package wavefront.mc.ui;

import com.redsmods.wavefront.config.Config;
import com.redsmods.wavefront.config.QualityPreset;
import dev.isxander.yacl3.config.v2.api.SerialEntry;
import dev.isxander.yacl3.config.v2.api.autogen.AutoGen;
import dev.isxander.yacl3.config.v2.api.autogen.DoubleSlider;
import dev.isxander.yacl3.config.v2.api.autogen.FloatSlider;
import dev.isxander.yacl3.config.v2.api.autogen.IntSlider;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Wavefront's settings screen. A Quality switch at the top, then one row per topic. Each row
 * opens with "+" to show its settings, and larger groups inside a row have their own "+".
 * Every setting is reachable here; hover one to read what it does to realism and performance.
 *
 * Performance, Balanced and Realism are fixed presets. Changing a setting while on one asks
 * first, then switches Quality to Custom, starting from that preset's values plus the change.
 */
public class WavefrontScreen extends Screen {
    private static final int ACCENT = 0xFF8FC0E8;
    private static final int PANEL = 0xB0101216;
    private static final int BORDER = 0xFF3A3F48;
    private static final int TEXT = 0xFFECEEF1;
    private static final int MUTED = 0xFFAEB5BF;
    private static final int DIM = 0xC0000000;

    private static final int TOP = 10;
    private static final int HEAD = 30;
    private static final int GAP = 4;
    private static final int CONTROL_H = 16;
    private static final int LINE = CONTROL_H + 2;
    private static final String CFG = "yacl3.config.wavefront:config.";
    private static final String UI = "wavefront.screen.";

    private record Section(String id, String titleKey, List<String> fields) {}
    private record Topic(String id, String mainField, List<String> fields, List<Section> sections) {}
    private record Panel(int x, int y, int w, int h, boolean open) {}
    private record Label(Component text, int x, int y, int color, boolean shadow) {}
    /** A change waiting for the player to agree to switch to Custom. {@code value} may be null (just switch). */
    private record Pending(Setting setting, Object value) {}

    private final Screen parent;
    private final List<Topic> topics;
    private final Map<String, Setting> settings = new LinkedHashMap<>();
    private final Set<String> open = new HashSet<>();
    private final List<Panel> panels = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final int[][] qualityBounds = new int[QualityPreset.values().length][];

    private int left, columnWidth, listTop, listBottom, contentHeight;
    private double scroll;
    private boolean measuring;
    private Pending pending;

    public WavefrontScreen(Screen parent) {
        super(Component.translatable(UI + "title"));
        this.parent = parent;
        for (Field f : Config.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getAnnotation(SerialEntry.class) == null) continue;
            if (f.getName().equals("qualityPreset")) continue; // shown as the switch at the top
            settings.put(f.getName(), new Setting(f));
        }
        this.topics = buildTopics();
    }

    // ------------------------------------------------------------------ what goes where

    private List<Topic> buildTopics() {
        Set<String> placed = new HashSet<>();
        List<Topic> out = new ArrayList<>();

        out.add(topic(placed, "walls", "wallStrength", List.of("wavePhysics", "autoOpenness"),
                List.of(new Section("walls.classic", UI + "section.classic", List.of("permeation", "useExplosionResistance",
                        "permeationAbsorption", "maxBlocksPermeated", "permeationStepSize", "attenuationType",
                        "shortcutDirectionality", "barrierAsAir")))));
        out.add(topic(placed, "diffraction", "diffraction",
                List.of("diffractionStrength", "openingSize", "diffractionRoutes", "diffractionRange", "diffractionSearchBudget", "searchTimeMs"), List.of()));

        out.add(topic(placed, "distance", "soundDelay",
                List.of("airAbsorptionStrength", "doppler", "dopplerStrength", "echoes", "echoContinuous", "echoRough", "echoStrength", "echoCount", "echoRange"), List.of()));

        out.add(topic(placed, "water", "water", List.of("waterSurfaceStrength", "submergedHearing", "submergedDirection"), List.of()));

        out.add(topic(placed, "bass", "waveBass", List.of("fullBass", "waveStrength", "waveDetail", "waveRange"), List.of()));

        out.add(topic(placed, "structure", "structureBorne", List.of("structureStrength", "structureRange"), List.of()));

        // Reverb: the main values, then one group per set of fine-tuning values.
        List<String> reverbMain = List.of("raysCast", "raysBounced", "maxRayLength", "bounceAbsorptionMultiplier");
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Setting s : settings.values()) {
            AutoGen auto = s.field.getAnnotation(AutoGen.class);
            if (auto == null || !"reverb_tuning".equals(auto.category())) continue;
            if (reverbMain.contains(s.name) || s.name.equals("reverb")) continue;
            groups.computeIfAbsent(auto.group(), k -> new ArrayList<>()).add(s.name);
        }
        List<Section> reverbSections = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            reverbSections.add(new Section("reverb." + e.getKey(), CFG + "category.reverb_tuning.group." + e.getKey(), e.getValue()));
        }
        out.add(topic(placed, "reverb", "reverb", reverbMain, reverbSections));

        out.add(topic(placed, "voice", "voicechatMuffle",
                List.of("voicechatMuffleVolume", "voicechatReverb", "voicechatReverbSelf"), List.of()));

        // General: whatever belongs to no single topic, plus anything not placed above.
        List<String> general = new ArrayList<>(List.of("tickRate", "procRange", "maxSounds", "volumeMultiplier", "disableFXOnUI"));
        List<String> tools = List.of("debug", "showSoundPaths", "showBassField", "soundBlacklist", "soundTickBlacklist");
        placed.addAll(general);
        placed.addAll(tools);
        for (String name : settings.keySet()) {
            if (!placed.contains(name)) general.add(name);
        }
        out.add(new Topic("general", null, general, List.of(new Section("general.tools", UI + "section.tools", tools))));
        return out;
    }

    private static Topic topic(Set<String> placed, String id, String main, List<String> fields, List<Section> sections) {
        placed.add(main);
        placed.addAll(fields);
        for (Section s : sections) placed.addAll(s.fields());
        return new Topic(id, main, fields, sections);
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        panels.clear();
        labels.clear();
        columnWidth = Math.min(420, this.width - 24);
        left = (this.width - columnWidth) / 2;
        listTop = TOP + CONTROL_H + 16;
        listBottom = this.height - 30;

        if (pending != null) {
            int w = 110, y = this.height / 2 + 24;
            addRenderableWidget(Button.builder(Component.translatable(UI + "prompt.confirm"), b -> confirmPending())
                    .bounds(this.width / 2 - w - 4, y, w, 20).build());
            addRenderableWidget(Button.builder(Component.translatable(UI + "prompt.cancel"), b -> cancelPending())
                    .bounds(this.width / 2 + 4, y, w, 20).build());
            return;
        }

        // Quality switch, top right.
        QualityPreset[] presets = QualityPreset.values();
        int quality = Math.max(40, Math.min(66, (columnWidth - 70) / presets.length));
        int qualityX = left + columnWidth - quality * presets.length;
        for (int i = 0; i < presets.length; i++) {
            QualityPreset preset = presets[i];
            String key = UI + "quality." + preset.name().toLowerCase(Locale.ROOT);
            int x = qualityX + i * quality;
            qualityBounds[i] = new int[]{x, TOP, quality, CONTROL_H};
            addRenderableWidget(Button.builder(Component.translatable(key), b -> selectPreset(preset))
                    .bounds(x, TOP, quality, CONTROL_H)
                    .tooltip(Tooltip.create(Component.translatable(key + ".tip")))
                    .build());
        }

        // Measure first so the scroll position can be kept inside the content, then place for real.
        measuring = true;
        contentHeight = layoutList(listTop) - listTop;
        measuring = false;
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        layoutList(listTop - (int) scroll);

        int doneWidth = Math.min(120, columnWidth);
        addRenderableWidget(Button.builder(Component.translatable(UI + "done"), b -> onClose())
                .bounds(left + columnWidth - doneWidth, this.height - 25, doneWidth, 20).build());
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (listBottom - listTop));
    }

    /** Lays out every topic row starting at {@code y}. Returns the y just below the last row. */
    private int layoutList(int y) {
        for (Topic topic : topics) {
            y = layoutTopic(topic, y) + GAP;
        }
        return y - GAP;
    }

    private int layoutTopic(Topic topic, int y) {
        boolean isOpen = open.contains(topic.id());
        int controlY = y + (HEAD - CONTROL_H) / 2;
        int expandX = left + columnWidth - 6 - CONTROL_H;
        place(Button.builder(Component.literal(isOpen ? "-" : "+"), b -> toggleOpen(topic.id()))
                .bounds(expandX, controlY, CONTROL_H, CONTROL_H)
                .tooltip(Tooltip.create(Component.translatable(UI + (isOpen ? "less" : "more"))))
                .build());

        int controlsLeft = expandX - 4;
        Setting main = topic.mainField() == null ? null : settings.get(topic.mainField());
        if (main != null) {
            int w = main.kind == Kind.BOOL ? 44 : Math.min(120, columnWidth / 3);
            controlsLeft -= w;
            place(main.kind == Kind.BOOL ? bareToggle(main, controlsLeft, controlY, w) : control(main, controlsLeft, controlY, w));
        }

        String base = UI + "topic." + topic.id();
        boolean on = main == null || main.kind != Kind.BOOL || Boolean.TRUE.equals(main.get());
        label(Component.translatable(base + ".title"), left + 8, y + 5, TEXT, true);
        String summary = Component.translatable(base + (on ? ".summary" : ".summary_off")).getString();
        label(Component.literal(this.font.plainSubstrByWidth(summary, Math.max(20, controlsLeft - left - 16))),
                left + 8, y + 17, MUTED, false);

        int bottom = y + HEAD;
        if (isOpen) {
            bottom = layoutFields(topic.fields(), left + 8, bottom + 2, columnWidth - 16);
            for (Section section : topic.sections()) {
                boolean sectionOpen = open.contains(section.id());
                place(Button.builder(Component.literal(sectionOpen ? "-" : "+"), b -> toggleOpen(section.id()))
                        .bounds(left + 8, bottom + 2, 14, 14).build());
                label(Component.translatable(section.titleKey()), left + 28, bottom + 5, sectionOpen ? TEXT : MUTED, false);
                bottom += LINE;
                if (sectionOpen) bottom = layoutFields(section.fields(), left + 18, bottom, columnWidth - 26);
            }
            bottom += 4;
        }
        if (!measuring) panels.add(new Panel(left, y, columnWidth, bottom - y, isOpen));
        return bottom;
    }

    /** Places the controls for a list of settings, two per line, text boxes on a line of their own. */
    private int layoutFields(List<String> names, int x, int y, int w) {
        int half = (w - 4) / 2;
        int column = 0;
        for (String name : names) {
            Setting s = settings.get(name);
            if (s == null) continue;
            if (s.kind == Kind.NUMBER || s.kind == Kind.LIST) {
                if (column == 1) { y += LINE; column = 0; }
                placeTextSetting(s, x, y, w);
                y += LINE;
            } else {
                place(control(s, x + column * (half + 4), y, half));
                if (column == 1) { y += LINE; column = 0; } else column = 1;
            }
        }
        if (column == 1) y += LINE;
        return y;
    }

    /** Adds a widget if it lies fully inside the scrolling list (and this is not a measuring pass). */
    private void place(AbstractWidget widget) {
        if (measuring) return;
        if (widget.getY() >= listTop && widget.getY() + widget.getHeight() <= listBottom) addRenderableWidget(widget);
    }

    private void label(Component text, int x, int y, int color, boolean shadow) {
        if (measuring) return;
        if (y >= listTop - 2 && y + this.font.lineHeight <= listBottom + 2) labels.add(new Label(text, x, y, color, shadow));
    }

    // ------------------------------------------------------------------ controls

    private boolean locked(Setting s) {
        return Config.stored().qualityPreset != QualityPreset.CUSTOM && !Config.isPersonal(s.name);
    }

    private Tooltip tip(Setting s) {
        return Tooltip.create(Component.translatable(CFG + s.name + ".description"));
    }

    private AbstractWidget bareToggle(Setting s, int x, int y, int w) {
        boolean value = Boolean.TRUE.equals(s.get());
        return Button.builder(onOff(value), b -> change(s, !value)).bounds(x, y, w, CONTROL_H).tooltip(tip(s)).build();
    }

    private AbstractWidget control(Setting s, int x, int y, int w) {
        Object value = s.get();
        switch (s.kind) {
            case BOOL -> {
                boolean on = Boolean.TRUE.equals(value);
                return Button.builder(named(s, onOff(on)), b -> change(s, !on)).bounds(x, y, w, CONTROL_H).tooltip(tip(s)).build();
            }
            case ENUM -> {
                Enum<?> current = (Enum<?>) value;
                Object[] all = s.field.getType().getEnumConstants();
                Object next = all[(current.ordinal() + 1) % all.length];
                Component shown = Component.translatable("yacl3.config.enum." + s.field.getType().getSimpleName() + "."
                        + current.name().toLowerCase(Locale.ROOT));
                return Button.builder(named(s, shown), b -> change(s, next)).bounds(x, y, w, CONTROL_H).tooltip(tip(s)).build();
            }
            default -> {
                ValueSlider slider = new ValueSlider(s, x, y, w, ((Number) value).doubleValue());
                slider.setTooltip(tip(s));
                return slider;
            }
        }
    }

    /** A number or list that is typed in: a name on the left and a text box on the right. */
    private void placeTextSetting(Setting s, int x, int y, int w) {
        int boxWidth = Math.min(150, w / 2);
        String shown = s.text();
        if (locked(s)) {
            // On a preset the value is shown on a button; pressing it offers to switch to Custom.
            place(Button.builder(named(s, Component.literal(shown)), b -> { pending = new Pending(s, null); rebuildWidgets(); })
                    .bounds(x, y, w, CONTROL_H).tooltip(tip(s)).build());
            return;
        }
        String name = this.font.plainSubstrByWidth(Component.translatable(CFG + s.name).getString(), w - boxWidth - 8);
        label(Component.literal(name), x + 2, y + 4, TEXT, false);
        EditBox box = new EditBox(this.font, x + w - boxWidth, y, boxWidth, CONTROL_H, Component.translatable(CFG + s.name));
        box.setMaxLength(400);
        box.setValue(shown);
        box.setTooltip(tip(s));
        box.setResponder(typed -> {
            Object parsed = s.parse(typed);
            box.setTextColor(parsed == null ? 0xFFFF7070 : 0xFFE0E0E0);
            if (parsed != null) {
                s.set(parsed);
                Config.refreshEffective();
            }
        });
        place(box);
    }

    /** Applies a change, or asks first when a preset is active. */
    private void change(Setting s, Object value) {
        if (locked(s)) {
            pending = new Pending(s, value);
        } else {
            s.set(value);
            Config.refreshEffective();
        }
        rebuildWidgets();
    }

    private void confirmPending() {
        Pending p = pending;
        pending = null;
        Config.switchToCustom();
        if (p != null && p.value() != null) p.setting().set(p.value());
        Config.refreshEffective();
        rebuildWidgets();
    }

    private void cancelPending() {
        pending = null;
        rebuildWidgets();
    }

    private void selectPreset(QualityPreset preset) {
        Config.stored().qualityPreset = preset;
        Config.refreshEffective();
        rebuildWidgets();
    }

    private void toggleOpen(String id) {
        if (!open.remove(id)) open.add(id);
        rebuildWidgets();
    }

    // ------------------------------------------------------------------ text helpers

    private static MutableComponent onOff(boolean on) {
        return Component.translatable(UI + (on ? "on" : "off")).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private static MutableComponent named(Setting s, Component value) {
        return Component.translatable(CFG + s.name).append(": ").append(value);
    }

    private static String number(double v, boolean whole) {
        if (whole) return Long.toString(Math.round(v));
        String text = String.format(Locale.ROOT, "%.4f", v);
        text = text.replaceAll("0+$", "");
        return text.endsWith(".") ? text + "0" : text;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (pending == null && maxScroll() > 0) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - scrollY * 24));
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        if (pending != null) {
            cancelPending();
            return;
        }
        Config.CONFIG.save();
        this.minecraft.gui.setScreen(parent);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (pending != null) {
            g.fill(0, 0, this.width, this.height, DIM);
            int w = Math.min(300, this.width - 40), x = (this.width - w) / 2, y = this.height / 2 - 50;
            g.fill(x - 10, y - 10, x + w + 10, this.height / 2 + 54, 0xF0101216);
            g.outline(x - 10, y - 10, w + 20, this.height / 2 + 64 - y, ACCENT);
            g.text(this.font, Component.translatable(UI + "prompt.title"), x, y, TEXT, true);
            String preset = Component.translatable(UI + "quality." + Config.stored().qualityPreset.name().toLowerCase(Locale.ROOT)).getString();
            g.textWithWordWrap(this.font, Component.translatable(UI + "prompt.body", preset,
                    Component.translatable(CFG + pending.setting().name).getString()), x, y + 16, w, MUTED);
            super.extractRenderState(g, mouseX, mouseY, partialTick);
            return;
        }

        // Row panels go down first so the buttons and sliders draw on top of them.
        g.enableScissor(left - 2, listTop, left + columnWidth + 2, listBottom);
        for (Panel p : panels) {
            g.fill(p.x(), p.y(), p.x() + p.w(), p.y() + p.h(), PANEL);
            g.outline(p.x(), p.y(), p.w(), p.h(), p.open() ? ACCENT : BORDER);
        }
        g.disableScissor();

        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.enableScissor(left - 2, listTop, left + columnWidth + 2, listBottom);
        for (Label l : labels) g.text(this.font, l.text(), l.x(), l.y(), l.color(), l.shadow());
        g.disableScissor();

        // Header: title, Quality highlight and a line saying what the current Quality means.
        QualityPreset preset = Config.stored().qualityPreset;
        g.text(this.font, this.title, left, TOP + 4, TEXT, true);
        int[] selected = qualityBounds[preset.ordinal()];
        if (selected != null) g.outline(selected[0] - 1, selected[1] - 1, selected[2] + 2, selected[3] + 2, ACCENT);
        String hint = Component.translatable(UI + (preset == QualityPreset.CUSTOM ? "hint.custom" : "hint.preset"),
                Component.translatable(UI + "quality." + preset.name().toLowerCase(Locale.ROOT)).getString()).getString();
        g.text(this.font, this.font.plainSubstrByWidth(hint, columnWidth), left, TOP + CONTROL_H + 4, MUTED, false);

        // Scroll bar.
        int max = maxScroll();
        if (max > 0) {
            int track = listBottom - listTop;
            int bar = Math.max(12, track * track / (track + max));
            int barY = listTop + (int) ((track - bar) * (scroll / max));
            g.fill(left + columnWidth + 4, barY, left + columnWidth + 6, barY + bar, MUTED);
        }
    }

    // ------------------------------------------------------------------ one setting

    private enum Kind { BOOL, ENUM, SLIDER, NUMBER, LIST }

    /** One option from the settings file, read and written by name. */
    private static final class Setting {
        final Field field;
        final String name;
        final Kind kind;
        double min, max, step;

        Setting(Field field) {
            this.field = field;
            this.name = field.getName();
            Class<?> type = field.getType();
            IntSlider ints = field.getAnnotation(IntSlider.class);
            FloatSlider floats = field.getAnnotation(FloatSlider.class);
            DoubleSlider doubles = field.getAnnotation(DoubleSlider.class);
            if (type == boolean.class) {
                kind = Kind.BOOL;
            } else if (type.isEnum()) {
                kind = Kind.ENUM;
            } else if (List.class.isAssignableFrom(type)) {
                kind = Kind.LIST;
            } else if (ints != null) {
                kind = Kind.SLIDER; min = ints.min(); max = ints.max(); step = ints.step();
            } else if (floats != null) {
                kind = Kind.SLIDER; min = floats.min(); max = floats.max(); step = floats.step();
            } else if (doubles != null) {
                kind = Kind.SLIDER; min = doubles.min(); max = doubles.max(); step = doubles.step();
            } else {
                kind = Kind.NUMBER;
            }
        }

        boolean whole() {
            return field.getType() == int.class;
        }

        /** The value in force now (the preset's value while a preset is active). */
        Object get() {
            try {
                return field.get(Config.getInstance());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Writes to the saved settings. Numbers are converted to the option's own type. */
        void set(Object value) {
            try {
                Class<?> type = field.getType();
                if (value instanceof Number n) {
                    if (type == int.class) value = (int) Math.round(n.doubleValue());
                    else if (type == float.class) value = n.floatValue();
                    else if (type == double.class) value = n.doubleValue();
                }
                field.set(Config.stored(), value);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        String text() {
            Object value = get();
            if (value instanceof List<?> list) {
                StringBuilder sb = new StringBuilder();
                for (Object o : list) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(o);
                }
                return sb.toString();
            }
            return number(((Number) value).doubleValue(), whole());
        }

        /** Turns typed text into a value, or null if it does not make sense for this option. */
        Object parse(String typed) {
            if (kind == Kind.LIST) {
                List<String> out = new ArrayList<>();
                for (String part : typed.split(",")) {
                    if (!part.trim().isEmpty()) out.add(part.trim());
                }
                return out;
            }
            try {
                double v = Double.parseDouble(typed.trim());
                if (Double.isNaN(v) || Double.isInfinite(v)) return null;
                return v;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ slider

    /** A slider over the option's range. On a preset it waits until released, then asks to switch. */
    private final class ValueSlider extends AbstractSliderButton {
        private final Setting setting;
        private boolean waiting;

        ValueSlider(Setting setting, int x, int y, int w, double current) {
            super(x, y, w, CONTROL_H, Component.empty(),
                    Math.max(0, Math.min(1, (current - setting.min) / (setting.max - setting.min))));
            this.setting = setting;
            updateMessage();
        }

        private double real() {
            if (this.value <= 0.0005) return setting.min;
            if (this.value >= 0.9995) return setting.max;
            double raw = setting.min + this.value * (setting.max - setting.min);
            double stepped = setting.step > 0 ? Math.round(raw / setting.step) * setting.step : raw;
            return Math.max(setting.min, Math.min(setting.max, stepped));
        }

        @Override
        protected void updateMessage() {
            if (setting == null) return; // called by the parent constructor before the field is set
            setMessage(named(setting, Component.literal(number(real(), setting.whole() || setting.step >= 1))));
        }

        @Override
        protected void applyValue() {
            if (locked(setting)) {
                waiting = true;
            } else {
                setting.set(real());
                Config.refreshEffective();
            }
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            askIfWaiting();
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            boolean handled = super.keyPressed(event);
            askIfWaiting();
            return handled;
        }

        private void askIfWaiting() {
            if (!waiting) return;
            waiting = false;
            pending = new Pending(setting, real());
            rebuildWidgets();
        }
    }
}

package com.redsmods.wavefront.config;

import com.redsmods.wavefront.ReverbHelpers.LegacyReverb;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.controller.ControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.config.v2.api.ConfigClassHandler;
import dev.isxander.yacl3.config.v2.api.ConfigField;
import dev.isxander.yacl3.config.v2.api.SerialEntry;
import dev.isxander.yacl3.config.v2.api.autogen.*;
import dev.isxander.yacl3.config.v2.api.autogen.Boolean;
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder;
import dev.isxander.yacl3.platform.YACLPlatform;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Config {
    public static final ConfigClassHandler<Config> CONFIG = ConfigClassHandler.createBuilder(Config.class)
            .id(YACLPlatform.rl("wavefront", "config"))
            .serializer(config -> GsonConfigSerializerBuilder.create(config)
                    .setPath(YACLPlatform.getConfigDir().resolve("Wavefront.json5"))
                    .setJson5(true)
                    .build())
            .build();


    public static Screen configScreen(@Nullable Screen parent) {
        return CONFIG.generateGui().generateScreen(parent);
    }

    // ------------------------------------------------------------------ presets
    //
    // The saved settings hold the player's Custom values, the chosen Quality and a few personal
    // options. While Quality is Performance, Balanced or Realism, the rest of the mod does not
    // read the saved values: it reads a stand-in built from the defaults (which are the most
    // realistic values) plus that preset's changes. getInstance() returns whichever applies.

    /** Options that are the player's own and never change with the Quality preset. */
    private static final java.util.Set<String> PERSONAL = java.util.Set.of("qualityPreset", "debug", "reverbTuning",
            "soundBlacklist", "soundTickBlacklist", "volumeMultiplier", "disableFXOnUI", "voicechatReverbSelf");

    private static volatile Config effective;

    /** The settings the mod should act on right now. */
    public static Config getInstance() {
        Config e = effective;
        return e != null ? e : refreshEffective();
    }

    /** The saved settings: Custom values, chosen Quality and personal options. */
    public static Config stored() {
        return CONFIG.instance();
    }

    public static boolean isPersonal(String fieldName) {
        return PERSONAL.contains(fieldName);
    }

    /** Rebuilds what getInstance() returns. Call after loading or changing the saved settings. */
    public static synchronized Config refreshEffective() {
        Config saved = CONFIG.instance();
        Config e = saved;
        if (saved.qualityPreset != QualityPreset.CUSTOM) {
            e = new Config(); // every default is the most realistic value
            applyPreset(e, saved.qualityPreset);
            copyFields(saved, e, true);
            e.qualityPreset = saved.qualityPreset;
        }
        effective = e;
        return e;
    }

    private static void applyPreset(Config c, QualityPreset preset) {
        switch (preset) {
            case PERFORMANCE -> {
                c.raysCast = 100;
                c.raysBounced = 2;
                c.maxRayLength = 3;
                c.tickRate = 10;
                c.diffractionRoutes = 1;
                c.diffractionRange = 24;
                c.diffractionSearchBudget = 3000;
                c.echoCount = 1;
                c.echoRange = 48;
            }
            case BALANCED -> {
                c.raysCast = 200;
                c.raysBounced = 3;
                c.maxRayLength = 4;
                c.tickRate = 10;
                c.diffractionRoutes = 3;
                c.diffractionRange = 32;
                c.diffractionSearchBudget = 6000;
            }
            case OVERKILL -> {
                // Every accuracy setting at its limit, whether or not the difference can be heard.
                // Strengths (walls, diffraction loss, air, Doppler, echoes) stay at 1.0 because that
                // is the real-world value, and bounces stay at 3 because the reverb was tuned there:
                // raising either would make the sound different, not more exact.
                c.raysCast = 2000;
                c.maxRayLength = 16;
                c.tickRate = 1;
                c.diffractionRoutes = 4;
                c.diffractionRange = 64;
                c.diffractionSearchBudget = 20000;
                c.echoCount = 3;
                c.echoRange = 128;
                c.procRange = -1;
                c.maxSounds = 1000;
            }
            default -> { } // Realism is the defaults; Custom never gets here
        }
    }

    /**
     * Makes Custom a copy of whatever preset is active, then switches to Custom.
     * Used when the player changes a setting while on a preset.
     */
    public static synchronized void switchToCustom() {
        Config saved = CONFIG.instance();
        if (saved.qualityPreset == QualityPreset.CUSTOM) return;
        copyFields(getInstance(), saved, false);
        saved.qualityPreset = QualityPreset.CUSTOM;
        refreshEffective();
    }

    /** Copies either the personal options or everything else from one settings object to another. */
    private static void copyFields(Config from, Config to, boolean personal) {
        for (java.lang.reflect.Field f : Config.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getAnnotation(SerialEntry.class) == null) continue;
            if (f.getName().equals("qualityPreset") || PERSONAL.contains(f.getName()) != personal) continue;
            try {
                Object value = f.get(from);
                if (value instanceof List<?> list) value = new ArrayList<>(list);
                f.set(to, value);
            } catch (IllegalAccessException ignored) {
                // public fields of this class are always accessible
            }
        }
    }

    /** Wavefront's physics mode runs on the permeation pipeline, so it counts as on in either case. */
    public boolean usesPermeationPath() {
        return permeation || wavePhysics;
    }

    // These read the active values; the preset has already been applied by getInstance().
    public int rays() { return raysCast; }
    public int bounces() { return raysBounced; }
    public int rayLength() { return maxRayLength; }
    public int updateTicks() { return tickRate; }
    public int routes() { return diffractionRoutes; }
    public int bendRange() { return diffractionRange; }
    public int searchBudget() { return diffractionSearchBudget; }

    // ===== Quick Setup tab =====
    @AutoGen(category = "quick", group = "main")
    @EnumCycler
    @CustomDescription("yacl3.config.wavefront:config.qualityPreset.description")
    @SerialEntry public QualityPreset qualityPreset = QualityPreset.REALISM;

    @AutoGen(category = "quick", group = "main")
    @Boolean(formatter = Boolean.Formatter.ON_OFF, colored = true)
    @CustomDescription("yacl3.config.wavefront:config.wavePhysics.description")
    @SerialEntry public boolean wavePhysics = true;

    @AutoGen(category = "quick", group = "main")
    @Boolean(formatter = Boolean.Formatter.ON_OFF, colored = true)
    @CustomDescription("yacl3.config.wavefront:config.reverb.description")
    @SerialEntry public boolean reverb = true;

    @AutoGen(category = "quick", group = "main")
    @FloatSlider(min = 0, max = 10, step = 0.1f)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.volumeMultiplier.description")
    @SerialEntry public float volumeMultiplier = 1;

    // ===== Walls and Corners tab =====
    @AutoGen(category = "physics", group = "walls")
    @FloatSlider(min = 0, max = 2, step = 0.05f)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.wallStrength.description")
    @SerialEntry public float wallStrength = 1.0f;

    @AutoGen(category = "physics", group = "bending")
    @Boolean(formatter = Boolean.Formatter.ON_OFF, colored = true)
    @CustomDescription("yacl3.config.wavefront:config.diffraction.description")
    @SerialEntry public boolean diffraction = true;

    @AutoGen(category = "physics", group = "bending")
    @Boolean(formatter = Boolean.Formatter.ON_OFF, colored = true)
    @CustomDescription("yacl3.config.wavefront:config.openingSize.description")
    @SerialEntry public boolean openingSize = true;

    @AutoGen(category = "physics", group = "bending")
    @FloatSlider(min = 0, max = 2, step = 0.05f)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.diffractionStrength.description")
    @SerialEntry public float diffractionStrength = 1.0f;

    // ===== Distance and motion =====
    @AutoGen(category = "physics", group = "distance")
    @Boolean(formatter = Boolean.Formatter.ON_OFF, colored = true)
    @CustomDescription("yacl3.config.wavefront:config.soundDelay.description")
    @SerialEntry public boolean soundDelay = true;

    @AutoGen(category = "physics", group = "distance")
    @FloatSlider(min = 0, max = 10, step = 0.25f)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.airAbsorptionStrength.description")
    @SerialEntry public float airAbsorptionStrength = 1.0f;

    @AutoGen(category = "physics", group = "distance")
    @Boolean(formatter = Boolean.Formatter.ON_OFF, colored = true)
    @CustomDescription("yacl3.config.wavefront:config.doppler.description")
    @SerialEntry public boolean doppler = true;

    @AutoGen(category = "physics", group = "distance")
    @FloatSlider(min = 0, max = 3, step = 0.1f)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.dopplerStrength.description")
    @SerialEntry public float dopplerStrength = 1.0f;

    @AutoGen(category = "physics", group = "distance")
    @Boolean(formatter = Boolean.Formatter.ON_OFF, colored = true)
    @CustomDescription("yacl3.config.wavefront:config.echoes.description")
    @SerialEntry public boolean echoes = true;

    @AutoGen(category = "physics", group = "distance")
    @FloatSlider(min = 0, max = 3, step = 0.1f)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.echoStrength.description")
    @SerialEntry public float echoStrength = 1.0f;

    @AutoGen(category = "physics", group = "distance")
    @IntSlider(min = 1, max = 3, step = 1)
    @CustomDescription("yacl3.config.wavefront:config.echoCount.description")
    @SerialEntry public int echoCount = 2;

    @AutoGen(category = "physics", group = "distance")
    @IntSlider(min = 16, max = 128, step = 8)
    @FormatTranslation("wavefront.config.unit.blocks")
    @CustomDescription("yacl3.config.wavefront:config.echoRange.description")
    @SerialEntry public int echoRange = 96;

    // ===== Voice Chat tab =====
    @AutoGen(category = "voicechat", group = "main")
    @Boolean(formatter = Boolean.Formatter.ON_OFF, colored = true)
    @CustomDescription("yacl3.config.wavefront:config.voicechatMuffle.description")
    @SerialEntry public boolean voicechatMuffle = true;

    @AutoGen(category = "voicechat", group = "main")
    @Boolean(formatter = Boolean.Formatter.ON_OFF, colored = true)
    @CustomDescription("yacl3.config.wavefront:config.voicechatMuffleVolume.description")
    @SerialEntry public boolean voicechatMuffleVolume = true;

    @AutoGen(category = "voicechat", group = "main")
    @Boolean(formatter = Boolean.Formatter.ON_OFF, colored = true)
    @CustomDescription("yacl3.config.wavefront:config.voicechatReverb.description")
    @SerialEntry public boolean voicechatReverb = true;

    @AutoGen(category = "voicechat", group = "main")
    @Boolean(formatter = Boolean.Formatter.ON_OFF, colored = true)
    @CustomDescription("yacl3.config.wavefront:config.voicechatReverbSelf.description")
    @SerialEntry public boolean voicechatReverbSelf = false;

    // ===== Performance tab (used when Quality is Custom) =====
    @AutoGen(category = "performance", group = "rays")
    @IntField(min = 0, max = 2000)
    @FormatTranslation("wavefront.config.unit.rays")
    @CustomDescription("yacl3.config.wavefront:config.raysCast.description")
    @SerialEntry public int raysCast = 500;

    @AutoGen(category = "performance", group = "rays")
    @IntSlider(min = 0, max = 16, step = 1)
    @FormatTranslation("wavefront.config.unit.rays")
    @CustomDescription("yacl3.config.wavefront:config.raysBounced.description")
    @SerialEntry public int raysBounced = 3;

    @AutoGen(category = "performance", group = "rays")
    @IntSlider(min = 2, max = 16, step = 1)
    @FormatTranslation("wavefront.config.unit.chunks")
    @CustomDescription("yacl3.config.wavefront:config.maxRayLength.description")
    @SerialEntry public int maxRayLength = 4; // chunks

    @AutoGen(category = "performance", group = "updates")
    @IntSlider(min = 0, max = 20, step = 1)
    @FormatTranslation("wavefront.config.unit.ticks")
    @CustomDescription("yacl3.config.wavefront:config.tickRate.description")
    @SerialEntry public int tickRate = 4; // once every 2 ticks bc i want poor people's pcs to burn

    @AutoGen(category = "performance", group = "updates")
    @IntSlider(min = -1, max = 1000, step = 10)
    @FormatTranslation("wavefront.config.unit.blocks")
    @CustomDescription("yacl3.config.wavefront:config.procRange.description")
    @SerialEntry public int procRange = 200; // blocks

    @AutoGen(category = "performance", group = "updates")
    @IntField(min = 0)
    @CustomDescription("yacl3.config.wavefront:config.maxSounds.description")
    @SerialEntry public int maxSounds = 100;

    @AutoGen(category = "performance", group = "bending")
    @IntSlider(min = 1, max = 4, step = 1)
    @CustomDescription("yacl3.config.wavefront:config.diffractionRoutes.description")
    @SerialEntry public int diffractionRoutes = 4;

    @AutoGen(category = "performance", group = "bending")
    @IntSlider(min = 8, max = 64, step = 4)
    @FormatTranslation("wavefront.config.unit.blocks")
    @CustomDescription("yacl3.config.wavefront:config.diffractionRange.description")
    @SerialEntry public int diffractionRange = 64;

    @AutoGen(category = "performance", group = "bending")
    @IntSlider(min = 500, max = 20000, step = 500)
    @FormatTranslation("wavefront.config.unit.steps")
    @CustomDescription("yacl3.config.wavefront:config.diffractionSearchBudget.description")
    @SerialEntry public int diffractionSearchBudget = 12000;

    // ===== Reverb Tuning tab (from Sound Physics Perfected) =====
    @AutoGen(category = "reverb_tuning", group = "main")
    @EnumCycler
    @CustomDescription("yacl3.config.wavefront:config.reverbTuning.description")
    @SerialEntry public DebugType reverbTuning = DebugType.OFF;

    @AutoGen(category = "reverb_tuning", group = "main")
    @EnumCycler
    @CustomDescription("yacl3.config.wavefront:config.legacyReverb.description")
    @SerialEntry public LegacyReverb legacyReverb = LegacyReverb.MODERN;

    @AutoGen(category = "reverb_tuning", group = "main")
    @Boolean(formatter = Boolean.Formatter.ON_OFF, colored = true)
    @CustomDescription("yacl3.config.wavefront:config.experimentalReverb.description")
    @SerialEntry public boolean experimentalReverb = true;

    @AutoGen(category = "reverb_tuning", group = "global")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.globalReverbIntensity.description")
    @SerialEntry public float globalReverbIntensity = 2.0f;

    @AutoGen(category = "reverb_tuning", group = "global")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.indoorBias.description")
    @SerialEntry public float indoorBias = 2.0f;

    @AutoGen(category = "reverb_tuning", group = "global")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.outdoorBias.description")
    @SerialEntry public float outdoorBias = 0.2f;

    @AutoGen(category = "reverb_tuning", group = "global")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.smallRoomEmphasis.description")
    @SerialEntry public float smallRoomEmphasis = 1.0f;

    @AutoGen(category = "reverb_tuning", group = "global")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.largeRoomEmphasis.description")
    @SerialEntry public float largeRoomEmphasis = 1.0f;

    @AutoGen(category = "reverb_tuning", group = "distance_and_attenuation")
    @FloatField(min = 0, max = 10000)
    @CustomDescription("yacl3.config.wavefront:config.distanceAttenuationLinear.description")
    @SerialEntry public float distanceAttenuationLinear = 0.0f;

    @AutoGen(category = "reverb_tuning", group = "distance_and_attenuation")
    @FloatField(min = 0, max = 10000)
    @CustomDescription("yacl3.config.wavefront:config.distanceAttenuationQuadratic.description")
    @SerialEntry public float distanceAttenuationQuadratic = 0.0f;

    @AutoGen(category = "reverb_tuning", group = "distance_and_attenuation")
    @FloatField(min = 0, max = 10000)
    @CustomDescription("yacl3.config.wavefront:config.airAbsorptionRate.description")
    @SerialEntry public float airAbsorptionRate = 0.003f;

    @AutoGen(category = "reverb_tuning", group = "distance_and_attenuation")
    @FloatField(min = 0, max = 10000)
    @CustomDescription("yacl3.config.wavefront:config.minAirAbsorption.description")
    @SerialEntry public float minAirAbsorption = 0.1f;

    @AutoGen(category = "reverb_tuning", group = "room_size_and_volume")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.roomVolumeMultiplier.description")
    @SerialEntry public float roomVolumeMultiplier = 0.7f;

    @AutoGen(category = "reverb_tuning", group = "room_size_and_volume")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.surfaceAreaMultiplier.description")
    @SerialEntry public float surfaceAreaMultiplier = 6.0f;

    @AutoGen(category = "reverb_tuning", group = "room_size_and_volume")
    @FloatField(min = 0, max = 10000)
    @CustomDescription("yacl3.config.wavefront:config.roomComplexityDivisor.description")
    @SerialEntry public float roomComplexityDivisor = 2.0f;

    @AutoGen(category = "reverb_tuning", group = "reverb_timing")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.lateReverbDelayMultiplier.description")
    @SerialEntry public float lateReverbDelayMultiplier = 1.5f;

    @AutoGen(category = "reverb_tuning", group = "reverb_timing")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.meters_per_second")
    @CustomDescription("yacl3.config.wavefront:config.soundSpeed.description")
    @SerialEntry public float soundSpeed = 343.0f;

    @AutoGen(category = "reverb_tuning", group = "gain_and_strength")
    @FloatField(min = 0, max = 10000)
    @CustomDescription("yacl3.config.wavefront:config.baseReverbGain.description")
    @SerialEntry public float baseReverbGain = 0.1f;

    @AutoGen(category = "reverb_tuning", group = "gain_and_strength")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.reverbGainMultiplier.description")
    @SerialEntry public float reverbGainMultiplier = 0.4f;

    @AutoGen(category = "reverb_tuning", group = "gain_and_strength")
    @FloatField(min = 0, max = 10000)
    @CustomDescription("yacl3.config.wavefront:config.maxOverallGain.description")
    @SerialEntry public float maxOverallGain = 1f;

    @AutoGen(category = "reverb_tuning", group = "gain_and_strength")
    @FloatField(min = 0, max = 10000)
    @CustomDescription("yacl3.config.wavefront:config.sendFilterHfReduction.description")
    @SerialEntry public float sendFilterHfReduction = 0.7f;

    @AutoGen(category = "reverb_tuning", group = "diffusion")
    @FloatField(min = 0, max = 1)
    @CustomDescription("yacl3.config.wavefront:config.minDiffusion.description")
    @SerialEntry public float minDiffusion = 0.3f;

    @AutoGen(category = "reverb_tuning", group = "diffusion")
    @FloatField(min = 0, max = 1)
    @CustomDescription("yacl3.config.wavefront:config.maxDiffusion.description")
    @SerialEntry public float maxDiffusion = 0.95f;

    @AutoGen(category = "reverb_tuning", group = "density")
    @FloatField(min = 0, max = 1)
    @CustomDescription("yacl3.config.wavefront:config.minDensity.description")
    @SerialEntry public float minDensity = 0.3f;

    @AutoGen(category = "reverb_tuning", group = "density")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.densityRoomSizeFactor.description")
    @SerialEntry public float densityRoomSizeFactor = 100.0f;

    @AutoGen(category = "reverb_tuning", group = "frequency_response")
    @FloatField(min = 0, max = 10000)
    @CustomDescription("yacl3.config.wavefront:config.dynamicAbsorptionLfFactor.description")
    @SerialEntry public float dynamicAbsorptionLfFactor = 3f;

    @AutoGen(category = "reverb_tuning", group = "frequency_response")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.decayLfMultiplier.description")
    @SerialEntry public float decayLfMultiplier = 1.2f;

    @AutoGen(category = "reverb_tuning", group = "frequency_response")
    @FloatField(min = 0, max = 10000)
    @CustomDescription("yacl3.config.wavefront:config.outdoorHfLeak.description")
    @SerialEntry public float outdoorHfLeak = 0.7f;

    @AutoGen(category = "reverb_tuning", group = "echo")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.echoTimeMultiplier.description")
    @SerialEntry public float echoTimeMultiplier = 0.002f;

    @AutoGen(category = "reverb_tuning", group = "echo")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.echoDepthMultiplier.description")
    @SerialEntry public float echoDepthMultiplier = 0.1f;

    @AutoGen(category = "reverb_tuning", group = "modulation")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.modulationTimeMultiplier.description")
    @SerialEntry public float modulationTimeMultiplier = 0.01f;

    @AutoGen(category = "reverb_tuning", group = "modulation")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.modulationDepthMultiplier.description")
    @SerialEntry public float modulationDepthMultiplier = 0.1f;

    @AutoGen(category = "reverb_tuning", group = "frequency_references")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.hertz")
    @CustomDescription("yacl3.config.wavefront:config.baseHfReference.description")
    @SerialEntry public float baseHfReference = 5000.0f;

    @AutoGen(category = "reverb_tuning", group = "frequency_references")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.hfRoomSizeFactor.description")
    @SerialEntry public float hfRoomSizeFactor = 20.0f;

    @AutoGen(category = "reverb_tuning", group = "frequency_references")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.hertz")
    @CustomDescription("yacl3.config.wavefront:config.baseLfReference.description")
    @SerialEntry public float baseLfReference = 250.0f;

    @AutoGen(category = "reverb_tuning", group = "frequency_references")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.lfRoomSizeFactor.description")
    @SerialEntry public float lfRoomSizeFactor = 2.0f;

    @AutoGen(category = "reverb_tuning", group = "rolloff")
    @FloatField(min = 0, max = 10000)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.roomRolloffSizeFactor.description")
    @SerialEntry public float roomRolloffSizeFactor = 50.0f;

    @AutoGen(category = "reverb_tuning", group = "rt60")
    @FloatField(min = 0, max = 10000)
    @CustomDescription("yacl3.config.wavefront:config.rt60SabineConstant.description")
    @SerialEntry public float rt60SabineConstant = 0.161f;

    @AutoGen(category = "reverb_tuning", group = "rt60")
    @FloatField(min = 0, max = 10000)
    @CustomDescription("yacl3.config.wavefront:config.minTotalAbsorption.description")
    @SerialEntry public float minTotalAbsorption = 0.1f;

    @AutoGen(category = "reverb_tuning", group = "interpolation_weights")
    @FloatField(min = 0, max = 10000)
    @CustomDescription("yacl3.config.wavefront:config.diffusionComplexityWeight.description")
    @SerialEntry public float diffusionComplexityWeight = 1.0f;

    @AutoGen(category = "reverb_tuning", group = "interpolation_weights")
    @FloatField(min = 0, max = 10000)
    @CustomDescription("yacl3.config.wavefront:config.diffusionEnclosureWeight.description")
    @SerialEntry public float diffusionEnclosureWeight = 1.0f;

    @AutoGen(category = "reverb_tuning", group = "interpolation_weights")
    @FloatField(min = 0, max = 10000)
    @CustomDescription("yacl3.config.wavefront:config.diffusionReverbWeight.description")
    @SerialEntry public float diffusionReverbWeight = 1.0f;

    // ===== Reverb Tuning: bounce loss =====
    @AutoGen(category = "reverb_tuning", group = "global")
    @DoubleSlider(min = 0.001, max = 1, step = 0.01)
    @CustomDescription("yacl3.config.wavefront:config.bounceAbsorptionMultiplier.description")
    @SerialEntry public double bounceAbsorptionMultiplier = 0.9;

    // ===== Advanced tab =====
    public static class StringValueFactory implements ListGroup.ValueFactory<String> {
        @Override
        public String provideNewValue() {
            return ""; // Default value for new entries
        }
    }

    public static class StringControllerFactory implements ListGroup.ControllerFactory<String> {
        @Override
        public ControllerBuilder<String> createController(ListGroup listGroup, ConfigField<List<String>> configField, OptionAccess optionAccess, Option<String> option) {
            return StringControllerBuilder.create(option);
        }
    }

    // ===== Advanced: tools, lists and classic mode =====
    @AutoGen(category = "advanced", group = "tools")
    @EnumCycler
    @CustomDescription("yacl3.config.wavefront:config.debug.description")
    @SerialEntry public DebugType debug = DebugType.OFF;

    @AutoGen(category = "advanced", group = "tools")
    @Boolean(formatter = Boolean.Formatter.ON_OFF, colored = true)
    @CustomDescription("yacl3.config.wavefront:config.disableFXOnUI.description")
    @SerialEntry public boolean disableFXOnUI = false;

    @AutoGen(category = "advanced")
    @ListGroup(valueFactory = StringValueFactory.class, controllerFactory = StringControllerFactory.class)
    @CustomDescription("yacl3.config.wavefront:config.soundBlacklist.description")
    @SerialEntry public List<String> soundBlacklist = new ArrayList<>();

    @AutoGen(category = "advanced")
    @ListGroup(valueFactory = StringValueFactory.class, controllerFactory = StringControllerFactory.class)
    @CustomDescription("yacl3.config.wavefront:config.soundTickBlacklist.description")
    @SerialEntry public List<String> soundTickBlacklist = new ArrayList<>();

    @AutoGen(category = "advanced", group = "classic")
    @Boolean(formatter = Boolean.Formatter.ON_OFF, colored = true)
    @CustomDescription("yacl3.config.wavefront:config.permeation.description")
    @SerialEntry public boolean permeation = true;

    @AutoGen(category = "advanced", group = "classic")
    @EnumCycler
    @CustomDescription("yacl3.config.wavefront:config.attenuationType.description")
    @SerialEntry public RedsAttenuationType attenuationType = RedsAttenuationType.VERCIDIUM_LINEAR;

    @AutoGen(category = "advanced", group = "classic")
    @Boolean(formatter = Boolean.Formatter.ON_OFF, colored = true)
    @CustomDescription("yacl3.config.wavefront:config.shortcutDirectionality.description")
    @SerialEntry public boolean shortcutDirectionality = true; // bugs are now fixed.

    @AutoGen(category = "advanced", group = "classic")
    @DoubleSlider(min = 0.01, max = 2, step = 0.05)
    @FormatTranslation("wavefront.config.unit.blocks")
    @CustomDescription("yacl3.config.wavefront:config.permeationStepSize.description")
    @SerialEntry public double permeationStepSize = 0.0625; // changed to 1/16 of a block bc u don't *need* 0.01 precision lets be so fr

    @AutoGen(category = "advanced", group = "classic")
    @Boolean(formatter = Boolean.Formatter.ON_OFF, colored = true)
    @CustomDescription("yacl3.config.wavefront:config.useExplosionResistance.description")
    @SerialEntry public boolean useExplosionResistance = true;

    @AutoGen(category = "advanced", group = "classic")
    @DoubleSlider(min = 1, max = 10, step = 0.1)
    @FormatTranslation("wavefront.config.unit.blocks")
    @CustomDescription("yacl3.config.wavefront:config.maxBlocksPermeated.description")
    @SerialEntry public double maxBlocksPermeated = 3;

    @AutoGen(category = "advanced", group = "classic")
    @DoubleSlider(min = 0.001, max = 1, step = 0.01)
    @FormatTranslation("wavefront.config.unit.multiplier")
    @CustomDescription("yacl3.config.wavefront:config.permeationAbsorption.description")
    @SerialEntry public double permeationAbsorption = 0.4;

    @AutoGen(category = "advanced", group = "classic")
    @Boolean(formatter = Boolean.Formatter.ON_OFF, colored = true)
    @CustomDescription("yacl3.config.wavefront:config.barrierAsAir.description")
    @SerialEntry public boolean barrierAsAir = true;
}

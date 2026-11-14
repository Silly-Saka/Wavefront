package com.redsmods.wavefront.wrappers;

import com.redsmods.wavefront.RaycastingHelper;
import com.redsmods.wavefront.config.Config;
import lombok.Getter;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;

import static com.redsmods.wavefront.RaycastingHelper.fxHandler;
import static org.joml.Math.lerp;

public class RedPermeatedSoundInstance extends RedTickableInstance {
    @Getter float permeationIndex;
    int tickCount = 0;
    private int id;
    private boolean sourceSet = false;
    private float targetMuffle;
    // Wavefront physics mode: the filter is driven by these instead of permeationIndex.
    private boolean bandMode = false;
    private float gain = 1f, gainLF = 1f, gainHF = 1f;
    private float targetGain = 1f, targetGainLF = 1f, targetGainHF = 1f;
    /** Wavefront: seconds the sound takes to reach the listener, and the device-clock time it should start at (0 = unset). */
    @Getter @lombok.Setter private float delaySeconds = 0f;
    @Getter @lombok.Setter private long startNanos = 0L;
    private boolean movingLastTick = false;
    /** Wavefront: true for an echo copy, which stays where it was put and is never re-solved. */
    @Getter @lombok.Setter private boolean fixedEcho = false;
    /** Wavefront: for an echo copy, which of the six block directions its surface lies in. */
    @Getter @lombok.Setter private int echoDirection = -1;
    /** Wavefront: the echo copies of this sound, kept so they can follow it and stop with it. */
    @Getter private final java.util.List<RedPermeatedSoundInstance> echoCopies = new java.util.concurrent.CopyOnWriteArrayList<>();
    private boolean originalStopped = false, echoStopped = false;
    private int stopCountdown = -1;

    public RedPermeatedSoundInstance(Identifier soundID, Sound sound, SoundSource category, Vec3 position, float volume, float pitch, SoundInstance wrapped, float permeationIndex, float attenuationMultiplier) {
        super(soundID, sound, category,position, volume, pitch, wrapped, attenuationMultiplier);
        this.permeationIndex = permeationIndex;
        this.targetMuffle = permeationIndex;
        super.updatePos();
        super.updateVolume();
        updateMuffle();
    }

    public void setPermeationIndex(float permeationIndex) {
        if (super.isStopped()) return;
        this.bandMode = false; // classic mode took over (Sound Physics was switched off mid-sound)
        this.targetMuffle = permeationIndex;
    }

    @Override
    public void tick() {
        if (fixedEcho) {
            tickEcho();
            return;
        }
        super.updateWrapped();
        tickCount++;
        if (super.isStopped() || Config.getInstance().updateTicks() == 0) return; // DONE or ticking sounds is off
        if (tickCount % Config.getInstance().updateTicks() == 0) { // only update once every .1 second
            if (!RaycastingHelper.permeatedTickQueue.contains(this))
                RaycastingHelper.permeatedTickQueue.add(this);
        }
        super.updatePos();
        super.updateVolume();
        super.updatePitch();
        updateMuffle();
        updateDopplerVelocity();
    }

    /** Wavefront: keeps OpenAL informed of the real source's speed so it can shift the pitch. */
    private void updateDopplerVelocity() {
        if (!sourceSet || !AL10.alIsSource(id)) return;
        boolean moving = sourceVelocity.lengthSqr() > 1.0e-6;
        if (moving || movingLastTick) {
            wavefront.mc.Doppler.setSourceVelocity(id, sourceVelocity.x, sourceVelocity.y, sourceVelocity.z);
        }
        movingLastTick = moving;
    }

    /**
     * Wavefront: switches this sound to physics mode and sets where its filter should head.
     * @param snap true to jump straight to the values (new sounds), false to glide (playing sounds)
     */
    public void setBandGains(float newGain, float newGainLF, float newGainHF, boolean snap) {
        bandMode = true;
        targetGain = clamp(newGain, 0f, 1f);
        targetGainLF = clamp(newGainLF, 0f, 1f);
        targetGainHF = clamp(newGainHF, 0f, 1f);
        if (snap) {
            gain = targetGain;
            gainLF = targetGainLF;
            gainHF = targetGainHF;
        }
    }

    /** Pushes the current filter settings to OpenAL, in whichever mode this sound uses. */
    public void applyFilter() {
        if (!sourceSet || !AL10.alIsSource(id)) return;
        if (bandMode) fxHandler.applyBandGainsToSource(id, gain, gainLF, gainHF);
        else fxHandler.applyMuffleToSource(id, 1 - permeationIndex);
    }

    /**
     * Wavefront: one tick of an echo copy. It glides to wherever it was last told to be, and
     * when its sound stops it carries on for as long as the echo takes to arrive, then stops.
     */
    private void tickEcho() {
        if (!originalStopped && wrappedSound() instanceof net.minecraft.client.resources.sounds.TickableSoundInstance source && source.isStopped()) {
            originalStopped = true;
        }
        if (originalStopped && !echoStopped) {
            if (stopCountdown < 0) stopCountdown = Math.max(1, Math.round(delaySeconds * 20f));
            if (--stopCountdown <= 0) echoStopped = true;
        }
        super.updatePos();
        super.updateVolume();
        super.updatePitch();
        updateMuffle();
    }

    /** Wavefront: tells an echo copy that the sound it repeats has been stopped. */
    public void originalStopped() {
        originalStopped = true;
    }

    /** Wavefront: passes a stop on to this sound's echoes. */
    public void stopEchoes() {
        for (RedPermeatedSoundInstance copy : echoCopies) copy.originalStopped();
    }

    @Override
    public boolean isStopped() {
        return fixedEcho ? echoStopped : super.isStopped();
    }

    public void updateMuffle() {
        if (bandMode) {
            // Glide a fixed fraction of the way each tick so walking past a corner fades smoothly.
            gain += (targetGain - gain) * 0.35f;
            gainLF += (targetGainLF - gainLF) * 0.35f;
            gainHF += (targetGainHF - gainHF) * 0.35f;
            if (Math.abs(targetGain - gain) < 0.002f) gain = targetGain;
            if (Math.abs(targetGainLF - gainLF) < 0.002f) gainLF = targetGainLF;
            if (Math.abs(targetGainHF - gainHF) < 0.002f) gainHF = targetGainHF;
            applyFilter();
            return;
        }

        // Calculate the difference between current and target volume
        float deltaVolume = targetMuffle - permeationIndex;
        float maxVolumeChange = Math.max(Math.abs(deltaVolume / Config.getInstance().updateTicks()),0.3f); // yay magic number

        // If we're already at the target or very close, set volume directly
        if (Math.abs(deltaVolume) <= 0.001f || Math.abs(deltaVolume) > maxVolumeChange * Config.getInstance().updateTicks()) {
            permeationIndex = targetMuffle;
            if (sourceSet && AL10.alIsSource(id))
                fxHandler.applyMuffleToSource(id,1-permeationIndex);
            return;
        }

        // Calculate how much we can change this tick
        float volumeChange = Math.min(maxVolumeChange, Math.abs(deltaVolume));

        // Apply the change in the correct direction
        if (deltaVolume > 0) {
            permeationIndex += volumeChange;
        } else {
            permeationIndex -= volumeChange;
        }

        if (sourceSet && AL10.alIsSource(id))
            fxHandler.applyMuffleToSource(id,1-permeationIndex);
    }

    public void setSource(int id) {
        sourceSet = true;
        this.id = id;
    }

    private static float clamp(float a, float b, float c) {
        return Math.min(Math.max(a,b),c);
    }
}

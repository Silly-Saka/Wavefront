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
    private float gain = 1f, gainHF = 1f;
    private float targetGain = 1f, targetGainHF = 1f;

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
        super.updateWrapped();
        tickCount++;
        if (super.isStopped() || Config.getInstance().tickRate == 0) return; // DONE or ticking sounds is off
        if (tickCount % Config.getInstance().tickRate == 0) { // only update once every .1 second
            if (!RaycastingHelper.permeatedTickQueue.contains(this))
                RaycastingHelper.permeatedTickQueue.add(this);
        }
        super.updatePos();
        super.updateVolume();
        super.updatePitch();
        updateMuffle();
    }

    /**
     * Wavefront: switches this sound to physics mode and sets where its filter should head.
     * @param snap true to jump straight to the values (new sounds), false to glide (playing sounds)
     */
    public void setBandGains(float newGain, float newGainHF, boolean snap) {
        bandMode = true;
        targetGain = clamp(newGain, 0f, 1f);
        targetGainHF = clamp(newGainHF, 0f, 1f);
        if (snap) {
            gain = targetGain;
            gainHF = targetGainHF;
        }
    }

    /** Pushes the current filter settings to OpenAL, in whichever mode this sound uses. */
    public void applyFilter() {
        if (!sourceSet || !AL10.alIsSource(id)) return;
        if (bandMode) fxHandler.applyBandGainsToSource(id, gain, gainHF);
        else fxHandler.applyMuffleToSource(id, 1 - permeationIndex);
    }

    public void updateMuffle() {
        if (bandMode) {
            // Glide a fixed fraction of the way each tick so walking past a corner fades smoothly.
            gain += (targetGain - gain) * 0.35f;
            gainHF += (targetGainHF - gainHF) * 0.35f;
            if (Math.abs(targetGain - gain) < 0.002f) gain = targetGain;
            if (Math.abs(targetGainHF - gainHF) < 0.002f) gainHF = targetGainHF;
            applyFilter();
            return;
        }

        // Calculate the difference between current and target volume
        float deltaVolume = targetMuffle - permeationIndex;
        float maxVolumeChange = Math.max(Math.abs(deltaVolume / Config.getInstance().tickRate),0.3f); // yay magic number

        // If we're already at the target or very close, set volume directly
        if (Math.abs(deltaVolume) <= 0.001f || Math.abs(deltaVolume) > maxVolumeChange * Config.getInstance().tickRate) {
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

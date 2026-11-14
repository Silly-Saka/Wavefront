package com.redsmods.wavefront.mixin.client;

import com.mojang.blaze3d.audio.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import wavefront.mc.SoundClock;

/**
 * Wavefront: lets a sound start at an exact time on the audio device's clock instead of
 * "as soon as possible". Sounds with no scheduled time start exactly as before.
 */
@Mixin(Channel.class)
public abstract class ChannelPlayMixin {
    @Redirect(method = "play", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/AL10;alSourcePlay(I)V", remap = false))
    private void wavefront$playAtScheduledTime(int source) {
        SoundClock.play(source);
    }
}

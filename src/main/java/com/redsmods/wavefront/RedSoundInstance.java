package com.redsmods.wavefront;

import lombok.*;
import lombok.experimental.Delegate;
import net.minecraft.client.resources.sounds.SoundInstance;

@AllArgsConstructor @EqualsAndHashCode @ToString
public class RedSoundInstance implements SoundInstance {
    @Delegate
    @Getter
    @NonNull
    SoundInstance original;
}
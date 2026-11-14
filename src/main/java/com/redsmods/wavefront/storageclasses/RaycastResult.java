package com.redsmods.wavefront.storageclasses;

import lombok.AllArgsConstructor;
import net.minecraft.world.phys.Vec3;

@AllArgsConstructor
public class RaycastResult {
    public final double totalDistance;
    public final Vec3 initialDirection;
    public final SoundData hitEntity;
}


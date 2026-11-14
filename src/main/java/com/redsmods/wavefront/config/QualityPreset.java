package com.redsmods.wavefront.config;

/**
 * How much work the mod does. Performance is the lightest, Realism uses the values past which
 * extra work stops being audible, and Overkill pushes every accuracy setting to its limit
 * regardless. Custom uses the player's own values.
 */
public enum QualityPreset {
    PERFORMANCE,
    BALANCED,
    REALISM,
    OVERKILL,
    CUSTOM
}

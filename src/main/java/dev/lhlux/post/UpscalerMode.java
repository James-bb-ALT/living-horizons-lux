package dev.lhlux.post;

/**
 * Spatial & Temporal Upscaler modes for Render Scale (LSR).
 */
public enum UpscalerMode {
    TEMPORAL_TAAU("TAAU (FSR2-Style)"),
    NIS_FSR1("NIS / FSR1"),
    LSR_CAS("LSR (CAS)");

    private final String label;

    UpscalerMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public UpscalerMode next() {
        UpscalerMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}

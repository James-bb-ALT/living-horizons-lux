package dev.lhlux.framegen;

/**
 * Supported frame generation / temporal reprojection modes.
 */
public enum FrameGenMode {
    OFF("Off"),
    TFR_2X("TFR 2x (Reprojection)"),
    TFR_3X("TFR 3x (Reprojection)"),
    TFR_ADAPTIVE("TFR Adaptive"),
    INTERPOLATED_2X("Interpolated 2x");

    private final String label;

    FrameGenMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isEnabled() {
        return this != OFF;
    }

    public FrameGenMode next() {
        FrameGenMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}

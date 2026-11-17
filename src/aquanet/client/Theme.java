package aquanet.client;

import aquanet.common.TankInfo;

import java.awt.Color;
import java.awt.Font;

/** Shared colours and fonts so every panel has the same look. */
final class Theme {
    private Theme() { }

    static final Color BG        = new Color(0xEEF2F6);
    static final Color CARD      = Color.WHITE;
    static final Color BORDER    = new Color(0xD0D7DE);
    static final Color TEXT      = new Color(0x1F2933);
    static final Color MUTED     = new Color(0x6B7785);
    static final Color ACCENT    = new Color(0x1E6FD9);
    static final Color GOOD      = new Color(0x2EA44F);
    static final Color BAD       = new Color(0xD83A34);
    static final Color PIPE      = new Color(0xB8C2CC);
    static final Color WATER     = new Color(0x3498DB);

    static final Color S_OK = new Color(0x2F86D0), S_LOW = new Color(0xE67E22), S_HIGH = new Color(0xF1B20F),
            S_EMPTY = new Color(0x95A5A6), S_OVERFLOW = new Color(0xE53935);

    /** Colour for a tank status string (OK=blue, LOW=orange, HIGH=amber, EMPTY=grey, OVERFLOW=red). */
    static Color statusColor(String status) {
        if (TankInfo.LOW.equals(status)) return S_LOW;
        if (TankInfo.HIGH.equals(status)) return S_HIGH;
        if (TankInfo.EMPTY.equals(status) || TankInfo.OFFLINE.equals(status)) return S_EMPTY;
        if (TankInfo.OVERFLOW.equals(status)) return S_OVERFLOW;
        return S_OK;
    }

    /** Palette for chart lines, indexed by tank order. */
    static final Color[] SERIES = { new Color(0x1E6FD9), new Color(0xE67E22), new Color(0x2EA44F),
            new Color(0x8E44AD), new Color(0xC0392B), new Color(0x16A085) };

    static Font font(int style, float size) { return new Font(Font.SANS_SERIF, style, 1).deriveFont(style, size); }
}

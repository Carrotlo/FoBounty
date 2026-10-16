package me.foesio.foBounty.util;

import me.foesio.core.gui.GuiTitles;
import me.foesio.core.message.FoStyle;
import me.foesio.core.number.LargeNumberParser;
import me.foesio.foBounty.config.DisplayFormats;
import me.foesio.core.text.FoText;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Style {
    public static final String THEME_HEX = FoStyle.THEME;
    public static final String MUTED_HEX = FoStyle.MUTED;
    public static final String WHITE_HEX = FoStyle.WHITE;
    public static final String GOOD_HEX = FoStyle.GOOD;
    public static final String BAD_HEX = FoStyle.BAD;
    public static final String BULLET = "\u2022";

    private Style() {
    }

    public static String colorize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String withTokens = input
                .replace("{theme}", "&" + THEME_HEX)
                .replace("{muted}", "&" + MUTED_HEX)
                .replace("{white}", "&" + WHITE_HEX)
                .replace("{good}", "&" + GOOD_HEX)
                .replace("{bad}", "&" + BAD_HEX);
        return FoText.color(withTokens);
    }

    public static String smallCaps(String value) {
        return GuiTitles.smallCaps(value);
    }

    public static String formatMoney(long amount) {
        return DisplayFormats.money(amount);
    }

    public static Long parseAmount(String input) {
        BigDecimal scaled = LargeNumberParser.parse(input)
                .map(value -> value.setScale(0, RoundingMode.HALF_UP))
                .orElse(null);
        if (scaled == null) {
            return null;
        }
        if (scaled.compareTo(BigDecimal.ZERO) < 0 || scaled.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            return null;
        }
        return scaled.longValue();
    }

    public static String formatTimestamp(long epochMillis) {
        return DisplayFormats.timestamp(epochMillis);
    }
}

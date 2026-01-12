package cn.oneostool.plus;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.SubscriptSpan;

/**
 * Utility class for formatting gear display text with subscript digits.
 * Example: "D3" -> "D₃" (large D, small subscript 3)
 */
public class GearTextUtils {

    // Digit size ratio relative to letter (60% as per design spec)
    private static final float DIGIT_SIZE_RATIO = 0.6f;

    /**
     * Format gear text with subscript digits.
     * Letters (P, R, N, D, M) stay full size, digits become 60% size subscript.
     * 
     * @param gearText Original gear string like "D", "D3", "M5", "R"
     * @return SpannableString with formatted text
     */
    public static CharSequence formatGear(String gearText) {
        if (gearText == null || gearText.isEmpty()) {
            return gearText;
        }

        // If no digits, return as-is
        boolean hasDigit = false;
        int digitStart = -1;
        for (int i = 0; i < gearText.length(); i++) {
            char c = gearText.charAt(i);
            if (c >= '0' && c <= '9') {
                if (digitStart == -1) {
                    digitStart = i;
                }
                hasDigit = true;
            }
        }

        if (!hasDigit) {
            return gearText;
        }

        SpannableString spannable = new SpannableString(gearText);

        // Apply subscript and smaller size to digits
        spannable.setSpan(
                new RelativeSizeSpan(DIGIT_SIZE_RATIO),
                digitStart,
                gearText.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        spannable.setSpan(
                new SubscriptSpan(),
                digitStart,
                gearText.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        return spannable;
    }
}

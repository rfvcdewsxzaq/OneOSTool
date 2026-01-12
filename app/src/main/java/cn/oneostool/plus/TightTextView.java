package cn.oneostool.plus;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;

import android.widget.TextView;

public class TightTextView extends TextView {

    private Rect mBounds = new Rect();

    public TightTextView(Context context) {
        super(context);
        init();
    }

    public TightTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TightTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setIncludeFontPadding(false);
    }

    @Override
    public void setTextSize(int unit, float size) {
        super.setTextSize(unit, size);
        requestLayout();
        invalidate();
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(text, type);
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        String text = getText().toString();
        if (text.length() == 0) {
            setMeasuredDimension(0, 0);
            return;
        }

        Paint paint = getPaint();

        // Calculate fixed-width measurement string:
        // - Digits (0-9) use '8' as reference (widest digit)
        // - Gear letters (P, R, N, D, M) use 'M' as reference (widest among them)
        // - Other characters use their actual width
        StringBuilder measureStr = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                measureStr.append('8'); // '8' is typically the widest digit
            } else if (c == 'P' || c == 'R' || c == 'N' || c == 'D' || c == 'M' ||
                    c == 'p' || c == 'r' || c == 'n' || c == 'd' || c == 'm') {
                measureStr.append('M'); // 'M' is widest among gear letters P/R/N/D/M
            } else {
                measureStr.append(c); // Keep other characters as-is
            }
        }

        // Measure the fixed-width reference string
        paint.getTextBounds(measureStr.toString(), 0, measureStr.length(), mBounds);

        int w = mBounds.width();
        int h = mBounds.height();

        // Enforce View size to match ink sizes
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        String text = getText().toString();
        if (text.length() == 0)
            return;

        // Recalculate bounds to be safe during draw (or use cached from measure if
        // known stable)
        getPaint().getTextBounds(text, 0, text.length(), mBounds);

        // Standard TextView draws with padding/margins.
        // We want to draw exact ink at (0,0).
        // super.onDraw(canvas); // DO NOT CALL SUPER

        Paint paint = getPaint();
        paint.setColor(getCurrentTextColor());

        // Draw at offset to align Top-Left of ink to (0,0) of View
        // Origin required = -left, -top
        canvas.drawText(text, -mBounds.left, -mBounds.top, paint);
    }
}

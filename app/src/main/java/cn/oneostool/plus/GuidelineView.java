package cn.oneostool.plus;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class GuidelineView extends View {

    public static final int TYPE_HORIZONTAL = 0;
    public static final int TYPE_VERTICAL = 1;

    public static final int STYLE_APPROACH = 0; // Grey Dashed
    public static final int STYLE_SNAPPED = 1; // Green Dashed

    private static class Line {
        int type;
        float pos; // X for Vertical, Y for Horizontal
        float start; // Y-start for Vertical, X-start for Horizontal
        float end; // Y-end for Vertical, X-end for Horizontal
        int style;

        Line(int type, float pos, float start, float end, int style) {
            this.type = type;
            this.pos = pos;
            this.start = start;
            this.end = end;
            this.style = style;
        }
    }

    private final List<Line> lines = new ArrayList<>();
    private Paint paintApproach;
    private Paint paintSnapped;

    public GuidelineView(Context context) {
        super(context);
        init();
    }

    public GuidelineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // Convert 1dp to pixels
        float density = getResources().getDisplayMetrics().density;
        float widthPx = 1 * density;

        paintApproach = new Paint();
        paintApproach.setColor(Color.DKGRAY);
        paintApproach.setStyle(Paint.Style.STROKE);
        paintApproach.setStrokeWidth(widthPx);
        paintApproach.setPathEffect(new DashPathEffect(new float[] { 10, 10 }, 0));

        paintSnapped = new Paint();
        paintSnapped.setColor(Color.parseColor("#FF4CAF50")); // Standard Material Green 500
        paintSnapped.setStyle(Paint.Style.STROKE);
        paintSnapped.setStrokeWidth(widthPx);
        paintSnapped.setPathEffect(new DashPathEffect(new float[] { 15, 5 }, 0));
    }

    public void clear() {
        if (!lines.isEmpty()) {
            lines.clear();
            invalidate();
        }
    }

    public void addLine(int type, float pos, float start, float end, int style) {
        lines.add(new Line(type, pos, start, end, style));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Line line : lines) {
            Paint p = (line.style == STYLE_SNAPPED) ? paintSnapped : paintApproach;
            if (line.type == TYPE_HORIZONTAL) {
                canvas.drawLine(line.start, line.pos, line.end, line.pos, p);
            } else {
                canvas.drawLine(line.pos, line.start, line.pos, line.end, p);
            }
        }
    }
}

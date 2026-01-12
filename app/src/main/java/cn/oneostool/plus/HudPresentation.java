package cn.oneostool.plus;

import android.app.Presentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.ImageView;

public class HudPresentation extends Presentation {

    private TightTextView tvTime;
    private FrameLayout rootLayout;
    private ImageView viewWhiteRect;
    private ImageView viewMediaCover; // Media Cover Widget
    private TightTextView tvGear; // Gear Widget

    // HUD dimensions (matches VirtualNavi analysis)
    public static final int HUD_WIDTH = 728;
    public static final int HUD_HEIGHT = 186;

    public HudPresentation(Context outerContext, Display display) {
        super(outerContext, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Transparent background
        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }

        rootLayout = new FrameLayout(getContext());
        rootLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.setClipChildren(false);
        rootLayout.setClipToPadding(false);

        setContentView(rootLayout);

        // Layer 1: Image Widget (Bottom)
        viewWhiteRect = new ImageView(getContext());
        viewWhiteRect.setImageResource(R.drawable.verification_image);
        viewWhiteRect.setScaleType(ImageView.ScaleType.FIT_XY);

        // Rounded Corners for Image
        viewWhiteRect.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(android.view.View view, android.graphics.Outline outline) {
                int size = Math.min(view.getWidth(), view.getHeight());
                float radius = size * 0.05f;
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        viewWhiteRect.setClipToOutline(true);

        FrameLayout.LayoutParams whiteParams = new FrameLayout.LayoutParams(100, 100);
        rootLayout.addView(viewWhiteRect, whiteParams);

        // Layer 1.5: Media Cover Widget (Middle)
        viewMediaCover = new ImageView(getContext());
        viewMediaCover.setImageResource(android.R.drawable.ic_media_play); // Placeholder
        viewMediaCover.setScaleType(ImageView.ScaleType.FIT_XY);

        // Rounded Corners for Media Cover
        viewMediaCover.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(android.view.View view, android.graphics.Outline outline) {
                int size = Math.min(view.getWidth(), view.getHeight());
                float radius = size * 0.05f;
                // Use view dimensions for rect
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        viewMediaCover.setClipToOutline(true);

        // Default hidden
        viewMediaCover.setVisibility(android.view.View.GONE);

        FrameLayout.LayoutParams coverParams = new FrameLayout.LayoutParams(100, 100); // Default dynamic
        rootLayout.addView(viewMediaCover, coverParams);

        // Layer 2: Time Widget (Top)
        tvTime = new TightTextView(getContext());
        tvTime.setTextColor(android.graphics.Color.WHITE);
        tvTime.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        // Gravity/Padding irrelevant for TightTextView but harmless

        rootLayout.addView(tvTime, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Layer 3: Gear Widget (Top)
        tvGear = new TightTextView(getContext());
        tvGear.setTextColor(android.graphics.Color.WHITE);
        tvGear.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvGear.setVisibility(android.view.View.GONE);
        rootLayout.addView(tvGear, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams winParams = window.getAttributes();
            winParams.width = HUD_WIDTH;
            winParams.height = HUD_HEIGHT;
            winParams.alpha = 1.0f;
            winParams.gravity = Gravity.TOP | Gravity.LEFT;
            winParams.x = 0;
            winParams.y = 720; // Coordinates from analysis
            window.setAttributes(winParams);

            // Required for overlay display from service or activity
            // Only apply for versions prior to Android R (API 30) to avoid "Window Type
            // Mismatch" crash on targetSdk 30+
            // when running on newer phones. Target vehicle is Android 9 (API 28), so it
            // will still get Overlay.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                winParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            }
        }
    }

    public void setTimeVisibility(boolean visible) {
        if (tvTime != null) {
            tvTime.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    public void setImageVisibility(boolean visible) {
        if (viewWhiteRect != null) {
            viewWhiteRect.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    public void setMediaCoverVisibility(boolean visible) {
        if (viewMediaCover != null) {
            // Only show if we have content? Or placeholder? For now just respect flag.
            // Caller loop will handle content binding.
            viewMediaCover.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    public void setGearVisibility(boolean visible) {
        if (tvGear != null) {
            tvGear.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    public void updateGear(String text) {
        if (tvGear != null && text != null) {
            tvGear.setText(text);
        }
    }

    public void updateMediaCover(android.graphics.Bitmap bitmap) {
        if (viewMediaCover != null) {
            if (bitmap != null) {
                viewMediaCover.setImageBitmap(bitmap);
            } else {
                // Determine what to show if null? Maybe transparent or placeholder
                // For HUD, maybe keep last or clear?
                // Let's set to null or placeholder
                viewMediaCover.setImageResource(android.R.drawable.ic_menu_gallery);
            }
        }
    }

    public void syncTimeWidget(float xRatio, float yRatio, float textSize, float scaleFactor, String text) {
        if (tvTime == null)
            return;

        tvTime.setText(text);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tvTime.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.setMargins(0, 0, 0, 0);
        tvTime.setLayoutParams(lp);

        tvTime.setX(xRatio * HUD_WIDTH);
        tvTime.setY(yRatio * HUD_HEIGHT);
        tvTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSize * scaleFactor);
    }

    public void syncWhiteWidget(float xRatio, float yRatio, float wRatio, float hRatio) {
        if (viewWhiteRect == null)
            return;

        int x = (int) (xRatio * HUD_WIDTH);
        int y = (int) (yRatio * HUD_HEIGHT);
        int w = (int) (wRatio * HUD_WIDTH);
        int h = (int) (hRatio * HUD_HEIGHT);

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) viewWhiteRect.getLayoutParams();
        params.width = w;
        params.height = h;
        // Clear margins logic to match Editor's absolute positioning
        params.leftMargin = 0;
        params.topMargin = 0;
        params.gravity = Gravity.TOP | Gravity.LEFT;

        viewWhiteRect.setLayoutParams(params);

        // Use Translation for positioning
        viewWhiteRect.setX(x);
        viewWhiteRect.setY(y);

        viewWhiteRect.invalidateOutline();
    }

    public void syncMediaCoverWidget(float xRatio, float yRatio, float wRatio, float hRatio) {
        if (viewMediaCover == null)
            return;

        int w = (int) (wRatio * HUD_WIDTH);
        int h = (int) (hRatio * HUD_HEIGHT);
        if (w < 1)
            w = 100;
        if (h < 1)
            h = 100;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) viewMediaCover.getLayoutParams();
        lp.width = w;
        lp.height = h;
        // Clear margins to use setX/setY for positioning (consistent with other
        // widgets)
        lp.leftMargin = 0;
        lp.topMargin = 0;
        lp.gravity = Gravity.TOP | Gravity.START;
        viewMediaCover.setLayoutParams(lp);

        // Use setX/setY for positioning (consistent with syncWhiteWidget,
        // syncTimeWidget, syncGearWidget)
        int x = (int) (xRatio * HUD_WIDTH);
        int y = (int) (yRatio * HUD_HEIGHT);
        viewMediaCover.setX(x);
        viewMediaCover.setY(y);

        viewMediaCover.invalidateOutline();
    }

    // Similar strictly to syncTimeWidget
    public void syncGearWidget(float xRatio, float yRatio, float textSize, float scaleFactor, String text) {
        if (tvGear != null) {
            if (text != null)
                tvGear.setText(text);

            // Position
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tvGear.getLayoutParams();
            if (lp == null) {
                lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setMargins(0, 0, 0, 0);
            tvGear.setLayoutParams(lp);

            tvGear.setX(xRatio * HUD_WIDTH);
            tvGear.setY(yRatio * HUD_HEIGHT);

            // Size - Apply scaleFactor like time widget
            if (textSize > 0) {
                tvGear.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSize * scaleFactor);
            }
        }
    }
}

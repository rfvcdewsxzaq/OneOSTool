package cn.oneostool.plus;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.LinearLayout;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.button.MaterialButton;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

public class HudManager {
    private static final String TAG = "HudManager";
    private static final String PREFS_NAME = "oneostool_prefs";

    private final MainActivity activity;
    private final SharedPreferences prefs;

    // UI Elements
    private SwitchMaterial switchHud;
    private CheckBox switchHudTime, switchHudImage, switchHudMediaCover, switchHudGear;
    private LinearLayout hudSwitchPanel;
    private ViewGroup editorContainer;
    private FrameLayout timeWidgetContainer;
    private TightTextView tvTime;
    private ImageView resizeHandle;
    private FrameLayout whiteWidgetContainer;
    private ImageView viewWhiteRect;
    private androidx.cardview.widget.CardView cardWhiteRect;
    private ImageView resizeHandleWhite;
    private FrameLayout mediaCoverWidgetContainer;
    private ImageView viewMediaCover;
    private androidx.cardview.widget.CardView cardMediaCover;
    private ImageView resizeHandleMediaCover;
    private FrameLayout gearWidgetContainer;
    private TightTextView tvGear;
    private ImageView resizeHandleGear;
    private TextView tvHudStatus;
    private GuidelineView guidelineView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeRunnable;

    // Drag & Resize state (Ported from VirtualNavi)
    private float dX, dY;
    private float originRawX, originRawY;
    private float initialDist;
    private float initialTextSize;
    private float initialWhiteWidth, initialWhiteHeight;
    private float initialCoverWidth, initialCoverHeight;
    private boolean isResizing = false;
    private float lastEditorWidth = 0;
    private boolean isEditorLocked = false;

    // Ink Anchor for Resizing
    private float anchorInkX, anchorInkY;

    // Limits cache
    private float minX, maxX, minY, maxY;
    private float deltaW, deltaH;

    // Initialization Flag to prevent flash
    private boolean isStateRestored = false;

    public HudManager(MainActivity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        init();
    }

    private void init() {
        switchHud = activity.findViewById(R.id.switchHud);
        hudSwitchPanel = activity.findViewById(R.id.cardHudEditor);
        switchHudTime = activity.findViewById(R.id.switchHudTime);
        switchHudImage = activity.findViewById(R.id.switchHudImage);
        switchHudTime = activity.findViewById(R.id.switchHudTime);
        switchHudImage = activity.findViewById(R.id.switchHudImage);
        switchHudMediaCover = activity.findViewById(R.id.switchHudMediaCover);
        switchHudGear = activity.findViewById(R.id.switchHudGear);
        editorContainer = activity.findViewById(R.id.hud_editor_container);
        tvHudStatus = activity.findViewById(R.id.tvHudStatus);

        appendStatus("初始化完成 (Remote Mode)。等待开关操作。");

        timeWidgetContainer = activity.findViewById(R.id.time_widget_container);
        tvTime = activity.findViewById(R.id.tv_time);
        resizeHandle = activity.findViewById(R.id.iv_resize_handle);

        whiteWidgetContainer = activity.findViewById(R.id.white_widget_container);
        viewWhiteRect = activity.findViewById(R.id.view_white_rect);
        cardWhiteRect = activity.findViewById(R.id.card_white_rect);
        resizeHandleWhite = activity.findViewById(R.id.iv_resize_white);

        mediaCoverWidgetContainer = activity.findViewById(R.id.media_cover_widget_container);
        viewMediaCover = activity.findViewById(R.id.view_media_cover);
        cardMediaCover = activity.findViewById(R.id.card_media_cover);
        resizeHandleMediaCover = activity.findViewById(R.id.iv_resize_media_cover);

        gearWidgetContainer = activity.findViewById(R.id.gear_widget_container);
        tvGear = activity.findViewById(R.id.tv_gear);
        resizeHandleGear = activity.findViewById(R.id.iv_resize_handle_gear);

        guidelineView = activity.findViewById(R.id.guideline_overlay);

        MaterialButton btnLockEditor = activity.findViewById(R.id.btnLockEditor);
        isEditorLocked = prefs.getBoolean("hud_editor_locked", false);
        updateLockUI(btnLockEditor);
        btnLockEditor.setOnClickListener(v -> toggleEditorLock(btnLockEditor));

        switchHud.setChecked(prefs.getBoolean("hud_enabled", false));
        updateEditorVisibility(switchHud.isChecked());

        switchHud.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("hud_enabled", isChecked).apply();
            updateEditorVisibility(isChecked);
            if (isChecked) {
                checkAndShowHud();
            } else {
                dismissHud();
            }
        });

        switchHudTime.setOnCheckedChangeListener((v, isChecked) -> {
            prefs.edit().putBoolean("time_widget_enabled", isChecked).apply();
            updateWidgetVisibility();
            syncToHud();
            appendStatus("时间组件: " + (isChecked ? "开启" : "关闭"));
        });
        switchHudImage.setOnCheckedChangeListener((v, isChecked) -> {
            prefs.edit().putBoolean("image_widget_enabled", isChecked).apply();
            updateWidgetVisibility();
            syncToHud();
            appendStatus("图片组件: " + (isChecked ? "开启" : "关闭"));
        });
        switchHudMediaCover.setOnCheckedChangeListener((v, isChecked) -> {
            prefs.edit().putBoolean("media_cover_widget_enabled", isChecked).apply();
            updateWidgetVisibility();
            syncToHud();
            appendStatus("封面组件: " + (isChecked ? "开启" : "关闭"));
        });
        switchHudGear.setOnCheckedChangeListener((v, isChecked) -> {
            prefs.edit().putBoolean("gear_widget_enabled", isChecked).apply();
            updateWidgetVisibility();
            syncToHud();
            appendStatus("档位组件: " + (isChecked ? "开启" : "关闭"));
        });

        // Init visibility
        switchHudTime.setChecked(prefs.getBoolean("time_widget_enabled", true));
        switchHudImage.setChecked(prefs.getBoolean("image_widget_enabled", true));
        switchHudTime.setChecked(prefs.getBoolean("time_widget_enabled", true));
        switchHudImage.setChecked(prefs.getBoolean("image_widget_enabled", true));
        switchHudMediaCover.setChecked(prefs.getBoolean("media_cover_widget_enabled", true));
        switchHudGear.setChecked(prefs.getBoolean("gear_widget_enabled", true));

        editorContainer.post(() -> {
            updateWidgetVisibility();
        });

        activity.findViewById(R.id.btnResetHud).setOnClickListener(v -> resetLayout());

        setupTouchListeners();
        setupLayoutChangeListener();
        startTimeUpdate();

        editorContainer.post(this::restoreWidgetStates);

        if (switchHud.isChecked()) {
            editorContainer.post(this::checkAndShowHud);
        }
        editorContainer.post(this::updateResizeHandlePosition);
        editorContainer.post(this::updateResizeHandlePositionGear);
        editorContainer.post(this::setupTouchDelegate);
    }

    private void setupTouchDelegate() {
        if (editorContainer == null)
            return;
        View parent = (View) editorContainer.getParent();
        if (parent == null)
            return;
        View grandfather = (View) parent.getParent();
        if (grandfather == null)
            return;

        grandfather.post(() -> {
            android.graphics.Rect editorRect = new android.graphics.Rect();
            int[] grandLoc = new int[2];
            grandfather.getLocationOnScreen(grandLoc);
            int[] editorLoc = new int[2];
            editorContainer.getLocationOnScreen(editorLoc);
            editorRect.set(0, 0, editorContainer.getWidth(), editorContainer.getHeight());
            editorRect.offset(editorLoc[0] - grandLoc[0], editorLoc[1] - grandLoc[1]);
            int expandAmount = (int) android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP, 60,
                    activity.getResources().getDisplayMetrics());
            editorRect.inset(-expandAmount, -expandAmount);
            grandfather.setTouchDelegate(new android.view.TouchDelegate(editorRect, editorContainer));
        });
    }

    private void updateEditorVisibility(boolean enabled) {
        if (hudSwitchPanel != null) {
            hudSwitchPanel.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
    }

    private void updateWidgetVisibility() {
        boolean isTimeEnabled = switchHudTime.isChecked();
        if (timeWidgetContainer != null) {
            timeWidgetContainer.setVisibility(isTimeEnabled ? View.VISIBLE : View.GONE);
        }
        if (resizeHandle != null) {
            if (isTimeEnabled && tvTime != null) {
                updateResizeHandlePosition();
                resizeHandle.setVisibility(View.VISIBLE);
            } else {
                resizeHandle.setVisibility(View.GONE);
            }
        }
        if (whiteWidgetContainer != null) {
            whiteWidgetContainer.setVisibility(switchHudImage.isChecked() ? View.VISIBLE : View.GONE);
        }
        if (mediaCoverWidgetContainer != null) {
            mediaCoverWidgetContainer.setVisibility(switchHudMediaCover.isChecked() ? View.VISIBLE : View.GONE);
        }
        boolean isGearEnabled = switchHudGear.isChecked();
        if (gearWidgetContainer != null) {
            gearWidgetContainer.setVisibility(isGearEnabled ? View.VISIBLE : View.GONE);
        }
        if (resizeHandleGear != null) {
            if (isGearEnabled && tvGear != null) {
                updateResizeHandlePositionGear();
                resizeHandleGear.setVisibility(View.VISIBLE);
            } else {
                resizeHandleGear.setVisibility(View.GONE);
            }
        }
    }

    public void updateDebugVisibility(boolean isDebug) {
        if (tvHudStatus != null) {
            tvHudStatus.setVisibility(isDebug ? View.VISIBLE : View.GONE);
        }
    }

    private void resetLayout() {
        timeWidgetContainer.setX(50);
        timeWidgetContainer.setY(50);
        tvTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 40);

        whiteWidgetContainer.setX(200);
        whiteWidgetContainer.setY(50);
        ViewGroup.LayoutParams p = viewWhiteRect.getLayoutParams();
        p.width = 300;
        p.height = 300;
        viewWhiteRect.setLayoutParams(p);

        if (cardWhiteRect != null) {
            cardWhiteRect.setRadius(300 * 0.05f);
        }

        if (mediaCoverWidgetContainer != null) {
            mediaCoverWidgetContainer.setX(400);
            mediaCoverWidgetContainer.setY(50);
            ViewGroup.LayoutParams cp = viewMediaCover.getLayoutParams();
            cp.width = 150;
            cp.height = 150;
            viewMediaCover.setLayoutParams(cp);
            if (cardMediaCover != null) {
                cardMediaCover.setRadius(150 * 0.05f);
            }
        }

        if (gearWidgetContainer != null) {
            gearWidgetContainer.setX(100);
            gearWidgetContainer.setY(50);
            tvGear.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 40);
        }

        syncToHud();
        saveWidgetState();
    }

    private void checkAndShowHud() {
        activity.sendBroadcast(new Intent("cn.oneostool.plus.ACTION_HUD_VISIBILITY_CHANGED"));
        appendStatus("请求 Service 显示/更新 HUD");
    }

    private void dismissHud() {
        activity.sendBroadcast(new Intent("cn.oneostool.plus.ACTION_HUD_VISIBILITY_CHANGED"));
        appendStatus("请求 Service 刷新可见性");
    }

    private void appendStatus(String msg) {
        if (tvHudStatus != null) {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            tvHudStatus.setText(String.format("[%s] %s\n%s", time, msg, tvHudStatus.getText()));
        }
    }

    private void syncToHud() {
        if (editorContainer == null)
            return;

        float w = editorContainer.getWidth();
        float h = editorContainer.getHeight();

        if (w <= 0 || h <= 0 || !isStateRestored) {
            return;
        }

        float xRatio = timeWidgetContainer.getX() / w;
        float yRatio = timeWidgetContainer.getY() / h;
        float textSize = tvTime.getTextSize();
        float scaleFactor = (float) 186 / h;

        float wxRatio = whiteWidgetContainer.getX() / w;
        float wyRatio = whiteWidgetContainer.getY() / h;

        float imageW = viewWhiteRect.getWidth();
        float imageH = viewWhiteRect.getHeight();
        ViewGroup.LayoutParams imageParams = viewWhiteRect.getLayoutParams();
        if (imageParams != null && imageParams.width > 0) {
            imageW = imageParams.width;
        }
        if (imageParams != null && imageParams.height > 0) {
            imageH = imageParams.height;
        }

        float wRatio = imageW / w;
        float hRatio = imageH / h;

        Intent intent = new Intent("cn.oneostool.plus.ACTION_HUD_CONFIG_CHANGED");
        intent.putExtra("time_x_ratio", xRatio);
        intent.putExtra("time_y_ratio", yRatio);
        intent.putExtra("time_text_size", textSize);
        intent.putExtra("scale_factor", scaleFactor);
        intent.putExtra("white_x_ratio", wxRatio);
        intent.putExtra("white_y_ratio", wyRatio);
        intent.putExtra("white_w_ratio", wRatio);
        intent.putExtra("white_w_ratio", wRatio);
        intent.putExtra("white_h_ratio", hRatio);

        // Media Cover
        if (mediaCoverWidgetContainer != null) {
            float cxRatio = mediaCoverWidgetContainer.getX() / w;
            float cyRatio = mediaCoverWidgetContainer.getY() / h;

            float coverW = viewMediaCover.getWidth();
            float coverH = viewMediaCover.getHeight();
            ViewGroup.LayoutParams cp = viewMediaCover.getLayoutParams();
            if (cp != null && cp.width > 0)
                coverW = cp.width;
            if (cp != null && cp.height > 0)
                coverH = cp.height;

            float cwRatio = coverW / w;
            float chRatio = coverH / h;

            intent.putExtra("cover_x_ratio", cxRatio);
            intent.putExtra("cover_y_ratio", cyRatio);
            intent.putExtra("cover_w_ratio", cwRatio);
            intent.putExtra("cover_h_ratio", chRatio);
            intent.putExtra("cover_w_ratio", cwRatio);
            intent.putExtra("cover_h_ratio", chRatio);
        }

        // Gear
        if (gearWidgetContainer != null && tvGear != null) {
            float gxRatio = gearWidgetContainer.getX() / w;
            float gyRatio = gearWidgetContainer.getY() / h;
            float gTextSize = tvGear.getTextSize();

            intent.putExtra("gear_x_ratio", gxRatio);
            intent.putExtra("gear_y_ratio", gyRatio);
            intent.putExtra("gear_text_size", gTextSize);
        }

        activity.sendBroadcast(intent);
    }

    private void startTimeUpdate() {
        timeRunnable = new Runnable() {
            @Override
            public void run() {
                tvTime.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
                syncToHud(); // Live sync
                updateWidgetVisibility();
                if (tvTime.getWidth() == 0 && timeWidgetContainer != null) {
                    timeWidgetContainer.requestLayout();
                    if (editorContainer != null)
                        editorContainer.requestLayout();
                }
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(timeRunnable);
    }

    private boolean ensureAbsoluteAlignment(View v) {
        ViewGroup.LayoutParams vp = v.getLayoutParams();
        if (vp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) vp;
            boolean isCentered = (lp.gravity & android.view.Gravity.CENTER) == android.view.Gravity.CENTER
                    || (lp.gravity & android.view.Gravity.CENTER_HORIZONTAL) == android.view.Gravity.CENTER_HORIZONTAL
                    || (lp.gravity & android.view.Gravity.CENTER_VERTICAL) == android.view.Gravity.CENTER_VERTICAL;
            boolean hasMargins = lp.leftMargin != 0 || lp.topMargin != 0 || lp.rightMargin != 0 || lp.bottomMargin != 0;

            if (isCentered || hasMargins) {
                float currentX = v.getX();
                float currentY = v.getY();
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                lp.setMargins(0, 0, 0, 0);
                v.setLayoutParams(lp);
                int w = v.getWidth();
                int h = v.getHeight();
                v.setLeft(0);
                v.setTop(0);
                v.setRight(w);
                v.setBottom(h);
                v.setX(currentX);
                v.setY(currentY);
                return true;
            }
        }
        return false;
    }

    private void updateResizeHandlePosition() {
        if (tvTime == null || resizeHandle == null || activity == null)
            return;
        float inkRight = tvTime.getWidth();
        float inkBottom = tvTime.getHeight();
        float marginPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 20,
                activity.getResources().getDisplayMetrics());
        float handleW = resizeHandle.getWidth();
        float handleH = resizeHandle.getHeight();
        if (handleW == 0)
            handleW = marginPx * 2;
        float containerX = timeWidgetContainer.getX();
        float containerY = timeWidgetContainer.getY();
        resizeHandle.setX(containerX + inkRight + marginPx - handleW);
        resizeHandle.setY(containerY + inkBottom + marginPx - handleH);
    }

    private void updateResizeHandlePositionGear() {
        if (tvGear == null || resizeHandleGear == null || activity == null)
            return;
        float inkRight = tvGear.getWidth();
        float inkBottom = tvGear.getHeight();
        float marginPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 20,
                activity.getResources().getDisplayMetrics());
        float handleW = resizeHandleGear.getWidth();
        float handleH = resizeHandleGear.getHeight();
        if (handleW == 0)
            handleW = marginPx * 2;
        float containerX = gearWidgetContainer.getX();
        float containerY = gearWidgetContainer.getY();
        resizeHandleGear.setX(containerX + inkRight + marginPx - handleW);
        resizeHandleGear.setY(containerY + inkBottom + marginPx - handleH);
    }

    private void setupTouchListeners() {
        timeWidgetContainer.setOnTouchListener((v, event) -> {
            if (isEditorLocked)
                return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    ensureAbsoluteAlignment(v);
                    dX = v.getX() - event.getRawX();
                    dY = v.getY() - event.getRawY();
                    int inkLeft = 0;
                    int inkRight = tvTime.getWidth();
                    int inkTop = 0;
                    int inkBottom = tvTime.getHeight();
                    float parentW = editorContainer.getWidth();
                    float parentH = editorContainer.getHeight();
                    minX = -inkLeft;
                    maxX = parentW - inkRight;
                    minY = -inkTop;
                    maxY = parentH - inkBottom;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float newX = event.getRawX() + dX;
                    float newY = event.getRawY() + dY;
                    if (newX < minX)
                        newX = minX;
                    if (newX > maxX)
                        newX = maxX;
                    if (newY < minY)
                        newY = minY;
                    if (newY > maxY)
                        newY = maxY;
                    float[] proposed = new float[] { newX, newY };
                    checkAlignment(v, proposed);
                    newX = proposed[0];
                    newY = proposed[1];
                    v.setX(newX);
                    v.setY(newY);
                    updateResizeHandlePosition();
                    syncToHud();
                    break;
                case MotionEvent.ACTION_UP:
                    if (guidelineView != null)
                        guidelineView.clear();
                    saveWidgetState();
                    break;
            }
            return true;
        });

        resizeHandle.setOnTouchListener((v, event) -> {
            if (isEditorLocked)
                return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    isResizing = true;
                    initialTextSize = tvTime.getTextSize();
                    int[] loc = new int[2];
                    timeWidgetContainer.getLocationOnScreen(loc);
                    float inkTopLeftX = loc[0] + tvTime.getLeft();
                    float inkTopLeftY = loc[1] + tvTime.getTop();
                    anchorInkX = inkTopLeftX;
                    anchorInkY = inkTopLeftY;
                    originRawX = event.getRawX();
                    originRawY = event.getRawY();
                    initialDist = (float) Math.hypot(originRawX - inkTopLeftX, originRawY - inkTopLeftY);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isResizing) {
                        float currentDist = (float) Math.hypot(event.getRawX() - anchorInkX,
                                event.getRawY() - anchorInkY);
                        if (initialDist < 1)
                            initialDist = 1;
                        float scale = currentDist / initialDist;
                        float desiredSize = initialTextSize * scale;
                        float minSizePx = android.util.TypedValue.applyDimension(
                                android.util.TypedValue.COMPLEX_UNIT_SP, 20,
                                activity.getResources().getDisplayMetrics());
                        if (desiredSize < minSizePx)
                            desiredSize = minSizePx;
                        if (desiredSize > 1000)
                            desiredSize = 1000;
                        float parentW = editorContainer.getWidth();
                        int[] parentLoc = new int[2];
                        editorContainer.getLocationOnScreen(parentLoc);
                        float anchorRelX = anchorInkX - parentLoc[0];
                        float anchorRelY = anchorInkY - parentLoc[1];
                        float marginPx = 0;
                        float limitW = parentW - anchorRelX - marginPx;
                        float limitH = editorContainer.getHeight() - anchorRelY - marginPx;
                        android.text.TextPaint tempPaint = new android.text.TextPaint(tvTime.getPaint());
                        tempPaint.setTextSize(desiredSize);
                        String textStr = tvTime.getText().toString();
                        android.graphics.Rect pBounds = new android.graphics.Rect();
                        tempPaint.getTextBounds(textStr, 0, textStr.length(), pBounds);
                        float visualW = pBounds.width();
                        float visualH = pBounds.height();
                        if (visualW > limitW && limitW > 1) {
                            desiredSize *= (limitW / visualW);
                            visualH *= (limitW / visualW);
                        }
                        if (visualH > limitH && limitH > 1) {
                            desiredSize *= (limitH / visualH);
                        }
                        tvTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, desiredSize);
                        timeWidgetContainer.setX(anchorRelX);
                        timeWidgetContainer.setY(anchorRelY);
                        updateResizeHandlePosition();
                        syncToHud();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    isResizing = false;
                    saveWidgetState();
                    break;
            }
            return true;
        });

        whiteWidgetContainer.setOnTouchListener((v, event) -> {
            if (isEditorLocked)
                return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    ensureAbsoluteAlignment(v);
                    float x = event.getX();
                    float y = event.getY();
                    if (x > v.getWidth() - resizeHandleWhite.getWidth() * 1.2
                            && y > v.getHeight() - resizeHandleWhite.getHeight() * 1.2) {
                        isResizing = true;
                        initialWhiteWidth = viewWhiteRect.getWidth();
                        initialWhiteHeight = viewWhiteRect.getHeight();
                        deltaW = v.getWidth() - initialWhiteWidth;
                        deltaH = v.getHeight() - initialWhiteHeight;
                        float density = activity.getResources().getDisplayMetrics().density;
                        float minDelta = 15 * density;
                        if (deltaW < minDelta)
                            deltaW = minDelta;
                        if (deltaH < minDelta)
                            deltaH = minDelta;
                        originRawX = event.getRawX() - event.getX();
                        originRawY = event.getRawY() - event.getY();
                        initialDist = (float) Math.hypot(event.getRawX() - originRawX, event.getRawY() - originRawY);
                    } else {
                        isResizing = false;
                        dX = v.getX() - event.getRawX();
                        dY = v.getY() - event.getRawY();
                        float imageW = viewWhiteRect.getWidth();
                        float imageH = viewWhiteRect.getHeight();
                        float parentW = editorContainer.getWidth();
                        float parentH = editorContainer.getHeight();
                        minX = 0;
                        maxX = parentW - imageW;
                        minY = 0;
                        maxY = parentH - imageH;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isResizing) {
                        float currentDist = (float) Math.hypot(event.getRawX() - originRawX,
                                event.getRawY() - originRawY);
                        if (initialDist < 1)
                            initialDist = 1;
                        float rawScale = currentDist / initialDist;
                        float aspectRatio = initialWhiteWidth / initialWhiteHeight;
                        float parentW = editorContainer.getWidth();
                        float parentH = editorContainer.getHeight();
                        float currentX = v.getX();
                        float currentY = v.getY();
                        float maxAllowedW = parentW - currentX;
                        float maxAllowedH = parentH - currentY;
                        float maxScaleW = maxAllowedW / initialWhiteWidth;
                        float maxScaleH = maxAllowedH / initialWhiteHeight;
                        float finalScale = rawScale;
                        if (finalScale > maxScaleW)
                            finalScale = maxScaleW;
                        if (finalScale > maxScaleH)
                            finalScale = maxScaleH;
                        float finalW = initialWhiteWidth * finalScale;
                        float finalH = initialWhiteHeight * finalScale;
                        if (finalW < 50) {
                            finalW = 50;
                            finalH = finalW / aspectRatio;
                        }
                        ViewGroup.LayoutParams params = viewWhiteRect.getLayoutParams();
                        params.width = (int) finalW;
                        params.height = (int) finalH;
                        viewWhiteRect.setLayoutParams(params);
                        if (cardWhiteRect != null) {
                            cardWhiteRect.setRadius(finalW * 0.05f);
                        }
                        ViewGroup.LayoutParams cp = v.getLayoutParams();
                        cp.width = (int) (finalW + deltaW);
                        cp.height = (int) (finalH + deltaH);
                        v.setLayoutParams(cp);
                    } else {
                        float newX = event.getRawX() + dX;
                        float newY = event.getRawY() + dY;
                        if (newX < minX)
                            newX = minX;
                        if (newX > maxX)
                            newX = maxX;
                        if (newY < minY)
                            newY = minY;
                        if (newY > maxY)
                            newY = maxY;
                        float[] proposed = new float[] { newX, newY };
                        checkAlignment(v, proposed);
                        newX = proposed[0];
                        newY = proposed[1];
                        v.setX(newX);
                        v.setY(newY);
                    }
                    syncToHud();
                    break;
                case MotionEvent.ACTION_UP:
                    isResizing = false;
                    if (guidelineView != null)
                        guidelineView.clear();
                    saveWidgetState();
                    break;
            }
            return true;
        });

        setupMediaCoverListeners();
        setupGearListeners();
    }

    private void setupGearListeners() {
        if (gearWidgetContainer == null || resizeHandleGear == null)
            return;

        gearWidgetContainer.setOnTouchListener((v, event) -> {
            if (isEditorLocked)
                return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    ensureAbsoluteAlignment(v);
                    dX = v.getX() - event.getRawX();
                    dY = v.getY() - event.getRawY();
                    int inkLeft = 0;
                    int inkRight = tvGear.getWidth();
                    int inkTop = 0;
                    int inkBottom = tvGear.getHeight();
                    float parentW = editorContainer.getWidth();
                    float parentH = editorContainer.getHeight();
                    minX = -inkLeft;
                    maxX = parentW - inkRight;
                    minY = -inkTop;
                    maxY = parentH - inkBottom;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float newX = event.getRawX() + dX;
                    float newY = event.getRawY() + dY;
                    if (newX < minX)
                        newX = minX;
                    if (newX > maxX)
                        newX = maxX;
                    if (newY < minY)
                        newY = minY;
                    if (newY > maxY)
                        newY = maxY;
                    float[] proposed = new float[] { newX, newY };
                    checkAlignment(v, proposed);
                    newX = proposed[0];
                    newY = proposed[1];
                    v.setX(newX);
                    v.setY(newY);
                    updateResizeHandlePositionGear();
                    syncToHud();
                    break;
                case MotionEvent.ACTION_UP:
                    if (guidelineView != null)
                        guidelineView.clear();
                    saveWidgetState();
                    break;
            }
            return true;
        });

        resizeHandleGear.setOnTouchListener((v, event) -> {
            if (isEditorLocked)
                return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    isResizing = true;
                    initialTextSize = tvGear.getTextSize();
                    int[] loc = new int[2];
                    gearWidgetContainer.getLocationOnScreen(loc);
                    float inkTopLeftX = loc[0] + tvGear.getLeft();
                    float inkTopLeftY = loc[1] + tvGear.getTop();
                    anchorInkX = inkTopLeftX;
                    anchorInkY = inkTopLeftY;
                    originRawX = event.getRawX();
                    originRawY = event.getRawY();
                    initialDist = (float) Math.hypot(originRawX - inkTopLeftX, originRawY - inkTopLeftY);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isResizing) {
                        float currentDist = (float) Math.hypot(event.getRawX() - anchorInkX,
                                event.getRawY() - anchorInkY);
                        if (initialDist < 1)
                            initialDist = 1;
                        float scale = currentDist / initialDist;
                        float desiredSize = initialTextSize * scale;
                        float minSizePx = android.util.TypedValue.applyDimension(
                                android.util.TypedValue.COMPLEX_UNIT_SP, 20,
                                activity.getResources().getDisplayMetrics());
                        if (desiredSize < minSizePx)
                            desiredSize = minSizePx;
                        if (desiredSize > 1000)
                            desiredSize = 1000;

                        float parentW = editorContainer.getWidth();
                        int[] parentLoc = new int[2];
                        editorContainer.getLocationOnScreen(parentLoc);
                        float anchorRelX = anchorInkX - parentLoc[0];
                        float anchorRelY = anchorInkY - parentLoc[1];
                        float marginPx = 0;
                        float limitW = parentW - anchorRelX - marginPx;
                        float limitH = editorContainer.getHeight() - anchorRelY - marginPx;

                        android.text.TextPaint tempPaint = new android.text.TextPaint(tvGear.getPaint());
                        tempPaint.setTextSize(desiredSize);
                        String textStr = tvGear.getText().toString();
                        android.graphics.Rect pBounds = new android.graphics.Rect();
                        tempPaint.getTextBounds(textStr, 0, textStr.length(), pBounds);
                        float visualW = pBounds.width();
                        float visualH = pBounds.height();
                        if (visualW > limitW && limitW > 1) {
                            desiredSize *= (limitW / visualW);
                            visualH *= (limitW / visualW);
                        }
                        if (visualH > limitH && limitH > 1) {
                            desiredSize *= (limitH / visualH);
                        }
                        tvGear.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, desiredSize);
                        gearWidgetContainer.setX(anchorRelX);
                        gearWidgetContainer.setY(anchorRelY);
                        updateResizeHandlePositionGear();
                        syncToHud();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    isResizing = false;
                    saveWidgetState();
                    break;
            }
            return true;
        });
    }

    private void setupMediaCoverListeners() {
        mediaCoverWidgetContainer.setOnTouchListener((v, event) -> {
            if (isEditorLocked)
                return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    ensureAbsoluteAlignment(v);
                    float x = event.getX();
                    float y = event.getY();
                    if (x > v.getWidth() - resizeHandleMediaCover.getWidth() * 1.2
                            && y > v.getHeight() - resizeHandleMediaCover.getHeight() * 1.2) {
                        isResizing = true;
                        initialCoverWidth = viewMediaCover.getWidth();
                        initialCoverHeight = viewMediaCover.getHeight();
                        deltaW = v.getWidth() - initialCoverWidth;
                        deltaH = v.getHeight() - initialCoverHeight;
                        float density = activity.getResources().getDisplayMetrics().density;
                        float minDelta = 15 * density;
                        if (deltaW < minDelta)
                            deltaW = minDelta;
                        if (deltaH < minDelta)
                            deltaH = minDelta;
                        originRawX = event.getRawX() - event.getX();
                        originRawY = event.getRawY() - event.getY();
                        initialDist = (float) Math.hypot(event.getRawX() - originRawX, event.getRawY() - originRawY);
                    } else {
                        isResizing = false;
                        dX = v.getX() - event.getRawX();
                        dY = v.getY() - event.getRawY();
                        float imageW = viewMediaCover.getWidth();
                        float imageH = viewMediaCover.getHeight();
                        float parentW = editorContainer.getWidth();
                        float parentH = editorContainer.getHeight();
                        minX = 0;
                        maxX = parentW - imageW;
                        minY = 0;
                        maxY = parentH - imageH;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isResizing) {
                        float currentDist = (float) Math.hypot(event.getRawX() - originRawX,
                                event.getRawY() - originRawY);
                        if (initialDist < 1)
                            initialDist = 1;
                        float rawScale = currentDist / initialDist;
                        float aspectRatio = initialCoverWidth / initialCoverHeight;

                        // Boundary constraints (same as white widget)
                        float parentW = editorContainer.getWidth();
                        float parentH = editorContainer.getHeight();
                        float currentX = v.getX();
                        float currentY = v.getY();
                        float maxAllowedW = parentW - currentX;
                        float maxAllowedH = parentH - currentY;
                        float maxScaleW = maxAllowedW / initialCoverWidth;
                        float maxScaleH = maxAllowedH / initialCoverHeight;
                        float finalScale = rawScale;
                        if (finalScale > maxScaleW)
                            finalScale = maxScaleW;
                        if (finalScale > maxScaleH)
                            finalScale = maxScaleH;

                        float finalW = initialCoverWidth * finalScale;
                        float finalH = initialCoverHeight * finalScale;
                        if (finalW < 50) {
                            finalW = 50;
                            finalH = finalW / aspectRatio;
                        }

                        ViewGroup.LayoutParams params = viewMediaCover.getLayoutParams();
                        params.width = (int) finalW;
                        params.height = (int) finalH;
                        viewMediaCover.setLayoutParams(params);
                        if (cardMediaCover != null) {
                            cardMediaCover.setRadius(finalW * 0.05f);
                        }
                        ViewGroup.LayoutParams cp = v.getLayoutParams();
                        cp.width = (int) (finalW + deltaW);
                        cp.height = (int) (finalH + deltaH);
                        v.setLayoutParams(cp);
                    } else {
                        float newX = event.getRawX() + dX;
                        float newY = event.getRawY() + dY;
                        if (newX < minX)
                            newX = minX;
                        if (newX > maxX)
                            newX = maxX;
                        if (newY < minY)
                            newY = minY;
                        if (newY > maxY)
                            newY = maxY;
                        float[] proposed = new float[] { newX, newY };
                        checkAlignment(v, proposed);
                        v.setX(proposed[0]);
                        v.setY(proposed[1]);
                    }
                    syncToHud();
                    break;
                case MotionEvent.ACTION_UP:
                    isResizing = false;
                    if (guidelineView != null)
                        guidelineView.clear();
                    saveWidgetState();
                    break;
            }
            return true;
        });
    }

    private void setupLayoutChangeListener() {
        editorContainer
                .addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    float currentW = v.getWidth();
                    if (currentW == 0)
                        return;
                    if (lastEditorWidth == 0) {
                        lastEditorWidth = currentW;
                        restoreWidgetStates();
                        updateWidgetVisibility();
                        setupTouchDelegate();
                        return;
                    }
                    if (Math.abs(currentW - lastEditorWidth) > 10) {
                        restoreWidgetStates();
                        lastEditorWidth = currentW;
                        setupTouchDelegate();
                    }
                });
    }

    private void saveWidgetState() {
        float w = editorContainer.getWidth();
        float h = editorContainer.getHeight();
        if (w == 0 || h == 0)
            return;
        SharedPreferences.Editor ed = prefs.edit();
        ed.putFloat("time_x_ratio", timeWidgetContainer.getX() / w);
        ed.putFloat("time_y_ratio", timeWidgetContainer.getY() / h);
        ed.putFloat("time_text_size", tvTime.getTextSize());
        ed.putFloat("white_x_ratio", whiteWidgetContainer.getX() / w);
        ed.putFloat("white_y_ratio", whiteWidgetContainer.getY() / h);
        ed.putInt("white_width", viewWhiteRect.getWidth());
        ed.putInt("white_height", viewWhiteRect.getHeight());
        ed.putFloat("saved_editor_width", w);
        ed.putFloat("saved_editor_height", h);

        // Media Cover State
        if (mediaCoverWidgetContainer != null) {
            ed.putFloat("cover_x_ratio", mediaCoverWidgetContainer.getX() / w);
            ed.putFloat("cover_y_ratio", mediaCoverWidgetContainer.getY() / h);
            ed.putInt("cover_width", viewMediaCover.getWidth());
            ed.putInt("cover_height", viewMediaCover.getHeight());
        }

        // Gear State
        if (gearWidgetContainer != null) {
            ed.putFloat("gear_x_ratio", gearWidgetContainer.getX() / w);
            ed.putFloat("gear_y_ratio", gearWidgetContainer.getY() / h);
            ed.putFloat("gear_text_size", tvGear.getTextSize());
        }

        ed.apply();

        // Also sync to HUD via service
        syncToHud();

    }

    private void toggleEditorLock(MaterialButton btn) {
        isEditorLocked = !isEditorLocked;
        prefs.edit().putBoolean("hud_editor_locked", isEditorLocked).apply();
        if (isEditorLocked) {
            saveWidgetState();
        }
        updateLockUI(btn);
    }

    private void updateLockUI(MaterialButton btn) {
        if (btn == null)
            return;
        if (isEditorLocked) {
            btn.setText("解锁编辑");
            btn.setIconResource(R.drawable.ic_lock_open);
        } else {
            btn.setText("保存并锁定");
            btn.setIconResource(R.drawable.ic_lock_outline);
        }
    }

    private static final int SNAP_THRESHOLD_DP = 20;
    private static final int SNAP_LOCK_DP = 1;

    private void checkAlignment(View me, float[] proposedXY) {
        if (guidelineView == null || editorContainer == null)
            return;
        guidelineView.clear();
        float density = activity.getResources().getDisplayMetrics().density;
        float distinctThreshold = SNAP_THRESHOLD_DP * density;
        float snapLock = SNAP_LOCK_DP * density;

        android.graphics.RectF myBounds = getVisualBounds(me, proposedXY[0], proposedXY[1]);
        float myLeft = myBounds.left;
        float myRight = myBounds.right;
        float myCenterX = myBounds.centerX();
        float myTop = myBounds.top;
        float myBottom = myBounds.bottom;
        float myCenterY = myBounds.centerY();

        List<Float> targetX = new ArrayList<>();
        List<Float> targetY = new ArrayList<>();

        float parentW = editorContainer.getWidth();
        float parentH = editorContainer.getHeight();
        targetX.add(parentW / 2f);
        targetY.add(parentH / 2f);

        View other = (me == timeWidgetContainer) ? whiteWidgetContainer : timeWidgetContainer;
        if (other != null && other.getVisibility() == View.VISIBLE) {
            android.graphics.RectF otherBounds = getVisualBounds(other, other.getX(), other.getY());
            targetX.add(otherBounds.left);
            targetX.add(otherBounds.right);
            targetX.add(otherBounds.centerX());
            targetY.add(otherBounds.top);
            targetY.add(otherBounds.bottom);
            targetY.add(otherBounds.centerY());
            targetY.add(otherBounds.centerY());
        }

        View cover = mediaCoverWidgetContainer;
        if (cover != null && cover.getVisibility() == View.VISIBLE && cover != me) {
            android.graphics.RectF coverBounds = getVisualBounds(cover, cover.getX(), cover.getY());
            targetX.add(coverBounds.left);
            targetX.add(coverBounds.right);
            targetX.add(coverBounds.centerX());
            targetY.add(coverBounds.top);
            targetY.add(coverBounds.bottom);
            targetY.add(coverBounds.centerY());
        }

        float bestDx = Float.MAX_VALUE;
        float targetLineX = 0;
        for (float tx : targetX) {
            float d = tx - myLeft;
            if (Math.abs(d) < Math.abs(bestDx)) {
                bestDx = d;
                targetLineX = tx;
            }
            d = tx - myRight;
            if (Math.abs(d) < Math.abs(bestDx)) {
                bestDx = d;
                targetLineX = tx;
            }
            d = tx - myCenterX;
            if (Math.abs(d) < Math.abs(bestDx)) {
                bestDx = d;
                targetLineX = tx;
            }
        }
        boolean snappedX = false;
        if (Math.abs(bestDx) < distinctThreshold) {
            if (Math.abs(bestDx) < snapLock) {
                proposedXY[0] += bestDx;
                snappedX = true;
            }
            guidelineView.addLine(GuidelineView.TYPE_VERTICAL, targetLineX, 0, parentH,
                    snappedX ? GuidelineView.STYLE_SNAPPED : GuidelineView.STYLE_APPROACH);
        }

        float bestDy = Float.MAX_VALUE;
        float targetLineY = 0;
        for (float ty : targetY) {
            float d = ty - myTop;
            if (Math.abs(d) < Math.abs(bestDy)) {
                bestDy = d;
                targetLineY = ty;
            }
            d = ty - myBottom;
            if (Math.abs(d) < Math.abs(bestDy)) {
                bestDy = d;
                targetLineY = ty;
            }
            d = ty - myCenterY;
            if (Math.abs(d) < Math.abs(bestDy)) {
                bestDy = d;
                targetLineY = ty;
            }
        }
        boolean snappedY = false;
        if (Math.abs(bestDy) < distinctThreshold) {
            if (Math.abs(bestDy) < snapLock) {
                proposedXY[1] += bestDy;
                snappedY = true;
            }
            guidelineView.addLine(GuidelineView.TYPE_HORIZONTAL, targetLineY, 0, parentW,
                    snappedY ? GuidelineView.STYLE_SNAPPED : GuidelineView.STYLE_APPROACH);
        }
    }

    private android.graphics.RectF getVisualBounds(View v, float x, float y) {
        android.graphics.RectF bounds = new android.graphics.RectF();
        if (v == timeWidgetContainer) {
            float tvLeft = tvTime.getLeft();
            float tvTop = tvTime.getTop();
            float w = tvTime.getWidth();
            float h = tvTime.getHeight();
            bounds.left = x + tvLeft;
            bounds.top = y + tvTop;
            bounds.right = x + tvLeft + w;
            bounds.bottom = y + tvTop + h;
        } else if (v == whiteWidgetContainer) {
            float margin = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 15,
                    activity.getResources().getDisplayMetrics());
            bounds.left = x;
            bounds.top = y;
            bounds.right = x + v.getWidth() - margin;
            bounds.bottom = y + v.getHeight() - margin;
        } else if (v == mediaCoverWidgetContainer) {
            float margin = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 15,
                    activity.getResources().getDisplayMetrics());
            bounds.left = x;
            bounds.top = y;
            bounds.right = x + v.getWidth() - margin;
            bounds.bottom = y + v.getHeight() - margin;
        } else {
            bounds.left = x;
            bounds.top = y;
            bounds.right = x + v.getWidth();
            bounds.bottom = y + v.getHeight();
        }
        return bounds;
    }

    private void restoreWidgetStates() {
        if (editorContainer == null)
            return;
        float w = editorContainer.getWidth();
        float h = editorContainer.getHeight();
        if (w == 0 || h == 0)
            return;
        float savedW = prefs.getFloat("saved_editor_width", 0);
        float scale = (savedW > 0) ? w / savedW : 1.0f;

        if (prefs.contains("time_x_ratio")) {
            ensureAbsoluteAlignment(timeWidgetContainer);
            timeWidgetContainer.setX(prefs.getFloat("time_x_ratio", 0) * w);
            timeWidgetContainer.setY(prefs.getFloat("time_y_ratio", 0) * h);
            tvTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, prefs.getFloat("time_text_size", 40) * scale);
        }
        if (prefs.contains("white_x_ratio")) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) whiteWidgetContainer.getLayoutParams();
            if (lp == null) {
                lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            lp.setMargins(0, 0, 0, 0);
            whiteWidgetContainer.setLayoutParams(lp);
            whiteWidgetContainer.setTranslationX(0);
            whiteWidgetContainer.setTranslationY(0);
            whiteWidgetContainer.setLeft(0);
            whiteWidgetContainer.setTop(0);
            whiteWidgetContainer.setRight(whiteWidgetContainer.getWidth());
            whiteWidgetContainer.setBottom(whiteWidgetContainer.getHeight());

            float targetX = prefs.getFloat("white_x_ratio", 0) * w;
            float targetY = prefs.getFloat("white_y_ratio", 0) * h;
            whiteWidgetContainer.setX(targetX);
            whiteWidgetContainer.setY(targetY);

            whiteWidgetContainer.setY(targetY);

            ViewGroup.LayoutParams p = viewWhiteRect.getLayoutParams();
            p.width = (int) (prefs.getInt("white_width", 100) * scale);
            p.height = (int) (prefs.getInt("white_height", 100) * scale);
            viewWhiteRect.setLayoutParams(p);
            if (cardWhiteRect != null) {
                cardWhiteRect.setRadius(p.width * 0.05f);
            }
        }

        if (prefs.contains("cover_x_ratio") && mediaCoverWidgetContainer != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mediaCoverWidgetContainer.getLayoutParams();
            if (lp == null) {
                lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            lp.setMargins(0, 0, 0, 0);
            mediaCoverWidgetContainer.setLayoutParams(lp);
            mediaCoverWidgetContainer.setTranslationX(0);
            mediaCoverWidgetContainer.setTranslationY(0);
            mediaCoverWidgetContainer.setLeft(0);
            mediaCoverWidgetContainer.setTop(0);
            mediaCoverWidgetContainer.setRight(mediaCoverWidgetContainer.getWidth());
            mediaCoverWidgetContainer.setBottom(mediaCoverWidgetContainer.getHeight());

            mediaCoverWidgetContainer.setX(prefs.getFloat("cover_x_ratio", 0) * w);
            mediaCoverWidgetContainer.setY(prefs.getFloat("cover_y_ratio", 0) * h);

            ViewGroup.LayoutParams p = viewMediaCover.getLayoutParams();
            p.width = (int) (prefs.getInt("cover_width", 100) * scale);
            p.height = (int) (prefs.getInt("cover_height", 100) * scale);
            viewMediaCover.setLayoutParams(p);
            if (cardMediaCover != null) {
                cardMediaCover.setRadius(p.width * 0.05f);
            }
        }

        if (prefs.contains("gear_x_ratio") && gearWidgetContainer != null) {
            ensureAbsoluteAlignment(gearWidgetContainer);
            gearWidgetContainer.setX(prefs.getFloat("gear_x_ratio", 0) * w);
            gearWidgetContainer.setY(prefs.getFloat("gear_y_ratio", 0) * h);
            tvGear.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, prefs.getFloat("gear_text_size", 40) * scale);
        }

        isStateRestored = true;

        syncToHud();

    }

    public void onDestroy() {
        // dismissHud(); // NO - Service controls HUD
        handler.removeCallbacks(timeRunnable);
    }
}

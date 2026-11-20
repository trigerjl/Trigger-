package com.triger.poc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Простой кастомный график роста баланса: линия по точкам + градиентная заливка под линией.
 */
public class BalanceChartView extends View {

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Float> values = new ArrayList<>();
    private final List<Long> timestamps = new ArrayList<>();
    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private LinearGradient fillGradient;

    private final int lineColor;
    private float minVal, maxVal;
    private float axisMin, axisMax = 1f;
    private String startLabel = "";
    private String midLabel = "";
    private String endLabel = "";

    public BalanceChartView(Context context) {
        this(context, null);
    }

    public BalanceChartView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BalanceChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        lineColor = ContextCompat.getColor(context, R.color.accent_purple);
        startLabel = context.getString(R.string.chart_start);
        endLabel = context.getString(R.string.chart_now);

        linePaint.setColor(lineColor);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2.5f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        glowPaint.setColor(ColorUtils.setAlphaComponent(lineColor, 55));
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(9));
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);

        dotPaint.setColor(lineColor);
        dotPaint.setStyle(Paint.Style.FILL);

        dotInnerPaint.setColor(Color.WHITE);
        dotInnerPaint.setStyle(Paint.Style.FILL);

        int secondary = resolveColor(android.R.attr.textColorSecondary, 0x99000000);
        gridPaint.setColor(ColorUtils.setAlphaComponent(secondary, 38));
        gridPaint.setStrokeWidth(dp(0.5f));

        labelPaint.setColor(secondary);
        labelPaint.setTextSize(sp(10));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int padT = (int) dp(14);
        int padB = (int) dp(24);
        float chartH = h - padT - padB;
        fillGradient = new LinearGradient(0, padT, 0, padT + chartH,
                ColorUtils.setAlphaComponent(lineColor, 125), Color.TRANSPARENT,
                Shader.TileMode.CLAMP);
    }

    public void setValues(List<Float> vals) {
        setData(vals, null);
    }

    public void setData(List<Float> vals, List<Long> times) {
        values.clear();
        timestamps.clear();
        if (vals != null) {
            values.addAll(vals);
        }
        if (times != null && times.size() == values.size()) {
            timestamps.addAll(times);
        }
        if (!values.isEmpty()) {
            minVal = values.get(0);
            maxVal = values.get(0);
            for (float v : values) {
                if (v < minVal) minVal = v;
                if (v > maxVal) maxVal = v;
            }
        }
        buildAxisLabels();
        invalidate();
    }

    private void buildAxisLabels() {
        startLabel = getContext().getString(R.string.chart_start);
        midLabel = "";
        endLabel = getContext().getString(R.string.chart_now);
        if (timestamps.size() < 2) {
            return;
        }
        SimpleDateFormat format = new SimpleDateFormat("dd.MM", Locale.getDefault());
        startLabel = format.format(new Date(timestamps.get(0)));
        endLabel = format.format(new Date(timestamps.get(timestamps.size() - 1)));
        if (timestamps.size() >= 3) {
            midLabel = format.format(new Date(timestamps.get(timestamps.size() / 2)));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        int padL = (int) dp(38);
        int padR = (int) dp(8);
        int padT = (int) dp(14);
        int padB = (int) dp(24);
        float chartW = w - padL - padR;
        float chartH = h - padT - padB;

        updateScale();

        // Grid: 3 horizontal lines
        for (int i = 0; i <= 2; i++) {
            float y = padT + chartH * i / 2f;
            canvas.drawLine(padL, y, w - padR, y, gridPaint);
        }

        // Y-axis labels (min / mid / max)
        if (!values.isEmpty()) {
            float mid = (axisMin + axisMax) / 2f;
            canvas.drawText(fmt(axisMax), dp(2), padT + labelPaint.getTextSize(), labelPaint);
            canvas.drawText(fmt(mid), dp(2), padT + chartH / 2f + labelPaint.getTextSize() / 2f, labelPaint);
            canvas.drawText(fmt(axisMin), dp(2), padT + chartH + labelPaint.getTextSize() / 2f, labelPaint);
        }

        drawXLabels(canvas, w, h, padL, padR, chartW);

        if (values.size() < 2) {
            if (values.size() == 1) {
                float cx = padL + chartW / 2f;
                float cy = padT + chartH / 2f;
                canvas.drawCircle(cx, cy, dp(8), glowPaint);
                canvas.drawCircle(cx, cy, dp(4), dotPaint);
                canvas.drawCircle(cx, cy, dp(1.6f), dotInnerPaint);
            }
            return;
        }

        float range = axisMax - axisMin;
        if (range == 0f) range = 1f;

        linePath.reset();
        fillPath.reset();
        for (int i = 0; i < values.size(); i++) {
            float x = padL + chartW * i / (values.size() - 1);
            float norm = (values.get(i) - axisMin) / range;
            float y = padT + chartH * (1f - norm);
            if (i == 0) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, padT + chartH);
                fillPath.lineTo(x, y);
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }
        float lastX = padL + chartW;
        fillPath.lineTo(lastX, padT + chartH);
        fillPath.close();

        fillPaint.setShader(fillGradient);

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, glowPaint);
        canvas.drawPath(linePath, linePaint);

        // Dots on each point, highlighted last point
        for (int i = 0; i < values.size(); i++) {
            float x = padL + chartW * i / (values.size() - 1);
            float norm = (values.get(i) - axisMin) / range;
            float y = padT + chartH * (1f - norm);
            boolean last = i == values.size() - 1;
            canvas.drawCircle(x, y, last ? dp(4.5f) : dp(3), dotPaint);
            if (last) {
                canvas.drawCircle(x, y, dp(1.8f), dotInnerPaint);
            }
        }
    }

    private void updateScale() {
        if (values.isEmpty()) {
            axisMin = 0f;
            axisMax = 1f;
            return;
        }
        float dataMin = values.get(0);
        float dataMax = values.get(0);
        for (float v : values) {
            if (v < dataMin) dataMin = v;
            if (v > dataMax) dataMax = v;
        }
        float range = dataMax - dataMin;
        float minSpan = Math.max(1f, Math.abs(dataMax) * 0.1f);
        boolean smallRange = range <= minSpan;
        float padding = smallRange
                ? (minSpan - range) / 2f + minSpan * 0.1f
                : range * 0.1f;

        axisMin = dataMin - padding;
        axisMax = dataMax + padding;
        if (!smallRange && dataMin >= 0f && axisMin < 0f) {
            axisMin = 0f;
        }
        if (axisMax - axisMin < 1f) {
            float mid = (axisMin + axisMax) / 2f;
            axisMin = mid - 0.5f;
            axisMax = mid + 0.5f;
        }
    }

    private void drawXLabels(Canvas canvas, int w, int h, int padL, int padR, float chartW) {
        float y = h - dp(4);
        canvas.drawText(startLabel, padL, y, labelPaint);
        if (!midLabel.isEmpty()) {
            float tw = labelPaint.measureText(midLabel);
            canvas.drawText(midLabel, padL + chartW / 2f - tw / 2f, y, labelPaint);
        }
        float endWidth = labelPaint.measureText(endLabel);
        canvas.drawText(endLabel, w - padR - endWidth, y, labelPaint);
    }

    private int resolveColor(int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (getContext().getTheme().resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) {
                return ContextCompat.getColor(getContext(), value.resourceId);
            }
            return value.data;
        }
        return fallback;
    }

    private String fmt(float v) {
        if (v >= 10000) {
            return String.format(Locale.getDefault(), "%.0f", v);
        }
        return String.format(Locale.getDefault(), "%.1f", v);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}

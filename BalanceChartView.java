package com.triger.poc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Простой кастомный график роста баланса: линия по точкам + заливка под линией.
 */
public class BalanceChartView extends View {

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Float> values = new ArrayList<>();
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    private float minVal, maxVal;

    public BalanceChartView(Context context) {
        this(context, null);
    }

    public BalanceChartView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BalanceChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        int lineColor = ContextCompat.getColor(context, R.color.colorPrimary);
        linePaint.setColor(lineColor);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2.5f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setColor(lineColor);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setAlpha(40);

        dotPaint.setColor(lineColor);
        dotPaint.setStyle(Paint.Style.FILL);

        gridPaint.setColor(0x22000000);
        gridPaint.setStrokeWidth(dp(0.5f));

        labelPaint.setColor(0x99000000);
        labelPaint.setTextSize(sp(10));
    }

    public void setValues(List<Float> vals) {
        values.clear();
        if (vals != null) {
            values.addAll(vals);
        }
        if (!values.isEmpty()) {
            minVal = values.get(0);
            maxVal = values.get(0);
            for (float v : values) {
                if (v < minVal) minVal = v;
                if (v > maxVal) maxVal = v;
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        int padL = (int) dp(36);
        int padR = (int) dp(8);
        int padT = (int) dp(12);
        int padB = (int) dp(20);
        float chartW = w - padL - padR;
        float chartH = h - padT - padB;

        // Grid: 3 horizontal lines
        for (int i = 0; i <= 2; i++) {
            float y = padT + chartH * i / 2f;
            canvas.drawLine(padL, y, w - padR, y, gridPaint);
        }

        // Y-axis labels (min / mid / max)
        if (!values.isEmpty()) {
            float mid = (minVal + maxVal) / 2f;
            canvas.drawText(fmt(maxVal), dp(2), padT + labelPaint.getTextSize(), labelPaint);
            canvas.drawText(fmt(mid), dp(2), padT + chartH / 2f + labelPaint.getTextSize() / 2f, labelPaint);
            canvas.drawText(fmt(minVal), dp(2), padT + chartH + labelPaint.getTextSize() / 2f, labelPaint);
        }

        if (values.size() < 2) {
            // Single point or empty — draw dot in center
            if (values.size() == 1) {
                float cx = padL + chartW / 2f;
                float cy = padT + chartH / 2f;
                canvas.drawCircle(cx, cy, dp(4), dotPaint);
            }
            return;
        }

        float range = maxVal - minVal;
        if (range == 0f) range = 1f;

        linePath.reset();
        fillPath.reset();
        for (int i = 0; i < values.size(); i++) {
            float x = padL + chartW * i / (values.size() - 1);
            float norm = (values.get(i) - minVal) / range;
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
        // Close fill path
        float lastX = padL + chartW;
        fillPath.lineTo(lastX, padT + chartH);
        fillPath.close();

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);

        // Dots on each point
        for (int i = 0; i < values.size(); i++) {
            float x = padL + chartW * i / (values.size() - 1);
            float norm = (values.get(i) - minVal) / range;
            float y = padT + chartH * (1f - norm);
            canvas.drawCircle(x, y, dp(3), dotPaint);
        }

        // X-axis labels: first and last
        canvas.drawText("начало", padL, h - dp(4), labelPaint);
        String last = "сейчас";
        float tw = labelPaint.measureText(last);
        canvas.drawText(last, w - padR - tw, h - dp(4), labelPaint);
    }

    private String fmt(float v) {
        if (v >= 10000) {
            return String.format(java.util.Locale.getDefault(), "%.0f", v);
        }
        return String.format(java.util.Locale.getDefault(), "%.1f", v);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}

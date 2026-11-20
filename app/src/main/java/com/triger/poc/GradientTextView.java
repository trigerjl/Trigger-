package com.triger.poc;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;

/**
 * TextView с горизонтальным градиентом для цифр и акцентных значений.
 */
public class GradientTextView extends AppCompatTextView {

    private final int startColor;
    private final int endColor;

    public GradientTextView(Context context) {
        this(context, null);
    }

    public GradientTextView(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.textViewStyle);
    }

    public GradientTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        int start = ContextCompat.getColor(context, R.color.accent_purple);
        int end = ContextCompat.getColor(context, R.color.accent_blue);
        if (attrs != null) {
            TypedArray array = context.obtainStyledAttributes(attrs, R.styleable.GradientTextView);
            start = array.getColor(R.styleable.GradientTextView_gradientStart, start);
            end = array.getColor(R.styleable.GradientTextView_gradientEnd, end);
            array.recycle();
        }
        startColor = start;
        endColor = end;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyGradient(w);
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        applyGradient(getWidth());
    }

    private void applyGradient(int width) {
        if (width <= 0) {
            return;
        }
        getPaint().setShader(new LinearGradient(0f, 0f, width, 0f,
                startColor, endColor, Shader.TileMode.CLAMP));
        invalidate();
    }
}

package com.example.medibook.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.example.medibook.R;
import com.example.medibook.utils.WeeklyAppointmentStats;

import java.util.Locale;
import java.util.Arrays;

/**
 * A small, dependency-free Saturday-Friday bar chart. Each bar is the real appointment count for
 * that scheduled Dhaka date; a zero count still draws a baseline bar so the empty state is legible.
 */
public final class WeeklyBarChartView extends View {
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private int[] counts = new int[WeeklyAppointmentStats.DAYS];
    private String[] labels = new String[WeeklyAppointmentStats.DAYS];
    private boolean statsBound;

    public WeeklyBarChartView(Context context) { this(context, null); }

    public WeeklyBarChartView(Context context, AttributeSet attributes) {
        super(context, attributes);
        barPaint.setColor(ContextCompat.getColor(context, R.color.medibook_blue));
        emptyBarPaint.setColor(ContextCompat.getColor(context, R.color.medibook_blue_container));
        valuePaint.setColor(ContextCompat.getColor(context, R.color.medibook_text));
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTextSize(sp(11f));
        labelPaint.setColor(ContextCompat.getColor(context, R.color.medibook_text_secondary));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(sp(10f));
        for (int index = 0; index < labels.length; index++) labels[index] = "";
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    public void setLabels(String[] dayLabels) {
        if (dayLabels == null || dayLabels.length != WeeklyAppointmentStats.DAYS) return;
        if (Arrays.equals(labels, dayLabels)) return;
        labels = dayLabels.clone();
        statsBound = false;
        invalidate();
    }

    /** Supplies the seven real counts, Saturday first. */
    public void setStats(WeeklyAppointmentStats stats) {
        if (stats == null) return;
        if (statsBound && Arrays.equals(counts, stats.counts)) return;
        statsBound = true;
        counts = stats.counts.clone();
        StringBuilder description = new StringBuilder();
        for (int index = 0; index < counts.length; index++) {
            if (index > 0) description.append(", ");
            description.append(labels.length > index ? labels[index] : Integer.toString(index))
                    .append(' ').append(counts[index]);
        }
        setContentDescription(description.toString());
        invalidate();
    }

    public int[] getCounts() { return counts.clone(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (width <= 0 || height <= 0) return;

        float labelHeight = sp(16f);
        float valueHeight = sp(16f);
        float plotTop = getPaddingTop() + valueHeight;
        float plotBottom = getPaddingTop() + height - labelHeight;
        float plotHeight = Math.max(1f, plotBottom - plotTop);

        int peak = 1;
        for (int count : counts) peak = Math.max(peak, count);

        float slot = width / (float) WeeklyAppointmentStats.DAYS;
        float barWidth = Math.min(slot * 0.55f, sp(26f));
        for (int index = 0; index < WeeklyAppointmentStats.DAYS; index++) {
            float centerX = getPaddingLeft() + slot * index + slot / 2f;
            float fraction = counts[index] / (float) peak;
            float barHeight = counts[index] == 0 ? sp(2f) : Math.max(sp(4f), plotHeight * fraction);
            bar.set(centerX - barWidth / 2f, plotBottom - barHeight, centerX + barWidth / 2f, plotBottom);
            canvas.drawRoundRect(bar, sp(3f), sp(3f), counts[index] == 0 ? emptyBarPaint : barPaint);
            canvas.drawText(String.format(Locale.getDefault(), "%d", counts[index]),
                    centerX, plotBottom - barHeight - sp(4f), valuePaint);
            if (index < labels.length) {
                canvas.drawText(labels[index], centerX, getPaddingTop() + height - sp(4f), labelPaint);
            }
        }
    }
}

package com.example.medibook.views;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Space;

/** Two equal columns when readable; stacked content when width/font scale requires it. */
public final class AdaptiveColumnsLayout extends LinearLayout {
    public AdaptiveColumnsLayout(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        float density = getResources().getDisplayMetrics().density;
        float scale = Math.max(1f, getResources().getConfiguration().fontScale);
        int gap = Math.round(8 * density);
        boolean stack = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED
                && available < Math.round(160 * density * scale) * 2 + gap;
        int direction = stack ? VERTICAL : HORIZONTAL;
        if (getOrientation() != direction) setOrientation(direction);
        boolean first = true;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child instanceof Space) {
                child.setVisibility(stack ? GONE : VISIBLE);
                continue;
            }
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            int width = stack ? LayoutParams.MATCH_PARENT : 0;
            float weight = stack ? 0 : 1;
            int margin = stack && !first ? gap : 0;
            if (params.width != width || params.weight != weight || params.topMargin != margin) {
                params.width = width; params.weight = weight; params.topMargin = margin;
                child.setLayoutParams(params);
            }
            first = false;
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}

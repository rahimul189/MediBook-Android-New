package com.example.medibook.wellness;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.viewpager2.widget.ViewPager2;

import com.example.medibook.R;
import com.example.medibook.adapters.WellnessCarouselAdapter;

/**
 * Owns the Health &amp; Wellness pager: one card visible, horizontal swipes, dots and an 8-second
 * automatic advance that wraps. A single retimed {@link Runnable} is used, so no duplicate timers
 * can exist. The activity drives {@link #start()} / {@link #stop()}; the pager's own attach state
 * additionally stops the timer while the header row is recycled off screen.
 */
public final class HealthWellnessCarousel {

    public interface PageListener {
        void onPageChanged(int page);
    }

    private final ViewPager2 pager;
    private final LinearLayout dots;
    private final TextView counter;
    private final WellnessCarouselAdapter adapter = new WellnessCarouselAdapter();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable advance = this::advance;
    private final ViewPager2.OnPageChangeCallback pageCallback = new ViewPager2.OnPageChangeCallback() {
        @Override public void onPageSelected(int position) {
            render(position);
            // A manual swipe selects a page; restart the countdown from this point.
            if (!interacting) schedule();
        }

        @Override public void onPageScrollStateChanged(int state) {
            if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                // The user is interacting: hold the timer until the pager settles.
                interacting = true;
                cancel();
            } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                interacting = false;
                schedule();
            }
        }
    };
    private final View.OnAttachStateChangeListener attachListener = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(@NonNull View view) {
            attached = true;
            schedule();
        }

        @Override public void onViewDetachedFromWindow(@NonNull View view) {
            attached = false;
            cancel();
        }
    };

    private PageListener listener;
    private boolean active;
    private boolean attached;
    private boolean interacting;
    private int page;

    public HealthWellnessCarousel(ViewPager2 pager, LinearLayout dots, TextView counter) {
        this.pager = pager;
        this.dots = dots;
        this.counter = counter;
        pager.setAdapter(adapter);
        pager.setOffscreenPageLimit(1);
        buildDots();
        pager.registerOnPageChangeCallback(pageCallback);
        pager.addOnAttachStateChangeListener(attachListener);
        attached = pager.isAttachedToWindow();
        render(0);
    }

    public void setPageListener(PageListener listener) { this.listener = listener; }

    public int getPage() { return page; }

    /** Restore a page without animating or touching the countdown. */
    public void select(int position) {
        int target = position < 0 || position >= adapter.getItemCount() ? 0 : position;
        pager.setCurrentItem(target, false);
        render(target);
    }

    /** Activity is started and visible. */
    public void start() {
        active = true;
        schedule();
    }

    /** Activity is stopping; the current page is kept for the next resume. */
    public void stop() {
        active = false;
        cancel();
    }

    public void release() {
        cancel();
        handler.removeCallbacksAndMessages(null);
        pager.unregisterOnPageChangeCallback(pageCallback);
        pager.removeOnAttachStateChangeListener(attachListener);
    }

    private void schedule() {
        // Always retime the single runnable instead of posting a second one.
        handler.removeCallbacks(advance);
        if (active && attached && !interacting && adapter.getItemCount() > 1) {
            handler.postDelayed(advance, WellnessCarouselPlan.INTERVAL_MS);
        }
    }

    private void cancel() { handler.removeCallbacks(advance); }

    private void advance() {
        if (!active || !attached || interacting) return;
        int next = WellnessCarouselPlan.nextPage(pager.getCurrentItem(), adapter.getItemCount());
        // Wrapping jumps straight to the first card instead of rewinding through every page.
        pager.setCurrentItem(next, next != 0);
    }

    private void render(int position) {
        page = position;
        for (int index = 0; index < dots.getChildCount(); index++) {
            dots.getChildAt(index).setSelected(index == position);
        }
        counter.setText(counter.getContext().getString(
                R.string.patient_wellness_counter, position + 1, adapter.getItemCount()));
        if (listener != null) listener.onPageChanged(position);
    }

    private void buildDots() {
        dots.removeAllViews();
        int size = dp(7);
        int margin = dp(3);
        for (int index = 0; index < adapter.getItemCount(); index++) {
            View dot = new View(dots.getContext());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMargins(margin, 0, margin, 0);
            dot.setLayoutParams(params);
            dot.setBackgroundResource(R.drawable.wellness_dot);
            dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            dots.addView(dot);
        }
    }

    private int dp(int value) {
        return Math.round(value * dots.getResources().getDisplayMetrics().density);
    }
}

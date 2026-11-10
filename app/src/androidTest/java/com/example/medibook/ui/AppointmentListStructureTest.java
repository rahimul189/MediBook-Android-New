package com.example.medibook.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.medibook.R;
import com.example.medibook.activities.AppointmentsActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Windowed, offline check of the appointment header inside the same ConcatAdapter/RecyclerView
 * structure the screen uses. Runs under UiOnlyTestRunner without signing in or authenticated reads.
 */
@RunWith(AndroidJUnit4.class)
public class AppointmentListStructureTest {

    private static final int ROWS = 40;

    private static final class StaticHeaderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final View header;
        StaticHeaderAdapter(View header) { this.header = header; }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            RecyclerView.ViewHolder holder = new RecyclerView.ViewHolder(header) { };
            holder.setIsRecyclable(false);
            return holder;
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) { }
        @Override public int getItemCount() { return 1; }
    }

    private static final class RowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            TextView row = new TextView(parent.getContext());
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 120));
            row.setText("Synthetic appointment row");
            return new RecyclerView.ViewHolder(row) { };
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((TextView) holder.itemView).setText("Synthetic appointment row " + position);
        }
        @Override public int getItemCount() { return ROWS; }
    }

    private void idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private RecyclerView recyclerView() {
        return new RecyclerView(InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    @Test public void appointmentsActivityCreatesItsRealHeaderWithoutCrashing() {
        UiFixtureActivity.fixtureFontScale = 1f;
        UiFixtureActivity.fixtureLayout = R.layout.activity_main;
        // No authenticated session: the real screen must still get through onCreate, then route to
        // Login through its normal session gate. Before the fix, inflating the list header with the
        // parent RecyclerView threw IllegalStateException("RecyclerView has no LayoutManager") here.
        try (ActivityScenario<AppointmentsActivity> ignored =
                     ActivityScenario.launch(AppointmentsActivity.class)) {
            idle();
        }
    }

    @Test public void staticHeaderSurvivesScrollRecycleAndReattachInConcatAdapter() {
        UiFixtureActivity.fixtureFontScale = 1f;
        UiFixtureActivity.fixtureLayout = R.layout.activity_main;
        try (ActivityScenario<UiFixtureActivity> scenario = ActivityScenario.launch(UiFixtureActivity.class)) {
            final RecyclerView[] list = new RecyclerView[1];
            final View[] header = new View[1];
            scenario.onActivity(activity -> {
                Context context = activity;
                list[0] = new RecyclerView(context);
                list[0].setLayoutManager(new LinearLayoutManager(context));
                header[0] = LayoutInflater.from(context)
                        .inflate(R.layout.item_appointment_list_header, list[0], false);
                list[0].setAdapter(new ConcatAdapter(new StaticHeaderAdapter(header[0]), new RowAdapter()));
                FrameLayout host = new FrameLayout(context);
                host.addView(list[0], new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                activity.setContentView(host);
            });
            idle();
            scenario.onActivity(activity -> {
                assertNotNull(list[0].getAdapter());
                assertEquals(ROWS + 1, list[0].getAdapter().getItemCount());
                assertSame(header[0], list[0].getChildAt(0));
            });

            // Scroll the header completely off-screen, then bring it back. A static, non-recyclable
            // header holder is removed from the RecyclerView and must be attachable again.
            for (int pass = 0; pass < 4; pass++) {
                scenario.onActivity(activity -> ((LinearLayoutManager) list[0].getLayoutManager())
                        .scrollToPositionWithOffset(ROWS, 0));
                idle();
                scenario.onActivity(activity -> ((LinearLayoutManager) list[0].getLayoutManager())
                        .scrollToPositionWithOffset(0, 0));
                idle();
                scenario.onActivity(activity -> {
                    assertSame(header[0], list[0].getChildAt(0));
                    list[0].getAdapter().notifyDataSetChanged();
                });
                idle();
            }
        }
    }
}

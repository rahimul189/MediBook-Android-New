package com.example.medibook.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.medibook.R;
import com.example.medibook.airquality.AirQualityReport;
import com.example.medibook.airquality.EnvironmentGuidance;
import com.example.medibook.databinding.ItemAirQualityHeaderBinding;
import com.example.medibook.databinding.ItemAirQualityMetricBinding;
import com.example.medibook.utils.AppConstants;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** One scroll: location, AQI, UV and particle cards with concise precautions and attribution. */
public final class AirQualityAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private AirQualityReport report;
    private boolean cached, fallback;
    private String locationLabel;

    public void submit(AirQualityReport report, boolean cached, String locationLabel, boolean fallback) {
        int before = getItemCount();
        boolean changed = this.report != report || this.cached != cached;
        this.report = report;
        this.cached = cached;
        this.locationLabel = locationLabel;
        this.fallback = fallback;
        notifyItemChanged(0);
        int after = getItemCount();
        if (after > before) notifyItemRangeInserted(1, after - before);
        else if (before > after) notifyItemRangeRemoved(1, before - after);
        else if (changed && after > 1) notifyItemRangeChanged(1, after - 1);
    }

    public void refreshFreshness() { notifyItemChanged(0); }

    @Override public int getItemCount() { return report == null ? 1 : 5; }

    @Override public int getItemViewType(int position) { return position == 0 ? 0 : 1; }

    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return type == 0 ? new Header(ItemAirQualityHeaderBinding.inflate(inflater, parent, false))
                : new Metric(ItemAirQualityMetricBinding.inflate(inflater, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Context context = holder.itemView.getContext();
        if (holder instanceof Header) {
            bindHeader(context, ((Header) holder).binding);
            return;
        }
        Metric metric = (Metric) holder;
        int index = position - 1;
        int[] titles = {R.string.air_quality_aqi, R.string.air_quality_uv,
                R.string.air_quality_pm25, R.string.air_quality_pm10};
        Double[] values = {report.usAqi, report.uv, report.pm25, report.pm10};
        Double value = values[index];
        metric.binding.title.setText(titles[index]);
        metric.binding.value.setText(metricValue(context, index, value));
        if (index == 1) metric.binding.value.setText(uvValue(context, value));
        String guidance = guidance(context, index, value);
        metric.binding.guidance.setVisibility(guidance == null ? View.GONE : View.VISIBLE);
        metric.binding.guidance.setText(guidance);
    }

    private void bindHeader(Context context, ItemAirQualityHeaderBinding header) {
        header.freshness.setVisibility(report == null ? View.GONE : View.VISIBLE);
        header.timestamp.setVisibility(report == null ? View.GONE : View.VISIBLE);
        header.locationNote.setVisibility(locationLabel == null ? View.GONE : View.VISIBLE);
        header.location.setText(locationLabel == null ? context.getString(R.string.air_quality_title) : locationLabel);
        if (locationLabel != null) {
            header.locationNote.setText(fallback ? R.string.environment_location_fallback_note
                    : R.string.environment_location_approximate_note);
        }
        if (report == null) return;
        long now = System.currentTimeMillis();
        boolean stale = report.staleEstimate(now) || !report.cacheFresh(now);
        header.freshness.setText(stale ? R.string.air_quality_stale
                : cached ? R.string.air_quality_cached : R.string.air_quality_live);
        header.freshness.setTextColor(androidx.core.content.ContextCompat.getColor(context,
                stale ? R.color.medibook_warning : R.color.medibook_blue));
        SimpleDateFormat date = new SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH);
        date.setTimeZone(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        header.timestamp.setText(context.getString(R.string.air_quality_timestamp,
                date.format(new Date(report.fetchedAt))));
    }

    private static String metricValue(Context context, int index, Double value) {
        if (value == null) return context.getString(R.string.air_quality_unavailable);
        String formatted = String.format(Locale.ENGLISH, index == 0 || index == 1 ? "%.0f" : "%.1f", value);
        return index == 2 || index == 3
                ? context.getString(R.string.air_quality_particle_unit, formatted) : formatted;
    }

    private static String uvValue(Context context, Double value) {
        if (value == null) return context.getString(R.string.air_quality_unavailable);
        return String.format(Locale.ENGLISH, "%.1f", value);
    }

    private static String guidance(Context context, int index, Double value) {
        if (value == null) return null;
        if (index == 0) {
            EnvironmentGuidance.Aqi category = EnvironmentGuidance.aqi(value);
            StringBuilder text = new StringBuilder(context.getString(aqiAdvice(category)));
            if (EnvironmentGuidance.respiratorAdvised(value, null, null)) {
                text.append(' ').append(context.getString(R.string.environment_n95_note));
            }
            return text.toString();
        }
        if (index == 1) return context.getString(uvAdvice(EnvironmentGuidance.uv(value)));
        // PM2.5 and PM10 are separate pollutants with separate precautions; both describe only
        // the current reading, never a 24-hour average or a WHO guideline exceedance.
        if (index == 2) return context.getString(pm25Advice(EnvironmentGuidance.pm25(value)));
        return context.getString(pm10Advice(EnvironmentGuidance.pm10(value)));
    }

    private static int pm25Advice(EnvironmentGuidance.Pm category) {
        switch (category) {
            case LOW: return R.string.environment_pm25_low;
            case ELEVATED: return R.string.environment_pm25_elevated;
            case HIGH: return R.string.environment_pm25_high;
            default: return R.string.air_quality_empty;
        }
    }

    private static int pm10Advice(EnvironmentGuidance.Pm category) {
        switch (category) {
            case LOW: return R.string.environment_pm10_low;
            case ELEVATED: return R.string.environment_pm10_elevated;
            case HIGH: return R.string.environment_pm10_high;
            default: return R.string.air_quality_empty;
        }
    }

    private static int aqiAdvice(EnvironmentGuidance.Aqi category) {
        switch (category) {
            case GOOD: return R.string.environment_aqi_good;
            case MODERATE: return R.string.environment_aqi_moderate;
            case SENSITIVE: return R.string.environment_aqi_sensitive;
            case UNHEALTHY: return R.string.environment_aqi_unhealthy;
            case VERY_UNHEALTHY: return R.string.environment_aqi_very_unhealthy;
            case HAZARDOUS: return R.string.environment_aqi_hazardous;
            default: return R.string.air_quality_empty;
        }
    }

    private static int uvAdvice(EnvironmentGuidance.Uv category) {
        switch (category) {
            case LOW: return R.string.environment_uv_low;
            case MODERATE: return R.string.environment_uv_moderate;
            case HIGH: return R.string.environment_uv_high;
            case VERY_HIGH: return R.string.environment_uv_very_high;
            case EXTREME: return R.string.environment_uv_extreme;
            default: return R.string.air_quality_empty;
        }
    }

    private static final class Header extends RecyclerView.ViewHolder {
        final ItemAirQualityHeaderBinding binding;
        Header(ItemAirQualityHeaderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            ViewCompat.setAccessibilityHeading(binding.location, true);
        }
    }

    private static final class Metric extends RecyclerView.ViewHolder {
        final ItemAirQualityMetricBinding binding;
        Metric(ItemAirQualityMetricBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            ViewCompat.setAccessibilityHeading(binding.title, true);
        }
    }
}

package com.example.medibook.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.example.medibook.R;

/** Debug-only, non-exported window for synthetic UI checks; never opens account/data services. */
public final class UiFixtureActivity extends AppCompatActivity {
    public static float fixtureFontScale = 1f;
    public static int fixtureLayout = R.layout.activity_edit_profile;
    public View content;

    @Override protected void attachBaseContext(Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.fontScale = fixtureFontScale;
        super.attachBaseContext(base.createConfigurationContext(config));
    }

    // Test-only fixture label, deliberately not product/localized interface copy.
    @android.annotation.SuppressLint("SetTextI18n")
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        frame.setBackgroundColor(android.graphics.Color.WHITE);
        TextView caption = new TextView(this);
        caption.setText("OFFLINE UI FIXTURE · synthetic data");
        caption.setTextAppearance(R.style.TextAppearance_MediBook_Label);
        frame.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        content = getLayoutInflater().inflate(fixtureLayout, frame, false);
        int width = Math.round(320 * getResources().getDisplayMetrics().density);
        frame.addView(content, new LinearLayout.LayoutParams(width, 0, 1));
        setContentView(frame);
        com.example.medibook.utils.AuthUi.applyInsets(this, frame);
    }
}

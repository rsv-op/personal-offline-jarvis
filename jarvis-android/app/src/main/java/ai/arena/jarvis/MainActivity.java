package ai.arena.jarvis;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildInterface();
    }

    private void buildInterface() {
        int padding = dp(24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(0xFF101820);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(0, dp(36), 0, dp(24));

        TextView title = new TextView(this);
        title.setText("JARVIS");
        title.setTextColor(0xFF64D8CB);
        title.setTextSize(36);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        content.addView(
                title,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        TextView subtitle = new TextView(this);
        subtitle.setText("Local-first Android assistant");
        subtitle.setTextColor(0xFFE7EEF3);
        subtitle.setTextSize(16);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
        subtitleParams.topMargin = dp(8);
        content.addView(subtitle, subtitleParams);

        statusText = new TextView(this);
        statusText.setText(
                "Status: Application started\n"
                        + "Core services: Not configured\n"
                        + "Voice activation: Not enabled\n"
                        + "Local memory: Not configured"
        );
        statusText.setTextColor(0xFFE7EEF3);
        statusText.setTextSize(16);
        statusText.setLineSpacing(dp(6), 1.0f);
        statusText.setPadding(dp(16), dp(18), dp(16), dp(18));
        statusText.setBackgroundColor(0xFF1B2A35);

        LinearLayout.LayoutParams statusParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
        statusParams.topMargin = dp(36);
        content.addView(statusText, statusParams);

        Button checkButton = new Button(this);
        checkButton.setText("Check Local Status");
        checkButton.setOnClickListener(view ->
                statusText.setText(
                        "Status: Application is responsive\n"
                                + "Core services: Not configured\n"
                                + "Voice activation: Not enabled\n"
                                + "Local memory: Not configured\n\n"
                                + "This check runs locally on this device."
                )
        );

        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
        buttonParams.topMargin = dp(20);
        content.addView(checkButton, buttonParams);

        TextView footer = new TextView(this);
        footer.setText("JARVIS Android • Initial scaffold");
        footer.setTextColor(0xFF9FB3C1);
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams footerParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
        footerParams.topMargin = dp(36);
        content.addView(footer, footerParams);

        scrollView.addView(
                content,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        root.addView(
                scrollView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1.0f
                )
        );

        setContentView(root);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}

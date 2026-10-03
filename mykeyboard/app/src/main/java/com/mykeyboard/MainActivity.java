package com.MyKeyboard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);

        TextView title = new TextView(this);
        title.setText("MyKeyboard");
        title.setTextSize(28);
        title.setTextColor(0xFF333333);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("لوحة مفاتيح خفيفة لأندرويد 7 وما بعده.\nفعّلها من إعدادات طرق الإدخال ثم اختر MyKeyboard.");
        info.setTextSize(17);
        info.setPadding(0, 24, 0, 32);
        root.addView(info);

        Button settings = new Button(this);
        settings.setText("فتح إعدادات لوحة المفاتيح");
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        root.addView(settings);

        setContentView(root);
    }
}

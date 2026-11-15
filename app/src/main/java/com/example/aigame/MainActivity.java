package com.example.aigame;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private EditText apiKeyField;
    private EditText ideaField;
    private EditText modelField;
    private TextView resultView;
    private Button generateButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(18);
        layout.setPadding(padding, padding, padding, padding);
        layout.setBackgroundColor(Color.rgb(245, 247, 250));
        scrollView.addView(layout);

        TextView title = new TextView(this);
        title.setText("AI Game Factory");
        title.setTextSize(26);
        title.setTextColor(Color.rgb(25, 45, 70));
        layout.addView(title);

        TextView intro = new TextView(this);
        intro.setText("Опиши гру — ШІ згенерує її початковий Android-код.");
        intro.setTextSize(16);
        intro.setPadding(0, dp(8), 0, dp(16));
        layout.addView(intro);

        addLabel(layout, "Ключ OpenRouter (вводь лише у своєму пристрої):");
        apiKeyField = new EditText(this);
        apiKeyField.setSingleLine(true);
        apiKeyField.setHint("sk-or-...");
        apiKeyField.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(apiKeyField);

        addLabel(layout, "Модель OpenRouter:");
        modelField = new EditText(this);
        modelField.setSingleLine(true);
        modelField.setText("deepseek/deepseek-chat");
        layout.addView(modelField);

        addLabel(layout, "Ідея гри:");
        ideaField = new EditText(this);
        ideaField.setHint("Наприклад: гра, де космічний корабель ухиляється від астероїдів");
        ideaField.setMinLines(3);
        ideaField.setGravity(android.view.Gravity.TOP);
        ideaField.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        layout.addView(ideaField);

        generateButton = new Button(this);
        generateButton.setText("Згенерувати код гри");
        layout.addView(generateButton);

        resultView = new TextView(this);
        resultView.setText("Тут з'явиться відповідь.");
        resultView.setTextSize(14);
        resultView.setTextColor(Color.rgb(25, 35, 45));
        resultView.setTextIsSelectable(true);
        resultView.setPadding(dp(12), dp(12), dp(12), dp(12));
        resultView.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        resultParams.topMargin = dp(14);
        layout.addView(resultView, resultParams);

        generateButton.setOnClickListener(v -> generateGameCode());

        setContentView(scrollView);
    }

    private void generateGameCode() {
        String apiKey = apiKeyField.getText().toString().trim();
        String model = modelField.getText().toString().trim();
        String idea = ideaField.getText().toString().trim();

        if (apiKey.isEmpty() || model.isEmpty() || idea.isEmpty()) {
            resultView.setText("Заповни ключ OpenRouter, модель та ідею гри.");
            return;
        }

        generateButton.setEnabled(false);
        resultView.setText("Генерую код… Зачекай.");

        executor.execute(() -> {
            try {
                String result = requestGameCode(apiKey, model, idea);
                runOnUiThread(() -> resultView.setText(result));
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Невідома помилка" : e.getMessage();
                runOnUiThread(() -> resultView.setText("Не вдалося згенерувати код:\n" + message));
            } finally {
                runOnUiThread(() -> generateButton.setEnabled(true));
            }
        });
    }

    private String requestGameCode(String apiKey, String model, String idea) throws Exception {
        HttpURLConnection connection = null;

        try {
            URL url = new URL("https://openrouter.ai/api/v1/chat/completions");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(120000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json");

            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");
            systemMessage.put(
                    "content",
                    "Ти помічник зі створення простих Android-ігор на Java. "
                            + "Запропонуй короткий опис гри та повний приклад MainActivity.java "
                            + "з малюванням через Canvas. Не використовуй зовнішні бібліотеки. "
                            + "Поясни, що код є початковим прикладом і може потребувати перевірки.");

            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", "Ідея гри: " + idea);

            JSONArray messages = new JSONArray();
            messages.put(systemMessage);
            messages.put(userMessage);

            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);
            requestBody.put("messages", messages);

            byte[] body = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int status = connection.getResponseCode();
            InputStream responseStream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            String responseText = readStream(responseStream);

            if (status < 200 || status >= 300) {
                throw new IOException("OpenRouter HTTP " + status + ": " + responseText);
            }

            JSONObject response = new JSONObject(responseText);
            JSONArray choices = response.getJSONArray("choices");
            return choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readStream(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }

        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        }
        return text.toString();
    }

    private void addLabel(LinearLayout layout, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(15);
        label.setPadding(0, dp(12), 0, dp(4));
        layout.addView(label);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}

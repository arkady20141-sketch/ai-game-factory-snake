import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Конвеєр в одному файлі:
 * Режисер (план JSON) -> Програміст (код по файлах) -> Тестувальник (баги)
 * -> GitHub Actions (збірка APK).
 *
 * Запуск (Java 17+):
 *   java -cp gson-2.11.0.jar AiGameFactory.java "ідея гри"
 */
public class AiGameFactory {

    // =====================================================================
    //  Клієнт OpenRouter (для всіх ШІ-ролей)
    // =====================================================================
    static class AiClient {

        private static final String URL = "https://openrouter.ai/api/v1/chat/completions";

        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        private final String apiKey;

        AiClient(String apiKey) {
            this.apiKey = apiKey;
        }

        /** Надсилає запит моделі й повертає текст відповіді (з повторами при 429/5xx). */
        String chat(String model, String system, String user) throws Exception {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            JsonArray messages = new JsonArray();
            messages.add(message("system", system));
            messages.add(message("user", user));
            body.add("messages", messages);
            String payload = body.toString();

            String lastError = "";
            for (int attempt = 1; attempt <= 5; attempt++) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                        .timeout(Duration.ofMinutes(5))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();

                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    String text = readContent(JsonParser.parseString(response.body()).getAsJsonObject());
                    if (text != null && !text.isBlank()) {
                        return stripThinking(text);
                    }
                    lastError = "порожня відповідь: " + shorten(response.body());
                } else {
                    lastError = "HTTP " + response.statusCode() + ": " + shorten(response.body());
                    if (response.statusCode() != 429 && response.statusCode() < 500) {
                        break; // інші помилки 4xx повторювати марно
                    }
                }
                Thread.sleep(attempt * 10_000L);
            }
            throw new IOException("OpenRouter: " + lastError);
        }

        /** Витягує JSON-об'єкт із відповіді моделі. */
        static String extractJson(String text) {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("У відповіді немає JSON");
            }
            return text.substring(start, end + 1);
        }

        /** Витягує код із блоку ```java ... ``` або повертає весь текст. */
        static String extractCode(String text) {
            Matcher m = Pattern.compile("```[a-zA-Z]*\\s*\\n(.*?)```", Pattern.DOTALL).matcher(text);
            return m.find() ? m.group(1).trim() : text.trim();
        }

        private static JsonObject message(String role, String content) {
            JsonObject m = new JsonObject();
            m.addProperty("role", role);
            m.addProperty("content", content);
            return m;
        }

        private static String readContent(JsonObject json) {
            if (!json.has("choices") || !json.get("choices").isJsonArray()
                    || json.getAsJsonArray("choices").size() == 0) {
                return null;
            }
            JsonObject msg = json.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message");
            if (msg == null || !msg.has("content") || msg.get("content").isJsonNull()) {
                return null;
            }
            return msg.get("content").getAsString();
        }

        private static String stripThinking(String text) {
            return text.replaceAll("(?s)<think>.*?</think>", "").trim();
        }

        private static String shorten(String s) {
            return s.length() > 300 ? s.substring(0, 300) + "..." : s;
        }
    }

    // =====================================================================
    //  Хмарний компілятор: GitHub Actions
    // =====================================================================
    static class GitHubBuilder {

        record BuildResult(boolean success, String log, Path apk) {}

        private static final String API = "https://api.github.com";
        private static final String WORKFLOW = "build.yml";

        // редиректи вручну, щоб не віддавати токен стороннім хостам
        private final HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        private final String token;
        private final String repo;   // "користувач/репозиторій"
        private final String branch; // гілка за замовчуванням, зазвичай main

        GitHubBuilder(String token, String repo, String branch) {
            this.token = token;
            this.repo = repo;
            this.branch = branch;
        }

        /** Створює або оновлює файл у репозиторії. */
        void putFile(String path, String content) throws Exception {
            String url = API + "/repos/" + repo + "/contents/" + path;

            for (int attempt = 1; attempt <= 3; attempt++) {
                HttpResponse<String> existing = http.send(
                        request(url + "?ref=" + branch).GET().build(),
                        HttpResponse.BodyHandlers.ofString());

                JsonObject body = new JsonObject();
                body.addProperty("message", "AI: " + path);
                body.addProperty("content",
                        Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
                body.addProperty("branch", branch);
                if (existing.statusCode() == 200) {
                    body.addProperty("sha", JsonParser.parseString(existing.body())
                            .getAsJsonObject().get("sha").getAsString());
                }

                HttpResponse<String> response = http.send(
                        request(url).header("Content-Type", "application/json")
                                .PUT(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                        HttpResponse.BodyHandlers.ofString());

                int code = response.statusCode();
                if (code / 100 == 2) return;
                if ((code == 409 || code == 422) && attempt < 3) {
                    Thread.sleep(2000); // конфлікт швидких комітів — пробуємо ще раз
                    continue;
                }
                throw new IOException("GitHub PUT " + path + " -> " + code + ": " + response.body());
            }
        }

        /** Запускає збірку, чекає результат і повертає APK або лог помилки. */
        BuildResult build(Path outDir) throws Exception {
            long before = latestRunId();
            dispatch();

            long runId = 0;
            for (int i = 0; i < 40 && runId == 0; i++) {
                Thread.sleep(5_000);
                long id = latestRunId();
                if (id > before) runId = id;
            }
            if (runId == 0) {
                throw new IOException("Запуск збірки не з'явився в GitHub Actions");
            }

            String conclusion = null;
            for (int i = 0; i < 120; i++) { // до ~30 хвилин
                JsonObject run = getJson("/actions/runs/" + runId);
                if ("completed".equals(run.get("status").getAsString())) {
                    conclusion = run.get("conclusion").getAsString();
                    break;
                }
                Thread.sleep(15_000);
            }
            if (conclusion == null) {
                throw new IOException("Збірка не завершилась вчасно");
            }

            if ("success".equals(conclusion)) {
                return new BuildResult(true, "", downloadApk(runId, outDir));
            }
            return new BuildResult(false, failedLog(runId), null);
        }

        private void dispatch() throws Exception {
            JsonObject body = new JsonObject();
            body.addProperty("ref", branch);
            HttpResponse<String> response = http.send(
                    request(API + "/repos/" + repo + "/actions/workflows/" + WORKFLOW + "/dispatches")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 204) {
                throw new IOException("Не вдалося запустити workflow: "
                        + response.statusCode() + " " + response.body());
            }
        }

        private long latestRunId() throws Exception {
            JsonObject json = getJson("/actions/workflows/" + WORKFLOW
                    + "/runs?event=workflow_dispatch&per_page=1");
            JsonArray runs = json.getAsJsonArray("workflow_runs");
            return runs.size() == 0 ? 0 : runs.get(0).getAsJsonObject().get("id").getAsLong();
        }

        private Path downloadApk(long runId, Path outDir) throws Exception {
            JsonArray artifacts = getJson("/actions/runs/" + runId + "/artifacts")
                    .getAsJsonArray("artifacts");
            if (artifacts.size() == 0) {
                throw new IOException("Збірка успішна, але артефакт відсутній");
            }
            long artifactId = artifacts.get(0).getAsJsonObject().get("id").getAsLong();
            byte[] zip = download(API + "/repos/" + repo + "/actions/artifacts/" + artifactId + "/zip");

            Files.createDirectories(outDir);
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().endsWith(".apk")) {
                        Path target = outDir.resolve("game.apk");
                        Files.write(target, zis.readAllBytes());
                        return target;
                    }
                }
            }
            throw new IOException("В артефакті немає .apk");
        }

        private String failedLog(long runId) {
            try {
                JsonArray jobs = getJson("/actions/runs/" + runId + "/jobs").getAsJsonArray("jobs");
                for (int i = 0; i < jobs.size(); i++) {
                    JsonObject job = jobs.get(i).getAsJsonObject();
                    if (job.has("conclusion") && !job.get("conclusion").isJsonNull()
                            && "failure".equals(job.get("conclusion").getAsString())) {
                        String log = new String(
                                download(API + "/repos/" + repo + "/actions/jobs/"
                                        + job.get("id").getAsLong() + "/logs"),
                                StandardCharsets.UTF_8);
                        return log.length() > 6000 ? log.substring(log.length() - 6000) : log;
                    }
                }
            } catch (Exception e) {
                return "Не вдалося отримати лог: " + e.getMessage();
            }
            return "Лог недоступний";
        }

        private JsonObject getJson(String path) throws Exception {
            HttpResponse<String> response = http.send(
                    request(API + "/repos/" + repo + path).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("GitHub GET " + path + " -> "
                        + response.statusCode() + ": " + response.body());
            }
            return JsonParser.parseString(response.body()).getAsJsonObject();
        }

        /** Завантажує файл, ідучи за редіректом без заголовка авторизації. */
        private byte[] download(String url) throws Exception {
            HttpResponse<byte[]> response = http.send(
                    request(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 == 3) {
                String location = response.headers().firstValue("location")
                        .orElseThrow(() -> new IOException("Редирект без Location"));
                response = http.send(HttpRequest.newBuilder(URI.create(location)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
            }
            if (response.statusCode() != 200) {
                throw new IOException("Завантаження не вдалося: HTTP " + response.statusCode());
            }
            return response.body();
        }

        private HttpRequest.Builder request(String url) {
            return HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28");
        }
    }

    // =====================================================================
    //  Оркестратор
    // =====================================================================
    private record Issue(String file, String problem) {}

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern FILE_NAME = Pattern.compile("^[A-Z][A-Za-z0-9]*\\.java$");
    private static final Pattern PACKAGE_NAME = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");

    private static final String DIRECTOR_SYSTEM =
            "Ти ШІ-Режисер. Плануєш проєкт мобільної гри для Android на чистій Java. "
                    + "Відповідай ЛИШЕ валідним JSON без пояснень.";

    private static final String DIRECTOR_RULES = """
            Формат відповіді:
            {"app_name":"Назва","package":"com.example.назва","description":"...",
             "files":[{"name":"MainActivity.java","purpose":"що робить файл"}]}

            Правила:
            - 3-6 файлів, лише .java, імена у стилі PascalCase.
            - Обов'язково MainActivity.java (extends android.app.Activity), який показує гру.
            - Гра малюється на Canvas у власному View, керування дотиком.
            - Без зовнішніх бібліотек, без XML-розміток і ресурсів.
            """;

    private static final String CODER_SYSTEM =
            "Ти ШІ-Програміст, досвідчений Android-розробник на Java 17. "
                    + "Відповідай ЛИШЕ повним кодом одного файлу в блоці ```java, без пояснень.";

    private static final String TESTER_SYSTEM =
            "Ти ШІ-Тестувальник. Ретельно шукаєш помилки компіляції та логічні баги в Java-коді "
                    + "для Android. Відповідай ЛИШЕ валідним JSON.";

    private static final String ISSUES_FORMAT = """
            Формат відповіді:
            {"ok":true або false,"issues":[{"file":"Назва.java","problem":"що не так і як виправити"}]}
            Якщо проблем немає: {"ok":true,"issues":[]}
            """;

    private final AiClient ai;
    private final GitHubBuilder github;
    private final String directorModel;
    private final String coderModel;
    private final String testerModel;

    private JsonObject plan;
    private String pkg;
    private String appName;
    private final Map<String, String> purposes = new LinkedHashMap<>(); // файл -> призначення
    private final Map<String, String> code = new LinkedHashMap<>();     // файл -> код

    private AiGameFactory(AiClient ai, GitHubBuilder github,
                          String directorModel, String coderModel, String testerModel) {
        this.ai = ai;
        this.github = github;
        this.directorModel = directorModel;
        this.coderModel = coderModel;
        this.testerModel = testerModel;
    }

    public static void main(String[] args) throws Exception {
        String idea = args.length > 0 ? String.join(" ", args) : "проста гра Змійка";

        AiClient ai = new AiClient(requireEnv("OPENROUTER_API_KEY"));
        GitHubBuilder github = new GitHubBuilder(
                requireEnv("GITHUB_TOKEN"),
                requireEnv("GITHUB_REPO"),
                env("GITHUB_BRANCH", "main"));

        new AiGameFactory(ai, github,
                env("DIRECTOR_MODEL", "deepseek/deepseek-r1:free"),
                env("CODER_MODEL", "qwen/qwen3-coder:free"),
                env("TESTER_MODEL", "deepseek/deepseek-r1:free"))
                .run(idea, Path.of("out"));
    }

    private void run(String idea, Path outDir) throws Exception {
        log("1/4 Планування (Режисер)");
        makePlan(idea);

        log("2/4 Написання коду (Програміст)");
        for (Map.Entry<String, String> file : purposes.entrySet()) {
            log("  пишу " + file.getKey());
            code.put(file.getKey(), writeFile(file.getKey(), file.getValue()));
        }

        log("3/4 Перевірка (Тестувальник)");
        for (int round = 1; round <= 2; round++) {
            List<Issue> issues = review(null);
            if (issues.isEmpty()) {
                log("  проблем не знайдено");
                break;
            }
            log("  знайдено проблем: " + issues.size() + ", виправляю");
            fix(issues);
        }

        log("4/4 Збірка APK (GitHub Actions)");
        pushScaffold();
        for (String name : code.keySet()) {
            github.putFile(javaPath(name), code.get(name));
        }

        for (int attempt = 1; attempt <= 3; attempt++) {
            log("  збірка, спроба " + attempt);
            GitHubBuilder.BuildResult result = github.build(outDir);
            if (result.success()) {
                log("Готово! APK: " + result.apk().toAbsolutePath());
                return;
            }
            log("  збірка впала, аналізую лог");
            Set<String> changed = fix(review(result.log()));
            if (changed.isEmpty()) break;
            for (String name : changed) {
                github.putFile(javaPath(name), code.get(name));
            }
        }
        throw new IllegalStateException("Не вдалося зібрати APK за 3 спроби");
    }

    // ---------- 1. Режисер ----------

    private void makePlan(String idea) throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String raw = ai.chat(directorModel, DIRECTOR_SYSTEM,
                        "Ідея гри: " + idea + "\n\n" + DIRECTOR_RULES);
                JsonObject p = JsonParser.parseString(AiClient.extractJson(raw)).getAsJsonObject();
                applyPlan(p);
                log(PRETTY.toJson(plan));
                return;
            } catch (RuntimeException e) {
                log("  план некоректний (" + e.getMessage() + "), повтор");
            }
        }
        throw new IllegalStateException("Режисер не зміг скласти коректний план");
    }

    private void applyPlan(JsonObject p) {
        appName = p.has("app_name") ? p.get("app_name").getAsString() : "AI Game";
        String candidate = p.has("package") ? p.get("package").getAsString() : "";
        pkg = PACKAGE_NAME.matcher(candidate).matches() ? candidate : "com.example.game";

        purposes.clear();
        for (JsonElement el : p.getAsJsonArray("files")) {
            JsonObject f = el.getAsJsonObject();
            String name = f.get("name").getAsString();
            if (!FILE_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("недопустиме ім'я файлу: " + name);
            }
            purposes.put(name, f.has("purpose") ? f.get("purpose").getAsString() : "");
        }
        if (!purposes.containsKey("MainActivity.java")) {
            throw new IllegalArgumentException("у плані немає MainActivity.java");
        }
        plan = p;
    }

    // ---------- 2. Програміст ----------

    private String writeFile(String name, String purpose) throws Exception {
        String prompt = "План проєкту:\n" + PRETTY.toJson(plan)
                + "\n\nНапиши файл " + name + " (призначення: " + purpose + ").\n"
                + "Вимоги:\n"
                + "- перший рядок: package " + pkg + ";\n"
                + "- лише android.* та java.*, без зовнішніх бібліотек і XML-ресурсів;\n"
                + "- код має компілюватися без змін.\n"
                + (code.isEmpty() ? "" : "\nУже написані файли (використовуй їхні класи та методи точно як є):\n"
                + allCode());
        return AiClient.extractCode(ai.chat(coderModel, CODER_SYSTEM, prompt));
    }

    private Set<String> fix(List<Issue> issues) throws Exception {
        Map<String, StringBuilder> byFile = new LinkedHashMap<>();
        for (Issue issue : issues) {
            if (code.containsKey(issue.file())) {
                byFile.computeIfAbsent(issue.file(), k -> new StringBuilder())
                        .append("- ").append(issue.problem()).append("\n");
            }
        }

        Set<String> changed = new LinkedHashSet<>();
        for (Map.Entry<String, StringBuilder> entry : byFile.entrySet()) {
            String name = entry.getKey();
            log("  виправляю " + name);
            String prompt = "Виправ файл " + name + ".\nПроблеми:\n" + entry.getValue()
                    + "\nПоточний код:\n" + code.get(name)
                    + "\n\nІнші файли проєкту:\n" + allCode()
                    + "\nПоверни ПОВНИЙ виправлений файл, збережи рядок package " + pkg + ";";
            code.put(name, AiClient.extractCode(ai.chat(coderModel, CODER_SYSTEM, prompt)));
            changed.add(name);
        }
        return changed;
    }

    // ---------- 3. Тестувальник ----------

    /** buildLog == null: огляд коду; інакше розбір логу невдалої збірки. */
    private List<Issue> review(String buildLog) throws Exception {
        String task = buildLog == null
                ? "Переглянь код: помилки компіляції, неправильні імпорти, виклики неіснуючих методів, "
                + "логічні баги, що ламають гру."
                : "Збірка Gradle впала. Знайди причину в логу та вкажи, які файли виправити.\n\nЛОГ:\n" + buildLog;

        String raw = ai.chat(testerModel, TESTER_SYSTEM,
                task + "\n\nКод проєкту:\n" + allCode() + "\n" + ISSUES_FORMAT);

        List<Issue> issues = new ArrayList<>();
        try {
            JsonObject json = JsonParser.parseString(AiClient.extractJson(raw)).getAsJsonObject();
            if (json.has("issues")) {
                for (JsonElement el : json.getAsJsonArray("issues")) {
                    JsonObject o = el.getAsJsonObject();
                    issues.add(new Issue(o.get("file").getAsString(), o.get("problem").getAsString()));
                }
            }
        } catch (RuntimeException e) {
            log("  відповідь тестувальника не розпізнано: " + e.getMessage());
        }
        return issues;
    }

    // ---------- 4. Проєкт для збірки ----------

    private void pushScaffold() throws Exception {
        github.putFile("settings.gradle", """
                pluginManagement {
                    repositories { google(); mavenCentral(); gradlePluginPortal() }
                }
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories { google(); mavenCentral() }
                }
                rootProject.name = "AiGame"
                include ':app'
                """);

        github.putFile("build.gradle", """
                plugins {
                    id 'com.android.application' version '8.5.2' apply false
                }
                """);

        github.putFile("gradle.properties", """
                org.gradle.jvmargs=-Xmx2g
                android.nonTransitiveRClass=true
                """);

        github.putFile("app/build.gradle", """
                plugins {
                    id 'com.android.application'
                }
                android {
                    namespace '%s'
                    compileSdk 34
                    defaultConfig {
                        applicationId '%s'
                        minSdk 24
                        targetSdk 34
                        versionCode 1
                        versionName '1.0'
                    }
                    compileOptions {
                        sourceCompatibility JavaVersion.VERSION_17
                        targetCompatibility JavaVersion.VERSION_17
                    }
                }
                """.formatted(pkg, pkg));

        github.putFile("app/src/main/AndroidManifest.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application
                        android:label="%s"
                        android:theme="@android:style/Theme.NoTitleBar.Fullscreen">
                        <activity
                            android:name=".MainActivity"
                            android:exported="true"
                            android:screenOrientation="portrait">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
                """.formatted(xmlEscape(appName)));

        github.putFile(".github/workflows/build.yml", """
                name: Build APK
                on:
                  workflow_dispatch:
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                      - uses: actions/setup-java@v4
                        with:
                          distribution: temurin
                          java-version: 17
                      - uses: gradle/actions/setup-gradle@v4
                        with:
                          gradle-version: 8.9
                      - run: gradle assembleDebug --no-daemon --stacktrace
                      - uses: actions/upload-artifact@v4
                        with:
                          name: game-apk
                          path: app/build/outputs/apk/debug/*.apk
                """);
    }

    // ---------- утиліти ----------

    private String javaPath(String name) {
        return "app/src/main/java/" + pkg.replace('.', '/') + "/" + name;
    }

    private String allCode() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> file : code.entrySet()) {
            sb.append("// ===== ").append(file.getKey()).append(" =====\n")
                    .append(file.getValue()).append("\n\n");
        }
        return sb.toString();
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Не задано змінну середовища " + name);
        }
        return value;
    }

    private static void log(String message) {
        System.out.println(message);
    }
}

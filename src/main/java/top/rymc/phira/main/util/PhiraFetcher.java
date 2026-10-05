package top.rymc.phira.main.util;

import com.google.gson.Gson;
import lombok.Getter;
import lombok.Setter;
import top.rymc.phira.function.throwable.ThrowableBiFunction;
import top.rymc.phira.function.throwable.ThrowableFunction;
import top.rymc.phira.function.throwable.ThrowableIntFunction;
import top.rymc.phira.main.data.ChartInfo;
import top.rymc.phira.main.data.GameRecord;
import top.rymc.phira.main.data.UserInfo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class PhiraFetcher {

    private static final String USER_AGENT = "JPhira/1";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_RETRIES = 8;
    private static final long RETRY_DELAY_MS = 150;

    private static final Gson GSON = GsonUtil.getGson();

    private static final HttpClient CLIENT = createHttpClient();

    @Setter
    private static String host = "https://phira.5wyxi.com/";

    @Getter
    private static final GenericCache<String, UserInfo> tokenCache =
            GenericCache.create(10, TimeUnit.MINUTES, 10000);
    @Getter
    private static final GenericCache<Integer, GameRecord> recordCache =
            GenericCache.create(30, TimeUnit.MINUTES, 50000);
    @Getter
    private static final GenericCache<Integer, ChartInfo> chartCache =
            GenericCache.create(30, TimeUnit.MINUTES, 10000);
    @Getter
    private static final GenericCache<Integer, UserInfo> userCache =
            GenericCache.create(10, TimeUnit.MINUTES, 5000);

    public static ThrowableFunction<String, UserInfo, IOException> GET_USER_INFO =
            token -> tokenCache.get(token, PhiraFetcher::fetchUserByToken);

    public static ThrowableIntFunction<UserInfo, IOException> GET_USER_INFO_BY_ID =
            id -> userCache.get(id, PhiraFetcher::fetchUserById);

    public static ThrowableIntFunction<ChartInfo, IOException> GET_CHART_INFO =
            id -> chartCache.get(id, PhiraFetcher::fetchChartById);

    public static ThrowableIntFunction<GameRecord, IOException> GET_RECORD_INFO =
            id -> recordCache.get(id, PhiraFetcher::fetchRecordById);

    public static ThrowableBiFunction<String, String, LoginResult, IOException> POST_LOGIN =
            PhiraFetcher::fetchLogin;

    /**
     * One page of the chart list.
     *
     * <p>The endpoint serves 30 charts per page and ignores {@code limit}. Only
     * {@code division=plain} actually filters; any other value behaves as if
     * no division were given.
     */
    public static ChartListPage fetchChartPage(int page, String division) throws IOException {
        String path = "chart?page=" + page + (division == null || division.isBlank() ? "" : "&division=" + division);
        HttpRequest request = createRequest(path);
        return GSON.fromJson(executeWithRetry(request), ChartListPage.class);
    }

    private static HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private static UserInfo fetchUserByToken(String token) throws IOException {
        HttpRequest request = createAuthRequest("me", token);
        String response = executeWithRetry(request);
        return GSON.fromJson(response, UserInfo.class);
    }

    private static UserInfo fetchUserById(int id) throws IOException {
        HttpRequest request = createRequest("user/" + id);
        String response = executeWithRetry(request);
        return GSON.fromJson(response, UserInfo.class);
    }

    private static ChartInfo fetchChartById(int id) throws IOException {
        HttpRequest request = createRequest("chart/" + id);
        String response = executeWithRetry(request);
        return GSON.fromJson(response, ChartInfo.class);
    }

    private static GameRecord fetchRecordById(int id) throws IOException {
        HttpRequest request = createRequest("record/" + id);
        String response = executeWithRetry(request);
        return GSON.fromJson(response, GameRecord.class);
    }

    private static LoginResult fetchLogin(String email, String password) throws IOException {
        String body = GSON.toJson(Map.of("email", email, "password", password));
        HttpRequest request = createPostRequest("login", body);
        String response = executeWithRetry(request);
        return GSON.fromJson(response, LoginResult.class);
    }

    private static HttpRequest createRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(host + path))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    private static HttpRequest createAuthRequest(String path, String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create(host + path))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
    }

    private static HttpRequest createPostRequest(String path, String jsonBody) {
        return HttpRequest.newBuilder()
                .uri(URI.create(host + path))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
    }

    private static String executeWithRetry(HttpRequest request) throws IOException {
        IOException lastError = null;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return executeOnce(request);
            } catch (IOException error) {
                lastError = error;
                sleepWithBackOff(attempt);
            }
        }

        throw lastError;
    }

    private static String executeOnce(HttpRequest request) throws IOException {
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return validateResponse(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }

    private static String validateResponse(HttpResponse<String> response) throws IOException {
        int status = response.statusCode();

        if (status >= 200 && status < 300) {
            return response.body();
        }

        throw new IOException(String.format("HTTP %d: %s", status, response.body()));
    }

    private static void sleepWithBackOff(int attempt) throws IOException {
        if (attempt >= MAX_RETRIES - 1) {
            return;
        }

        long delay = RETRY_DELAY_MS * (attempt + 1);

        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Retry interrupted", e);
        }
    }

    public record LoginResult(int id, String token, String refreshToken, String expireAt) {
    }

    public record ChartListPage(int count, List<ChartInfo> results) {
    }
}
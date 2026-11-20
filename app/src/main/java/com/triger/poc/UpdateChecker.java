package com.triger.poc;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateChecker {

    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/triger-poc/triger/releases/latest";
    private static final String RELEASES_PAGE =
            "https://github.com/triger-poc/triger/releases";

    private UpdateChecker() {
    }

    public static void check(Activity activity) {
        if (activity == null) {
            return;
        }
        new Thread(() -> {
            try {
                JSONObject release = fetchLatestRelease();
                String remoteVersion = release.optString("tag_name",
                        release.optString("name", "")).trim();
                String releaseUrl = release.optString("html_url", "").trim();
                if (remoteVersion.isEmpty()) {
                    throw new IllegalStateException("missing_version");
                }
                String currentVersion = currentVersion(activity);
                boolean newer = isNewer(remoteVersion, currentVersion);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    if (newer) {
                        showUpdateDialog(activity, remoteVersion,
                                releaseUrl.isEmpty() ? RELEASES_PAGE : releaseUrl);
                    } else {
                        Toast.makeText(activity, R.string.update_latest,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Throwable t) {
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        Toast.makeText(activity, R.string.update_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "github-update-check").start();
    }

    private static JSONObject fetchLatestRelease() throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Trigger-Android");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("http_" + code);
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            return new JSONObject(body.toString());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void showUpdateDialog(Activity activity, String version, String url) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_available)
                .setMessage(activity.getString(R.string.update_available_message, version))
                .setPositiveButton(R.string.update_download, (dialog, which) -> {
                    try {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Throwable t) {
                        Toast.makeText(activity, R.string.update_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static String currentVersion(Activity activity) {
        try {
            PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            return info.versionName == null ? "1.0" : info.versionName;
        } catch (Throwable t) {
            return "1.0";
        }
    }

    private static boolean isNewer(String remoteVersion, String currentVersion) {
        int[] remote = versionParts(remoteVersion);
        int[] current = versionParts(currentVersion);
        if (remote.length == 0) {
            return false;
        }
        int length = Math.max(remote.length, current.length);
        for (int i = 0; i < length; i++) {
            int remotePart = i < remote.length ? remote[i] : 0;
            int currentPart = i < current.length ? current[i] : 0;
            if (remotePart > currentPart) {
                return true;
            }
            if (remotePart < currentPart) {
                return false;
            }
        }
        return false;
    }

    private static int[] versionParts(String version) {
        if (version == null) {
            return new int[0];
        }
        String value = version.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("v")) {
            value = value.substring(1);
        }
        Matcher matcher = Pattern.compile("\\d+").matcher(value);
        List<Integer> parts = new ArrayList<>();
        while (matcher.find()) {
            try {
                parts.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException ignored) {
            }
        }
        int[] result = new int[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            result[i] = parts.get(i);
        }
        return result;
    }
}

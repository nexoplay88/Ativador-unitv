package com.ativadorunitv.revendedor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

public final class MainActivity extends Activity {
    private static final String IDS_JSON_URL = "https://nexoplay88.github.io/Ativador-unitv/ids.json";
    private static final String UPDATE_JSON_URL = "https://nexoplay88.github.io/Ativador-unitv/ativador/update.json";
    private static final String UNITV_APK_URL = "https://nexoplay88.github.io/Ativador-unitv/unitv-free/5.8.1.apk";
    private static final String DRIVE_PREFIX = "https://drive.google.com/uc?export=download&id=";
    private static final String ACTIVATION_NAMESPACE = "unitv-activation-v1|U7vF-93aL-2026|";
    private static final int REQUEST_STORAGE = 41;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private TextView statusText;
    private Button activateButton;
    private Button cleanupButton;
    private Dialog progressDialog;
    private TextView progressTitle;
    private TextView progressMessage;
    private ProgressBar progressSpinner;
    private Button progressClose;
    private String currentPage = "";
    private String deviceCode;
    private volatile boolean busy;
    private boolean activityResumed;
    private boolean permissionLaunched;
    private boolean permissionRetryShown;
    private long permissionLaunchTime;
    private boolean updateChecked;
    private File pendingInstallApk;
    private String pendingInstallFailureMessage;
    private boolean awaitingInstallPermission;
    private boolean installPermissionRetryShown;
    private long installPermissionLaunchTime;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(4, 14, 9));
        window.setNavigationBarColor(Color.rgb(4, 14, 9));
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        root = new FrameLayout(this);
        root.setBackground(screenBackground());
        setContentView(root);
        deviceCode = createDeviceCode();
        restorePendingInstall();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (root != null) {
            root.postDelayed(this::refreshPermissionFlow, 180L);
            root.postDelayed(this::resumePendingInstall, 260L);
        }
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        dismissProgressDialog();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void refreshPermissionFlow() {
        if (!activityResumed) {
            return;
        }
        if (!hasStorageAccess()) {
            showPermissionWaiting();
            if (!permissionLaunched) {
                permissionLaunched = true;
                permissionLaunchTime = SystemClock.elapsedRealtime();
                root.postDelayed(this::requestStoragePermission, 120L);
            } else if (!permissionRetryShown
                    && SystemClock.elapsedRealtime() - permissionLaunchTime > 900L) {
                permissionRetryShown = true;
                showPermissionRetryDialog();
            }
            return;
        }

        permissionLaunched = false;
        permissionRetryShown = false;
        showMainPage();
        checkForUpdateOnce();
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        if (!activityResumed) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent appPermission = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                if (appPermission.resolveActivity(getPackageManager()) != null) {
                    startActivity(appPermission);
                } else {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQUEST_STORAGE);
            }
        } catch (Exception error) {
            permissionLaunched = false;
            Toast.makeText(this, "Não foi possível abrir a permissão de arquivos.", Toast.LENGTH_LONG).show();
        }
    }

    private void showPermissionWaiting() {
        if ("permission-waiting".equals(currentPage)) {
            return;
        }
        currentPage = "permission-waiting";
        root.removeAllViews();

        LinearLayout waiting = new LinearLayout(this);
        waiting.setOrientation(LinearLayout.VERTICAL);
        waiting.setGravity(Gravity.CENTER);

        ProgressBar spinner = new ProgressBar(this);
        if (Build.VERSION.SDK_INT >= 21) {
            spinner.setIndeterminateTintList(ColorStateList.valueOf(Color.rgb(103, 233, 158)));
        }
        waiting.addView(spinner, size(dp(48), dp(48)));

        TextView message = text("Abrindo acesso aos arquivos…", 15, Color.rgb(185, 216, 198), false);
        message.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = wrap();
        messageParams.topMargin = dp(12);
        waiting.addView(message, messageParams);

        root.addView(waiting, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    private void showPermissionRetryDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Permissão necessária")
                .setMessage("Autorize o acesso aos arquivos para continuar.")
                .setCancelable(false)
                .setNegativeButton("Sair", (dialog, which) -> finish())
                .setPositiveButton("Tentar novamente", (dialog, which) -> {
                    permissionRetryShown = false;
                    permissionLaunched = true;
                    permissionLaunchTime = SystemClock.elapsedRealtime();
                    requestStoragePermission();
                })
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE) {
            permissionLaunched = true;
            permissionLaunchTime = SystemClock.elapsedRealtime() - 1_000L;
            root.postDelayed(this::refreshPermissionFlow, 120L);
        }
    }

    private void showMainPage() {
        if ("main".equals(currentPage)) {
            return;
        }
        currentPage = "main";
        root.removeAllViews();

        LinearLayout card = card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView brand = text("UniTV Free", 27, Color.WHITE, true);
        brand.setGravity(Gravity.CENTER);
        card.addView(brand, wrap());

        TextView subtitle = text("Ativador", 13, Color.rgb(132, 219, 169), true);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(1), 0, dp(14));
        card.addView(subtitle, wrap());

        TextView deviceLabel = text("CÓDIGO DESTE DISPOSITIVO", 11, Color.rgb(152, 194, 169), true);
        deviceLabel.setGravity(Gravity.CENTER);
        card.addView(deviceLabel, wrap());

        TextView deviceValue = text(formatEight(deviceCode), 28, Color.WHITE, true);
        deviceValue.setGravity(Gravity.CENTER);
        deviceValue.setLetterSpacing(.12f);
        deviceValue.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        deviceValue.setBackground(round(Color.rgb(5, 27, 16), 12, Color.rgb(65, 143, 96), 1));
        deviceValue.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams deviceParams = size(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        deviceParams.bottomMargin = dp(16);
        card.addView(deviceValue, deviceParams);

        activateButton = actionButton("Ativar", true);
        activateButton.setOnClickListener(view -> showNumericKeypad());
        LinearLayout.LayoutParams activateParams = size(dp(205), dp(44));
        activateParams.topMargin = dp(2);
        card.addView(activateButton, activateParams);

        cleanupButton = actionButton("Limpeza", false);
        cleanupButton.setOnClickListener(view -> confirmCleanup());
        LinearLayout.LayoutParams cleanupParams = size(dp(160), dp(38));
        cleanupParams.topMargin = dp(8);
        card.addView(cleanupButton, cleanupParams);

        statusText = text("", 13, Color.rgb(164, 207, 181), false);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(12), 0, 0);
        statusText.setVisibility(View.GONE);
        card.addView(statusText, size(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addCenteredCard(card);
        activateButton.requestFocus();
    }

    private void showNumericKeypad() {
        if (busy) {
            return;
        }

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        LinearLayout panel = modalPanel();
        panel.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = text("Senha de ativação", 18, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, size(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        StringBuilder digits = new StringBuilder();
        TextView display = text("00000000", 25, Color.WHITE, true);
        display.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        display.setLetterSpacing(.13f);
        display.setGravity(Gravity.CENTER);
        display.setBackground(round(Color.rgb(4, 23, 13), 10, Color.rgb(65, 143, 96), 1));
        LinearLayout.LayoutParams displayParams = size(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        displayParams.topMargin = dp(12);
        displayParams.bottomMargin = dp(10);
        panel.addView(display, displayParams);

        GridLayout keypad = new GridLayout(this);
        keypad.setColumnCount(3);
        keypad.setRowCount(4);
        keypad.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        keypad.setUseDefaultMargins(false);
        panel.addView(keypad, wrap());

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"};
        Button firstButton = null;
        for (String key : keys) {
            Button keyButton = actionButton(key, "OK".equals(key));
            keyButton.setTextSize("⌫".equals(key) ? 21f : 18f);
            GridLayout.LayoutParams keyParams = new GridLayout.LayoutParams();
            keyParams.width = dp(78);
            keyParams.height = dp(48);
            keyParams.setMargins(dp(4), dp(4), dp(4), dp(4));
            keypad.addView(keyButton, keyParams);

            if (firstButton == null) {
                firstButton = keyButton;
            }

            keyButton.setOnClickListener(view -> {
                if ("⌫".equals(key)) {
                    if (digits.length() > 0) {
                        digits.deleteCharAt(digits.length() - 1);
                    }
                } else if ("OK".equals(key)) {
                    if (digits.length() != 8) {
                        Toast.makeText(this, "Digite os 8 números.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String entered = digits.toString();
                    if (!activationCode(deviceCode).equals(entered)) {
                        Toast.makeText(this, "Senha de ativação inválida.", Toast.LENGTH_SHORT).show();
                        digits.setLength(0);
                        display.setText("00000000");
                        return;
                    }
                    dialog.dismiss();
                    startActivation(entered);
                    return;
                } else if (digits.length() < 8) {
                    digits.append(key);
                }
                display.setText(digits.length() == 0 ? "00000000" : digits.toString());
            });

            if ("⌫".equals(key)) {
                keyButton.setOnLongClickListener(view -> {
                    digits.setLength(0);
                    display.setText("00000000");
                    return true;
                });
            }
        }

        dialog.setContentView(panel);
        dialog.show();
        Window dialogWindow = dialog.getWindow();
        if (dialogWindow != null) {
            dialogWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialogWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            dialogWindow.setLayout(Math.min(dp(310), screenWidth - dp(28)), ViewGroup.LayoutParams.WRAP_CONTENT);
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        if (firstButton != null) {
            firstButton.requestFocus();
        }
    }

    private void startActivation(String enteredValue) {
        if (busy) {
            return;
        }
        if (!hasStorageAccess()) {
            currentPage = "";
            permissionLaunched = false;
            refreshPermissionFlow();
            return;
        }

        String entered = digitsOnly(enteredValue);
        if (entered.length() != 8 || !activationCode(deviceCode).equals(entered)) {
            setStatus("Senha inválida. Confira os 8 números.", true);
            return;
        }

        setBusy(true);
        setStatus("", false);
        showProgressDialog("Ativando Unitv Free", "Aplicando a configuração deste aparelho.");
        worker.execute(() -> {
            try {
                downloadAndActivateConfig();
                runOnUiThread(() -> updateProgressDialog(
                        "Baixando Unitv Free",
                        "Aguarde enquanto o aplicativo é baixado."
                ));
                File apk = downloadApkFromUrl(
                        UNITV_APK_URL,
                        "UnitvFree-5.8.1.apk",
                        1024 * 1024,
                        "O APK do UniTV Free baixado é inválido."
                );
                runOnUiThread(() -> {
                    setBusy(false);
                    updateProgressDialog("Download concluído", "Abrindo o instalador.");
                    root.postDelayed(() -> {
                        dismissProgressDialog();
                        installOrRequestPermission(apk, "Ativado, mas não foi possível abrir o instalador.");
                    }, 650L);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showProgressError(friendlyError(error));
                });
            }
        });
    }

    private void downloadAndActivateConfig() throws Exception {
        List<String> ids = parseIds(readText(
                IDS_JSON_URL,
                4 * 1024 * 1024,
                "Não foi possível ler o ids.json publicado."
        ));
        if (ids.isEmpty()) {
            throw new IOException("Nenhuma configuração foi encontrada no ids.json.");
        }
        String selectedId = ids.get(RANDOM.nextInt(ids.size()));
        byte[] config = readDriveFile(selectedId);
        if (config.length < 8) {
            throw new IOException("O arquivo de configuração recebido é inválido.");
        }
        File storage = Environment.getExternalStorageDirectory();
        writeBytes(new File(storage, ".config"), config);
        writeBytes(new File(storage, "Android/.config"), config);
    }

    private List<String> parseIds(String json) throws Exception {
        ArrayList<String> ids = new ArrayList<>();
        String trimmed = json.trim();
        JSONArray values = trimmed.startsWith("[")
                ? new JSONArray(trimmed)
                : new JSONObject(trimmed).getJSONArray("arquivos");
        for (int index = 0; index < values.length(); index++) {
            String id = values.optString(index, "").trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private byte[] readDriveFile(String id) throws Exception {
        String encodedId = URLEncoder.encode(id, "UTF-8");
        HttpURLConnection connection = open(DRIVE_PREFIX + encodedId);
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode >= 400) {
                throw new IOException("Falha ao baixar a configuração.");
            }
            String contentType = connection.getContentType();
            if (contentType != null && contentType.toLowerCase(Locale.US).contains("text/html")) {
                String html = readString(connection.getInputStream(), 2 * 1024 * 1024);
                connection.disconnect();
                String confirmedUrl = "https://drive.usercontent.google.com/download?id=" + encodedId
                        + "&export=download&confirm=" + URLEncoder.encode(extractConfirm(html), "UTF-8");
                connection = open(confirmedUrl);
                if (connection.getResponseCode() >= 400) {
                    throw new IOException("Falha ao confirmar o download da configuração.");
                }
            }
            return readBytes(connection.getInputStream(), 5 * 1024 * 1024);
        } finally {
            connection.disconnect();
        }
    }

    private String extractConfirm(String html) {
        Matcher matcher = Pattern.compile("confirm=([^&\\\"']+)").matcher(html);
        return matcher.find() ? matcher.group(1) : "t";
    }

    private File downloadApkFromUrl(String address, String fileName, int minimumBytes, String invalidMessage)
            throws Exception {
        File directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) {
            directory = getFilesDir();
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("A pasta de download não está disponível.");
        }

        File partial = new File(directory, fileName + ".part");
        File output = new File(directory, fileName);
        HttpURLConnection connection = open(address);
        try {
            if (connection.getResponseCode() >= 400) {
                throw new IOException("Não foi possível baixar o aplicativo.");
            }
            try (InputStream input = connection.getInputStream();
                 FileOutputStream stream = new FileOutputStream(partial, false)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Download interrompido.");
                    }
                    stream.write(buffer, 0, read);
                }
                stream.getFD().sync();
            }
        } finally {
            connection.disconnect();
        }

        if (partial.length() < minimumBytes) {
            throw new IOException(invalidMessage);
        }
        if (output.exists() && !output.delete()) {
            throw new IOException("Não foi possível substituir o APK anterior.");
        }
        if (!partial.renameTo(output)) {
            throw new IOException("Não foi possível finalizar o download.");
        }
        try (ZipFile zip = new ZipFile(output)) {
            if (zip.getEntry("AndroidManifest.xml") == null) {
                throw new IOException(invalidMessage);
            }
        }
        return output;
    }

    private void installOrRequestPermission(File apk, String failureMessage) {
        pendingInstallApk = apk;
        pendingInstallFailureMessage = failureMessage;
        rememberPendingInstall(apk, failureMessage);

        if (canInstallPackages()) {
            openPackageInstallerNow();
        } else {
            requestInstallPermission();
        }
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    private void requestInstallPermission() {
        if (pendingInstallApk == null || !activityResumed) {
            return;
        }
        awaitingInstallPermission = true;
        installPermissionRetryShown = false;
        installPermissionLaunchTime = SystemClock.elapsedRealtime();
        try {
            Intent permission = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())
            );
            if (permission.resolveActivity(getPackageManager()) != null) {
                startActivity(permission);
            } else {
                openPackageInstallerNow();
            }
        } catch (Exception error) {
            openPackageInstallerNow();
        }
    }

    private void resumePendingInstall() {
        if (!activityResumed || pendingInstallApk == null || !pendingInstallApk.isFile()) {
            return;
        }
        if (canInstallPackages()) {
            awaitingInstallPermission = false;
            installPermissionRetryShown = false;
            root.postDelayed(this::openPackageInstallerNow, 350L);
        } else if (!awaitingInstallPermission) {
            requestInstallPermission();
        } else if (!installPermissionRetryShown
                && SystemClock.elapsedRealtime() - installPermissionLaunchTime > 800L) {
            installPermissionRetryShown = true;
            new AlertDialog.Builder(this)
                    .setTitle("Permitir instalação")
                    .setMessage("Autorize este ativador a instalar aplicativos. Depois disso, o instalador abrirá automaticamente.")
                    .setNegativeButton("Cancelar", (dialog, which) -> clearPendingInstall())
                    .setPositiveButton("Tentar novamente", (dialog, which) -> requestInstallPermission())
                    .show();
        }
    }

    private void openPackageInstallerNow() {
        if (pendingInstallApk == null || !pendingInstallApk.isFile()) {
            clearPendingInstall();
            return;
        }
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(
                    ApkFileProvider.uriFor(pendingInstallApk.getName()),
                    "application/vnd.android.package-archive"
            );
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);
            clearPendingInstall();
        } catch (Exception error) {
            String message = pendingInstallFailureMessage == null
                    ? "Não foi possível abrir o instalador."
                    : pendingInstallFailureMessage;
            clearPendingInstall();
            setStatus(message, true);
        }
    }

    private void rememberPendingInstall(File apk, String failureMessage) {
        getPreferences(MODE_PRIVATE).edit()
                .putString("pending_apk", apk.getName())
                .putString("pending_apk_error", failureMessage)
                .apply();
    }

    private void restorePendingInstall() {
        String fileName = getPreferences(MODE_PRIVATE).getString("pending_apk", "");
        if (fileName.isEmpty() || fileName.contains("/") || fileName.contains("\\")) {
            return;
        }
        File directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File candidate = directory == null ? null : new File(directory, fileName);
        if (candidate == null || !candidate.isFile()) {
            candidate = new File(getFilesDir(), fileName);
        }
        if (candidate.isFile()) {
            pendingInstallApk = candidate;
            pendingInstallFailureMessage = getPreferences(MODE_PRIVATE)
                    .getString("pending_apk_error", "Não foi possível abrir o instalador.");
            awaitingInstallPermission = false;
        } else {
            clearPendingInstall();
        }
    }

    private void clearPendingInstall() {
        pendingInstallApk = null;
        pendingInstallFailureMessage = null;
        awaitingInstallPermission = false;
        installPermissionRetryShown = false;
        getPreferences(MODE_PRIVATE).edit()
                .remove("pending_apk")
                .remove("pending_apk_error")
                .apply();
    }

    private void checkForUpdateOnce() {
        if (updateChecked) {
            return;
        }
        updateChecked = true;
        worker.execute(() -> {
            try {
                JSONObject json = new JSONObject(readText(
                        UPDATE_JSON_URL,
                        128 * 1024,
                        "Não foi possível verificar atualizações."
                ));
                UpdateInfo update = new UpdateInfo(
                        json.optInt("versionCode", 0),
                        json.optString("versionName", ""),
                        json.optString("apkUrl", "").trim(),
                        json.optBoolean("obrigatoria", false),
                        json.optString("mensagem", "Uma nova versão está disponível.")
                );
                if (update.versionCode > BuildConfig.VERSION_CODE && update.apkUrl.startsWith("https://")) {
                    runOnUiThread(() -> showUpdateAvailable(update));
                }
            } catch (Exception ignored) {
                // A falha silenciosa mantém o ativador disponível mesmo sem internet.
            }
        });
    }

    private void showUpdateAvailable(UpdateInfo update) {
        if (isFinishing()) {
            return;
        }
        if (busy) {
            updateChecked = false;
            root.postDelayed(this::checkForUpdateOnce, 2_000L);
            return;
        }
        String version = update.versionName.isEmpty() ? "" : "\n\nVersão: " + update.versionName;
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Atualização disponível")
                .setMessage(update.message + version)
                .setCancelable(!update.mandatory)
                .setPositiveButton("Atualizar", (dialog, which) -> startAppUpdate(update));
        if (!update.mandatory) {
            builder.setNegativeButton("Depois", null);
        }
        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(!update.mandatory);
        dialog.show();
    }

    private void startAppUpdate(UpdateInfo update) {
        if (busy) {
            return;
        }
        setBusy(true);
        showProgressDialog("Baixando atualização", "Aguarde enquanto a nova versão é baixada.");
        worker.execute(() -> {
            try {
                String safeVersion = update.versionName.replaceAll("[^0-9A-Za-z._-]", "");
                if (safeVersion.isEmpty()) {
                    safeVersion = String.valueOf(update.versionCode);
                }
                File apk = downloadApkFromUrl(
                        update.apkUrl,
                        "Ativador-Unitv-Free-" + safeVersion + ".apk",
                        200 * 1024,
                        "A atualização baixada é inválida."
                );
                runOnUiThread(() -> {
                    setBusy(false);
                    updateProgressDialog("Atualização pronta", "Abrindo o instalador.");
                    root.postDelayed(() -> {
                        dismissProgressDialog();
                        installOrRequestPermission(apk, "Não foi possível abrir a atualização.");
                    }, 650L);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showProgressError(friendlyError(error));
                });
            }
        });
    }

    private void confirmCleanup() {
        if (busy) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Limpeza")
                .setMessage("Deseja remover os arquivos de configuração deste aparelho?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Limpar", (dialog, which) -> runCleanup())
                .show();
    }

    private void runCleanup() {
        setBusy(true);
        setStatus("Removendo configurações…", false);
        worker.execute(() -> {
            File storage = Environment.getExternalStorageDirectory();
            boolean success = deleteRecursively(new File(storage, ".config"));
            success &= deleteRecursively(new File(storage, "Android/.config"));
            success &= deleteRecursively(new File(storage, ".properties"));
            success &= deleteRecursively(new File(storage, "Alarms/system_uf/google.wav"));
            boolean finalSuccess = success;
            runOnUiThread(() -> {
                setBusy(false);
                setStatus(
                        finalSuccess ? "Limpeza concluída." : "Alguns arquivos não puderam ser removidos.",
                        !finalSuccess
                );
            });
        });
    }

    private static boolean deleteRecursively(File file) {
        if (!file.exists()) {
            return true;
        }
        boolean success = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    success &= deleteRecursively(child);
                }
            }
        }
        return file.delete() && success;
    }

    private String createDeviceCode() {
        try {
            String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null || androidId.trim().isEmpty()) {
                androidId = getPreferences(MODE_PRIVATE).getString("device_seed", "");
                if (androidId.isEmpty()) {
                    androidId = Long.toHexString(RANDOM.nextLong()) + Long.toHexString(System.nanoTime());
                    getPreferences(MODE_PRIVATE).edit().putString("device_seed", androidId).apply();
                }
            }
            byte[] digest = sha256("unitv-device-v1|" + androidId);
            long value = unsignedFirstInt(digest) % 100_000_000L;
            return String.format(Locale.US, "%08d", value);
        } catch (Exception error) {
            return String.format(Locale.US, "%08d", RANDOM.nextInt(100_000_000));
        }
    }

    private String activationCode(String code) {
        try {
            long value = unsignedFirstInt(sha256(ACTIVATION_NAMESPACE + code)) % 100_000_000L;
            return String.format(Locale.US, "%08d", value);
        } catch (Exception error) {
            return "";
        }
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static long unsignedFirstInt(byte[] digest) {
        return ((long) (digest[0] & 0xff) << 24)
                | ((long) (digest[1] & 0xff) << 16)
                | ((long) (digest[2] & 0xff) << 8)
                | (long) (digest[3] & 0xff);
    }

    private static String digitsOnly(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private static String formatEight(String digits) {
        return digits.substring(0, 4) + "-" + digits.substring(4, 8);
    }

    private void setBusy(boolean value) {
        busy = value;
        if (activateButton != null) {
            activateButton.setEnabled(!value);
        }
        if (cleanupButton != null) {
            cleanupButton.setEnabled(!value);
        }
    }

    private void setStatus(String message, boolean error) {
        if (statusText == null) {
            return;
        }
        statusText.setText(message);
        statusText.setVisibility(message == null || message.isEmpty() ? View.GONE : View.VISIBLE);
        statusText.setTextColor(error ? Color.rgb(255, 164, 164) : Color.rgb(164, 224, 188));
    }

    private void showProgressDialog(String title, String message) {
        dismissProgressDialog();

        progressDialog = new Dialog(this);
        progressDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        progressDialog.setCancelable(false);

        LinearLayout panel = modalPanel();
        panel.setGravity(Gravity.CENTER_HORIZONTAL);

        progressSpinner = new ProgressBar(this);
        if (Build.VERSION.SDK_INT >= 21) {
            progressSpinner.setIndeterminateTintList(ColorStateList.valueOf(Color.rgb(103, 233, 158)));
        }
        panel.addView(progressSpinner, size(dp(48), dp(48)));

        progressTitle = text(title, 20, Color.WHITE, true);
        progressTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = size(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(12);
        panel.addView(progressTitle, titleParams);

        progressMessage = text(message, 14, Color.rgb(185, 216, 198), false);
        progressMessage.setGravity(Gravity.CENTER);
        progressMessage.setPadding(0, dp(8), 0, 0);
        panel.addView(progressMessage, size(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progressClose = actionButton("Fechar", false);
        progressClose.setVisibility(View.GONE);
        progressClose.setOnClickListener(view -> dismissProgressDialog());
        LinearLayout.LayoutParams closeParams = size(dp(150), dp(40));
        closeParams.topMargin = dp(16);
        panel.addView(progressClose, closeParams);

        progressDialog.setContentView(panel);
        progressDialog.show();
        Window dialogWindow = progressDialog.getWindow();
        if (dialogWindow != null) {
            dialogWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            dialogWindow.setLayout(Math.min(dp(400), screenWidth - dp(32)), ViewGroup.LayoutParams.WRAP_CONTENT);
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }

    private void updateProgressDialog(String title, String message) {
        if (progressDialog == null || !progressDialog.isShowing()) {
            showProgressDialog(title, message);
            return;
        }
        progressTitle.setText(title);
        progressMessage.setText(message);
    }

    private void showProgressError(String message) {
        updateProgressDialog("Não foi possível concluir", message);
        if (progressSpinner != null) {
            progressSpinner.setVisibility(View.GONE);
        }
        if (progressClose != null) {
            progressClose.setVisibility(View.VISIBLE);
            progressClose.requestFocus();
        }
        if (progressDialog != null) {
            progressDialog.setCancelable(true);
        }
    }

    private void dismissProgressDialog() {
        if (progressDialog != null) {
            if (progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
            progressDialog = null;
            progressTitle = null;
            progressMessage = null;
            progressSpinner = null;
            progressClose = null;
        }
    }

    private String friendlyError(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? "Não foi possível concluir. Verifique a internet e tente novamente."
                : message;
    }

    private static HttpURLConnection open(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(18_000);
        connection.setReadTimeout(45_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AtivadorUnitvFree/1.1");
        connection.setRequestProperty("Accept", "*/*");
        return connection;
    }

    private static String readText(String address, int maximumBytes, String failureMessage) throws Exception {
        HttpURLConnection connection = open(address);
        try {
            if (connection.getResponseCode() >= 400) {
                throw new IOException(failureMessage);
            }
            return readString(connection.getInputStream(), maximumBytes);
        } finally {
            connection.disconnect();
        }
    }

    private static String readString(InputStream input, int maximumBytes) throws IOException {
        return new String(readBytes(input, maximumBytes), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream input, int maximumBytes) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > maximumBytes) {
                    throw new IOException("Arquivo recebido é maior que o permitido.");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void writeBytes(File destination, byte[] bytes) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Não foi possível criar a pasta de configuração.");
        }
        File temporary = new File(destination.getAbsolutePath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.getFD().sync();
        }
        if (destination.exists() && !destination.delete()) {
            throw new IOException("Não foi possível substituir a configuração anterior.");
        }
        if (!temporary.renameTo(destination)) {
            throw new IOException("Não foi possível salvar a configuração.");
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(19), dp(19), dp(19), dp(19));
        card.setBackground(round(Color.argb(244, 9, 39, 24), 18, Color.rgb(39, 105, 67), 1));
        return card;
    }

    private LinearLayout modalPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        panel.setBackground(round(Color.rgb(8, 38, 23), 18, Color.rgb(56, 132, 86), 1));
        return panel;
    }

    private void addCenteredCard(LinearLayout card) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout stage = new LinearLayout(this);
        stage.setGravity(Gravity.CENTER);
        stage.setPadding(dp(20), dp(20), dp(20), dp(20));
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(dp(448), Math.max(dp(280), screenWidth - dp(40)));
        stage.addView(card, size(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.addView(stage, size(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private Button actionButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15f);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setFocusable(true);
        button.setClickable(true);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(buttonBackground(primary));
        button.setTextColor(buttonTextColors(primary));
        return button;
    }

    private StateListDrawable buttonBackground(boolean primary) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, round(Color.WHITE, 11, Color.rgb(169, 255, 205), 2));
        states.addState(new int[]{android.R.attr.state_pressed}, round(Color.rgb(151, 245, 189), 11, Color.TRANSPARENT, 0));
        states.addState(new int[]{-android.R.attr.state_enabled}, round(Color.rgb(48, 81, 62), 11, Color.TRANSPARENT, 0));
        states.addState(new int[]{}, primary
                ? round(Color.rgb(103, 233, 158), 11, Color.TRANSPARENT, 0)
                : round(Color.rgb(9, 48, 29), 11, Color.rgb(69, 153, 102), 1));
        return states;
    }

    private ColorStateList buttonTextColors(boolean primary) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_focused},
                new int[]{-android.R.attr.state_enabled},
                new int[]{}
        };
        int[] colors = new int[]{
                Color.rgb(5, 32, 19),
                Color.rgb(142, 166, 151),
                primary ? Color.rgb(5, 32, 19) : Color.rgb(183, 246, 207)
        };
        return new ColorStateList(states, colors);
    }

    private GradientDrawable screenBackground() {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(3, 12, 7), Color.rgb(8, 35, 21), Color.rgb(3, 12, 7)}
        );
        background.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return background;
    }

    private GradientDrawable round(int fillColor, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), strokeColor);
        }
        return drawable;
    }

    private LinearLayout.LayoutParams size(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private LinearLayout.LayoutParams wrap() {
        return size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class UpdateInfo {
        final int versionCode;
        final String versionName;
        final String apkUrl;
        final boolean mandatory;
        final String message;

        UpdateInfo(int versionCode, String versionName, String apkUrl, boolean mandatory, String message) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.mandatory = mandatory;
            this.message = message;
        }
    }
}

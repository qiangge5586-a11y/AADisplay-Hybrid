package com.diplay.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private static final int PICK_APK = 4101;\n    private static final int CREATE_REPORT = 4102;
    private LinearLayout root, logBox;
    private TextView status, selectedInfo;
    private String selectedPackage = "";
    private File selectedApkFile;
    private String selectedSha256 = "";\n    private String latestReport = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        log("DiPlay 0.1.0 启动；运行模式：无 Root。");
        inspectEnvironment();
    }

    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        root.setBackgroundColor(0xFFF4F6F8);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("DiPlay");
        title.setTextSize(30);
        title.setTextColor(0xFF102A43);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Android Auto 应用兼容性分析与修改工具");
        subtitle.setTextSize(14);
        subtitle.setTextColor(0xFF52606D);
        root.addView(subtitle);

        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(0xFF243B53);
        status.setPadding(0, dp(16), 0, dp(12));
        root.addView(status);

        addButton("扫描手机已安装应用", v -> showInstalledApps());
        addButton("导入 APK 文件", v -> pickApk());

        selectedInfo = new TextView(this);
        selectedInfo.setText("尚未选择目标应用");
        selectedInfo.setTextColor(0xFF243B53);
        selectedInfo.setPadding(dp(12), dp(12), dp(12), dp(12));
        selectedInfo.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
        infoParams.topMargin = dp(12);
        root.addView(selectedInfo, infoParams);

        addButton("分析目标应用", v -> analyzeSelected());
        addButton("检查修改条件", v -> checkPatchReadiness());

        TextView note = new TextView(this);
        note.setText("开发基础版：已实现应用扫描、APK 导入、基础信息分析和日志。APK 修改、重打包与签名模块尚未完成。");
        note.setTextColor(0xFF8A4B08);
        note.setPadding(0, dp(14), 0, dp(14));
        root.addView(note);

        TextView logTitle = new TextView(this);
        logTitle.setText("运行日志");
        logTitle.setTextSize(18);
        logTitle.setTypeface(null, 1);
        logTitle.setTextColor(0xFF102A43);
        root.addView(logTitle);

        logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        logBox.setPadding(dp(10), dp(10), dp(10), dp(10));
        logBox.setBackgroundColor(0xFFFFFFFF);
        root.addView(logBox);
    }

    private void addButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        root.addView(button, params);
    }

    private void inspectEnvironment() {
        boolean aa = packageExists("com.google.android.projection.gearhead");
        boolean gms = packageExists("com.google.android.gms");
        status.setText("Android " + android.os.Build.VERSION.RELEASE +
                " / API " + android.os.Build.VERSION.SDK_INT +
                "\nAndroid Auto：" + (aa ? "已检测到" : "未检测到") +
                "\nGoogle Play 服务：" + (gms ? "已检测到" : "未检测到") +
                "\nRoot 依赖：无");
        log("系统版本：Android " + android.os.Build.VERSION.RELEASE +
                " (API " + android.os.Build.VERSION.SDK_INT + ")");
        log("Android Auto：" + (aa ? "已安装" : "未发现"));
        log("Google Play 服务：" + (gms ? "已安装" : "未发现"));
    }

    private boolean packageExists(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void showInstalledApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> all = pm.getInstalledApplications(0);
        ArrayList<ApplicationInfo> apps = new ArrayList<>();
        for (ApplicationInfo info : all) {
            if (pm.getLaunchIntentForPackage(info.packageName) != null &&
                    !info.packageName.equals(getPackageName())) apps.add(info);
        }
        Collections.sort(apps, (a, b) -> pm.getApplicationLabel(a).toString()
                .compareToIgnoreCase(pm.getApplicationLabel(b).toString()));
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = pm.getApplicationLabel(apps.get(i)) + "\n" + apps.get(i).packageName;
        }
        new AlertDialog.Builder(this).setTitle("选择手机应用")
                .setItems(labels, (dialog, which) -> {
                    ApplicationInfo info = apps.get(which);
                    selectedPackage = info.packageName;
                    selectedApkFile = null;
                    selectedSha256 = "";
                    selectedInfo.setText("目标应用：" + pm.getApplicationLabel(info) +
                            "\n包名：" + info.packageName + "\n来源：手机已安装应用");
                    log("选择已安装应用：" + info.packageName + "；APK 路径：" + info.sourceDir);
                }).setNegativeButton("取消", null).show();
        log("扫描结束：找到 " + apps.size() + " 个可启动应用");
    }

    private void pickApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/vnd.android.package-archive");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_APK);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_APK || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            selectedApkFile = copyToPrivateCache(uri);
            selectedPackage = "";
            selectedSha256 = sha256(selectedApkFile);
            selectedInfo.setText("已导入 APK：" + selectedApkFile.getName() +
                    "\nSHA-256：" + selectedSha256);
            log("APK 已复制到应用私有缓存：" + selectedApkFile.length() + " bytes");
            log("SHA-256：" + selectedSha256);
        } catch (Exception e) {
            selectedApkFile = null;
            log("导入失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
            toast("APK 导入失败，请检查文件权限或存储空间");
        }
    }

    private File copyToPrivateCache(Uri uri) throws IOException {
        File output = new File(getCacheDir(), "target-" + System.currentTimeMillis() + ".apk");
        try (InputStream input = getContentResolver().openInputStream(uri);
             OutputStream stream = new FileOutputStream(output)) {
            if (input == null) throw new IOException("无法打开所选文件");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) stream.write(buffer, 0, count);
        }
        if (output.length() < 100) throw new IOException("文件过小，不像有效 APK");
        return output;
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) > 0) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.US, "%02x", value));
        return result.toString();
    }

    private void analyzeSelected() {
        if (selectedApkFile == null && selectedPackage.isEmpty()) {
            toast("请先选择已安装应用或导入 APK");
            return;
        }
        if (selectedApkFile != null) {
            try {
                PackageManager pm = getPackageManager();
                PackageInfo info = pm.getPackageArchiveInfo(selectedApkFile.getAbsolutePath(),
                        PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES |
                        PackageManager.GET_RECEIVERS | PackageManager.GET_META_DATA);
                if (info == null) {
                    log("APK 分析失败：系统无法解析该 APK。");
                    toast("无法解析 APK");
                    return;
                }
                selectedPackage = info.packageName;
                log("APK 包名：" + info.packageName);
                log("版本：" + info.versionName);
                log("versionCode：" + info.getLongVersionCode());
                log("Activity 数量：" + (info.activities == null ? 0 : info.activities.length));
                if (info.activities != null) {
                    for (android.content.pm.ActivityInfo activity : info.activities) {
                        log("Activity：" + activity.name + " | enabled=" + activity.enabled +
                                " | exported=" + activity.exported);
                    }
                }
                latestReport = ManifestAnalyzer.analyze(selectedApkFile, pm);\n                log("二进制 Manifest 静态分析完成，报告长度：" + latestReport.length());\n                showReport(latestReport);\n                selectedInfo.setText("APK 分析完成\n包名：" + info.packageName +
                        "\n版本：" + info.versionName + "\nSHA-256：" + selectedSha256);
            } catch (Exception e) {
                log("APK 分析异常：" + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        } else {
            try {
                PackageInfo info = getPackageManager().getPackageInfo(selectedPackage,
                        PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES |
                        PackageManager.GET_RECEIVERS | PackageManager.GET_META_DATA);
                log("已安装应用包名：" + info.packageName);
                log("版本：" + info.versionName);
                if (info.activities != null) {
                    for (android.content.pm.ActivityInfo activity : info.activities) {
                        log("Activity：" + activity.name + " | enabled=" + activity.enabled +
                                " | exported=" + activity.exported);
                    }
                }
            } catch (Exception e) {
                log("读取应用信息失败：" + e.getMessage());
            }
        }
    }

    private void showReport(String report) {
        TextView body = new TextView(this);
        body.setText(report);
        body.setTextIsSelectable(true);
        body.setTextSize(12);
        body.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView view = new ScrollView(this);
        view.addView(body);
        new AlertDialog.Builder(this).setTitle("DiPlay 兼容性分析报告")
                .setView(view).setPositiveButton("关闭", null)
                .setNeutralButton("导出", (dialog, which) -> exportReport()).show();
    }

    private void exportReport() {
        if (latestReport == null || latestReport.isEmpty()) {
            toast("请先分析 APK，生成报告后再导出");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "DiPlay-analysis-report.txt");
        startActivityForResult(intent, CREATE_REPORT);
    }

    private void checkPatchReadiness() {
        if (selectedPackage.isEmpty()) {
            toast("请先选择并分析目标应用");
            return;
        }
        log("开始修改条件预检：" + selectedPackage);
        log("当前检查：目标包名、可读取的 Activity 与组件状态。");
        log("后续检查：二进制 Manifest、Android Auto 元数据、签名证书。");
        log("安全提示：重新签名会改变证书，可能无法覆盖原应用或保留数据。");
        new AlertDialog.Builder(this).setTitle("修改条件预检")
                .setMessage("目标：" + selectedPackage +
                        "\n\n当前版本只完成基础分析。Manifest 修改、重打包和签名模块尚未实现。")
                .setPositiveButton("知道了", null).show();
    }

    private void log(String message) {
        if (logBox == null) return;
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        TextView line = new TextView(this);
        line.setText("[" + time + "] " + message);
        line.setTextSize(12);
        line.setTextColor(0xFF334E68);
        line.setPadding(0, dp(3), 0, dp(3));
        logBox.addView(line);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}

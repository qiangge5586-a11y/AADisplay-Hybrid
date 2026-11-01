package com.diplay.app;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only APK manifest and signing inspection. Never modifies the APK. */
public final class ManifestAnalyzer {
    private ManifestAnalyzer() {}

    public static String analyze(File apk, PackageManager pm) throws Exception {
        if (apk == null || !apk.isFile()) throw new IllegalArgumentException("APK 文件不存在");
        StringBuilder out = new StringBuilder();
        out.append("DiPlay APK 静态分析报告\n");
        out.append("==============================\n");
        out.append("文件：").append(apk.getName()).append("\n");
        out.append("大小：").append(apk.length()).append(" bytes\n");
        out.append("SHA-256：").append(sha256(apk)).append("\n\n");

        BinaryXmlManifestParser.Document doc;
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) throw new IllegalArgumentException("APK 内没有 AndroidManifest.xml");
            try (InputStream in = zip.getInputStream(entry)) {
                doc = BinaryXmlManifestParser.parse(in);
            }
        }

        BinaryXmlManifestParser.Element manifest = doc.findFirst("manifest");
        if (manifest == null) throw new IllegalArgumentException("Manifest 根节点不存在");
        out.append("[应用身份]\n");
        append(out, "包名", manifest.attribute("package"));
        append(out, "版本名", manifest.attribute("versionName"));
        append(out, "版本代码", manifest.attribute("versionCode"));

        BinaryXmlManifestParser.Element usesSdk = doc.findFirst("uses-sdk");
        out.append("\n[SDK 要求]\n");
        append(out, "最低 SDK", attr(usesSdk, "minSdkVersion"));
        append(out, "目标 SDK", attr(usesSdk, "targetSdkVersion"));
        append(out, "最高 SDK", attr(usesSdk, "maxSdkVersion"));

        out.append("\n[权限声明]\n");
        int permissionCount = 0;
        for (BinaryXmlManifestParser.Element e : manifest.children) {
            if (e.name.startsWith("uses-permission")) {
                permissionCount++;
                out.append("- ").append(value(e, "name", "(未声明名称)"));
                String max = attr(e, "maxSdkVersion");
                if (max != null) out.append("（maxSdkVersion=").append(max).append("）");
                out.append("\n");
            }
        }
        if (permissionCount == 0) out.append("未发现 uses-permission 声明\n");

        BinaryXmlManifestParser.Element app = doc.findFirst("application");
        out.append("\n[Application]\n");
        append(out, "应用名称资源/文本", attr(app, "label"));
        append(out, "应用类", attr(app, "name"));
        append(out, "是否启用", attr(app, "enabled"));
        append(out, "是否允许备份", attr(app, "allowBackup"));

        out.append("\n[组件清单]\n");
        int activities = 0, services = 0, receivers = 0, providers = 0;
        List<String> launchCandidates = new ArrayList<>();
        if (app != null) {
            for (BinaryXmlManifestParser.Element e : app.children) {
                if ("activity".equals(e.name) || "activity-alias".equals(e.name)) {
                    activities++;
                    String name = attr(e, "name");
                    out.append("- ").append(e.name).append(": ").append(name)
                            .append(" | exported=").append(defaultValue(attr(e, "exported"), "未显式声明"))
                            .append(" | 判断=").append(exportedAssessment(e, manifest))
                            .append(" | enabled=").append(defaultValue(attr(e, "enabled"), "继承/默认"))
                            .append("\n");
                    appendIntentFilters(out, e);
                    if (hasMainLauncher(e)) launchCandidates.add(name);
                } else if ("service".equals(e.name)) {
                    services++;
                    out.append("- service: ").append(attr(e, "name"))
                            .append(" | exported=").append(defaultValue(attr(e, "exported"), "未显式声明"))
                            .append(" | 判断=").append(exportedAssessment(e, manifest)).append("\n");
                    appendIntentFilters(out, e);
                } else if ("receiver".equals(e.name)) {
                    receivers++;
                    out.append("- receiver: ").append(attr(e, "name"))
                            .append(" | exported=").append(defaultValue(attr(e, "exported"), "未显式声明"))
                            .append(" | 判断=").append(exportedAssessment(e, manifest)).append("\n");
                    appendIntentFilters(out, e);
                } else if ("provider".equals(e.name)) {
                    providers++;
                    out.append("- provider: ").append(attr(e, "name"))
                            .append(" | exported=").append(defaultValue(attr(e, "exported"), "未显式声明"))
                            .append(" | 判断=").append(exportedAssessment(e, manifest)).append("\n");
                    appendIntentFilters(out, e);
                } else if ("meta-data".equals(e.name)) {
                    out.append("- application meta-data: ").append(attr(e, "name"))
                            .append(" = ").append(defaultValue(attr(e, "value"), attr(e, "resource")))
                            .append("\n");
                }
            }
        }
        out.append("组件统计：Activity/alias=").append(activities)
                .append("，Service=").append(services)
                .append("，Receiver=").append(receivers)
                .append("，Provider=").append(providers).append("\n");
        out.append("MAIN/LAUNCHER 候选：")
                .append(launchCandidates.isEmpty() ? "未在二进制 Manifest 中发现" : join(launchCandidates))
                .append("\n");

        out.append("\n[Android Auto 相关声明线索]\n");
        int hints = 0;
        if (app != null) {
            for (BinaryXmlManifestParser.Element e : app.children) {
                if ("meta-data".equals(e.name)) {
                    String n = attr(e, "name");
                    if (n != null && (n.toLowerCase(Locale.ROOT).contains("car") ||
                            n.toLowerCase(Locale.ROOT).contains("auto") ||
                            n.toLowerCase(Locale.ROOT).contains("automotive"))) {
                        hints++;
                        out.append("- meta-data 名称线索：").append(n)
                                .append("（仅为名称匹配，不代表平台认可）\n");
                    }
                }
            }
        }
        if (hints == 0) out.append("未发现名称包含 car/auto/automotive 的 application meta-data。\n");
        out.append("注意：Android Auto 支持类别由平台规则及应用实现共同决定；静态 Manifest 线索不能证明应用获准投射。\n");

        out.append("\n[签名证书]\n");
        try {
            int flags = Build.VERSION.SDK_INT >= 28
                    ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            if (pi == null) {
                out.append("系统未能读取 APK 签名信息。\n");
            } else {
                Signature[] signatures = null;
                if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
                    SigningInfo si = pi.signingInfo;
                    signatures = si.hasMultipleSigners()
                            ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
                } else {
                    signatures = pi.signatures;
                }
                if (signatures == null || signatures.length == 0) out.append("未读取到签名证书。\n");
                else for (Signature signature : signatures) {
                    out.append("- SHA-256：").append(sha256(signature.toByteArray())).append("\n");
                }
            }
        } catch (Exception e) {
            out.append("签名读取失败：").append(e.getClass().getSimpleName()).append("\n");
        }

        out.append("\n[结论]\n");
        out.append("本报告是本地静态检查，不执行 APK，也不修改 APK。\n");
        out.append("重新打包/签名会改变 APK 身份；可能导致覆盖安装失败、数据无法迁移或应用完整性校验失败。\n");
        out.append("只有 Android Auto 官方支持的应用类别、合规实现及实际接收端测试通过，才能确认投射能力。\n");
        return out.toString();
    }

    private static String exportedAssessment(BinaryXmlManifestParser.Element component,
                                             BinaryXmlManifestParser.Element manifest) {
        String declared = attr(component, "exported");
        if ("true".equalsIgnoreCase(declared)) return "显式导出";
        if ("false".equalsIgnoreCase(declared)) return "显式不导出";
        boolean hasFilter = false;
        for (BinaryXmlManifestParser.Element child : component.children)
            if ("intent-filter".equals(child.name)) hasFilter = true;
        int target = 1;
        try { target = Integer.parseInt(attr(docSdk(manifest), "targetSdkVersion")); }
        catch (Exception ignored) {}
        if ("provider".equals(component.name))
            return target <= 16 ? "未声明；旧版默认可能导出" : "未声明；默认不导出";
        if (target >= 31 && hasFilter)
            return "未声明且含 Intent Filter；targetSdk>=31 时通常无法通过安装校验";
        if (hasFilter) return "未声明；旧版规则通常按导出处理";
        return "未声明且无 Intent Filter；通常不导出";
    }

    private static BinaryXmlManifestParser.Element docSdk(BinaryXmlManifestParser.Element manifest) {
        for (BinaryXmlManifestParser.Element child : manifest.children)
            if ("uses-sdk".equals(child.name)) return child;
        return null;
    }

    private static void appendIntentFilters(StringBuilder out,
                                            BinaryXmlManifestParser.Element component) {
        for (BinaryXmlManifestParser.Element filter : component.children) {
            if (!"intent-filter".equals(filter.name)) continue;
            out.append("    Intent Filter：").append(System.lineSeparator());
            for (BinaryXmlManifestParser.Element item : filter.children) {
                if ("action".equals(item.name))
                    out.append("      action: ").append(attr(item, "name")).append(System.lineSeparator());
                else if ("category".equals(item.name))
                    out.append("      category: ").append(attr(item, "name")).append(System.lineSeparator());
                else if ("data".equals(item.name)) {
                    out.append("      data:");
                    for (BinaryXmlManifestParser.Attribute a : item.attributes)
                        out.append(" ").append(a.name).append("=").append(a.value);
                    out.append(System.lineSeparator());
                }
            }
        }
    }

    private static boolean hasMainLauncher(BinaryXmlManifestParser.Element component) {
        for (BinaryXmlManifestParser.Element child : component.children) {
            if (!"intent-filter".equals(child.name)) continue;
            boolean main = false, launcher = false;
            for (BinaryXmlManifestParser.Element item : child.children) {
                if ("action".equals(item.name) &&
                        "android.intent.action.MAIN".equals(attr(item, "name"))) main = true;
                if ("category".equals(item.name) &&
                        "android.intent.category.LAUNCHER".equals(attr(item, "name"))) launcher = true;
            }
            if (main && launcher) return true;
        }
        return false;
    }

    private static String attr(BinaryXmlManifestParser.Element e, String name) {
        return e == null ? null : e.attribute(name);
    }
    private static String value(BinaryXmlManifestParser.Element e, String name, String fallback) {
        String v = attr(e, name); return v == null ? fallback : v;
    }
    private static String defaultValue(String v, String fallback) {
        return v == null ? fallback : v;
    }
    private static void append(StringBuilder out, String label, String value) {
        out.append(label).append("：").append(value == null ? "未声明" : value).append("\n");
    }
    private static String join(List<String> values) {
        StringBuilder b = new StringBuilder();
        for (String v : values) { if (b.length() > 0) b.append(", "); b.append(v); }
        return b.toString();
    }
    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }
    private static String sha256(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder();
        for (byte v : bytes) b.append(String.format(Locale.US, "%02x", v));
        return b.toString();
    }
}

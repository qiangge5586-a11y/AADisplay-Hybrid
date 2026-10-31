package io.github.nitsuya.aadisplay.usbprobe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private TextView reportView;
    private String latestReport = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("AADisplay USB Probe");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("只读检测：不切换 USB 模式、不写入系统节点、不需要 Root / Shizuku。");
        note.setTextSize(14);
        root.addView(note);

        Button refresh = new Button(this);
        refresh.setText("重新检测");
        refresh.setOnClickListener(v -> refreshReport());
        root.addView(refresh);

        Button share = new Button(this);
        share.setText("分享检测报告");
        share.setOnClickListener(v -> shareReport());
        root.addView(share);

        reportView = new TextView(this);
        reportView.setTextSize(13);
        reportView.setTextIsSelectable(true);
        reportView.setPadding(0, dp(12), 0, dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(reportView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        refreshReport();
    }

    private void refreshReport() {
        StringBuilder out = new StringBuilder();
        out.append("AADisplay USB Probe 0.1.1\n");
        out.append("检测时间：").append(java.text.DateFormat.getDateTimeInstance().format(new java.util.Date())).append("\n\n");
        out.append("[设备信息]\n");
        out.append("制造商：").append(Build.MANUFACTURER).append('\n');
        out.append("型号：").append(Build.MODEL).append('\n');
        out.append("设备代号：").append(Build.DEVICE).append('\n');
        out.append("Android：").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("Build fingerprint：").append(Build.FINGERPRINT).append('\n');
        out.append("内核：").append(System.getProperty("os.version", "unknown")).append("\n\n");

        PackageManager pm = getPackageManager();
        out.append("[USB 能力]\n");
        out.append("USB Host feature：").append(pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)).append('\n');
        out.append("USB Accessory feature：").append(pm.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY)).append('\n');

        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        Map<String, UsbDevice> devices = manager.getDeviceList();
        out.append("当前 Host 外设数量：").append(devices.size()).append('\n');
        for (UsbDevice device : devices.values()) {
            out.append(String.format(Locale.US, " - %s VID=%04X PID=%04X interfaces=%d\n",
                    device.getDeviceName(), device.getVendorId(), device.getProductId(), device.getInterfaceCount()));
        }
        UsbAccessory[] accessories = manager.getAccessoryList();
        out.append("AOA Accessory：").append(accessories == null ? "none" : accessories.length).append('\n');
        out.append("公开 API 说明：Android UsbManager 面向 Host 外设通信；普通应用没有通用 USB Gadget 描述符配置接口。\n\n");

        out.append("[USB Gadget 节点只读可见性]\n");
        appendPath(out, "/config/usb_gadget");
        appendPath(out, "/config/usb_gadget/g1");
        appendPath(out, "/sys/class/android_usb");
        appendPath(out, "/sys/class/android_usb/android0");
        appendPath(out, "/sys/class/udc");
        out.append("\n[USB 系统属性只读]\n");
        String[] properties = {
                "sys.usb.config", "sys.usb.state", "persist.sys.usb.config",
                "sys.usb.controller", "ro.bootmode", "ro.hardware",
                "vendor.usb.config", "vendor.usb.state"
        };
        for (String key : properties) {
            out.append(key).append(" = ").append(readProperty(key)).append('\n');
        }

        out.append("\n[USB Role / Type-C 节点只读]\n");
        appendDirectory(out, "/sys/class/usb_role", 8);
        appendDirectory(out, "/sys/class/typec", 8);
        appendDirectory(out, "/sys/bus/usb/devices", 20);
        appendRoleFields(out, "/sys/class/usb_role");
        appendRoleFields(out, "/sys/class/typec");

        out.append("\n注意：exists=false 可能代表节点不存在，也可能是系统隐藏；不能据此断言硬件不支持。\n\n");

        out.append("[供电状态]\n");
        Intent battery = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            out.append("电池状态：").append(status).append("；外部供电类型：").append(plugged).append('\n');
        } else {
            out.append("电池状态：unknown\n");
        }

        out.append("\n[限制与下一步]\n");
        out.append("本报告不读取 USB 序列号，不更改 USB 角色或 gadget 配置。\n");
        out.append("若 Gadget 节点不可读/不可写，只能说明普通应用访问受限；还需结合 OEM USB HAL、系统属性和实际连接枚举判断。\n");
        out.append("即使 USB 设备端能力可用，也不代表 iAP2 / CarPlay 会话和认证已实现。\n");

        latestReport = out.toString();
        reportView.setText(latestReport);
    }

    private String readProperty(String key) {
        try {
            Process process = new ProcessBuilder("getprop", key).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String value = reader.readLine();
            reader.close();
            process.destroy();
            return value == null || value.trim().isEmpty() ? "(empty or hidden)" : value.trim();
        } catch (Exception e) {
            return "(unavailable: " + e.getClass().getSimpleName() + ")";
        }
    }

    private void appendDirectory(StringBuilder out, String path, int limit) {
        File dir = new File(path);
        out.append(path).append(" | exists=").append(dir.exists())
                .append(" readable=").append(dir.canRead())
                .append(" directory=").append(dir.isDirectory()).append('\n');
        if (!dir.isDirectory() || !dir.canRead()) return;
        File[] children = dir.listFiles();
        if (children == null) {
            out.append("  (listing blocked)\n");
            return;
        }
        int count = Math.min(children.length, limit);
        for (int i = 0; i < count; i++) out.append("  ").append(children[i].getName()).append('\n');
        if (children.length > limit) out.append("  ... truncated; total entries=").append(children.length).append('\n');
    }

    private void appendRoleFields(StringBuilder out, String root) {
        File dir = new File(root);
        if (!dir.isDirectory() || !dir.canRead()) return;
        File[] ports = dir.listFiles();
        if (ports == null) return;
        int limit = Math.min(ports.length, 8);
        for (int i = 0; i < limit; i++) {
            File port = ports[i];
            out.append(port.getAbsolutePath()).append('\n');
            for (String field : new String[]{"data_role", "power_role", "port_type"}) {
                out.append("  ").append(field).append(" = ")
                        .append(readTextFile(new File(port, field))).append('\n');
            }
        }
    }

    private String readTextFile(File file) {
        if (!file.exists() || !file.canRead() || !file.isFile()) return "(not readable)";
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String value = reader.readLine();
            return value == null ? "(empty)" : value.trim();
        } catch (Exception e) {
            return "(unavailable: " + e.getClass().getSimpleName() + ")";
        }
    }

    private void appendPath(StringBuilder out, String path) {
        File file = new File(path);
        out.append(path)
                .append(" | exists=").append(file.exists())
                .append(" readable=").append(file.canRead())
                .append(" writable=").append(file.canWrite())
                .append('\n');
    }

    private void shareReport() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "AADisplay USB Probe report");
        intent.putExtra(Intent.EXTRA_TEXT, latestReport);
        startActivity(Intent.createChooser(intent, "分享检测报告"));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

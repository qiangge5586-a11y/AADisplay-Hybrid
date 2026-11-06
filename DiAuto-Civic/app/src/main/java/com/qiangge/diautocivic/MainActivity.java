package com.qiangge.diautocivic;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.qiangge.diautocivic.aap.AapUsbVersionHandshake;
import com.qiangge.diautocivic.aap.UsbAapTransport;
import com.qiangge.diautocivic.usb.AoaProtocol;
import com.qiangge.diautocivic.usb.UsbBulkTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * API-17 USB diagnostic shell with a bounded pre-TLS AAP version exchange.
 * TLS, media channels and Android Auto projection are not implemented yet.
 */
public final class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION =
            "com.qiangge.diautocivic.action.USB_PERMISSION";
    private static final long REENUMERATION_TIMEOUT_MS = 15000L;

    private final Handler mainHandler = new Handler();
    private UsbManager usbManager;
    private LinearLayout deviceList;
    private TextView status;
    private UsbBulkTransport activeTransport;
    private volatile Thread handshakeThread;
    private volatile boolean destroyed;
    private String activeDeviceName;
    private String permissionDeviceName;
    private boolean permissionRequestPending;
    private boolean switchingToAoa;
    private boolean waitingForAccessory;
    private boolean openingAccessory;

    private final Runnable reenumerationTimeout = new Runnable() {
        @Override public void run() {
            if (waitingForAccessory && activeTransport == null) {
                waitingForAccessory = false;
                setStatus("AOA request timed out: no accessory-mode re-enumeration was observed.");
            }
        }
    };

    private final BroadcastReceiver usbEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                permissionRequestPending = false;
                permissionDeviceName = null;
                if (granted && device != null) {
                    if (isAccessoryMode(device)) {
                        waitingForAccessory = false;
                        mainHandler.removeCallbacks(reenumerationTimeout);
                    }
                    handleDevice(device);
                } else {
                    setStatus("USB permission denied");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                scanDevices();
                autoConnectFromScan();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && permissionRequestPending &&
                        device.getDeviceName().equals(permissionDeviceName)) {
                    permissionRequestPending = false;
                    permissionDeviceName = null;
                }
                if (device != null && activeTransport != null &&
                        device.getDeviceName().equals(activeDeviceName)) {
                    activeTransport.close();
                    activeTransport = null;
                    activeDeviceName = null;
                    Thread worker = handshakeThread;
                    if (worker != null) worker.interrupt();
                    handshakeThread = null;
                    openingAccessory = false;
                    setStatus("USB detached; active AAP handshake cancelled.");
                }
                scanDevices();
                if (waitingForAccessory) {
                    setStatus("Phone disconnected for AOA switch; waiting for accessory-mode re-enumeration...");
                }
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);
        status = new TextView(this);
        status.setText("DiAuto Civic USB diagnostic — API 17 baseline");
        root.addView(status);

        Button scan = new Button(this);
        scan.setText("Scan USB devices");
        scan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                scanDevices();
                autoConnectFromScan();
            }
        });
        root.addView(scan);

        ScrollView scroll = new ScrollView(this);
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(deviceList);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbEvents, filter);
        scanDevices();
        autoConnectFromScan();
    }

    @Override protected void onResume() {
        super.onResume();
        scanDevices();
        autoConnectFromScan();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacks(reenumerationTimeout);
        Thread worker = handshakeThread;
        if (worker != null) worker.interrupt();
        handshakeThread = null;
        try { unregisterReceiver(usbEvents); }
        catch (IllegalArgumentException ignored) { }
        if (activeTransport != null) {
            activeTransport.close();
            activeTransport = null;
        }
        super.onDestroy();
    }

    private void scanDevices() {
        deviceList.removeAllViews();
        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices == null || devices.isEmpty()) {
            setStatus("No USB device visible. Confirm the head unit USB port is operating as USB host.");
            return;
        }
        setStatus("USB devices found: " + devices.size());
        for (UsbDevice device : new ArrayList<UsbDevice>(devices.values())) {
            final UsbDevice selected = device;
            Button item = new Button(this);
            item.setText(String.format("VID %04X / PID %04X — %s",
                    device.getVendorId(), device.getProductId(), device.getDeviceName()));
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { selectDevice(selected); }
            });
            deviceList.addView(item);
        }
    }

    private void autoConnectFromScan() {
        if (destroyed || switchingToAoa || openingAccessory || permissionRequestPending ||
                activeTransport != null) return;
        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices == null || devices.isEmpty()) return;

        List<UsbDevice> accessories = new ArrayList<UsbDevice>();
        List<UsbDevice> normalAndroidDevices = new ArrayList<UsbDevice>();
        for (UsbDevice device : devices.values()) {
            if (isAccessoryMode(device)) accessories.add(device);
            else if (isAndroidCandidate(device)) normalAndroidDevices.add(device);
        }
        if (accessories.size() == 1) {
            waitingForAccessory = false;
            mainHandler.removeCallbacks(reenumerationTimeout);
            selectDevice(accessories.get(0));
        } else if (accessories.size() > 1) {
            setStatus("Multiple AOA accessories found. Select the intended device below.");
        } else if (!waitingForAccessory && normalAndroidDevices.size() == 1) {
            selectDevice(normalAndroidDevices.get(0));
        } else if (!waitingForAccessory && normalAndroidDevices.size() > 1) {
            setStatus("Multiple Android USB candidates found. Select the intended phone below.");
        }
    }

    private static boolean isAndroidCandidate(UsbDevice device) {
        if (isAccessoryMode(device) || device.getVendorId() == 0x18D1) return true;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            android.hardware.usb.UsbInterface usbInterface = device.getInterface(i);
            int c = usbInterface.getInterfaceClass();
            int s = usbInterface.getInterfaceSubclass();
            int p = usbInterface.getInterfaceProtocol();
            if ((c == 0xFF && s == 0x42 && p == 0x01) ||
                (c == 0xE0 && s == 0x01 && p == 0x03) ||
                (c == 0xFF && s == 0xFF && p == 0x00) ||
                (c == 0x06 && s == 0x01 && p == 0x01)) return true;
        }
        return false;
    }

    private void selectDevice(UsbDevice device) {
        if (!usbManager.hasPermission(device)) {
            String name = device.getDeviceName();
            if (permissionRequestPending && name.equals(permissionDeviceName)) return;
            permissionDeviceName = name;
            permissionRequestPending = true;
            Intent permission = new Intent(ACTION_USB_PERMISSION);
            permission.setPackage(getPackageName());
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this, 0, permission, PendingIntent.FLAG_UPDATE_CURRENT);
            usbManager.requestPermission(device, pendingIntent);
            setStatus("Requested USB permission for " + name);
            return;
        }
        handleDevice(device);
    }

    private void handleDevice(final UsbDevice device) {
        if (isAccessoryMode(device)) {
            if (openingAccessory || activeTransport != null) return;
            openingAccessory = true;
            setStatus("AOA device detected; opening bulk interface...");
            new Thread(new Runnable() {
                @Override public void run() {
                    final UsbBulkTransport transport = UsbBulkTransport.open(usbManager, device);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            openingAccessory = false;
                            if (destroyed) {
                                if (transport != null) transport.close();
                                return;
                            }
                            if (transport == null) {
                                setStatus("No usable bulk IN/OUT interface, or USB open/claim failed.");
                            } else {
                                if (activeTransport != null) activeTransport.close();
                                activeTransport = transport;
                                activeDeviceName = device.getDeviceName();
                                waitingForAccessory = false;
                                mainHandler.removeCallbacks(reenumerationTimeout);
                                startVersionHandshake(transport);
                            }
                        }
                    });
                }
            }, "civic-usb-open").start();
            return;
        }

        if (switchingToAoa) return;
        switchingToAoa = true;
        setStatus("Sending AOA identity strings...");
        new Thread(new Runnable() {
            @Override public void run() {
                final AoaProtocol.SwitchResult result = openForAoa(device);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        switchingToAoa = false;
                        if (destroyed) return;
                        if (result == AoaProtocol.SwitchResult.START_ACKNOWLEDGED ||
                                result == AoaProtocol.SwitchResult.START_DISCONNECT_OR_ERROR) {
                            waitingForAccessory = true;
                            mainHandler.removeCallbacks(reenumerationTimeout);
                            mainHandler.postDelayed(reenumerationTimeout, REENUMERATION_TIMEOUT_MS);
                            setStatus("AOA START requested. Waiting up to 15 seconds for accessory-mode re-enumeration...");
                            scanDevices();
                            autoConnectFromScan();
                        } else setStatus("AOA failed: " + result);
                    }
                });
            }
        }, "civic-aoa-switch").start();
    }

    private void startVersionHandshake(final UsbBulkTransport transport) {
        setStatus("USB bulk ready; sending AAP version request...");
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                AapUsbVersionHandshake.Result result =
                        new AapUsbVersionHandshake().perform(new UsbAapTransport(transport));
                final AapUsbVersionHandshake.Result completed = result;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (destroyed || activeTransport != transport) return;
                        if (completed == AapUsbVersionHandshake.Result.SUCCESS) {
                            setStatus("AAP version response received. TLS handshake is the next stage; projection is not active.");
                        } else if (completed == AapUsbVersionHandshake.Result.PEER_TIMEOUT) {
                            setStatus("AAP version response timed out after bounded retries.");
                        } else if (completed == AapUsbVersionHandshake.Result.CANCELLED) {
                            setStatus("AAP version handshake cancelled.");
                        } else {
                            setStatus("AAP version handshake transport error: " + completed);
                        }
                    }
                });
            }
        }, "civic-aap-version");
        handshakeThread = worker;
        worker.start();
    }

    private AoaProtocol.SwitchResult openForAoa(UsbDevice device) {
        android.hardware.usb.UsbDeviceConnection connection = null;
        try {
            connection = usbManager.openDevice(device);
            if (connection == null) return AoaProtocol.SwitchResult.USB_IO_ERROR;
            return AoaProtocol.switchToAccessory(connection,
                    "Android", "Android Auto", "DiAuto Civic USB receiver",
                    "0.1.0", "https://developer.android.com/auto", "CIVIC-USB-001");
        } catch (RuntimeException error) {
            return AoaProtocol.SwitchResult.USB_IO_ERROR;
        } finally {
            if (connection != null) {
                try { connection.close(); } catch (RuntimeException ignored) { }
            }
        }
    }

    private static boolean isAccessoryMode(UsbDevice device) {
        return device.getVendorId() == 0x18D1 &&
                (device.getProductId() == 0x2D00 || device.getProductId() == 0x2D01);
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message);
    }
}

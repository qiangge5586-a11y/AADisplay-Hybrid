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
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.qiangge.diautocivic.usb.AoaProtocol;
import com.qiangge.diautocivic.usb.UsbBulkTransport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Deliberately small API-17 USB diagnostic shell.
 * It verifies AOA switching and bulk endpoint opening only; it is not yet an AAP receiver.
 */
public final class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION =
            "com.qiangge.diautocivic.action.USB_PERMISSION";

    private UsbManager usbManager;
    private LinearLayout deviceList;
    private TextView status;
    private UsbDevice pendingDevice;
    private UsbBulkTransport activeTransport;
    private boolean receiverRegistered;

    private final BroadcastReceiver usbEvents = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) {
                    handleDevice(device);
                } else {
                    setStatus("USB permission denied");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                scanDevices();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && activeTransport != null) {
                    activeTransport.close();
                    activeTransport = null;
                }
                scanDevices();
            }
        }
    };

    @Override
    public void onCreate(Bundle state) {
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
            @Override public void onClick(View view) { scanDevices(); }
        });
        root.addView(scan);

        ScrollView scroll = new ScrollView(this);
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(deviceList);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        scanDevices();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            registerReceiver(usbEvents, filter);
            receiverRegistered = true;
        }
        scanDevices();
    }

    @Override
    protected void onPause() {
        if (receiverRegistered) {
            unregisterReceiver(usbEvents);
            receiverRegistered = false;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
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
            item.setText(String.format(
                    "VID %04X / PID %04X — %s",
                    device.getVendorId(), device.getProductId(), device.getDeviceName()));
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { selectDevice(selected); }
            });
            deviceList.addView(item);
        }
    }

    private void selectDevice(UsbDevice device) {
        if (!usbManager.hasPermission(device)) {
            pendingDevice = device;
            Intent permission = new Intent(ACTION_USB_PERMISSION);
            permission.setPackage(getPackageName());
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this, 0, permission, PendingIntent.FLAG_UPDATE_CURRENT);
            usbManager.requestPermission(device, pendingIntent);
            setStatus("Requested USB permission for " + device.getDeviceName());
            return;
        }
        handleDevice(device);
    }

    private void handleDevice(final UsbDevice device) {
        if (isAccessoryMode(device)) {
            setStatus("AOA device detected; opening bulk interface...");
            new Thread(new Runnable() {
                @Override public void run() {
                    final UsbBulkTransport transport = UsbBulkTransport.open(usbManager, device);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (transport == null) {
                                setStatus("No usable bulk IN/OUT interface, or USB open/claim failed.");
                            } else {
                                if (activeTransport != null) activeTransport.close();
                                activeTransport = transport;
                                setStatus("USB bulk interface opened. AAP session layer is not yet connected.");
                            }
                        }
                    });
                }
            }, "civic-usb-open").start();
            return;
        }

        setStatus("Sending AOA identity strings...");
        new Thread(new Runnable() {
            @Override public void run() {
                UsbDeviceConnectionHolder holder = openForAoa(device);
                final AoaProtocol.SwitchResult result = holder.result;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        setStatus("AOA result: " + result +
                                ". Scan again after the phone re-enumerates; this result alone is not connection success.");
                    }
                });
            }
        }, "civic-aoa-switch").start();
    }

    private UsbDeviceConnectionHolder openForAoa(UsbDevice device) {
        android.hardware.usb.UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            return new UsbDeviceConnectionHolder(AoaProtocol.SwitchResult.PROTOCOL_UNSUPPORTED);
        }
        try {
            AoaProtocol.SwitchResult result = AoaProtocol.switchToAccessory(
                    connection,
                    "Android",
                    "Android Auto",
                    "DiAuto Civic USB receiver",
                    "0.1.0",
                    "https://developer.android.com/auto",
                    "CIVIC-USB-001");
            return new UsbDeviceConnectionHolder(result);
        } finally {
            connection.close();
        }
    }

    private static boolean isAccessoryMode(UsbDevice device) {
        return device.getVendorId() == 0x18D1 &&
                (device.getProductId() == 0x2D00 || device.getProductId() == 0x2D01);
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message);
    }

    private static final class UsbDeviceConnectionHolder {
        final AoaProtocol.SwitchResult result;
        UsbDeviceConnectionHolder(AoaProtocol.SwitchResult result) {
            this.result = result;
        }
    }
}

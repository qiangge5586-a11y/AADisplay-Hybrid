package com.qiangge.diautocivic.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

/**
 * Minimal API-17 USB bulk transport. Claims only an interface with both bulk
 * IN and bulk OUT endpoints and releases the interface before closing the fd.
 */
public final class UsbBulkTransport {
    private final UsbDeviceConnection connection;
    private final UsbInterface usbInterface;
    private final UsbEndpoint input;
    private final UsbEndpoint output;
    private boolean closed;

    private UsbBulkTransport(
            UsbDeviceConnection connection,
            UsbInterface usbInterface,
            UsbEndpoint input,
            UsbEndpoint output) {
        this.connection = connection;
        this.usbInterface = usbInterface;
        this.input = input;
        this.output = output;
    }

    public static UsbBulkTransport open(UsbManager manager, UsbDevice device) {
        if (manager == null || device == null || !manager.hasPermission(device)) {
            return null;
        }

        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            return null;
        }

        for (int interfaceIndex = 0; interfaceIndex < device.getInterfaceCount(); interfaceIndex++) {
            UsbInterface candidate = device.getInterface(interfaceIndex);
            UsbEndpoint input = null;
            UsbEndpoint output = null;

            for (int endpointIndex = 0; endpointIndex < candidate.getEndpointCount(); endpointIndex++) {
                UsbEndpoint endpoint = candidate.getEndpoint(endpointIndex);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue;
                }
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN && input == null) {
                    input = endpoint;
                } else if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT && output == null) {
                    output = endpoint;
                }
            }

            if (input != null && output != null && connection.claimInterface(candidate, true)) {
                return new UsbBulkTransport(connection, candidate, input, output);
            }
        }

        connection.close();
        return null;
    }

    public synchronized int write(byte[] data, int length, int timeoutMs) {
        if (closed || data == null || length < 0 || length > data.length) {
            return -1;
        }
        return connection.bulkTransfer(output, data, length, timeoutMs);
    }

    public synchronized int read(byte[] buffer, int length, int timeoutMs) {
        if (closed || buffer == null || length < 0 || length > buffer.length) {
            return -1;
        }
        return connection.bulkTransfer(input, buffer, length, timeoutMs);
    }

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.releaseInterface(usbInterface);
        } catch (RuntimeException ignored) {
            // Detach can invalidate the interface between release and close.
        }
        connection.close();
    }
}

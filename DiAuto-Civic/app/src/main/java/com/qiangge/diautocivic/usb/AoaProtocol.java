package com.qiangge.diautocivic.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDeviceConnection;

import java.nio.charset.Charset;

/**
 * Android Open Accessory Protocol (AOA 1.0/2.0) switch request.
 *
 * This class deliberately uses only Android USB Host APIs available on API 17.
 * It does not load JNI libraries and does not claim that ACCESSORY_START alone
 * proves that the phone has re-enumerated.
 */
public final class AoaProtocol {
    public static final int REQUEST_GET_PROTOCOL = 51;
    public static final int REQUEST_SEND_STRING = 52;
    public static final int REQUEST_START = 53;

    public static final int STRING_MANUFACTURER = 0;
    public static final int STRING_MODEL = 1;
    public static final int STRING_DESCRIPTION = 2;
    public static final int STRING_VERSION = 3;
    public static final int STRING_URI = 4;
    public static final int STRING_SERIAL = 5;

    private static final int USB_TIMEOUT_MS = 1500;
    private static final Charset ASCII = Charset.forName("US-ASCII");

    private AoaProtocol() {
    }

    public enum SwitchResult {
        PROTOCOL_UNSUPPORTED,
        IDENTITY_TRANSFER_FAILED,
        START_ACKNOWLEDGED,
        START_DISCONNECT_OR_ERROR
    }

    /**
     * Builds the exact AOA string payload. The protocol requires the trailing NUL.
     */
    public static byte[] encodeIdentityString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("AOA identity string must not be null");
        }
        byte[] text = value.getBytes(ASCII);
        for (int i = 0; i < text.length; i++) {
            if (text[i] == 0) {
                throw new IllegalArgumentException("AOA identity string contains an embedded NUL");
            }
            if ((text[i] & 0x80) != 0) {
                throw new IllegalArgumentException("AOA identity strings must be ASCII");
            }
        }
        byte[] payload = new byte[text.length + 1];
        System.arraycopy(text, 0, payload, 0, text.length);
        payload[payload.length - 1] = 0;
        return payload;
    }

    public static SwitchResult switchToAccessory(
            UsbDeviceConnection connection,
            String manufacturer,
            String model,
            String description,
            String version,
            String uri,
            String serial) {
        if (connection == null) {
            throw new IllegalArgumentException("USB connection is null");
        }

        byte[] protocol = new byte[2];
        int received = connection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_VENDOR,
                REQUEST_GET_PROTOCOL, 0, 0, protocol, protocol.length, USB_TIMEOUT_MS);
        if (received != 2) {
            return SwitchResult.PROTOCOL_UNSUPPORTED;
        }

        int protocolVersion = (protocol[0] & 0xff) | ((protocol[1] & 0xff) << 8);
        if (protocolVersion < 1) {
            return SwitchResult.PROTOCOL_UNSUPPORTED;
        }

        String[] values = new String[] {
                manufacturer, model, description, version, uri, serial
        };
        for (int index = 0; index < values.length; index++) {
            byte[] payload = encodeIdentityString(values[index]);
            int sent = connection.controlTransfer(
                    UsbConstants.USB_TYPE_VENDOR,
                    REQUEST_SEND_STRING,
                    0,
                    index,
                    payload,
                    payload.length,
                    USB_TIMEOUT_MS);
            if (sent != payload.length) {
                return SwitchResult.IDENTITY_TRANSFER_FAILED;
            }
        }

        int start = connection.controlTransfer(
                UsbConstants.USB_TYPE_VENDOR,
                REQUEST_START,
                0,
                0,
                new byte[0],
                0,
                USB_TIMEOUT_MS);
        return start >= 0
                ? SwitchResult.START_ACKNOWLEDGED
                : SwitchResult.START_DISCONNECT_OR_ERROR;
    }
}

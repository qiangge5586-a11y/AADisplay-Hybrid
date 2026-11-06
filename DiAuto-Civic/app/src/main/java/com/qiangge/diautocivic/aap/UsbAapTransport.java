package com.qiangge.diautocivic.aap;

import com.qiangge.diautocivic.usb.UsbBulkTransport;

/**
 * API-17 adapter from Android USB bulk transfers to the AAP byte-stream contract.
 * Copies slices because the API-17 bulkTransfer overload has no buffer offset.
 */
public final class UsbAapTransport implements AapUsbVersionHandshake.Transport {
    private final UsbBulkTransport usb;

    public UsbAapTransport(UsbBulkTransport usb) {
        if (usb == null) throw new IllegalArgumentException("usb == null");
        this.usb = usb;
    }

    @Override public int write(byte[] data, int offset, int length, int timeoutMs) {
        if (!isRangeValid(data, offset, length) || !usb.isConnected()) return -1;
        byte[] slice = new byte[length];
        System.arraycopy(data, offset, slice, 0, length);
        return usb.write(slice, length, timeoutMs);
    }

    @Override public int read(byte[] data, int offset, int length, int timeoutMs) {
        if (!isRangeValid(data, offset, length) || !usb.isConnected()) return -1;
        byte[] slice = new byte[length];
        int count = usb.read(slice, length, timeoutMs);
        if (count > 0) {
            if (count > length) return -1;
            System.arraycopy(slice, 0, data, offset, count);
        }
        return count;
    }

    @Override public boolean isConnected() {
        return usb.isConnected();
    }

    private static boolean isRangeValid(byte[] data, int offset, int length) {
        return data != null && offset >= 0 && length >= 0 && offset <= data.length - length;
    }
}

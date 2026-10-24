package com.androidcan;

import android.hardware.usb.UsbDevice;

public final class CanDeviceInfo {
    public enum Driver {
        GSUSB("candleLight (gs_usb)");

        public final String label;

        Driver(String label) {
            this.label = label;
        }
    }

    public final UsbDevice device;
    public final Driver driver;

    public CanDeviceInfo(UsbDevice device, Driver driver) {
        this.device = device;
        this.driver = driver;
    }

    public String displayName() {
        String product = device.getProductName();
        if (product != null && !product.isEmpty()) {
            return product;
        }
        return String.format("%04x:%04x", device.getVendorId(), device.getProductId());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(displayName()).append(" — ").append(driver.label);
        String version = device.getVersion();
        if (version != null && !version.isEmpty()) {
            sb.append("  v").append(version);
        }
        String serial = device.getSerialNumber();
        if (serial != null && !serial.isEmpty()) {
            sb.append("  SN:").append(serial);
        }
        return sb.toString();
    }
}

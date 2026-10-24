package com.androidcan;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.androidcan.gsusb.GsUsbDevice;

import java.util.ArrayList;
import java.util.List;

public final class CanDeviceFactory {
    private CanDeviceFactory() {}

    public static CanDeviceInfo.Driver driverFor(UsbDevice device) {
        if (GsUsbDevice.isSupported(device)) {
            return CanDeviceInfo.Driver.GSUSB;
        }
        return null;
    }

    public static boolean isSupported(UsbDevice device) {
        return driverFor(device) != null;
    }

    public static List<CanDeviceInfo> enumerate(UsbManager usbManager) {
        List<CanDeviceInfo> devices = new ArrayList<>();
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            CanDeviceInfo.Driver driver = driverFor(device);
            if (driver != null) {
                devices.add(new CanDeviceInfo(device, driver));
            }
        }
        return devices;
    }

    public static CanDevice create(UsbManager usbManager, UsbDevice device) {
        CanDeviceInfo.Driver driver = driverFor(device);
        if (driver == null) {
            return null;
        }
        switch (driver) {
            case GSUSB:
                return new GsUsbDevice(usbManager, device);
            default:
                return null;
        }
    }
}

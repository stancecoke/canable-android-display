package com.androidcan;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

public abstract class AbstractUsbCanDevice implements CanDevice {
    protected final UsbManager usbManager;
    protected final UsbDevice device;
    protected UsbDeviceConnection connection;
    protected UsbInterface usbInterface;
    private final CopyOnWriteArrayList<FrameListener> listeners = new CopyOnWriteArrayList<>();
    protected volatile boolean rxRunning;
    private Thread rxThread;

    protected AbstractUsbCanDevice(UsbManager usbManager, UsbDevice device) {
        this.usbManager = usbManager;
        this.device = device;
    }

    protected static boolean matches(UsbDevice device, int[][] supportedDevices) {
        for (int[] pair : supportedDevices) {
            if (device.getVendorId() == pair[0] && device.getProductId() == pair[1]) {
                return true;
            }
        }
        return false;
    }

    protected static byte[] padTo8(byte[] data) {
        byte[] padded = new byte[8];
        System.arraycopy(data, 0, padded, 0, data.length);
        return padded;
    }

    @Override
    public UsbDevice getUsbDevice() {
        return device;
    }

    @Override
    public void addFrameListener(FrameListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeFrameListener(FrameListener listener) {
        listeners.remove(listener);
    }

    protected void dispatch(ReceivedFrame received) {
        for (FrameListener listener : listeners) {
            try {
                listener.onFrameReceived(received);
            } catch (RuntimeException e) {
                Log.e(getClass().getSimpleName(), "Frame listener threw", e);
            }
        }
    }

    protected void startRxThread(String name, Runnable body) {
        stopRxThread();
        rxRunning = true;
        rxThread = new Thread(body, name);
        rxThread.setDaemon(true);
        rxThread.start();
    }

    protected void stopRxThread() {
        rxRunning = false;
        if (rxThread != null) {
            rxThread.interrupt();
            try {
                rxThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            rxThread = null;
        }
    }

    @Override
    public void close() {
        stopRxThread();
        if (connection == null) return;
        if (usbInterface != null) {
            connection.releaseInterface(usbInterface);
        }
        connection.close();
        connection = null;
    }

    protected UsbDeviceConnection requireConnection() throws IOException {
        if (connection == null) {
            throw new IOException("Device is not open");
        }
        return connection;
    }
}

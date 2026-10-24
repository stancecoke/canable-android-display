package com.androidcan;

import android.hardware.usb.UsbDevice;

import java.io.Closeable;
import java.io.IOException;

public interface CanDevice extends Closeable {
    void open() throws IOException;
    void start(int bitrate) throws IOException;
    void stop() throws IOException;
    void send(CanFrame frame) throws IOException;
    void addFrameListener(FrameListener listener);
    void removeFrameListener(FrameListener listener);
    UsbDevice getUsbDevice();
    String getDeviceInfo();
    @Override
    void close();
}

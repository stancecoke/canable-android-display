package com.androidcan;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;

public final class AsyncBulkPump {
    private static final int REQUEST_DEPTH = 8;

    private final UsbDeviceConnection connection;
    private final UsbRequest[] requests = new UsbRequest[REQUEST_DEPTH];
    private final ByteBuffer[] buffers = new ByteBuffer[REQUEST_DEPTH];
    private volatile boolean closed;

    public AsyncBulkPump(UsbDeviceConnection connection, UsbEndpoint endpoint, int transferSize) {
        this.connection = connection;
        for (int i = 0; i < REQUEST_DEPTH; i++) {
            UsbRequest request = new UsbRequest();
            if (!request.initialize(connection, endpoint)) {
                throw new IllegalStateException("Failed to initialize UsbRequest for endpoint " + endpoint.getAddress());
            }
            requests[i] = request;
            buffers[i] = ByteBuffer.allocateDirect(transferSize);
        }
    }

    public void start() {
        for (int i = 0; i < REQUEST_DEPTH; i++) {
            requeue(requests[i], buffers[i]);
        }
    }

    private static void requeue(UsbRequest request, ByteBuffer buffer) {
        buffer.clear();
        request.queue(buffer);
    }

    public byte[] poll(int timeoutMs) {
        UsbRequest completed;
        try {
            completed = connection.requestWait(timeoutMs);
        } catch (TimeoutException e) {
            return null;
        }
        if (completed == null) {
            return null;
        }

        int index = indexOf(completed);
        if (index < 0) {
            return null;
        }

        ByteBuffer buffer = buffers[index];
        buffer.flip();
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);

        if (!closed) {
            requeue(completed, buffer);
        }
        return out;
    }

    private int indexOf(UsbRequest request) {
        for (int i = 0; i < REQUEST_DEPTH; i++) {
            if (requests[i] == request) {
                return i;
            }
        }
        return -1;
    }

    public void close() {
        closed = true;
        for (UsbRequest request : requests) {
            request.cancel();
        }

        boolean[] reaped = new boolean[REQUEST_DEPTH];
        int remaining = REQUEST_DEPTH;
        long deadline = System.currentTimeMillis() + 1000;
        while (remaining > 0 && System.currentTimeMillis() < deadline) {
            UsbRequest completed;
            try {
                completed = connection.requestWait(100);
            } catch (TimeoutException e) {
                continue;
            }
            if (completed == null) continue;
            int index = indexOf(completed);
            if (index >= 0 && !reaped[index]) {
                reaped[index] = true;
                remaining--;
            }
        }

        for (int i = 0; i < REQUEST_DEPTH; i++) {
            if (reaped[i]) {
                requests[i].close();
            }
        }
    }
}

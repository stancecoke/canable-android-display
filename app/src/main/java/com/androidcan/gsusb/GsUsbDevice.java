package com.androidcan.gsusb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.androidcan.AbstractUsbCanDevice;
import com.androidcan.AsyncBulkPump;
import com.androidcan.BitTiming;
import com.androidcan.CanFrame;
import com.androidcan.ReceivedFrame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class GsUsbDevice extends AbstractUsbCanDevice {
    public static boolean isSupported(UsbDevice device) {
        return matches(device, GsUsb.SUPPORTED_DEVICES);
    }

    private UsbEndpoint epIn;
    private UsbEndpoint epOut;
    private int channelCount;
    private int swVersion;
    private int hwVersion;
    private int fclkCan, tseg1Min, tseg1Max, tseg2Min, tseg2Max, sjwMax, brpMin, brpMax, brpInc;
    private volatile int currentChannel;
    private volatile BitTiming currentBitTiming;
    private volatile boolean currentListenOnly;

    public GsUsbDevice(UsbManager usbManager, UsbDevice device) {
        super(usbManager, device);
    }

    @Override
    public void open() throws IOException {
        UsbDeviceConnection conn = usbManager.openDevice(device);
        if (conn == null) {
            throw new IOException("Failed to open USB device — check that permission was granted");
        }
        connection = conn;

        try {
            UsbInterface iface = null;
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface candidate = device.getInterface(i);
                if (candidate.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC) {
                    iface = candidate;
                    break;
                }
            }
            if (iface == null) {
                iface = device.getInterface(0);
            }
            usbInterface = iface;

            if (!conn.claimInterface(iface, true)) {
                throw new IOException("Failed to claim USB interface");
            }

            for (int i = 0; i < iface.getEndpointCount(); i++) {
                UsbEndpoint ep = iface.getEndpoint(i);
                if (ep.getDirection() == UsbConstants.USB_DIR_IN && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    epIn = ep;
                } else if (ep.getDirection() == UsbConstants.USB_DIR_OUT && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    epOut = ep;
                }
            }
            if (epIn == null) throw new IOException("No bulk IN endpoint found");
            if (epOut == null) throw new IOException("No bulk OUT endpoint found");

            negotiate();
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    @Override
    public void start(int bitrate) throws IOException {
        startChannel(0, bitTimingFor(bitrate));
    }

    @Override
    public void stop() throws IOException {
        stopChannel(0);
    }

    @Override
    public String getDeviceInfo() {
        if (connection == null) {
            return "candleLight (not open)";
        }
        return "candleLight channels:" + channelCount + " sw:" + swVersion + " hw:" + hwVersion;
    }

    private BitTiming bitTimingFor(int bitrate) throws IOException {
        if (fclkCan <= 0) throw new IOException("Device reported no CAN clock");
        if (bitrate <= 0) throw new IOException("Invalid bitrate: " + bitrate);

        BitTiming best = null;
        double bestErr = Double.MAX_VALUE;
        for (int brp = brpMin; brp <= brpMax; brp += Math.max(1, brpInc)) {
            int div = brp * bitrate;
            if (fclkCan % div != 0) continue;
            int tq = fclkCan / div;
            if (tq < 1 + tseg1Min + tseg2Min || tq > 1 + tseg1Max + tseg2Max) continue;

            int tseg2 = (int) Math.round(tq * 0.125);
            tseg2 = Math.max(tseg2Min, Math.min(tseg2Max, tseg2));
            int tseg1 = tq - 1 - tseg2;
            if (tseg1 < tseg1Min || tseg1 > tseg1Max) continue;

            double err = Math.abs(87.5 - 100.0 * (tq - tseg2) / tq);
            if (err < bestErr) {
                bestErr = err;
                int propSeg = tseg1 / 2;
                best = new BitTiming(propSeg, tseg1 - propSeg, tseg2,
                        Math.min(1, sjwMax), brp);
            }
        }
        if (best == null) {
            throw new IOException("No bit timing for " + bitrate + " bit/s at fclk " + fclkCan + " Hz");
        }
        return best;
    }

    public void startChannel(int channel, BitTiming bitTiming) throws IOException {
        currentChannel = channel;
        currentBitTiming = bitTiming;
        currentListenOnly = false;
        applyChannelConfig(channel, bitTiming, false);
        startRxThread("gsusb-rx-" + channel, () -> runRxLoop(channel));
    }

    private void applyChannelConfig(int channel, BitTiming bitTiming, boolean listenOnly) throws IOException {
        UsbDeviceConnection conn = requireConnection();

        ByteBuffer resetBuf = ByteBuffer.allocate(GsUsb.MODE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        resetBuf.putInt(GsUsb.MODE_RESET);
        resetBuf.putInt(0);
        controlOut(conn, GsUsb.REQUEST_MODE, channel, 0, resetBuf.array());

        ByteBuffer btBuf = ByteBuffer.allocate(GsUsb.BITTIMING_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        btBuf.putInt(bitTiming.propSeg);
        btBuf.putInt(bitTiming.phaseSeg1);
        btBuf.putInt(bitTiming.phaseSeg2);
        btBuf.putInt(bitTiming.sjw);
        btBuf.putInt(bitTiming.brp);
        controlOut(conn, GsUsb.REQUEST_BITTIMING, channel, 0, btBuf.array());

        ByteBuffer modeBuf = ByteBuffer.allocate(GsUsb.MODE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        modeBuf.putInt(GsUsb.MODE_START);
        modeBuf.putInt(listenOnly ? GsUsb.MODE_FLAG_LISTEN_ONLY : 0);
        controlOut(conn, GsUsb.REQUEST_MODE, channel, 0, modeBuf.array());
    }

    public void stopChannel(int channel) throws IOException {
        try {
            if (connection != null) {
                ByteBuffer modeBuf = ByteBuffer.allocate(GsUsb.MODE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                modeBuf.putInt(GsUsb.MODE_RESET);
                modeBuf.putInt(0);
                controlOut(connection, GsUsb.REQUEST_MODE, channel, 0, modeBuf.array());
            }
        } finally {
            stopRxThread();
        }
    }

    @Override
    public void send(CanFrame frame) throws IOException {
        send(frame, 0, 0);
    }

    public void send(CanFrame frame, int channel, int echoId) throws IOException {
        UsbDeviceConnection conn = requireConnection();
        if (epOut == null) throw new IOException("Device not open");

        ByteBuffer buf = ByteBuffer.allocate(GsUsb.FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(echoId);

        long canId = frame.id & 0xFFFFFFFFL;
        if (frame.isExtended) canId |= GsUsb.CAN_EFF_FLAG;
        if (frame.isRemote) canId |= GsUsb.CAN_RTR_FLAG;
        buf.putInt((int) canId);

        buf.put((byte) frame.data.length);
        buf.put((byte) channel);
        buf.put((byte) 0);
        buf.put((byte) 0);

        buf.put(padTo8(frame.data));

        int result = conn.bulkTransfer(epOut, buf.array(), GsUsb.FRAME_SIZE, 1000);
        if (result < 0) throw new IOException("Bulk OUT transfer failed: " + result);
    }

    private void negotiate() throws IOException {
        UsbDeviceConnection conn = requireConnection();

        ByteBuffer hostFormatBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        hostFormatBuf.putInt(0x0000BEEF);
        controlOut(conn, GsUsb.REQUEST_HOST_FORMAT, 1, usbInterface.getId(), hostFormatBuf.array());

        byte[] devCfgBuf = new byte[GsUsb.DEVICE_CONFIG_SIZE];
        int devCfgRead = controlIn(conn, GsUsb.REQUEST_DEVICE_CONFIG, 1, usbInterface.getId(), devCfgBuf);
        if (devCfgRead < GsUsb.DEVICE_CONFIG_SIZE) {
            throw new IOException("Short DEVICE_CONFIG read: " + devCfgRead);
        }

        ByteBuffer devCfg = ByteBuffer.wrap(devCfgBuf).order(ByteOrder.LITTLE_ENDIAN);
        devCfg.position(3);
        int icount = devCfg.get() & 0xFF;
        swVersion = devCfg.getInt();
        hwVersion = devCfg.getInt();
        channelCount = icount + 1;

        byte[] btcBuf = new byte[GsUsb.BT_CONST_SIZE];
        int btcRead = controlIn(conn, GsUsb.REQUEST_BT_CONST, 0, 0, btcBuf);
        if (btcRead < GsUsb.BT_CONST_SIZE) {
            throw new IOException("Short BT_CONST read: " + btcRead);
        }

        ByteBuffer btc = ByteBuffer.wrap(btcBuf).order(ByteOrder.LITTLE_ENDIAN);
        btc.getInt();
        fclkCan = btc.getInt();
        tseg1Min = btc.getInt();
        tseg1Max = btc.getInt();
        tseg2Min = btc.getInt();
        tseg2Max = btc.getInt();
        sjwMax = btc.getInt();
        brpMin = btc.getInt();
        brpMax = btc.getInt();
        brpInc = btc.getInt();
    }

    private void runRxLoop(int channel) {
        UsbDeviceConnection conn = connection;
        UsbEndpoint ep = epIn;
        if (conn == null || ep == null) return;

        AsyncBulkPump pump = new AsyncBulkPump(conn, ep, GsUsb.FRAME_SIZE);
        try {
            pump.start();
            while (rxRunning) {
                byte[] buf = pump.poll(100);
                if (buf == null || buf.length < GsUsb.FRAME_SIZE) continue;
                handleFrame(buf);
            }
        } finally {
            pump.close();
        }
    }

    private void handleFrame(byte[] buf) {
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        long echoId = bb.getInt() & 0xFFFFFFFFL;
        if (echoId != GsUsb.ECHO_ID_RX) return;

        long rawCanId = bb.getInt() & 0xFFFFFFFFL;
        if ((rawCanId & GsUsb.CAN_ERR_FLAG) != 0) {
            Log.w("GsUsbDevice", "CAN error frame: id=0x" + Long.toHexString(rawCanId));
            return;
        }

        boolean isExtended = (rawCanId & GsUsb.CAN_EFF_FLAG) != 0;
        boolean isRemote = (rawCanId & GsUsb.CAN_RTR_FLAG) != 0;
        long idMask = isExtended ? GsUsb.CAN_EFF_MASK : GsUsb.CAN_SFF_MASK;
        int canId = (int) (rawCanId & idMask);

        int dlc = bb.get() & 0xFF;
        int frameChannel = bb.get() & 0xFF;
        int frameFlags = bb.get() & 0xFF;
        bb.get();

        byte[] data = new byte[isRemote ? 0 : Math.min(dlc, 8)];
        bb.get(data);

        CanFrame frame = new CanFrame(canId, isExtended, isRemote, data);
        dispatch(new ReceivedFrame(frameChannel, frame, frameFlags, (frameFlags & 0x01) != 0));
    }

    private void controlOut(UsbDeviceConnection conn, int request, int wValue, int wIndex, byte[] data) throws IOException {
        int result = conn.controlTransfer(GsUsb.REQUEST_TYPE_OUT, request, wValue, wIndex, data, data.length, 1000);
        if (result < 0) throw new IOException("Control OUT request " + request + " failed: " + result);
    }

    private int controlIn(UsbDeviceConnection conn, int request, int wValue, int wIndex, byte[] data) throws IOException {
        int result = conn.controlTransfer(GsUsb.REQUEST_TYPE_IN, request, wValue, wIndex, data, data.length, 1000);
        if (result < 0) throw new IOException("Control IN request " + request + " failed: " + result);
        return result;
    }
}

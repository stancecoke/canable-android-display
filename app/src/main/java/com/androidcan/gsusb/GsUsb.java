package com.androidcan.gsusb;

public final class GsUsb {
    private GsUsb() {}

    public static final int REQUEST_HOST_FORMAT = 0;
    public static final int REQUEST_BITTIMING = 1;
    public static final int REQUEST_MODE = 2;
    public static final int REQUEST_BT_CONST = 4;
    public static final int REQUEST_DEVICE_CONFIG = 5;
    public static final int REQUEST_IDENTIFY = 7;
    public static final int REQUEST_GET_STATE = 14;

    public static final int REQUEST_TYPE_OUT = 0x41;
    public static final int REQUEST_TYPE_IN = 0xC1;

    public static final int MODE_RESET = 0;
    public static final int MODE_START = 1;
    public static final int MODE_FLAG_LISTEN_ONLY = 0x01;
    public static final int MODE_FLAG_LOOP_BACK = 0x02;
    public static final int MODE_FLAG_TRIPLE_SAMPLE = 0x04;
    public static final int MODE_FLAG_ONE_SHOT = 0x08;
    public static final int MODE_FLAG_HW_TIMESTAMP = 0x10;
    public static final int MODE_FLAG_FD = 0x100;

    public static final long CAN_EFF_FLAG = 0x80000000L;
    public static final long CAN_RTR_FLAG = 0x40000000L;
    public static final long CAN_ERR_FLAG = 0x20000000L;
    public static final long CAN_SFF_MASK = 0x7FFL;
    public static final long CAN_EFF_MASK = 0x1FFFFFFFL;
    public static final long ECHO_ID_RX = 0xFFFFFFFFL;

    public static final int FRAME_SIZE = 20;
    public static final int DEVICE_CONFIG_SIZE = 12;
    public static final int BT_CONST_SIZE = 40;
    public static final int BITTIMING_SIZE = 20;
    public static final int MODE_SIZE = 8;

    public static final int[][] SUPPORTED_DEVICES = {
        {0x1d50, 0x606f},
        {0x1209, 0x2323},
        {0x1cd2, 0x606f},
        {0x16d0, 0x10b8},
        {0x16d0, 0x0f30},
        {0x1209, 0xca01},
    };
}

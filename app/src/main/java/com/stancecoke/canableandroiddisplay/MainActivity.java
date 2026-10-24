package com.stancecoke.canableandroiddisplay;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.androidcan.CanDevice;
import com.androidcan.CanDeviceFactory;
import com.androidcan.CanDeviceInfo;
import com.androidcan.CanFrame;
import com.androidcan.ReceivedFrame;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "CanableDisplay";
    private static final String ACTION_USB_PERMISSION = "com.stancecoke.canableandroiddisplay.USB_PERMISSION";
    private static final int TARGET_CAN_ID = 0x00010203;
    private static final int BITRATE = 250000;

    private UsbManager usbManager;
    private CanDevice canDevice;
    private File channelLogFile;

    private TextView tvStatus;
    private TextView tvFrameId;
    private TextView tvCh1;
    private TextView tvCh2;
    private TextView tvCh3;
    private TextView tvCh4;
    private TextView tvRaw;
    private Button btnConnect;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) {
                    openDevice(device);
                } else {
                    tvStatus.setText("USB permission denied");
                    Toast.makeText(MainActivity.this, "USB permission denied", Toast.LENGTH_SHORT).show();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                refreshAndConnectIfAvailable();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        channelLogFile = createSessionLogFile();

        tvStatus = findViewById(R.id.tvStatus);
        tvFrameId = findViewById(R.id.tvFrameId);
        tvCh1 = findViewById(R.id.tvCh1);
        tvCh2 = findViewById(R.id.tvCh2);
        tvCh3 = findViewById(R.id.tvCh3);
        tvCh4 = findViewById(R.id.tvCh4);
        tvRaw = findViewById(R.id.tvRaw);
        btnConnect = findViewById(R.id.btnConnect);

        btnConnect.setOnClickListener(v -> refreshAndConnectIfAvailable());
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        refreshAndConnectIfAvailable();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(usbReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeDevice();
    }

    private void refreshAndConnectIfAvailable() {
        List<CanDeviceInfo> devices = CanDeviceFactory.enumerate(usbManager);
        if (devices.isEmpty()) {
            tvStatus.setText("No Canable / CandleLight device found");
            return;
        }

        UsbDevice selected = devices.get(0).device;
        if (usbManager.hasPermission(selected)) {
            openDevice(selected);
        } else {
            requestPermission(selected);
        }
    }

    private void requestPermission(UsbDevice device) {
        tvStatus.setText("Requesting USB permission...");

        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), flags);
        usbManager.requestPermission(device, pi);
    }

    private void openDevice(UsbDevice usbDevice) {
        closeDevice();

        try {
            canDevice = CanDeviceFactory.create(usbManager, usbDevice);
            if (canDevice == null) {
                tvStatus.setText("Unsupported USB adapter");
                return;
            }

            canDevice.open();
            canDevice.addFrameListener(this::handleFrame);
            canDevice.start(BITRATE);

            tvStatus.setText("Listening for 0x00010203");
            Log.i(TAG, "Device opened: " + canDevice.getDeviceInfo());
        } catch (IOException e) {
            tvStatus.setText("Open failed: " + e.getMessage());
            Log.e(TAG, "Failed to open device", e);
        }
    }

    private void closeDevice() {
        if (canDevice == null) return;
        try {
            canDevice.stop();
        } catch (IOException ignored) {
        }
        canDevice.close();
        canDevice = null;
    }

    private void handleFrame(ReceivedFrame receivedFrame) {
        if (receivedFrame == null || receivedFrame.frame == null) {
            return;
        }

        CanFrame frame = receivedFrame.frame;
        if (frame.id != TARGET_CAN_ID || !frame.isExtended || frame.data == null) {
            return;
        }

        if (frame.data.length != 8) {
            runOnUiThread(() -> tvStatus.setText("Wrong payload length: " + frame.data.length));
            return;
        }

        int ch1 = decodeInt16(frame.data[0], frame.data[1]);
        int ch2 = decodeInt16(frame.data[2], frame.data[3]);
        int ch3 = decodeInt16(frame.data[4], frame.data[5]);
        int ch4 = decodeInt16(frame.data[6], frame.data[7]);

        writeChannelSnapshot(ch1, ch2, ch3, ch4);

        runOnUiThread(() -> {
            tvFrameId.setText("ID: 0x" + Integer.toHexString(frame.id).toUpperCase());
            tvCh1.setText("Ch1: " + ch1);
            tvCh2.setText("Ch2: " + ch2);
            tvCh3.setText("Ch3: " + ch3);
            tvCh4.setText("Ch4: " + ch4);
            tvStatus.setText("Receiving 0x00010203");
        });
    }

    private File createSessionLogFile() {
        File dir = new File(Environment.getExternalStorageDirectory(), "Canable");
        if (!dir.exists() && !dir.mkdirs()) {
            dir = getExternalFilesDir(null);
            if (dir == null) {
                dir = getFilesDir();
            }
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(new Date());
        File file = new File(dir, "can_ch1_ch4_" + timestamp + ".csv");

        try (FileOutputStream fos = new FileOutputStream(file, true);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            writer.append("timestamp,Ch1,Ch2,Ch3,Ch4\n");
        } catch (IOException e) {
            Log.e(TAG, "Failed to create session log file", e);
            File fallbackDir = getFilesDir();
            return new File(fallbackDir, "can_ch1_ch4_" + timestamp + ".csv");
        }

        return file;
    }

    private void writeChannelSnapshot(int ch1, int ch2, int ch3, int ch4) {
        if (channelLogFile == null) {
            channelLogFile = createSessionLogFile();
        }

        try (FileOutputStream fos = new FileOutputStream(channelLogFile, true);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            writer.append(String.format(
                    Locale.US,
                    "%s,%d,%d,%d,%d\n",
                    timestamp,
                    ch1,
                    ch2,
                    ch3,
                    ch4
            ));
        } catch (IOException e) {
            Log.e(TAG, "Failed to write channel data", e);
        }
    }

    private static int decodeInt16(byte hi, byte lo) {
        int value = ((hi & 0xFF) << 8) | (lo & 0xFF);
        if ((value & 0x8000) != 0) {
            value -= 0x10000;
        }
        return value;
    }
}

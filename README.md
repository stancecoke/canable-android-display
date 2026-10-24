# canable-android-display

Android app for a Canable / CandleLight USB CAN adapter. It listens for extended CAN ID `0x00010203`, decodes the payload as four signed 16-bit values, and displays `Ch1`..`Ch4` on the phone screen.

This project is based on the USB CAN driver architecture from `Alcantor/androidCAN` and keeps only the CandleLight/gs_usb path needed for the app.

## Supported hardware
- Canable with CandleLight firmware
- gs_usb / candleLight-compatible USB CAN adapters

## Python equivalent
The Android app reproduces this logic:

```python
Ch1 = int16(data[0:2])
Ch2 = int16(data[2:4])
Ch3 = int16(data[4:6])
Ch4 = int16(data[6:8])
```

## Build
Open this project in Android Studio and run it on a device with USB host support.

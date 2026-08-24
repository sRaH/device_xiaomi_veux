# Veux Miracast receiver

`WfdReceiver` is a small, platform-signed client for the Qualcomm Wi-Fi Display sink stack already
shipped by the device vendor files. It does not implement Google Cast and it does not add another
media decoder. While its full-screen activity is open it:

1. advertises the phone as a Miracast primary sink over Wi-Fi Direct;
2. binds to `com.qualcomm.wfd.service.WfdService`;
3. passes the connected source and a full-screen `Surface` to the vendor WFD session; and
4. tears down the session and stops advertising when the activity closes.

The Quick Settings tile is `custom(com.android.wfdreceiver/.ReceiverTileService)`. Settings launches
the same activity with `com.android.wfdreceiver.action.OPEN`.

## Targeted build and test

No clean build is needed after these changes:

```bash
m WfdReceiver Settings SystemUIOverlayVEUX
```

After the first ROM flash installs the permission XML files, receiver-only app iterations can be
installed with the platform-signed output APK:

```bash
adb install -r out/target/product/veux/system_ext/priv-app/WfdReceiver/WfdReceiver.apk
adb shell am start -a com.android.wfdreceiver.action.OPEN
adb logcat -s WfdReceiver WfdService WFDSession WifiP2pService
```

Open the wireless display/Miracast UI on the source device and select the phone. The receiver screen
must stay open for the first implementation.

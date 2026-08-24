/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.wfdreceiver;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen, explicitly activated Miracast primary sink. */
public final class ReceiverActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "WfdReceiver";
    private static final int PERMISSION_REQUEST = 7236;
    private static final int RTSP_PORT = 7236;
    private static final int MAX_THROUGHPUT_MBIT = 50;
    private static volatile boolean sRunning;

    private final ExecutorService mWfdExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, WifiP2pDevice> mPeers = new HashMap<>();

    private WifiP2pManager mP2pManager;
    private WifiP2pManager.Channel mP2pChannel;
    private WifiP2pDevice mLocalDevice;
    private WifiP2pInfo mConnectionInfo;
    private WifiP2pGroup mGroup;
    private IBinder mWfdManagerBinder;
    private WfdSessionClient mWfdSession;
    private Surface mSurface;
    private TextView mTitle;
    private TextView mSummary;
    private View mStatusPanel;
    private boolean mReceiverEnabled;
    private boolean mReceiverRegistered;
    private boolean mServiceBound;
    private boolean mSessionStarting;
    private boolean mSessionStarted;
    private int mSessionGeneration;

    private final WfdSessionClient.Listener mSessionListener =
            new WfdSessionClient.Listener() {
                @Override
                public void onStateUpdate(int state, int sessionId) {
                    Log.i(TAG, "WFD state=" + state + " session=" + sessionId);
                    runOnUiThread(() -> {
                        // Qualcomm SessionState PLAY/PLAYING are 3 and 7.
                        if (state == 3 || state == 7) {
                            mSessionStarted = true;
                            mStatusPanel.setVisibility(View.GONE);
                        } else if (state == 6 || state == 10) {
                            showReadyState();
                        }
                    });
                }

                @Override
                public void onEvent(int event, int sessionId) {
                    Log.i(TAG, "WFD event=" + event + " session=" + sessionId);
                    // Qualcomm WfdEvent.START_SESSION_FAIL is 22.
                    if (event == 22) {
                        runOnUiThread(() -> showError(getString(R.string.session_failed, event)));
                    }
                }

                @Override
                public void onNotify(Bundle data, int sessionId) {
                    Log.d(TAG, "WFD notify session=" + sessionId + " data=" + data);
                }
            };

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mServiceBound = true;
            mWfdManagerBinder = service;
            try {
                mWfdSession = new WfdSessionClient(service);
                maybeStartSession();
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to obtain Qualcomm WFD session", e);
                showError(getString(R.string.service_unavailable));
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceBound = false;
            mWfdManagerBinder = null;
            mWfdSession = null;
            showError(getString(R.string.service_unavailable));
        }
    };

    private final BroadcastReceiver mP2pReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    showError(getString(R.string.wifi_direct_off));
                } else {
                    advertiseAndDiscover();
                }
            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                mLocalDevice = intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice.class);
                maybeStartSession();
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                requestPeers();
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                NetworkInfo networkInfo = intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo.class);
                if (networkInfo != null && networkInfo.isConnected()) {
                    requestConnectionDetails();
                } else {
                    onP2pDisconnected();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        buildUi();

        mP2pManager = getSystemService(WifiP2pManager.class);
        if (mP2pManager == null) {
            showError(getString(R.string.service_unavailable));
            return;
        }
        mP2pChannel = mP2pManager.initialize(this, getMainLooper(), () -> {
            Log.w(TAG, "Wi-Fi Direct channel disconnected");
            showError(getString(R.string.wifi_direct_off));
        });
        ensurePermissionsAndStart();
    }

    static boolean isRunning() {
        return sRunning;
    }

    @Override
    protected void onDestroy() {
        stopReceiver();
        mWfdExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST) {
            return;
        }
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                showError(getString(R.string.permission_required));
                return;
            }
        }
        startReceiver();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mSurface = holder.getSurface();
        maybeStartSession();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mSurface = holder.getSurface();
        WfdSessionClient session = mWfdSession;
        if (session != null && mSessionStarted) {
            mWfdExecutor.execute(() -> {
                try {
                    session.setSurface(mSurface);
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to update WFD surface", e);
                }
            });
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mSurface = null;
        WfdSessionClient session = mWfdSession;
        if (session != null) {
            mWfdExecutor.execute(() -> {
                try {
                    session.setSurface(null);
                } catch (RemoteException e) {
                    Log.w(TAG, "Unable to clear WFD surface", e);
                }
            });
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        SurfaceView surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(40), dp(28), dp(40), dp(28));
        panel.setBackgroundColor(0xD914161A);
        mStatusPanel = panel;

        mTitle = new TextView(this);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setTextSize(28);
        mTitle.setGravity(Gravity.CENTER);
        panel.addView(mTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mSummary = new TextView(this);
        mSummary.setTextColor(0xFFCBD0D8);
        mSummary.setTextSize(16);
        mSummary.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                dp(520), LinearLayout.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = dp(12);
        panel.addView(mSummary, summaryParams);

        Button stop = new Button(this);
        stop.setText(R.string.stop);
        stop.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stopParams.topMargin = dp(20);
        panel.addView(stop, stopParams);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(panel, panelParams);
        setContentView(root);
        mTitle.setText(R.string.ready_title);
        mSummary.setText(R.string.starting_summary);
    }

    private void ensurePermissionsAndStart() {
        String[] permissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? new String[] { Manifest.permission.NEARBY_WIFI_DEVICES }
                : new String[] { Manifest.permission.ACCESS_FINE_LOCATION };
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(permissions, PERMISSION_REQUEST);
                return;
            }
        }
        startReceiver();
    }

    private void startReceiver() {
        if (mReceiverEnabled) {
            return;
        }
        mReceiverEnabled = true;
        sRunning = true;
        updateTile();
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        registerReceiver(mP2pReceiver, filter, Context.RECEIVER_EXPORTED);
        mReceiverRegistered = true;

        Intent serviceIntent = new Intent("com.qualcomm.wfd.service.WfdService")
                .setPackage("com.qualcomm.wfd.service");
        if (!bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE)) {
            showError(getString(R.string.service_unavailable));
        }

        mP2pManager.requestDeviceInfo(mP2pChannel, device -> {
            mLocalDevice = device;
            maybeStartSession();
        });
        advertiseAndDiscover();
    }

    private void advertiseAndDiscover() {
        if (!mReceiverEnabled) {
            return;
        }
        WifiP2pWfdInfo info = new WifiP2pWfdInfo();
        info.setEnabled(true);
        info.setDeviceType(WifiP2pWfdInfo.DEVICE_TYPE_PRIMARY_SINK);
        info.setSessionAvailable(!mSessionStarting && !mSessionStarted);
        info.setContentProtectionSupported(true);
        info.setControlPort(RTSP_PORT);
        info.setMaxThroughput(MAX_THROUGHPUT_MBIT);
        mP2pManager.setWfdInfo(mP2pChannel, info, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                mP2pManager.setMiracastMode(WifiP2pManager.MIRACAST_SINK);
                showReadyState();
                mP2pManager.discoverPeers(mP2pChannel, new LoggingActionListener("discoverPeers"));
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "setWfdInfo failed: " + reason);
                showError(getString(R.string.service_unavailable));
            }
        });
    }

    private void requestPeers() {
        mP2pManager.requestPeers(mP2pChannel, (WifiP2pDeviceList list) -> {
            mPeers.clear();
            for (WifiP2pDevice device : list.getDeviceList()) {
                mPeers.put(device.deviceAddress, device);
            }
        });
    }

    private void requestConnectionDetails() {
        mP2pManager.requestConnectionInfo(mP2pChannel, info -> {
            mConnectionInfo = info;
            maybeStartSession();
        });
        mP2pManager.requestGroupInfo(mP2pChannel, group -> {
            mGroup = group;
            maybeStartSession();
        });
    }

    private void maybeStartSession() {
        if (!mReceiverEnabled || mSessionStarting || mSessionStarted || mWfdSession == null
                || mSurface == null || !mSurface.isValid() || mLocalDevice == null
                || mConnectionInfo == null || !mConnectionInfo.groupFormed || mGroup == null) {
            return;
        }

        WifiP2pDevice peer = findSourcePeer(mGroup, mLocalDevice);
        if (peer == null) {
            Log.d(TAG, "P2P group is connected but no Miracast source is known yet");
            requestPeers();
            return;
        }
        String peerIp = getPeerIp(peer, mConnectionInfo);
        if (peerIp == null) {
            showError(getString(R.string.peer_address_unavailable));
            return;
        }

        WfdSessionClient.Device local = toWfdDevice(mLocalDevice, null,
                WfdSessionClient.DEVICE_TYPE_PRIMARY_SINK);
        // requestDeviceInfo may still contain the framework sender's previous WFD IE.
        local.deviceType = WfdSessionClient.DEVICE_TYPE_PRIMARY_SINK;
        WfdSessionClient.Device remote = toWfdDevice(peer, peerIp,
                WfdSessionClient.DEVICE_TYPE_SOURCE);
        String peerName = peer.deviceName == null ? peer.deviceAddress : peer.deviceName;
        mSessionStarting = true;
        int generation = ++mSessionGeneration;
        mTitle.setText(R.string.ready_title);
        mSummary.setText(getString(R.string.connecting_summary, peerName));
        advertiseAvailability(false);

        WfdSessionClient session = mWfdSession;
        Surface surface = mSurface;
        mWfdExecutor.execute(() -> {
            int result = -1;
            try {
                result = session.setDeviceType(WfdSessionClient.DEVICE_TYPE_PRIMARY_SINK);
                if (result >= 0) {
                    result = session.setSurface(surface);
                }
                if (result >= 0) {
                    result = session.init(mSessionListener, local);
                }
                if (result >= 0) {
                    result = session.start(remote);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to start WFD sink session", e);
            }
            int finalResult = result;
            runOnUiThread(() -> {
                if (generation != mSessionGeneration || !mReceiverEnabled
                        || mConnectionInfo == null) {
                    return;
                }
                mSessionStarting = false;
                if (finalResult < 0) {
                    showError(getString(R.string.session_failed, finalResult));
                    advertiseAvailability(true);
                    resetWfdSession();
                } else {
                    mSessionStarted = true;
                    mSummary.setText(getString(R.string.connected_summary, peerName));
                }
            });
        });
    }

    private WifiP2pDevice findSourcePeer(WifiP2pGroup group, WifiP2pDevice local) {
        Collection<WifiP2pDevice> candidates;
        if (mConnectionInfo.isGroupOwner) {
            candidates = group.getClientList();
        } else {
            candidates = java.util.List.of(group.getOwner());
        }
        for (WifiP2pDevice candidate : candidates) {
            if (candidate == null || (local.deviceAddress != null
                    && local.deviceAddress.equals(candidate.deviceAddress))) {
                continue;
            }
            WifiP2pDevice detailed = mPeers.getOrDefault(candidate.deviceAddress, candidate);
            WifiP2pWfdInfo info = detailed.getWfdInfo();
            if (info == null || info.getDeviceType() == WifiP2pWfdInfo.DEVICE_TYPE_WFD_SOURCE
                    || info.getDeviceType()
                    == WifiP2pWfdInfo.DEVICE_TYPE_SOURCE_OR_PRIMARY_SINK) {
                return detailed;
            }
        }
        return null;
    }

    private String getPeerIp(WifiP2pDevice peer, WifiP2pInfo info) {
        if (!info.isGroupOwner && info.groupOwnerAddress != null) {
            return info.groupOwnerAddress.getHostAddress();
        }
        return findArpAddress(peer.deviceAddress);
    }

    private static String findArpAddress(String macAddress) {
        if (macAddress == null) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length >= 4 && macAddress.equalsIgnoreCase(fields[3])) {
                    return fields[0];
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to resolve group client from ARP", e);
        }
        return null;
    }

    private static WfdSessionClient.Device toWfdDevice(WifiP2pDevice device, String ip,
            int fallbackType) {
        WfdSessionClient.Device result = new WfdSessionClient.Device();
        WifiP2pWfdInfo info = device.getWfdInfo();
        result.deviceType = info == null ? fallbackType : info.getDeviceType();
        result.macAddress = device.deviceAddress;
        result.deviceName = device.deviceName == null ? Build.MODEL : device.deviceName;
        result.ipAddress = ip;
        result.rtspPort = info == null || info.getControlPort() <= 0
                ? RTSP_PORT : info.getControlPort();
        result.availableForSession = info == null || info.isSessionAvailable();
        return result;
    }

    private void advertiseAvailability(boolean available) {
        WifiP2pWfdInfo info = new WifiP2pWfdInfo();
        info.setEnabled(true);
        info.setDeviceType(WifiP2pWfdInfo.DEVICE_TYPE_PRIMARY_SINK);
        info.setSessionAvailable(available);
        info.setContentProtectionSupported(true);
        info.setControlPort(RTSP_PORT);
        info.setMaxThroughput(MAX_THROUGHPUT_MBIT);
        mP2pManager.setWfdInfo(mP2pChannel, info, new LoggingActionListener("setWfdInfo"));
    }

    private void onP2pDisconnected() {
        mConnectionInfo = null;
        mGroup = null;
        boolean hadSession = mSessionStarting || mSessionStarted;
        mSessionStarting = false;
        mSessionStarted = false;
        mSessionGeneration++;
        showReadyState();
        advertiseAvailability(true);
        if (hadSession) {
            resetWfdSession();
        }
    }

    private void stopReceiver() {
        if (!mReceiverEnabled) {
            return;
        }
        mReceiverEnabled = false;
        mSessionGeneration++;
        sRunning = false;
        updateTile();
        if (mReceiverRegistered) {
            unregisterReceiver(mP2pReceiver);
            mReceiverRegistered = false;
        }
        if (mP2pManager != null && mP2pChannel != null) {
            advertiseAvailability(false);
            mP2pManager.stopPeerDiscovery(mP2pChannel,
                    new LoggingActionListener("stopPeerDiscovery"));
            mP2pManager.setMiracastMode(WifiP2pManager.MIRACAST_DISABLED);
        }
        WfdSessionClient session = mWfdSession;
        if (session != null) {
            mWfdExecutor.execute(() -> {
                try {
                    session.setSurface(null);
                    session.teardown();
                    session.deinit();
                } catch (RemoteException e) {
                    Log.w(TAG, "Unable to tear down WFD session", e);
                } finally {
                    runOnUiThread(this::unbindWfdService);
                }
            });
        }
        if (session == null) {
            unbindWfdService();
        }
    }

    private void showReadyState() {
        runOnUiThread(() -> {
            mStatusPanel.setVisibility(View.VISIBLE);
            mTitle.setText(R.string.ready_title);
            mSummary.setText(R.string.ready_summary);
        });
    }

    private void unbindWfdService() {
        if (mServiceBound) {
            unbindService(mServiceConnection);
            mServiceBound = false;
            mWfdManagerBinder = null;
        }
    }

    private void resetWfdSession() {
        mSessionGeneration++;
        WfdSessionClient oldSession = mWfdSession;
        IBinder manager = mWfdManagerBinder;
        mWfdSession = null;
        if (oldSession == null || manager == null) {
            return;
        }
        mWfdExecutor.execute(() -> {
            try {
                oldSession.setSurface(null);
                oldSession.teardown();
                oldSession.deinit();
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to reset old WFD session", e);
            }

            WfdSessionClient replacement = null;
            try {
                replacement = new WfdSessionClient(manager);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to create replacement WFD session", e);
            }
            WfdSessionClient finalReplacement = replacement;
            runOnUiThread(() -> {
                if (mReceiverEnabled && manager == mWfdManagerBinder) {
                    mWfdSession = finalReplacement;
                    if (finalReplacement == null) {
                        showError(getString(R.string.service_unavailable));
                    } else {
                        maybeStartSession();
                    }
                }
            });
        });
    }

    private void updateTile() {
        TileService.requestListeningState(
                this, new ComponentName(this, ReceiverTileService.class));
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            mStatusPanel.setVisibility(View.VISIBLE);
            mTitle.setText(R.string.app_name);
            mSummary.setText(message);
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class LoggingActionListener implements WifiP2pManager.ActionListener {
        private final String mOperation;

        LoggingActionListener(String operation) {
            mOperation = operation;
        }

        @Override
        public void onSuccess() {
            Log.d(TAG, mOperation + " succeeded");
        }

        @Override
        public void onFailure(int reason) {
            Log.w(TAG, mOperation + " failed: " + reason);
        }
    }
}

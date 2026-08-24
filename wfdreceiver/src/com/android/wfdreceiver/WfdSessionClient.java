/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.wfdreceiver;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.view.Surface;

/**
 * Small Binder client for the stable Qualcomm WfdService contract shipped on veux/peux.
 *
 * WfdCommon is distributed as a dex-only device blob, so it cannot be a Soong compile-time
 * dependency. Keeping the few calls used by the receiver here also avoids copying the complete
 * vendor client into the ROM.
 */
final class WfdSessionClient {
    private static final String MANAGER_DESCRIPTOR =
            "com.qualcomm.wfd.service.ISessionManagerService";
    private static final String SESSION_DESCRIPTOR = "com.qualcomm.wfd.service.IWfdSession";
    private static final int MANAGER_GET_WIFI_DISPLAY_SESSION = 44;

    private static final int SESSION_SET_DEVICE_TYPE = 1;
    private static final int SESSION_INIT = 4;
    private static final int SESSION_DEINIT = 8;
    private static final int SESSION_START = 9;
    private static final int SESSION_TEARDOWN = 21;
    private static final int SESSION_SET_SURFACE = 35;

    static final int DEVICE_TYPE_SOURCE = 0;
    static final int DEVICE_TYPE_PRIMARY_SINK = 1;
    static final int DEVICE_TYPE_SOURCE_OR_PRIMARY_SINK = 3;
    static final int NET_TYPE_WIFI_P2P = 1;

    private final IBinder mSession;
    private ActionListenerBinder mCallback;

    interface Listener {
        void onStateUpdate(int state, int sessionId);
        void onEvent(int event, int sessionId);
        void onNotify(Bundle data, int sessionId);
    }

    WfdSessionClient(IBinder manager) throws RemoteException {
        mSession = getSession(manager);
        if (mSession == null) {
            throw new RemoteException("WfdService returned no session binder");
        }
    }

    int setDeviceType(int type) throws RemoteException {
        return transactInt(SESSION_SET_DEVICE_TYPE, data -> data.writeInt(type));
    }

    int init(Listener listener, Device device) throws RemoteException {
        mCallback = new ActionListenerBinder(listener);
        return transactInt(SESSION_INIT, data -> {
            data.writeStrongBinder(mCallback);
            data.writeTypedObject(device, 0);
        });
    }

    int setSurface(Surface surface) throws RemoteException {
        return transactInt(SESSION_SET_SURFACE, data -> data.writeTypedObject(surface, 0));
    }

    int start(Device peer) throws RemoteException {
        return transactInt(SESSION_START, data -> data.writeTypedObject(peer, 0));
    }

    int teardown() throws RemoteException {
        return transactInt(SESSION_TEARDOWN, null);
    }

    int deinit() throws RemoteException {
        return transactInt(SESSION_DEINIT, null);
    }

    private static IBinder getSession(IBinder manager) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MANAGER_DESCRIPTOR);
            if (!manager.transact(MANAGER_GET_WIFI_DISPLAY_SESSION, data, reply, 0)) {
                throw new RemoteException("getWiFiDisplaySession transaction was rejected");
            }
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private int transactInt(int code, ParcelWriter writer) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SESSION_DESCRIPTOR);
            if (writer != null) {
                writer.write(data);
            }
            if (!mSession.transact(code, data, reply, 0)) {
                throw new RemoteException("WfdSession transaction " + code + " was rejected");
            }
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private interface ParcelWriter {
        void write(Parcel data);
    }

    static final class Device implements Parcelable {
        int deviceType;
        int netType = NET_TYPE_WIFI_P2P;
        String macAddress;
        String deviceName;
        String ipAddress;
        int rtspPort = 7236;
        int decoderLatency;
        boolean availableForSession = true;
        int preferredConnectivity;
        String addressOfAp;
        int coupledSinkStatus;
        int extSupport;
        Bundle capabilities = new Bundle();

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            // Must match com.qualcomm.wfd.WfdDevice exactly.
            dest.writeInt(deviceType);
            dest.writeInt(netType);
            dest.writeString(macAddress);
            dest.writeString(deviceName);
            dest.writeString(ipAddress);
            dest.writeInt(rtspPort);
            dest.writeInt(decoderLatency);
            dest.writeByte((byte) (availableForSession ? 1 : 0));
            dest.writeInt(preferredConnectivity);
            dest.writeString(addressOfAp);
            dest.writeInt(coupledSinkStatus);
            dest.writeInt(extSupport);
            dest.writeBundle(capabilities);
        }

        public static final Creator<Device> CREATOR = new Creator<>() {
            @Override
            public Device createFromParcel(Parcel source) {
                throw new UnsupportedOperationException("Receiver only writes WfdDevice parcels");
            }

            @Override
            public Device[] newArray(int size) {
                return new Device[size];
            }
        };
    }

    private static final class ActionListenerBinder extends Binder implements IInterface {
        private static final String DESCRIPTOR = "com.qualcomm.wfd.service.IWfdActionListener";
        private static final int ON_STATE_UPDATE = 1;
        private static final int NOTIFY_EVENT = 2;
        private static final int NOTIFY = 3;

        private final Listener mListener;

        ActionListenerBinder(Listener listener) {
            mListener = listener;
            attachInterface(this, DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code >= FIRST_CALL_TRANSACTION && code <= LAST_CALL_TRANSACTION) {
                data.enforceInterface(DESCRIPTOR);
            }
            switch (code) {
                case INTERFACE_TRANSACTION:
                    if (reply != null) {
                        reply.writeString(DESCRIPTOR);
                    }
                    return true;
                case ON_STATE_UPDATE:
                    mListener.onStateUpdate(data.readInt(), data.readInt());
                    return true;
                case NOTIFY_EVENT:
                    mListener.onEvent(data.readInt(), data.readInt());
                    return true;
                case NOTIFY:
                    mListener.onNotify(data.readTypedObject(Bundle.CREATOR), data.readInt());
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }
}

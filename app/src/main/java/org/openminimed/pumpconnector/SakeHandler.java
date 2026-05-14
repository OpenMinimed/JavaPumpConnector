package org.openminimed.pumpconnector;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.openminimed.sake.Constants;
import org.openminimed.sake.SakeServer;

/**
 * BLE glue layer for the SAKE handshake.
 *
 * <p>Owns a {@link SakeServer} pinned to the extracted-from-pump key database
 * and translates between BLE GATT server events and handshake step calls.
 * All SAKE work is serialized onto a dedicated {@link HandlerThread} so the
 * binder thread that fires BLE callbacks is never blocked by crypto work.</p>
 *
 * <p>Wire protocol the pump expects, in order:</p>
 * <ol>
 *   <li>Pump subscribes to notifications on the SAKE characteristic.
 *       Handler emits twenty zero bytes as a wake-up notification.</li>
 *   <li>Pump writes its own twenty zero bytes as the matching wake-up.
 *       Handler feeds that to {@link SakeServer#handshake(byte[])}, gets
 *       msg0 back, notifies it to the pump.</li>
 *   <li>Pump writes msg1, msg3, msg5 in turn. Handler responds with
 *       msg2, msg4, and finally completes at stage 6.</li>
 * </ol>
 */
public final class SakeHandler {

    private static final String TAG = "SakeHandler";
    private static final int HANDSHAKE_COMPLETE_STAGE = 6;
    private static final byte[] WAKE_UP = new byte[20];

    private final SakeServer server;
    private final HandlerThread thread;
    private final Handler handler;

    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic characteristic;
    private BluetoothDevice peer;
    private boolean pumpSubscribed;

    public SakeHandler() {
        this.server = new SakeServer(Constants.KEYDB_PUMP_EXTRACTED);
        this.thread = new HandlerThread("sake-handler");
        this.thread.start();
        this.handler = new Handler(this.thread.getLooper());
    }

    /**
     * Bind the GATT server and characteristic the handler will use to send
     * SAKE notifications back to the pump.
     */
    public void attach(BluetoothGattServer gattServer,
                       BluetoothGattCharacteristic characteristic) {
        this.gattServer = gattServer;
        this.characteristic = characteristic;
    }

    /**
     * Called from the GATT server callback when the pump subscribes to
     * notifications on the SAKE characteristic. Emits a 20-byte wake-up frame.
     */
    public void onNotificationsEnabled(BluetoothDevice device) {
        handler.post(() -> {
            peer = device;
            if (pumpSubscribed) {
                return;
            }
            pumpSubscribed = true;
            Log.i(TAG, "Pump subscribed to SAKE notifications; sending wake-up");
            sendNotification(WAKE_UP.clone());
        });
    }

    /** Called from the GATT server callback when the pump unsubscribes. */
    public void onNotificationsDisabled() {
        handler.post(() -> {
            pumpSubscribed = false;
            Log.w(TAG, "Pump unsubscribed from SAKE notifications");
        });
    }

    /**
     * Called from the GATT server callback for every write on the SAKE
     * characteristic. Drives the next handshake step and emits the response.
     */
    public void onWrite(byte[] value) {
        byte[] copy = value.clone();
        handler.post(() -> {
            if (server.getStage() == HANDSHAKE_COMPLETE_STAGE) {
                Log.w(TAG, "Ignoring write after handshake completion");
                return;
            }
            try {
                byte[] response = server.handshake(copy);
                if (response != null) {
                    sendNotification(response);
                } else {
                    Log.i(TAG, "SAKE handshake complete");
                }
            } catch (Exception e) {
                Log.e(TAG, "SAKE handshake failed", e);
            }
        });
    }

    /** @return true once the handshake has reached stage 6. */
    public boolean isHandshakeComplete() {
        return server.getStage() == HANDSHAKE_COMPLETE_STAGE;
    }

    /** Stop the worker thread and release resources. */
    public void close() {
        thread.quitSafely();
    }

    @SuppressWarnings("deprecation")
    private void sendNotification(byte[] data) {
        if (gattServer == null || characteristic == null || peer == null) {
            Log.e(TAG, "Cannot send: handler is not attached or has no peer");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer.notifyCharacteristicChanged(peer, characteristic, false, data);
            } else {
                characteristic.setValue(data);
                gattServer.notifyCharacteristicChanged(peer, characteristic, false);
            }
            Log.d(TAG, "Notified SAKE bytes: " + bytesToHex(data));
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception sending SAKE notification", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}

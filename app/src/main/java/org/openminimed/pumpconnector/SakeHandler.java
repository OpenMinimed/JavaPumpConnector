package org.openminimed.pumpconnector;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import java.util.Objects;
import org.openminimed.sake.Constants;
import org.openminimed.sake.SakeServer;

/**
 * BLE glue layer for the SAKE handshake.
 *
 * <p>Owns a {@link SakeServer} pinned to the extracted-from-pump key database and translates
 * between BLE GATT server events and handshake step calls. All SAKE work is serialized onto a
 * dedicated {@link HandlerThread} so the binder thread that fires BLE callbacks is never blocked by
 * crypto work.
 *
 * <p>Wire protocol the pump expects, in order:
 *
 * <ol>
 *   <li>Pump subscribes to notifications on the SAKE characteristic. Handler emits twenty zero
 *       bytes as a wake-up notification.
 *   <li>Pump writes its own twenty zero bytes as the matching wake-up. Handler feeds that to {@link
 *       SakeServer#handshake(byte[])}, gets msg0 back, notifies it to the pump.
 *   <li>Pump writes msg1, msg3, msg5 in turn. Handler responds with msg2, msg4, and finally
 *       completes at stage 6.
 * </ol>
 *
 * <p>The handler is reusable across pump disconnect/reconnect cycles. A re-subscribe before
 * completion is treated as an abort: the {@link SakeServer} is rebuilt from scratch and the
 * handshake starts fresh with a new wake-up frame.
 */
public final class SakeHandler {

    private static final String TAG = "SakeHandler";
    private static final int HANDSHAKE_COMPLETE_STAGE = 6;
    private static final byte[] WAKE_UP = new byte[20];

    private final HandlerThread thread;
    private final Handler handler;

    private SakeServer server;
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic characteristic;
    private BluetoothDevice peer;
    private boolean pumpSubscribed;

    private volatile boolean handshakeComplete;
    private volatile boolean closed;

    public SakeHandler() {
        this.server = new SakeServer(Constants.KEYDB_PUMP_EXTRACTED);
        this.thread = new HandlerThread("sake-handler");
        this.thread.start();
        this.handler = new Handler(this.thread.getLooper());
    }

    /**
     * Bind the GATT server and characteristic the handler will use to send SAKE notifications back
     * to the pump. Must be called before any of the {@code on*} hooks.
     */
    public void attach(BluetoothGattServer gattServer, BluetoothGattCharacteristic characteristic) {
        Objects.requireNonNull(gattServer, "gattServer");
        Objects.requireNonNull(characteristic, "characteristic");
        this.gattServer = gattServer;
        this.characteristic = characteristic;
    }

    /**
     * Called from the GATT server callback when the pump subscribes to notifications on the SAKE
     * characteristic. Emits a 20-byte wake-up frame.
     *
     * <p>If the handshake has not completed and the pump resubscribes (e.g. after a transient
     * disconnect), the {@link SakeServer} is reset so the new wake-up starts a fresh handshake.
     */
    public void onNotificationsEnabled(BluetoothDevice device) {
        if (closed) {
            return;
        }
        handler.post(
                () -> {
                    peer = device;
                    if (pumpSubscribed) {
                        return;
                    }
                    if (!handshakeComplete && server.getStage() != 0) {
                        Log.w(TAG, "Pump resubscribed mid-handshake; restarting SAKE state");
                        server = new SakeServer(Constants.KEYDB_PUMP_EXTRACTED);
                    }
                    pumpSubscribed = true;
                    Log.i(TAG, "Pump subscribed to SAKE notifications; sending wake-up");
                    sendNotification(WAKE_UP.clone());
                });
    }

    /** Called from the GATT server callback when the pump unsubscribes. */
    public void onNotificationsDisabled() {
        if (closed) {
            return;
        }
        handler.post(
                () -> {
                    pumpSubscribed = false;
                    Log.w(TAG, "Pump unsubscribed from SAKE notifications");
                });
    }

    /**
     * Called from the GATT server callback for every write on the SAKE characteristic. Drives the
     * next handshake step and emits the response.
     */
    public void onWrite(byte[] value) {
        if (closed) {
            return;
        }
        byte[] copy = value.clone();
        handler.post(
                () -> {
                    if (handshakeComplete) {
                        Log.w(TAG, "Ignoring write after handshake completion");
                        return;
                    }
                    try {
                        byte[] response = server.handshake(copy);
                        if (response != null) {
                            sendNotification(response);
                        } else {
                            handshakeComplete = true;
                            Log.i(TAG, "SAKE handshake complete");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "SAKE handshake failed", e);
                    }
                });
    }

    /**
     * @return true once the handshake has reached stage 6.
     */
    public boolean isHandshakeComplete() {
        return handshakeComplete;
    }

    /** Stop the worker thread and release resources. Subsequent {@code on*} calls become no-ops. */
    public void close() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        handler.post(
                () -> {
                    gattServer = null;
                    characteristic = null;
                    peer = null;
                });
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
            Log.d(TAG, "Notified SAKE frame (" + data.length + " bytes)");
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception sending SAKE notification", e);
        }
    }
}

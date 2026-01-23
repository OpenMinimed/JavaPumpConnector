package me.palmarci.pumpconnector;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import androidx.appcompat.app.AppCompatActivity;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

/**
 * BLE Peripheral Device implementation with custom advertisement and services
 */
public class BlePeripheralDevice {
    private static final String TAG = "BlePeripheralDevice";

    // Custom UUIDs
    private static final UUID DEVICE_INFO_SERVICE_UUID = UUID.fromString("00000900-0000-1000-0000-009132591325");
    private static final UUID SAKE_SERVICE_UUID = UUID.fromString("0000fe82-0000-1000-8000-00805f9b34fb");
    private static final UUID SAKE_CHARACTERISTIC_UUID = UUID.fromString("0000fe82-0000-1000-0000-009132591325");

    // Standard Device Information Service characteristics (16-bit UUIDs)
    private static final UUID MANUFACTURER_NAME_UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb");
    private static final UUID MODEL_NUMBER_UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb");
    private static final UUID SERIAL_NUMBER_UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb");
    private static final UUID HARDWARE_REVISION_UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb");
    private static final UUID FIRMWARE_REVISION_UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb");
    private static final UUID SOFTWARE_REVISION_UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb");
    private static final UUID SYSTEM_ID_UUID = UUID.fromString("00002a23-0000-1000-8000-00805f9b34fb");
    private static final UUID PNP_ID_UUID = UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb");
    private static final UUID REGULATORY_CERT_UUID = UUID.fromString("00002a2a-0000-1000-8000-00805f9b34fb");

    // Client Characteristic Configuration descriptor
    private static final UUID CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private Queue<BluetoothGattService> addServiceQueue;

    // Permission check method
    public boolean hasBluetoothPermissions() {
        // For API 31+ (Android 12+), we need BLUETOOTH_CONNECT and BLUETOOTH_ADVERTISE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                   context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
        }
        // For API 23-30 (Android 6.0 - 11), we need ACCESS_FINE_LOCATION for Bluetooth operations
        else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        // For API < 23, no runtime permissions needed
        else {
            return true;
        }
    }

    // Manufacturer data constants
    private static final int MANUFACTURER_ID = 0x01f9; // reversed
    private static final String MOBILE_NAME = "Mobile 000001";
    private static final String FAKE_APP_VER = "2.9.0 f1093d1";

    public BlePeripheralDevice(Context context) {
        this.context = context;
        initializeBluetooth();
    }

    // Method to request Bluetooth permissions
    public void requestBluetoothPermissions() {
        if (context instanceof AppCompatActivity) {
            AppCompatActivity activity = (AppCompatActivity) context;

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                // For Android 12+
                activity.requestPermissions(new String[]{
                        android.Manifest.permission.BLUETOOTH_CONNECT,
                        android.Manifest.permission.BLUETOOTH_ADVERTISE
                }, 101);
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                // For Android 6.0 - 11
                activity.requestPermissions(new String[]{
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                }, 101);
            }
            // For Android < 6.0, no runtime permissions needed
        }
    }

    private void initializeBluetooth() {
        bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            Log.e(TAG, "Unable to initialize BluetoothManager.");
            return;
        }

        bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain BluetoothAdapter.");
            return;
        }

        // Enable debug logging
        /*
        Log.d(TAG, "Bluetooth initialized successfully");
        Log.d(TAG, "Bluetooth address: " + bluetoothAdapter.getAddress());
        Log.d(TAG, "Bluetooth name: " + bluetoothAdapter.getName());
        */

        // Initialize service queue
        addServiceQueue = new LinkedList<>();
    }

    public void start() {
        if (!bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled");
            return;
        }

        startAdvertising();
        startGattServer();
    }

    public void stop() {
        stopAdvertising();
        stopGattServer();
    }

    private void startAdvertising() {
        advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            Log.e(TAG, "Failed to create advertiser");
            return;
        }

        // Create advertisement data
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0) // Advertise until explicitly stopped
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build();

        AdvertiseData data = createAdvertisementData();

        AdvertiseCallback callback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                Log.i(TAG, "Advertising started successfully");
                Log.d(TAG, "Advertise settings: " + settingsInEffect);
            }

            @Override
            public void onStartFailure(int errorCode) {
                Log.e(TAG, "Advertising failed with error: " + errorCode);
            }
        };

        advertiser.startAdvertising(settings, data, callback);
        Log.d(TAG, "Advertising started");
    }

    private AdvertiseData createAdvertisementData() {
        AdvertiseData.Builder builder = new AdvertiseData.Builder();

        // Add flags (0x02 - LE General Discoverable Mode)
        builder.addManufacturerData(MANUFACTURER_ID, createManufacturerData());
        builder.setIncludeDeviceName(false);
        builder.setIncludeTxPowerLevel(true);

        // Add service UUID (16-bit: 0xFE82)
        ParcelUuid serviceUuid = new ParcelUuid(SAKE_SERVICE_UUID);
        builder.addServiceUuid(serviceUuid);

        // Add service data for the custom service
        byte[] serviceData = new byte[] {0x01}; // Simple service data
    //    builder.addServiceData(serviceUuid, serviceData);

        return builder.build();
    }

    private byte[] createManufacturerData() {
        // Manufacturer data format: 0x00 + "Mobile 000001" + 0x00
        byte[] mobileNameBytes = MOBILE_NAME.getBytes(StandardCharsets.UTF_8);
        byte[] manufacturerData = new byte[1 + mobileNameBytes.length + 1];

        manufacturerData[0] = 0x00; // First byte
        System.arraycopy(mobileNameBytes, 0, manufacturerData, 1, mobileNameBytes.length);
        manufacturerData[manufacturerData.length - 1] = 0x00; // Last byte

        Log.d(TAG, "Manufacturer data: " + bytesToHex(manufacturerData));
        return manufacturerData;
    }

    private void startGattServer() {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing required Bluetooth permissions");
            return;
        }

        try {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback);
            if (gattServer == null) {
                Log.e(TAG, "Failed to create GATT server");
                return;
            }

            // Create all services and add them to the queue
            List<BluetoothGattService> gattServices = createServices();
            addServiceQueue = new LinkedList<>(gattServices);

            // Start adding services sequentially
            addNextService();

        } catch (SecurityException e) {
            Log.e(TAG, "Security exception when starting GATT server: " + e.getMessage());
        }
    }

    private BluetoothGattService createDeviceInfoService() {
        BluetoothGattService service = new BluetoothGattService(
                DEVICE_INFO_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        );

        // Manufacturer Name String [R]
        BluetoothGattCharacteristic manufacturerName = new BluetoothGattCharacteristic(
                MANUFACTURER_NAME_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        manufacturerName.setValue("Test Manufacturer");
        service.addCharacteristic(manufacturerName);

        // Model Number String [R]
        BluetoothGattCharacteristic modelNumber = new BluetoothGattCharacteristic(
                MODEL_NUMBER_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        modelNumber.setValue("Test Model Number");
        service.addCharacteristic(modelNumber);

        // Serial Number String [R]
        BluetoothGattCharacteristic serialNumber = new BluetoothGattCharacteristic(
                SERIAL_NUMBER_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        serialNumber.setValue(MOBILE_NAME);
        service.addCharacteristic(serialNumber);

        // Hardware Revision String [R]
        BluetoothGattCharacteristic hardwareRevision = new BluetoothGattCharacteristic(
                HARDWARE_REVISION_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        hardwareRevision.setValue("HW-1.0");
        service.addCharacteristic(hardwareRevision);

        // Firmware Revision String [R]
        BluetoothGattCharacteristic firmwareRevision = new BluetoothGattCharacteristic(
                FIRMWARE_REVISION_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        firmwareRevision.setValue("14587043");
        service.addCharacteristic(firmwareRevision);

        // Software Revision String [R]
        BluetoothGattCharacteristic softwareRevision = new BluetoothGattCharacteristic(
                SOFTWARE_REVISION_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
      //  softwareRevision.setValue("SW-3.2.1");
        softwareRevision.setValue(FAKE_APP_VER); // APK version with git commit?

        service.addCharacteristic(softwareRevision);

        // System ID [R]
        BluetoothGattCharacteristic systemId = new BluetoothGattCharacteristic(
                SYSTEM_ID_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        systemId.setValue(new byte[]{(byte) 0x0, (byte) 0x0, (byte) 0x0, (byte) 0x0, (byte) 0x0, (byte)0x0, (byte) 0x0, (byte) 0x0});
        service.addCharacteristic(systemId);

        // PnP ID [R]
        BluetoothGattCharacteristic pnpId = new BluetoothGattCharacteristic(
                PNP_ID_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        pnpId.setValue(new byte[]{0x0});
        service.addCharacteristic(pnpId);

        // IEEE 11073-20601 Regulatory Certification Data List [R]
        BluetoothGattCharacteristic regulatoryCert = new BluetoothGattCharacteristic(
                REGULATORY_CERT_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        regulatoryCert.setValue(new byte[]{});
        service.addCharacteristic(regulatoryCert);

        return service;
    }

    private BluetoothGattService createSakeService() {
        BluetoothGattService service = new BluetoothGattService(
                SAKE_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        );

        // Unknown Characteristic [N W]
        BluetoothGattCharacteristic sakeChar = new BluetoothGattCharacteristic(
                SAKE_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
        );

        // Add Client Characteristic Configuration descriptor
        BluetoothGattDescriptor cccDescriptor = new BluetoothGattDescriptor(
                CCC_DESCRIPTOR_UUID,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE
        );
        sakeChar.addDescriptor(cccDescriptor);

        service.addCharacteristic(sakeChar);

        return service;
    }

    private List<BluetoothGattService> createServices() {
        List<BluetoothGattService> services = new LinkedList<>();
        services.add(createDeviceInfoService());
        services.add(createSakeService());
        return services;
    }

    private void addNextService() {
        if (addServiceQueue.isEmpty()) {
            Log.d(TAG, "All services added successfully");
            Log.d(TAG, "Device Info Service UUID: " + DEVICE_INFO_SERVICE_UUID);
            Log.d(TAG, "SAKE Service UUID: " + SAKE_SERVICE_UUID);
        } else {
            BluetoothGattService firstItem = addServiceQueue.poll();
            if (firstItem != null) {
                Log.d(TAG, "Adding service: " + firstItem.getUuid());
                try {
                    gattServer.addService(firstItem);
                } catch (SecurityException e) {
                    Log.e(TAG, "Security exception when adding service: " + e.getMessage());
                }
            }
        }
    }

    private void stopAdvertising() {
        if (advertiser != null) {
            try {
                advertiser.stopAdvertising(new AdvertiseCallback() {
                    @Override
                    public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                        // Not expected for stop
                    }
                });
                Log.d(TAG, "Advertising stopped");
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception when stopping advertising: " + e.getMessage());
            }
        }
    }

    private void stopGattServer() {
        if (gattServer != null) {
            try {
                gattServer.close();
                gattServer = null;
                Log.d(TAG, "GATT server stopped");
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception when stopping GATT server: " + e.getMessage());
            }
        }
    }

    // GATT Server Callback
    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            super.onConnectionStateChange(device, status, newState);

            String deviceAddress = device != null ? device.getAddress() : "unknown";

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Device connected: " + deviceAddress);
                Log.d(TAG, "Connection status: " + status);

                // Stop advertising when connected
                stopAdvertising();

                // Set security requirements - No Input No Output (Just Works)
                /*
                if (device != null) {
                    device.setPairingConfirmation(true);
                    Log.d(TAG, "Pairing confirmation set to true for: " + deviceAddress);
                }
                */

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Device disconnected: " + deviceAddress);
                Log.d(TAG, "Disconnection status: " + status);

                // Restart advertising when disconnected
                startAdvertising();
            }
        }

        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            super.onServiceAdded(status, service);
            Log.d(TAG, "Service added: " + service.getUuid() + " status: " + status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Service added successfully: " + service.getUuid());
                addNextService();
            } else {
                Log.e(TAG, "Failed to add service: " + service.getUuid() + " with status: " + status);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);

            var uuid = characteristic.getUuid();
            Log.i(TAG, "Read request from: " + device.getAddress());
            Log.d(TAG, "Characteristic UUID: " + uuid);
            Log.d(TAG, "Request ID: " + requestId + ", Offset: " + offset);

            try {
                byte[] toSend = "DummyData".getBytes(StandardCharsets.UTF_8);

                if (uuid.equals(SOFTWARE_REVISION_UUID)) {
                    toSend = FAKE_APP_VER.getBytes(StandardCharsets.UTF_8); // might not be necessary?
                }

                if (uuid.equals(SYSTEM_ID_UUID)) {
                    toSend = new byte[]{0, 0, 0, 0,0, 0, 0, 0};
                }

                if (uuid.equals(PNP_ID_UUID)) {
                    // https://btprodspecificationrefs.blob.core.windows.net/gatt-specification-supplement/GATT_Specification_Supplement.pdf
                    // vid source, vendor id, product id, product version,
                    toSend = new byte[]{0,   0, 0,     0,0,    0, 0,     };

                }
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, toSend);

                Log.d(TAG, "Sent response: 0x " + bytesToHex(toSend));
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception in read request: " + e.getMessage());
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);

            Log.i(TAG, "Write request from: " + device.getAddress());
            Log.d(TAG, "Characteristic UUID: " + characteristic.getUuid());
            Log.d(TAG, "Value: " + bytesToHex(value));
            Log.d(TAG, "Prepared write: " + preparedWrite + ", Response needed: " + responseNeeded);

            if (responseNeeded) {
                try {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                } catch (SecurityException e) {
                    Log.e(TAG, "Security exception in write request: " + e.getMessage());
                }
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor);

            Log.i(TAG, "Descriptor read request from: " + device.getAddress());
            Log.d(TAG, "Descriptor UUID: " + descriptor.getUuid());

            try {
                // For CCC descriptor, return current value (0 by default)
                byte[] value = descriptor.getValue();
                if (value == null) {
                    value = new byte[]{0x00, 0x00}; // Default CCC value
                }
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);

                Log.d(TAG, "Sent descriptor value: " + bytesToHex(value));
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception in descriptor read request: " + e.getMessage());
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value);

            Log.i(TAG, "Descriptor write request from: " + device.getAddress());
            Log.d(TAG, "Descriptor UUID: " + descriptor.getUuid());
            Log.d(TAG, "Value: " + bytesToHex(value));

            if (descriptor.getUuid().equals(CCC_DESCRIPTOR_UUID)) {
                int cccValue = (value[1] << 8) | (value[0] & 0xFF);

                if ((cccValue & 0x0001) != 0) {
                    Log.i(TAG, "Client subscribed to NOTIFICATIONS");
                }
                if ((cccValue & 0x0002) != 0) {
                    Log.i(TAG, "Client subscribed to INDICATIONS");
                }
                if (cccValue == 0x0000) {
                    Log.i(TAG, "Client unsubscribed from notifications/indications");
                }
            }

            // Update descriptor value
            descriptor.setValue(value);

            if (responseNeeded) {
                try {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                } catch (SecurityException e) {
                    Log.e(TAG, "Security exception in descriptor write request: " + e.getMessage());
                }
            }
        }

        @Override
        public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
            super.onExecuteWrite(device, requestId, execute);
            Log.d(TAG, "Execute write from: " + device.getAddress() + ", Execute: " + execute);
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            super.onNotificationSent(device, status);
            Log.d(TAG, "Notification sent to: " + device.getAddress() + ", Status: " + status);
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            super.onMtuChanged(device, mtu);
            Log.i(TAG, "MTU changed for device: " + device.getAddress() + ", New MTU: " + mtu);
        }

        @Override
        public void onPhyUpdate(BluetoothDevice device, int txPhy, int rxPhy, int status) {
            super.onPhyUpdate(device, txPhy, rxPhy, status);
            Log.d(TAG, "PHY update for device: " + device.getAddress() +
                    ", TX PHY: " + txPhy + ", RX PHY: " + rxPhy + ", Status: " + status);
        }

        @Override
        public void onPhyRead(BluetoothDevice device, int txPhy, int rxPhy, int status) {
            super.onPhyRead(device, txPhy, rxPhy, status);
            Log.d(TAG, "PHY read for device: " + device.getAddress() +
                    ", TX PHY: " + txPhy + ", RX PHY: " + rxPhy + ", Status: " + status);
        }
    };

    // Utility method to convert bytes to hex string
    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex).append(" ");
        }
        return hexString.toString().trim();
    }

    // Security callback for pairing/bonding
    private final BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            // Not used in peripheral mode, but kept for reference
        }
    };
}
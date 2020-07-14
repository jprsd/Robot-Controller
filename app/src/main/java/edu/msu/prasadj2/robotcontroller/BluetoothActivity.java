/*
 * Author: Jaideep Prasad
 * CSE 476 Spring 2020
 * Honors Option Project
 */

package edu.msu.prasadj2.robotcontroller;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.LocationManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Starting activity for finding nearby devices and
 * establishing a bluetooth connection with the robot
 */
public class BluetoothActivity extends AppCompatActivity {

    /// The permissions this app requires
    private static final String[] NECESSARY_PERMISSIONS = {
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
    };

    /// Request code for getting permissions granted
    private static final int REQUEST_NECESSARY_PERMISSIONS = 1;
    /// Request code for turning on bluetooth services
    private static final int REQUEST_BLUETOOTH_ENABLE = 2;
    /// Request code for turning on location services
    private static final int REQUEST_LOCATION_ENABLE = 3;

    /// Tracks if the app has been granted all necessary permissions
    private boolean permissionsGranted = false;
    /// Determines if a new connection thread can be launched
    private boolean readyToConnect = true;
    /// Tracks if an initial connection has been established with the robot
    private boolean connectedToRobot = false;

    /// List of alert dialog boxes. Used for dismissing purposes to avoid window leaks.
    private List<AlertDialog> alertDialogs = new ArrayList<AlertDialog>();

    /// The device's location manager
    private LocationManager locationManager = null;
    /// The device's bluetooth adapter
    private BluetoothAdapter bluetoothAdapter = null;
    /// The set of bluetooth devices this device has previously bonded to
    private Set<BluetoothDevice> pairedDevices = new HashSet<BluetoothDevice>();
    /// The set of bluetooth devices found during a discovery search
    private Set<BluetoothDevice> discoveredDevices = new HashSet<BluetoothDevice>();

    /// The bluetooth device currently selected by the user
    private BluetoothDevice selectedDevice = null;

    /// Worker thread used to establish a connection with the robot
    private EstablishConnectionThread connectionThread = null;

    /// The broadcast receiver this app will use to listen to bluetooth and location statuses
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, @NonNull Intent intent) {
            String action = intent.getAction();
            if (LocationManager.PROVIDERS_CHANGED_ACTION.equals(action)) {
                if (!isLocationEnabled()) {
                    // Clear the bluetooth functionality when location is disabled
                    resetBluetooth();
                    Toast toast = Toast.makeText(context,
                            R.string.location_settings_instructions, Toast.LENGTH_SHORT);
                    toast.setGravity(Gravity.CENTER, 0, 0);
                    toast.show();
                }
            }
            else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                // Build a fresh list of devices whenever a new discovery process is started
                discoveredDevices.clear();
                clearDeviceListUI(R.id.discoveredDevicesList);
            }
            else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    // Refresh and rescan for nearby devices
                    refreshBluetooth();
                }
                else if (state == BluetoothAdapter.STATE_OFF) {
                    // Clear the bluetooth functionality when the bluetooth services are disabled
                    resetBluetooth();
                    Toast toast = Toast.makeText(context, R.string.bluetooth_permissions,
                            Toast.LENGTH_LONG);
                    toast.setGravity(Gravity.CENTER, 0, 0);
                    toast.show();
                }
            }
            else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Update the discovered device list whenever a new bluetooth device is found
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    discoveredDevices.add(device);
                    updateDeviceListUI(R.id.discoveredDevicesList,
                            device.getName(), device.getAddress());
                }
            }
        }
    };

    /**
     * Creates this activity
     * @param savedInstanceState Any previously saved bundle data
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bluetooth);

        // Register a broadcast receiver with bluetooth and location filters
        registerBroadcastReceiver();

        // Get permissions
        requestNecessaryPermissions();

        // Proceed with app functionality only if permissions have been granted
        if (permissionsGranted) {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null) {
                // Device does not support bluetooth capabilities
                Toast toast = Toast.makeText(this,
                        R.string.bluetooth_hardware_fail, Toast.LENGTH_LONG);
                toast.setGravity(Gravity.CENTER, 0, 0);
                toast.show();
                finish();
                return;
            }

            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (locationManager == null) {
                // Device does not support location capabilities
                Toast toast = Toast.makeText(this,
                        R.string.location_hardware_fail, Toast.LENGTH_LONG);
                toast.setGravity(Gravity.CENTER, 0, 0);
                toast.show();
                finish();
                return;
            }

            // Enable location if it is disabled
            if (!isLocationEnabled()) {
                requestEnableLocation();
            }

            // Enable bluetooth if it is disabled
            if (!bluetoothAdapter.isEnabled()) {
                requestEnableBluetoothAdapter();
            }
            else {
                // Bluetooth was already enabled beforehand.
                // Populate list of paired (any previously bonded) devices.
                pairedDevices = bluetoothAdapter.getBondedDevices();
                for (BluetoothDevice device : pairedDevices) {
                    updateDeviceListUI(R.id.pairedDevicesList,
                            device.getName(), device.getAddress());
                }
                bluetoothAdapter.startDiscovery();
            }

        }
    }

    /**
     * Unregisters receivers, terminates any worker threads, and
     * dismisses any open dialogs when the activity is destroyed
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(broadcastReceiver);
        if (bluetoothAdapter != null) {
            bluetoothAdapter.cancelDiscovery();
        }
        if (connectionThread != null) {
            connectionThread.terminate();
        }
        for (AlertDialog alertDialog: alertDialogs) {
            if (alertDialog != null && alertDialog.isShowing()) {
                alertDialog.dismiss();
            }
        }
    }

    /**
     * Button handler for bluetooth device selection
     * @param view The current view
     */
    public void onSelectDevice(View view) {
        if (!isConnectedToRobot()) {
            Set<BluetoothDevice> allDevices = new HashSet<BluetoothDevice>(pairedDevices);
            allDevices.addAll(discoveredDevices);
            for (BluetoothDevice device : allDevices) {
                if ((device.getAddress() != null &&
                        device.getAddress().equals(((Button) view).getText().toString())) ||
                        (device.getName() != null &&
                                device.getName().equals(((Button) view).getText().toString()))) {
                    selectedDevice = device;
                    ((Button) findViewById(R.id.connect)).setEnabled(true);
                    clearSelectedDevicesUI();
                    view.setBackgroundColor(Color.LTGRAY);
                    return;
                }
            }
        }
    }

    /**
     * Handler for the connect button.
     * Attempts to connect with the selected device.
     * @param view The current view
     */
    public void onConnect(View view) {
        if (selectedDevice != null && isReadyToConnect() && !isConnectedToRobot()) {
            setReadyToConnect(false);
            Toast toast =
                    Toast.makeText(this, R.string.attempting_connection, Toast.LENGTH_SHORT);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
            connectionThread = new EstablishConnectionThread(selectedDevice);
            connectionThread.start();
        }
    }

    /**
     * Handler for the control button
     * Starts controller activity
     * @param view The current view
     */
    public void onControl(View view) {
        if (isConnectedToRobot()) {
            Intent intent = new Intent(this, ControllerActivity.class);
            startActivity(intent);
        }
    }

    /**
     * Refreshes the bluetooth services to get the latest list of nearby devices
     * @param view The current view
     */
    public void onRefresh(View view) {
        refreshBluetooth();
    }

    /**
     * Handler for when the app heading is clicked
     * @param view The current view
     */
    public void onAbout(View view) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.app_name);
        builder.setMessage(R.string.about);
        builder.setPositiveButton(android.R.string.ok, null);

        AlertDialog alertDialog = builder.create();
        alertDialogs.add(alertDialog);
        alertDialog.show();
    }

    /**
     * Handles results for bluetooth and location enable requests
     * @param requestCode The request code for the activity
     * @param resultCode The result code of the activity
     * @param data Data associated with the activity
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_BLUETOOTH_ENABLE:
                if (resultCode == RESULT_OK) {
                    if (bluetoothAdapter != null && !bluetoothAdapter.isDiscovering()) {
                        bluetoothAdapter.startDiscovery();
                    }
                }
                else {
                    AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle(R.string.app_name);
                    builder.setMessage(R.string.bluetooth_permissions);
                    builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialog) {
                            Toast toast = Toast.makeText(BluetoothActivity.this,
                                    R.string.bluetooth_permissions, Toast.LENGTH_LONG);
                            toast.setGravity(Gravity.CENTER, 0, 0);
                            toast.show();
                            BluetoothActivity.this.finish();
                        }
                    });
                    builder.setNegativeButton(android.R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.cancel();
                                }
                            });
                    builder.setPositiveButton(android.R.string.ok,
                            new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            requestEnableBluetoothAdapter();
                        }
                    });
                    AlertDialog alertDialog = builder.create();
                    alertDialog.setCanceledOnTouchOutside(true);
                    alertDialogs.add(alertDialog);
                    alertDialog.show();
                }
                break;
            case REQUEST_LOCATION_ENABLE:
                if (isLocationEnabled()) {
                    recreate();
                }
                else {
                    requestEnableLocation();
                }
                break;
        }
    }

    /**
     * Handles results for permission requests
     * @param requestCode The request code for the permissions
     * @param permissions The permissions requested
     * @param grantResults The result codes for each requested permission
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_NECESSARY_PERMISSIONS:
                if (grantResults.length > 0) {
                    for (int result : grantResults) {
                        if (result != PackageManager.PERMISSION_GRANTED) {
                            if (shouldShowNecessaryPermissionsRationale()) {
                                showNecessaryPermissionsRationale();
                                return;
                            }
                            else {
                                Toast toast = Toast.makeText(this,
                                        R.string.go_to_settings, Toast.LENGTH_LONG);
                                toast.setGravity(Gravity.CENTER, 0, 0);
                                toast.show();
                                finish();
                                return;
                            }
                        }
                    }
                    recreate();
                }
                else {
                    Toast toast = Toast.makeText(this,
                            R.string.go_to_settings, Toast.LENGTH_LONG);
                    toast.setGravity(Gravity.CENTER, 0, 0);
                    toast.show();
                    finish();
                }
                break;
        }
    }

    /**
     * Evaluates currently granted permissions and requests whatever is missing
     */
    private void requestNecessaryPermissions() {
        List<String> missingPermissions = new ArrayList<String>();
        for (String permission : NECESSARY_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }
        if (missingPermissions.size() > 0) {
            ActivityCompat.requestPermissions(this,
                    missingPermissions.toArray(new String[0]), REQUEST_NECESSARY_PERMISSIONS);
        }
        else {
            permissionsGranted = true;
        }
    }

    /**
     * Determines if the user needs to see a justification
     * for why this app needs its permissions granted
     * @return true if the user should be shown an explanatory dialog box
     */
    private boolean shouldShowNecessaryPermissionsRationale() {
        for (String permission: NECESSARY_PERMISSIONS) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Shows a dialog box with an explanation of why the app needs its permissions granted
     */
    private void showNecessaryPermissionsRationale() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.permissions_required);
        builder.setMessage(R.string.permissions_rationale);
        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                requestNecessaryPermissions();
            }
        });
        builder.setPositiveButton(android.R.string.ok,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        requestNecessaryPermissions();
                    }
                });

        AlertDialog alertDialog = builder.create();
        alertDialog.setCanceledOnTouchOutside(true);
        alertDialogs.add(alertDialog);
        alertDialog.show();
    }

    /**
     * Determines if the device currently has location services enabled
     * @return true if location services are enabled
     */
    private boolean isLocationEnabled() {
        if (locationManager == null) {
            return false;
        }
        boolean gpsEnabled = false;
        boolean networkEnabled = false;
        try {
            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            // Ignore
        }
        try {
            networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            // Ignore
        }
        return gpsEnabled || networkEnabled;
    }

    /**
     * Requests the user to enable location services
     */
    private void requestEnableLocation() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.app_name);
        builder.setMessage(R.string.location_permissions);
        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                Toast toast = Toast.makeText(BluetoothActivity.this,
                        R.string.location_permissions, Toast.LENGTH_LONG);
                toast.setGravity(Gravity.CENTER, 0, 0);
                toast.show();
                BluetoothActivity.this.finish();
            }
        });
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Intent enableLocationIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                startActivityForResult(enableLocationIntent, REQUEST_LOCATION_ENABLE);
            }
        });

        AlertDialog alertDialog = builder.create();
        alertDialog.setCanceledOnTouchOutside(true);
        alertDialogs.add(alertDialog);
        alertDialog.show();
    }

    /**
     * Requests the user to enable bluetooth services
     */
    private void requestEnableBluetoothAdapter() {
        Intent enableBluetoothIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(enableBluetoothIntent, REQUEST_BLUETOOTH_ENABLE);
    }

    /**
     * Registers this activity's broadcast receiver for bluetooth and location statuses
     */
    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        registerReceiver(broadcastReceiver, filter);
    }

    /**
     * Adds to the list of bluetooth devices this device has found or has previously paired with
     * @param listId The list to update on screen (previously paired or discovered nearby).
     * @param deviceName The name of the bluetooth device
     * @param deviceHardwareAddress The MAC address of the bluetooth device
     */
    private void updateDeviceListUI(int listId, String deviceName, String deviceHardwareAddress) {
        LinearLayout linearLayout = (LinearLayout)findViewById(listId);
        Button deviceEntry = new Button(this, null,
                android.R.attr.borderlessButtonStyle);
        deviceEntry.setLayoutParams(new
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        deviceEntry.setText(deviceName == null ? deviceHardwareAddress : deviceName);
        deviceEntry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSelectDevice(v);
            }
        });
        linearLayout.addView(deviceEntry);
        linearLayout.invalidate();
    }

    /**
     * Clears a list of bluetooth devices on screen
     * @param listId The specific list to clear
     */
    private void clearDeviceListUI(int listId) {
        LinearLayout linearLayout = (LinearLayout)findViewById(listId);
        linearLayout.removeAllViews();
        linearLayout.invalidate();
    }

    /**
     * UI update for deselecting a bluetooth device from the devices list
     */
    private void clearSelectedDevicesUI() {
        LinearLayout linearLayout = (LinearLayout)findViewById(R.id.pairedDevicesList);
        for (int i = 0; i < linearLayout.getChildCount(); i++) {
            linearLayout.getChildAt(i).setBackgroundColor(Color.TRANSPARENT);
        }
        linearLayout = (LinearLayout)findViewById(R.id.discoveredDevicesList);
        for (int i = 0; i < linearLayout.getChildCount(); i++) {
            linearLayout.getChildAt(i).setBackgroundColor(Color.TRANSPARENT);
        }
    }

    /**
     * Resets (clears) the bluetooth functionality for this app
     */
    private void resetBluetooth() {
        discoveredDevices.clear();
        pairedDevices = new HashSet<BluetoothDevice>();
        clearDeviceListUI(R.id.discoveredDevicesList);
        clearDeviceListUI(R.id.pairedDevicesList);
        selectedDevice = null;
        ((Button)findViewById(R.id.connect)).setEnabled(false);
        ((Button)findViewById(R.id.control)).setEnabled(false);
        if (connectionThread != null) {
            connectionThread.terminate();
        }
        setReadyToConnect(true);
        setConnectedToRobot(false);
    }

    /**
     * Refreshes the bluetooth functionality for this app by starting a new discovery process
     */
    private void refreshBluetooth() {
        resetBluetooth();
        if (bluetoothAdapter != null) {
            if (!bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.startDiscovery();
            }
            pairedDevices = bluetoothAdapter.getBondedDevices();
        }
        for (BluetoothDevice device : pairedDevices) {
            updateDeviceListUI(R.id.pairedDevicesList,
                    device.getName(), device.getAddress());
        }
    }

    /**
     * Determines if a worker thread to establish a bluetooth connection can be launched
     * @return true if no other connection worker threads are active
     */
    public synchronized boolean isReadyToConnect() {
        return readyToConnect;
    }

    /**
     * Sets the connection worker thread status for this activity
     * @param isReady The current thread status
     */
    public synchronized void setReadyToConnect(boolean isReady) {
        readyToConnect = isReady;
    }

    /**
     * Determines if a connection has been established to the robot
     * @return true if there is a connection to the robot
     */
    public synchronized boolean isConnectedToRobot() {
        return connectedToRobot;
    }

    /**
     * Sets if the app has established a connection to the robot
     * @param isConnected The current connection status
     */
    public synchronized void setConnectedToRobot(boolean isConnected) {
        connectedToRobot = isConnected;
    }

    /**
     * Class for the worker thread that will set up the initial connection to the robot
     */
    private class EstablishConnectionThread extends Thread {
        // The specific UUID for the Arduino HC-06 bluetooth module
        private static final String UUID_STRING = "00001101-0000-1000-8000-00805F9B34FB";
        // The robot's bluetooth socket
        private final BluetoothSocket socket;

        /**
         * Constructor for this connection thread
         * Attempts to set up the bluetooth socket
         * @param device The robot's bluetooth module
         */
        public EstablishConnectionThread(@NonNull BluetoothDevice device) {
            BluetoothSocket testSocket = null;
            try {
                testSocket = device.createRfcommSocketToServiceRecord(UUID.fromString(UUID_STRING));
            } catch (IOException e) {
                Toast toast = Toast.makeText(BluetoothActivity.this,
                        R.string.socket_creation_failed, Toast.LENGTH_SHORT);
                toast.setGravity(Gravity.CENTER, 0, 0);
                toast.show();
            }
            socket = testSocket;
        }

        /**
         * Attempts to establish a connection with the robot
         */
        @Override
        public void run() {
            if (bluetoothAdapter != null) {
                bluetoothAdapter.cancelDiscovery();
            }

            try {
                socket.connect();
            } catch (IOException e) {
                setReadyToConnect(true);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast toast = Toast.makeText(BluetoothActivity.this,
                                R.string.connection_failed, Toast.LENGTH_SHORT);
                        toast.setGravity(Gravity.CENTER, 0, 0);
                        toast.show();
                    }
                });
                try {
                    socket.close();
                } catch (IOException ex) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast toast = Toast.makeText(BluetoothActivity.this,
                                    R.string.socket_close_fail, Toast.LENGTH_SHORT);
                            toast.setGravity(Gravity.CENTER, 0, 0);
                            toast.show();
                        }
                    });
                }
                return;
            }

            // Passes the socket to the app's global socket manager if the connection is successful
            GlobalSocketManager.setBluetoothSocket(socket);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ((Button)findViewById(R.id.connect)).setEnabled(false);
                    ((Button)findViewById(R.id.control)).setEnabled(true);
                    Toast toast = Toast.makeText(BluetoothActivity.this,
                            R.string.connection_successful, Toast.LENGTH_SHORT);
                    toast.setGravity(Gravity.CENTER, 0, 0);
                    toast.show();
                }
            });
            setConnectedToRobot(true);
        }

        /**
         * Terminates the thread by closing the socket
         */
        public void terminate() {
            try {
                socket.close();
            } catch (IOException ex) {
                // Ignore
            }
        }
    }

}

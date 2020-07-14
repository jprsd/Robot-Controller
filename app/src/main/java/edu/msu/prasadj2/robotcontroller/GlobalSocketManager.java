/*
 * Author: Jaideep Prasad
 * CSE 476: Spring 2020
 * Honors Option Project
 */

package edu.msu.prasadj2.robotcontroller;

import android.bluetooth.BluetoothSocket;

/**
 * Class for managing a bluetooth socket across multiple activities
 */
public class GlobalSocketManager {
    /// The bluetooth socket
    private static BluetoothSocket bluetoothSocket;

    /**
     * Gets the bluetooth socket
     * @return The bluetooth socket
     */
    public static synchronized BluetoothSocket getBluetoothSocket() {
        return bluetoothSocket;
    }

    /**
     * Sets the bluetooth socket that will be managed throughout the app
     * @param bluetoothSocket The bluetooth socket
     */
    public static synchronized void setBluetoothSocket(BluetoothSocket bluetoothSocket) {
        GlobalSocketManager.bluetoothSocket = bluetoothSocket;
    }
}

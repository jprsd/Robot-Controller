/*
 * Author: Jaideep Prasad
 * CSE 476: Spring 2020
 * Honors Option Project
 */

package edu.msu.prasadj2.robotcontroller;

import android.bluetooth.BluetoothSocket;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The activity for controlling the robot after a connection has successfully been established.
 *
 * Motor speeds are set using seek bar sliders and commands are sent when the on-screen
 * buttons are pressed. The direction of each motor (forward/positive or backward/negative)
 * is automatically reflected on the sliders depending on the type of motion requested.
 *
 * Communication between the app and the robot is logged.
 */
public class ControllerActivity extends AppCompatActivity
        implements View.OnClickListener, SeekBar.OnSeekBarChangeListener {

    /// Offset for converting seek bar progress (0-200) to motor speeds (-100 to 100)
    private static final int SEEK_BAR_OFFSET = 100;

    /// The speed of the robot's left motor
    private int leftMotorSpeed = 0;
    /// The speed of the robot's right motor
    private int rightMotorSpeed = 0;

    /// The seek bar that controls the robot's left motor speed
    SeekBar leftMotorController = null;
    /// The seek bar that controls the robot's right motor speed
    SeekBar rightMotorController = null;

    /// Worker thread for communicating with the robot
    private ControllerThread controllerThread = null;
    /// Boolean for tracking if the worker thread is active. Accessed with synchronized methods.
    private boolean communicationActive = false;

    /**
     * Creates this activity
     * @param savedInstanceState Any previously saved bundle data
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_controller);

        // Listeners are handled directly in this class
        leftMotorController = (SeekBar)findViewById(R.id.leftMotorController);
        leftMotorController.setOnSeekBarChangeListener(this);
        rightMotorController = (SeekBar)findViewById(R.id.rightMotorController);
        rightMotorController.setOnSeekBarChangeListener(this);

        // Start only one worker thread
        if (!isCommunicationActive()) {
            setCommunicationActive(true);
            controllerThread = new ControllerThread(GlobalSocketManager.getBluetoothSocket());
            controllerThread.start();
        }

    }

    /**
     * Terminates the worker thread, if it exists,
     * when the activity is destroyed
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (controllerThread != null) {
            controllerThread.terminate();
        }
    }

    /**
     * Handles clicks for all of the button controls on the screen
     * @param v The view (button) that was pressed
     */
    @Override
    public void onClick(View v) {
        if (controllerThread == null) {
            return;
        }
        switch (v.getId()) {
            // Stops all movement
            case R.id.stopButton:
                leftMotorController.setProgress(SEEK_BAR_OFFSET);
                rightMotorController.setProgress(SEEK_BAR_OFFSET);
                controllerThread.sendCommand("STP");
                break;
            // Moves the robot forward
            case R.id.forwardButton:
                {
                int speed = Math.max(Math.abs(leftMotorSpeed), Math.abs(rightMotorSpeed));
                leftMotorController.setProgress(speed + SEEK_BAR_OFFSET);
                rightMotorController.setProgress(speed + SEEK_BAR_OFFSET);
                controllerThread.sendCommand("F " + speed);
                }
                break;
            // Moves the robot backward
            case R.id.backwardButton:
                {
                int speed = -Math.max(Math.abs(leftMotorSpeed), Math.abs(rightMotorSpeed));
                leftMotorController.setProgress(speed + SEEK_BAR_OFFSET);
                rightMotorController.setProgress(speed + SEEK_BAR_OFFSET);
                controllerThread.sendCommand("B " + -speed);
                }
                break;
            // Turns the robot right
            case R.id.rightButton:
                {
                int leftSpeed = Math.abs(leftMotorSpeed);
                int rightSpeed = -Math.abs(rightMotorSpeed);
                leftMotorController.setProgress(leftSpeed + SEEK_BAR_OFFSET);
                rightMotorController.setProgress(rightSpeed + SEEK_BAR_OFFSET);
                controllerThread.sendCommand("TR " + -rightSpeed + " " + leftSpeed);
                }
                break;
            // Turns the robot left
            case R.id.leftButton:
                {
                int leftSpeed = -Math.abs(leftMotorSpeed);
                int rightSpeed = Math.abs(rightMotorSpeed);
                leftMotorController.setProgress(leftSpeed + SEEK_BAR_OFFSET);
                rightMotorController.setProgress(rightSpeed + SEEK_BAR_OFFSET);
                controllerThread.sendCommand("TL " + rightSpeed + " " + -leftSpeed);
                }
                break;
            // Spins the robot right
            case R.id.spinRightButton:
                {
                int leftSpeed = Math.abs(leftMotorSpeed);
                leftMotorController.setProgress(leftSpeed + SEEK_BAR_OFFSET);
                rightMotorController.setProgress(SEEK_BAR_OFFSET);
                controllerThread.sendCommand("SR " + leftMotorSpeed);
                }
                break;
            // Spins the robot left
            case R.id.spinLeftButton:
                {
                int rightSpeed = Math.abs(rightMotorSpeed);
                leftMotorController.setProgress(SEEK_BAR_OFFSET);
                rightMotorController.setProgress(rightSpeed + SEEK_BAR_OFFSET);
                controllerThread.sendCommand("SL " + rightMotorSpeed);
                }
                break;
        }
    }

    /**
     * Handles seek bar slider changes
     * @param seekBar The seek bar that was changed
     * @param progress The current value of the slider
     * @param fromUser Tracks if the change came directly from the user
     */
    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        switch (seekBar.getId()) {
            case R.id.leftMotorController:
                leftMotorSpeed = progress - SEEK_BAR_OFFSET;
                break;
            case R.id.rightMotorController:
                rightMotorSpeed = progress - SEEK_BAR_OFFSET;
                break;
        }

    }

    /**
     * Determines if there is a communication link with the robot
     * @return true if there is a communication link
     */
    public synchronized boolean isCommunicationActive() {
        return communicationActive;
    }

    /**
     * Sets the communication link state
     * @param isActive The current link state
     */
    public synchronized void setCommunicationActive(boolean isActive) {
        communicationActive = isActive;
    }

    /**
     * Class for the worker thread that communicates with the robot
     */
    private class ControllerThread extends Thread {
        /// The bluetooth socket this thread will communicate with
        private final BluetoothSocket socket;
        /// Reader for incoming communication from the robot
        private final BufferedReader bufferedReader;
        /// Byte stream for outgoing communication to the robot
        private final OutputStream outputStream;

        /**
         * Constructor for this controller thread
         * Establishes input and output streams
         * @param socket The robot's bluetooth socket
         */
        public ControllerThread(@NonNull BluetoothSocket socket) {
            this.socket = socket;
            InputStream inputStream = null;
            OutputStream outputStream = null;

            try {
                inputStream = socket.getInputStream();
            } catch (IOException e) {
                setCommunicationActive(false);
                Toast.makeText(ControllerActivity.this,
                        R.string.failed_input, Toast.LENGTH_SHORT).show();
            }

            try {
                outputStream = socket.getOutputStream();
            } catch (IOException e) {
                setCommunicationActive(false);
                Toast.makeText(ControllerActivity.this,
                        R.string.failed_output, Toast.LENGTH_SHORT).show();
            }

            bufferedReader = inputStream == null ? null :
                    new BufferedReader(new InputStreamReader(inputStream));
            this.outputStream = outputStream;
        }

        /**
         * Listens to incoming messages from the robot
         */
        @Override
        public void run() {
            try {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    Log.i("Robot Message", line);
                }
            } catch (IOException e) {
                presentErrorUI();
            }
        }

        /**
         * Sends a command to the robot as a byte array.
         * @param command The command string
         */
        public void sendCommand(String command) {
            try {
                outputStream.write((command + "\r\n").getBytes(StandardCharsets.UTF_8));
                Log.i("User Command", command);
            } catch (IOException e) {
                presentErrorUI();
            }
        }

        /**
         * Terminates the thread by closing the socket
         */
        public void terminate() {
            try {
                socket.close();
                setCommunicationActive(false);
            } catch (IOException e) {
                // Ignore
            }
        }

        /**
         * Presents an error message on the UI thread
         */
        private void presentErrorUI() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(ControllerActivity.this,
                            R.string.communication_disrupted, Toast.LENGTH_SHORT).show();
                }
            });
        }

    }

    // Do nothing for these

    /**
     * Handles the start of a motion touch event
     * @param seekBar The affected seek bar
     */
    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {}

    /**
     * Handles the end of a motion touch event
     * @param seekBar The affected seek bar
     */
    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {}
}

package com.example.sgoesconnect;

import androidx.appcompat.app.AppCompatActivity;
import android.bluetooth.*;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private class ConnectedThread extends Thread {
        private final BluetoothSocket copyBtSocket;
        private final OutputStream outStream;

        public ConnectedThread(BluetoothSocket socket) {
            copyBtSocket = socket;
            OutputStream tmpOut = null;

            try {
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
            }
            outStream = tmpOut;
        }

        public void sendData(String message) {
            byte[] msgBuffer = message.getBytes();

            try {
                outStream.write(msgBuffer);
            } catch (IOException e) {
            }
        }

        public void cancel() {
            try {
                copyBtSocket.close();
            } catch (IOException e) {
                myError("Fatal Error", "не могу закрыть сокет" + e.getMessage() + ".");
            }
        }

        public Object status_outStream() {
            if (outStream == null) {
                return null;
            } else {
                return outStream;
            }
        }
    }

    private static final int REQUEST_ENABLE_BT = 0;
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static String macAddress = "98:D3:71:F5:DA:46";
    private BluetoothSocket btSocket = null;
    Button bt_settings;
    Button bt_connect;
    private ConnectedThread myThread = null;
    final String LOG_TAG = "myLogs";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bt_settings = (Button) findViewById(R.id.bt_settings);
        bt_settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // вызываем экран настроек bluetooth:
                startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
            }
        });

        bt_connect = (Button) findViewById(R.id.bt_connect);
        bt_connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

                if (bluetoothAdapter != null) {
                    //Toast.makeText(getApplicationContext(), "bluetooth adapter is detected", Toast.LENGTH_SHORT).show();

                    if (bluetoothAdapter.isEnabled()) {
                        //Toast.makeText(getApplicationContext(), "bluetooth is enabled", Toast.LENGTH_SHORT).show();
                        //String myDeviceName = bluetoothAdapter.getName();
                        //Toast.makeText(getApplicationContext(), myDeviceName, Toast.LENGTH_SHORT).show();
                    } else {
                        //Toast.makeText(getApplicationContext(), "bluetooth is disabled", Toast.LENGTH_SHORT).show();

                        // запрос на включение bluetooth:
                        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);

                    }

                } else {
                    Toast.makeText(getApplicationContext(), "bluetooth adapter is not detected", Toast.LENGTH_SHORT).show();
                }

                // TODO переделать: продумать правильную последовательность, чтобы дальнейший код не
                // выполнялся при отключенном блютусе и тп...

                BluetoothDevice bluetoothDevice = bluetoothAdapter.getRemoteDevice(macAddress);
                Toast.makeText(getApplicationContext(), bluetoothDevice.getName(), Toast.LENGTH_SHORT).show();
                //Log.d(LOG_TAG, "***Получили удаленный Device***" + bluetoothDevice.getName());

                try {
                    btSocket = bluetoothDevice.createRfcommSocketToServiceRecord(MY_UUID);
                    Toast.makeText(getApplicationContext(), "socket is created", Toast.LENGTH_SHORT).show();
                } catch (IOException e) {
                    myError("Fatal Error", "Не могу создать сокет: " + e.getMessage() + ".");
                }

                bluetoothAdapter.cancelDiscovery();

                try {
                    btSocket.connect();
                    Toast.makeText(getApplicationContext(), "connect is open", Toast.LENGTH_SHORT).show();
                } catch (IOException e) {
                    try {
                        btSocket.close();
                        Toast.makeText(getApplicationContext(), "exception, connect is closed", Toast.LENGTH_SHORT).show();
                    } catch (IOException e2) {
                        myError("Fatal Error", "не могу закрыть сокет" + e2.getMessage() + ".");
                    }
                }

                myThread = new ConnectedThread(btSocket);
            }
        });
    }

/*    @Override
    public void onPause() {
        super.onPause();

        if (myThread.status_outStream() != null) {
            myThread.cancel();
        }

        try {
            btSocket.close();
        } catch (IOException e2) {
            myError("Fatal Error", "В onPause() Не могу закрыть сокет" + e2.getMessage() + ".");
        }
    }*/

    //TODO  вот это надо как-то переделать...
    @Override
    public void onDestroy() {
        super.onDestroy();

        if (myThread.status_outStream() != null) {
            myThread.cancel();
        }

        try {
            btSocket.close();
            Toast.makeText(getApplicationContext(), "socket is closed", Toast.LENGTH_SHORT).show();
        } catch (IOException e2) {
            myError("Fatal Error", "В onPause() Не могу закрыть сокет" + e2.getMessage() + ".");
        }
    }

    private void myError(String title, String message) {
        Toast.makeText(getBaseContext(), title + " - " + message, Toast.LENGTH_LONG).show();
        finish();
    }
}

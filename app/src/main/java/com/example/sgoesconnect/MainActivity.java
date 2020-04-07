package com.example.sgoesconnect;

import androidx.appcompat.app.AppCompatActivity;
import android.bluetooth.*;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ResultReceiver;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private class ConnectedThread extends Thread {
        private final BluetoothSocket copyBtSocket;
        private final OutputStream outStream;
        private final InputStream inStream;

        public ConnectedThread(BluetoothSocket socket) {
            copyBtSocket = socket;
            OutputStream tmpOut = null;
            InputStream tmpIn = null;

            try {
                tmpOut = socket.getOutputStream();
                tmpIn = socket.getInputStream();
            } catch (IOException e) {
                //
            }
            outStream = tmpOut;
            inStream = tmpIn;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int numBytes;

            while (true) {
                try {
                    numBytes = inStream.read(buffer);
                    byte[] data = Arrays.copyOf(buffer, numBytes);
                    //Log.d(LOG_TAG, "data:" + bytesToHex(data));
                    myHandler.obtainMessage(arduinoData, numBytes, -1, data).sendToTarget();
                } catch (IOException e) {
                    break;
                }
            }
        }

        public void sendData(byte[] msgBuffer) {
            //byte[] msgBuffer = msg.getBytes();

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

    // from: https://stackoverflow.com/questions/140131/convert-a-string-representation-of-a-hex-dump-to-a-byte-array-using-java/140861#140861
    // Dave L.
    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    // from: https://stackoverflow.com/questions/9655181/how-to-convert-a-byte-array-to-a-hex-string-in-java
    // maybeWeCouldStealAVan
    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    // from: http://developer.alexanderklimov.ru/android/java/array.php#concat
    private byte[] concatArray(byte[] a, byte[] b) {
        if (a == null)
            return b;
        if (b == null)
            return a;
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static final int REQUEST_ENABLE_BT = 0;
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static String macAddress = "98:D3:71:F5:DA:46";
    private BluetoothSocket btSocket = null;
    Button bt_settings;
    Button bt_connect;
    Button connect_to_sensor;
    private ConnectedThread myThread = null;
    final String LOG_TAG = "myLogs";
    EditText sensor_address;
    TextView gas_level_nkpr;
    Handler myHandler;
    final int arduinoData = 1;
    int numResponseBytes = 0;  // счётчик байт, полученных в текущем ответе
    byte[] response;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(LOG_TAG, "ready");

        gas_level_nkpr = (TextView) findViewById(R.id.gas_level_nkpr);

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
                        Toast.makeText(getApplicationContext(), "exception, socket is closed", Toast.LENGTH_SHORT).show();
                    } catch (IOException e2) {
                        myError("Fatal Error", "не могу закрыть сокет" + e2.getMessage() + ".");
                    }
                }

                myThread = new ConnectedThread(btSocket);
                myThread.start();
            }
        });

        connect_to_sensor = (Button) findViewById(R.id.connect_to_sensor);
        connect_to_sensor.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                // Обнуляем счётчик принятых байт и массив:
                numResponseBytes = 0;
                response = null;

                // первый способ формирования массива байт
                //byte[] data = new byte[] { (byte)0x01, (byte)0x03};

                // второй способ
                String outputHexString = "010300000001840A";
                byte[] data = hexStringToByteArray(outputHexString);
                Log.d(LOG_TAG, "outputHexString: " + outputHexString);

                //sensor_address = (EditText) findViewById(R.id.sensor_address);
                //byte[] data = hexStringToByteArray(sensor_address.getText().toString());

                myThread.sendData(data);
            }
        });

        myHandler = new Handler() {
            public void handleMessage(android.os.Message msg) {
                switch (msg.what) {
                    case arduinoData:
                        // Увеличиваем счётчик принятых байт:
                        numResponseBytes = numResponseBytes + msg.arg1;
                        Log.d(LOG_TAG, "numResponseBytes:" + numResponseBytes);

                        // Добавляем принятые байты в общий массив:
                        byte[] readBuf = (byte[]) msg.obj;
                        response = concatArray(response, readBuf);
                        Log.d(LOG_TAG, "response:" + bytesToHex(response) + "\n ");
//                        Log.d(LOG_TAG, "=================================");

                        gas_level_nkpr.setText(bytesToHex(response));
                        break;
                }
            };
        };
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

    //TODO  вот это надо как-то переделать... чтобы корректно всё закрывалось когда надо...
    @Override
    public void onDestroy() {
        super.onDestroy();

        if (myThread != null) {
            if (myThread.status_outStream() != null) {
                myThread.cancel();
            }
        }

        if (btSocket != null) {
            try {
                btSocket.close();
                Toast.makeText(getApplicationContext(), "socket is closed", Toast.LENGTH_SHORT).show();
            } catch (IOException e2) {
                myError("Fatal Error", "В onDestroy() Не могу закрыть сокет" + e2.getMessage() + ".");
            }
        }
    }

    private void myError(String title, String message) {
        Toast.makeText(getBaseContext(), title + " - " + message, Toast.LENGTH_LONG).show();
        finish();
    }
}

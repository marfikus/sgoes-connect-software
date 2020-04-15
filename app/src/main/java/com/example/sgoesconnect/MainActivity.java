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
import java.security.CryptoPrimitive;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.Checksum;
import java.util.BitSet;

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
    private byte[] hexStringToByteArray(String s) {
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
    private final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    private String bytesToHex(byte[] bytes) {
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

    private void checkResponse() {
        // TODO: 08.04.2020 возможно проще будет проверять response.length и отказаться от numResponseBytes
        if (numResponseBytes < 5) {
            return;
        }
        // отделяем 2 последних байта ответа
        byte[] respMsg = new byte[response.length - 2];
        byte[] respCRC = new byte[2];
        System.arraycopy(response, 0, respMsg, 0, response.length - 2);
//        Log.d(LOG_TAG, "respMsg: " + bytesToHex(respMsg));
        System.arraycopy(response, response.length - 2, respCRC, 0, respCRC.length);
//        Log.d(LOG_TAG, "respCRC: " + bytesToHex(respCRC));

        // сравниваем последние 2 байта ответа с тем, что вычислим здесь

//        Log.d(LOG_TAG, "calcCRC: " + bytesToHex(calcCRC(respMsg)));
        Log.d(LOG_TAG, "response: " + bytesToHex(response));

        // если контрольная сумма из ответа не совпадает с расчётом, то выходим:
        if (!Arrays.equals(respCRC, calcCRC(respMsg))) {
            Log.d(LOG_TAG, bytesToHex(respCRC) + " != " + bytesToHex(calcCRC(respMsg)));
            return;
        }

        // парсим ответ, выводим данные... (тоже отдельные функции)
//        Log.d(LOG_TAG, "go to parsing response... " + bytesToHex(response));
        parseResponse();
    }

    private void parseResponse() {

//      Разбор ответа должен производиться на основании запроса.
//      Сравниваем первый байт запроса с первым байтом ответа (адреса) Если не совпадают:
        int reqAddress = request[0] & 0xFF; // & 0xFF необходимо для приведения значения байта к виду 0..255
        int respAddress = response[0] & 0xFF;

        if (reqAddress != respAddress) {
//          Это ответ на другой запрос, от другого датчика. Выходим?
            Log.d(LOG_TAG, "reqAddress(" + reqAddress + ") != respAddress(" + respAddress + ")");
            return;
        }

//      Сравниваем вторые байты (код функции). Если совпадают:
        int reqFuncCode = request[1] & 0xFF;
        int respFuncCode = response[1] & 0xFF;

        if (reqFuncCode == respFuncCode) {
            Log.d(LOG_TAG, "reqFuncCode(" + reqFuncCode + ") == respFuncCode(" + respFuncCode + ") Parsing of data...");
//          Ответ на этот запрос, без ошибки, переходим далее к разбору данных:
            parseRespData();

        } else { // не совпадают
            Log.d(LOG_TAG, "reqFuncCode(" + reqFuncCode + ") != respFuncCode(" + respFuncCode + ")");
//          Если второй байт ответа равен второму байту запроса с единицей в старшем бите (код ошибки):
            int modReqFuncCode = (request[1] | 0b10000000) & 0xFF; // устанавливаем единицу в старший бит

            if (respFuncCode == modReqFuncCode) {
                Log.d(LOG_TAG, "respFuncCode(" + respFuncCode + ") == modReqFuncCode(" + modReqFuncCode + ")");
//              значит ответ на этот запрос, но с ошибкой:
//              читаем третий байт ответа и выводим информацию об ошибке...
                int respError = response[2] & 0xFF;
                Log.d(LOG_TAG, "Error in response. Error code: " + respError);
            } else {
                // значит это хз что за ответ)...
                Log.d(LOG_TAG, "respFuncCode(" + respFuncCode + ") != modReqFuncCode(" + modReqFuncCode + ")");
            }
        }
    }

    private void parseRespData() {
        // читаем третий байт - количество байт идущих далее.
        int respNumDataBytes = response[2] & 0xFF;
        Log.d(LOG_TAG, "respNumDataBytes: " + respNumDataBytes);

        // todo: количество регистров и адрес первого пока беру так, а вообще будет формироваться в запросе
        int reqNumRegisters = ((request[4] & 0xFF) << 8) | (request[5] & 0xFF); // склеивание двух байт в одно целое число
        Log.d(LOG_TAG, "reqNumRegisters: " + reqNumRegisters);
        int reqFirstRegAddress = ((request[2] & 0xFF) << 8) | (request[3] & 0xFF); // склеивание двух байт в одно целое число
        Log.d(LOG_TAG, "reqFirstRegAddress: " + reqFirstRegAddress);

//            если количество регистров в запросе не равно половине количества байт в ответе (регистры 2х байтные):
        if ((reqNumRegisters * 2) != respNumDataBytes) {
            Log.d(LOG_TAG, "reqNumRegisters * 2(" + (reqNumRegisters * 2) + ") != respNumDataBytes(" + respNumDataBytes + ")");
            return;
        }

        int curRegAddress = 0; // адрес текущего регистра
        int curRegDataFull = 0; // данные из текущего регистра (2 байта вместе)
        int curRegDataHighByte = 0; // данные из старшего байта текущего регистра
        int curRegDataLowByte = 0; // данные из младшего байта текущего регистра
        int curBytePos = 1; // позиция текущего байта данных в ответе

//            цикл по количеству регистров:
        for (int i = 0; i < reqNumRegisters; i++) {
            Log.d(LOG_TAG, "========================");
//          вычисляем адрес регистра относительно адреса первого регистра из запроса
            curRegAddress = reqFirstRegAddress + i;
            Log.d(LOG_TAG, "curRegAddress: " + curRegAddress);
            curBytePos = curBytePos + 2;
            Log.d(LOG_TAG, "curBytePos: " + curBytePos);

//          если адрес регистра такой-то:
            switch (curRegAddress) {
                case 0: // старший байт: адрес устройства, младший: скорость обмена

//                  берём в ответе соответсвующие 2 байта
//                  преобразовываем их в соответсвии со спецификацией!

                    curRegDataHighByte = response[curBytePos] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataHighByte: " + curRegDataHighByte);
                    sensor_address.setText(Integer.toString(curRegDataHighByte));

                    curRegDataLowByte = response[curBytePos + 1] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataLowByte: " + curRegDataLowByte);
//                  выводим в соответсвующее поле
//                    gas_level_nkpr.setText(Integer.toString(curRegDataFull));
                    break;

                case 1: // старший байт: тип прибора, младший: флаги состояния
                    curRegDataHighByte = response[curBytePos] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataHighByte: " + curRegDataHighByte);
                    /* TODO: 14.04.2020 пока вывожу только метан, а остальные в виде номера,
                                        а вообще можно расписать все коды по спецификации... */
                    if (curRegDataHighByte == 1) {
                        sensor_type.setText("Метан");
                    } else {
                        sensor_type.setText(Integer.toString(curRegDataHighByte));
                    }

//                    curRegDataLowByte = response[curBytePos + 1] & 0xFF;
//                    Log.d(LOG_TAG, "curRegDataLowByte: " + curRegDataLowByte);
                    String stFlags = Integer.toBinaryString(response[curBytePos + 1]);
                    stFlags = String.format("%8s", stFlags).replace(' ', '0');
                    int stFlagsLen = stFlags.length();
                    int flagState = 0;
                    for (int flag = 1; flag <= 8; flag++) {
                        flagState = Character.digit(stFlags.charAt(stFlagsLen - flag), 10);
                        Log.d(LOG_TAG, "State flag " + flag + ": " + flagState);
                        switch (flag) {
                            case 1: // реле отказа (0 - авария 1 - норма)
                                if (flagState == 1) {
                                    fault_relay.setText("замкнуто(норма)");
                                    fault_relay.setBackgroundColor(0xFFFFFFFF);
                                } else {
                                    fault_relay.setText("разомкнуто(авария)");
                                    fault_relay.setBackgroundColor(0xFFFF0000);
                                }
                                break;
                            case 2: // реле порога 1 (0 - норма 1 - сработка)
                                if (flagState == 1) {
                                    relay_1.setText("замкнуто");
                                    relay_1.setBackgroundColor(0xFFFF0000);
                                } else {
                                    relay_1.setText("разомкнуто");
                                    relay_1.setBackgroundColor(0xFFFFFFFF);
                                }
                                break;
                            case 3: // реле порога 2 (0 - норма 1 - сработка)
                                if (flagState == 1) {
                                    relay_2.setText("замкнуто");
                                    relay_2.setBackgroundColor(0xFFFF0000);
                                } else {
                                    relay_2.setText("разомкнуто");
                                    relay_2.setBackgroundColor(0xFFFFFFFF);
                                }
                                break;
                            // TODO: 14.04.2020 остальные флаги пока не обрабатываются
                        }
                    }
                    break;

                case 2: // концентрация измеряемого газа в % НКПР (целое знаковое)
                    curRegDataFull = ((response[curBytePos] & 0xFF) << 8) | (response[curBytePos + 1] & 0xFF);
                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);
//                    Log.d(LOG_TAG, "curRegDataFull_float: " + Float.intBitsToFloat(curRegDataFull));
//                    gas_level_nkpr.setText(Integer.toString(curRegDataFull));

//                    float fCurRegDataFull = ((response[curBytePos] & 0xFF) << 8) | (response[curBytePos + 1] & 0xFF);
//                    Log.d(LOG_TAG, "fCurRegDataFull: " + fCurRegDataFull);
//                    gas_level_nkpr.setText(Float.toString(fCurRegDataFull));

//                    float fCurRegDataHighByte = response[curBytePos] & 0xFF;
//                    Log.d(LOG_TAG, "fCurRegDataHighByte: " + fCurRegDataHighByte);
//                    float fCurRegDataLowByte = response[curBytePos + 1] & 0xFF;
//                    Log.d(LOG_TAG, "fCurRegDataLowByte: " + fCurRegDataLowByte);
//                    float fCurRegDataFull = fCurRegDataHighByte + fCurRegDataLowByte;
//                    Log.d(LOG_TAG, "fCurRegDataFull: " + fCurRegDataFull);
//                    gas_level_nkpr.setText(Float.toString(fCurRegDataFull));

                    break;

                case 3: // старший байт: порог 1, младший: порог 2
                    curRegDataHighByte = response[curBytePos] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataHighByte: " + curRegDataHighByte);
                    threshold_1.setText(Integer.toString(curRegDataHighByte));

                    curRegDataLowByte = response[curBytePos + 1] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataLowByte: " + curRegDataLowByte);
                    threshold_2.setText(Integer.toString(curRegDataLowByte));
                    break;

                case 4: // D - приведённое
                    curRegDataFull = ((response[curBytePos] & 0xFF) << 8) | (response[curBytePos + 1] & 0xFF);
                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
                    break;

                case 5: // напряжение опорного канала
                    curRegDataFull = ((response[curBytePos] & 0xFF) << 8) | (response[curBytePos + 1] & 0xFF);
                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
                    break;

                case 6: // напряжение рабочего канала
                    curRegDataFull = ((response[curBytePos] & 0xFF) << 8) | (response[curBytePos + 1] & 0xFF);
                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
                    break;

                case 7: // D - приборное
                    curRegDataFull = ((response[curBytePos] & 0xFF) << 8) | (response[curBytePos + 1] & 0xFF);
                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
                    break;

                case 8: // температура, показания встроенного терморезистора
                    curRegDataFull = ((response[curBytePos] & 0xFF) << 8) | (response[curBytePos + 1] & 0xFF);
                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
                    break;

                case 9: // серийный номер прибора
                    curRegDataFull = ((response[curBytePos] & 0xFF) << 8) | (response[curBytePos + 1] & 0xFF);
                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);
                    serial_number.setText(Integer.toString(curRegDataFull));

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
                    break;

                case 10: // концентрация измеряемого газа в % НКПР * 10 (целое знаковое)
//                    curRegDataFull = ((response[curBytePos] & 0xFF) << 8) | (response[curBytePos + 1] & 0xFF);
//                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);
//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));

                    float fCurRegDataFull = ((response[curBytePos] & 0xFF) << 8) | (response[curBytePos + 1] & 0xFF);
                    fCurRegDataFull = fCurRegDataFull / 10;
                    Log.d(LOG_TAG, "fCurRegDataFull: " + fCurRegDataFull);
                    gas_level_nkpr.setText(Float.toString(fCurRegDataFull));
                    break;

                case 11: // номер версии ПО прибора (беззнаковое целое)
                    // TODO: 12.04.2020 знаковое\беззнаковое. Возможно иначе надо преобразовывать...
                    curRegDataFull = ((response[curBytePos] & 0xFF) << 8) | (response[curBytePos + 1] & 0xFF);
                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
                    break;

                case 12: // старший байт: тип прибора, младший: модификация прибора
                    curRegDataHighByte = response[curBytePos] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataHighByte: " + curRegDataHighByte);
                    curRegDataLowByte = response[curBytePos + 1] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataLowByte: " + curRegDataLowByte);

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
                    break;

            }
        }

//              в цикле читаем по 2 байта далее, пока не наберём это число
//            byte[] respData = new byte[respNumDataBytes];
//            for (int i = 0; i < respNumDataBytes; i++) {
//
//            }
//              далее, по какой-то таблице соответсвия, определять, что в какое поле выводить...

    }

    private byte[] calcCRC(byte[] msg) {
        char fullCRC = 0xFFFF;
        byte highByteCRC, lowByteCRC;

        for (int i = 0; i < msg.length; i++) {
            fullCRC = (char) ((msg[i] & 0xFF) ^ fullCRC);

            for (int j = 0; j < 8; j++) {

                if ((fullCRC & 0x0001) == 1) {
                    fullCRC = (char) (fullCRC >> 1);
                    fullCRC = (char) (fullCRC ^ 0xA001);
                } else {
                    fullCRC = (char) (fullCRC >> 1);
                }
            }
        }

        lowByteCRC = (byte) (fullCRC & 0xFFFF);
        highByteCRC = (byte) ((fullCRC >> 8) & 0xFFFF);

        return new byte[] {lowByteCRC, highByteCRC};
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
    TextView sensor_address;
    TextView serial_number;
    TextView sensor_type;
    TextView gas_level_nkpr;
    TextView threshold_1;
    TextView threshold_2;
    TextView fault_relay;
    TextView relay_1;
    TextView relay_2;
    EditText input_sensor_address;
    Handler myHandler;
    final int arduinoData = 1; // TODO: 08.04.2020 константа заглавными буквами
    byte[] request; // текущий запрос
    byte[] response; // текущий ответ
    int numResponseBytes = 0;  // счётчик байт, полученных в текущем ответе
    boolean sensorConnection = false;
    int requestFuncCode = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(LOG_TAG, "ready");


//        byte[] respMsg = hexStringToByteArray("0103030105A0");
//        Log.d(LOG_TAG, "calcCRC: " + bytesToHex(calcCRC(respMsg)));


        sensor_address = (TextView) findViewById(R.id.sensor_address);
        serial_number = (TextView) findViewById(R.id.serial_number);
        sensor_type = (TextView) findViewById(R.id.sensor_type);
        gas_level_nkpr = (TextView) findViewById(R.id.gas_level_nkpr);
        threshold_1 = (TextView) findViewById(R.id.threshold_1);
        threshold_2 = (TextView) findViewById(R.id.threshold_2);
        fault_relay = (TextView) findViewById(R.id.fault_relay);
        relay_1 = (TextView) findViewById(R.id.relay_1);
        relay_2 = (TextView) findViewById(R.id.relay_2);
        input_sensor_address = (EditText) findViewById(R.id.input_sensor_address);

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
                // если подключения нет
                if (!sensorConnection) {
                    // проверяем поле адреса, если адрес корректный
                    if (checkInputAddress()) {
                        connect_to_sensor.setText("Стоп");
                        input_sensor_address.setEnabled(false);
                        // запускаем цикл отправки запроса
                        sensorConnection = true;
                        startSensorConnection();
                    }
                } else {
                    connect_to_sensor.setText("Старт");
                    input_sensor_address.setEnabled(true);
                    // останавливаем цикл отправки запроса
                    // (он останавливается сам, когда sensorConnection == false)
                    sensorConnection = false;
                }


                // Обнуляем счётчик принятых байт и массив:
                numResponseBytes = 0;
                response = null;

                // первый способ формирования массива байт
                //byte[] data = new byte[] { (byte)0x01, (byte)0x03};

                // второй способ
//                String outputHexString = "010300000001840A";
//                String outputHexString = "010300010001840A";
//                String outputHexString = "010300000002840A";
                String outputHexString = "01030000000C45CF";
                request = hexStringToByteArray(outputHexString);
                Log.d(LOG_TAG, "outputHexString: " + outputHexString);

                //sensor_address = (EditText) findViewById(R.id.sensor_address);
                //byte[] data = hexStringToByteArray(sensor_address.getText().toString());

                myThread.sendData(request);
            }
        });

        myHandler = new Handler() {
            public void handleMessage(android.os.Message msg) {
                // TODO: 15.04.2020 switch здесь можно убрать, заменить на if()
                switch (msg.what) {
                    case arduinoData:
                        // Увеличиваем счётчик принятых байт:
                        numResponseBytes = numResponseBytes + msg.arg1;
                        Log.d(LOG_TAG, "numResponseBytes:" + numResponseBytes);

                        // Добавляем принятые байты в общий массив:
                        byte[] readBuf = (byte[]) msg.obj;
                        response = concatArray(response, readBuf);
                        //Log.d(LOG_TAG, "response:" + bytesToHex(response) + "\n ");
//                        Log.d(LOG_TAG, "=================================");

                        checkResponse();

//                        gas_level_nkpr.setText(bytesToHex(response));
                        break;
                }
            }
        };
    }

    // TODO: 15.04.2020 возможно этот метод лучше вынести в отдельный поток...
    private void startSensorConnection() {
        // пока подключение активно
        while (sensorConnection) {
            // создаём запрос
            createRequest();
            // отправляем запрос
            myThread.sendData(request);
            // если команда == 06, то меняем её на 03
            if (requestFuncCode == 6) {
                requestFuncCode = 3;
            }
            // ждём некоторое время (1-2 сек)

        }
    }

    private void createRequest() {

    }

    private boolean checkInputAddress() {

        return false;
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

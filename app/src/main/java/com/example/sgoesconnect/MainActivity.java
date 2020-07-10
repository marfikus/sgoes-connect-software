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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.CryptoPrimitive;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.ToDoubleBiFunction;
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
            byte[] buffer = new byte[40];
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

    private void checkResponse(byte[] _request, byte[] _response) {

        // если код функции в текущем запросе == 06, то не обрабатываем пока эти ответы
        int reqFuncCode = _request[1] & 0xFF;
        if (reqFuncCode == 6) {
            Log.d(LOG_TAG, "checkResponse: reqFuncCode == " + reqFuncCode + ". Skip it.");
            return;
        }

        int respLength = _response.length;
        if (respLength < 5) {
            return;
        }

        // Копируем ответ себе локально,
        // чтобы не допустить добавления в него новых порций байт, во время обработки.
        // Делаю это, поскольку не уверен в том, как передаются массивы в методы,
        // по значению или по ссылке. Однозначного ответа не нашёл пока, поэтому пока так.
//        byte[] localCopyResponse = new byte[respLength];
        byte[] localCopyResponse = Arrays.copyOf(_response, respLength);
        // и запрос тоже скопируем, поскольку он тоже может измениться
//        byte[] localCopyRequest = new byte[_request.length];
        byte[] localCopyRequest = Arrays.copyOf(_request, _request.length);
        
        // отделяем 2 последних байта ответа
        byte[] respMsg = new byte[respLength - 2];
        byte[] respCRC = new byte[2];
        System.arraycopy(localCopyResponse, 0, respMsg, 0, respLength - 2);
//        Log.d(LOG_TAG, "respMsg: " + bytesToHex(respMsg));
        System.arraycopy(localCopyResponse, respLength - 2, respCRC, 0, respCRC.length);
//        Log.d(LOG_TAG, "respCRC: " + bytesToHex(respCRC));

        // сравниваем последние 2 байта ответа с тем, что вычислим здесь

//        Log.d(LOG_TAG, "calcCRC: " + bytesToHex(calcCRC(respMsg)));
        Log.d(LOG_TAG, "localCopyResponse: " + bytesToHex(localCopyResponse));

        // если контрольная сумма из ответа не совпадает с расчётом, то выходим:
        if (!Arrays.equals(respCRC, calcCRC(respMsg))) {
            Log.d(LOG_TAG, bytesToHex(respCRC) + " != " + bytesToHex(calcCRC(respMsg)));
            return;
        }

        // Глобальный ответ обнуляем, поскольку проверка ответа пройдена
        // и далее работаем с его копией
        response = null;

        connectionState = ConnectionState.CONNECTED;
        sensor_connection_state.setText("СТАТУС: ПОДКЛЮЧЕН");

        // Востанавливаем индикацию обычного режима:
        workingMode = WorkingMode.READING_DATA;
        working_mode.setText("РЕЖИМ: ОПРОС");
        // Разблокируем кнопки посылки команд:
        set_zero.setEnabled(true);

        // парсим ответ, выводим данные... (тоже отдельные функции)
//        Log.d(LOG_TAG, "go to parsing localCopyResponse... " + bytesToHex(localCopyResponse));
        parseResponse(localCopyRequest, localCopyResponse);
    }

    private void parseResponse(byte[] localCopyRequest, byte[] localCopyResponse) {

//      Разбор ответа должен производиться на основании запроса.
//      Сравниваем первый байт запроса с первым байтом ответа (адреса) Если не совпадают:
        int reqAddress = localCopyRequest[0] & 0xFF; // & 0xFF необходимо для приведения значения байта к виду 0..255
        int respAddress = localCopyResponse[0] & 0xFF;

        if (reqAddress != respAddress) {
//          Это ответ на другой запрос, от другого датчика. Выходим?
            Log.d(LOG_TAG, "reqAddress(" + reqAddress + ") != respAddress(" + respAddress + ")");
            return;
        }

//      Сравниваем вторые байты (код функции). Если совпадают:
        int reqFuncCode = localCopyRequest[1] & 0xFF;
        int respFuncCode = localCopyResponse[1] & 0xFF;

        if (reqFuncCode == respFuncCode) {
            // TODO: 17.04.2020 добавить проверку на значение кода функции: если 3, то парсим,
            //  если 6, то пропускаем, ибо незачем тратить время на разбор этого ответа (сообщение в лог выдаём)
            Log.d(LOG_TAG, "reqFuncCode(" + reqFuncCode + ") == respFuncCode(" + respFuncCode + ") Parsing of data...");
//          Ответ на этот запрос, без ошибки, переходим далее к разбору данных:
            parseRespData(localCopyRequest, localCopyResponse);

        } else { // не совпадают
            Log.d(LOG_TAG, "reqFuncCode(" + reqFuncCode + ") != respFuncCode(" + respFuncCode + ")");
//          Если второй байт ответа равен второму байту запроса с единицей в старшем бите (код ошибки):
            int modReqFuncCode = (localCopyRequest[1] | 0b10000000) & 0xFF; // устанавливаем единицу в старший бит

            if (respFuncCode == modReqFuncCode) {
                Log.d(LOG_TAG, "respFuncCode(" + respFuncCode + ") == modReqFuncCode(" + modReqFuncCode + ")");
//              значит ответ на этот запрос, но с ошибкой:
//              читаем третий байт ответа и выводим информацию об ошибке...
                int respError = localCopyResponse[2] & 0xFF;
                Log.d(LOG_TAG, "Error in localCopyResponse. Error code: " + respError);
                // TODO: 22.04.2020 вывод на экран, можно тостом наверное
            } else {
                // значит это хз что за ответ)...
                Log.d(LOG_TAG, "respFuncCode(" + respFuncCode + ") != modReqFuncCode(" + modReqFuncCode + ")");
            }
        }
    }

    private void parseRespData(byte[] localCopyRequest, byte[] localCopyResponse) {
        // читаем третий байт - количество байт идущих далее.
        int respNumDataBytes = localCopyResponse[2] & 0xFF;
        Log.d(LOG_TAG, "respNumDataBytes: " + respNumDataBytes);

        // todo: количество регистров и адрес первого пока беру так, а вообще будет формироваться в запросе
        int reqNumRegisters = ((localCopyRequest[4] & 0xFF) << 8) | (localCopyRequest[5] & 0xFF); // склеивание двух байт в одно целое число
        Log.d(LOG_TAG, "reqNumRegisters: " + reqNumRegisters);
        int reqFirstRegAddress = ((localCopyRequest[2] & 0xFF) << 8) | (localCopyRequest[3] & 0xFF); // склеивание двух байт в одно целое число
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

                    curRegDataHighByte = localCopyResponse[curBytePos] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataHighByte: " + curRegDataHighByte);
                    sensor_address.setText(Integer.toString(curRegDataHighByte));

                    curRegDataLowByte = localCopyResponse[curBytePos + 1] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataLowByte: " + curRegDataLowByte);
//                  выводим в соответсвующее поле
//                    gas_level_nkpr.setText(Integer.toString(curRegDataFull));
                    break;

                case 1: // старший байт: тип прибора, младший: флаги состояния
                    curRegDataHighByte = localCopyResponse[curBytePos] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataHighByte: " + curRegDataHighByte);
                    /* TODO: 14.04.2020 пока вывожу только метан, а остальные в виде номера,
                                        а вообще можно расписать все коды по спецификации... */
                    if (curRegDataHighByte == 1) {
                        sensor_type.setText("Метан");
                    } else {
                        sensor_type.setText(Integer.toString(curRegDataHighByte));
                    }

//                    curRegDataLowByte = localCopyResponse[curBytePos + 1] & 0xFF;
//                    Log.d(LOG_TAG, "curRegDataLowByte: " + curRegDataLowByte);
                    String stFlags = Integer.toBinaryString(localCopyResponse[curBytePos + 1]);
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
                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);
//                    Log.d(LOG_TAG, "curRegDataFull_float: " + Float.intBitsToFloat(curRegDataFull));
//                    gas_level_nkpr.setText(Integer.toString(curRegDataFull));

//                    float fCurRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
//                    Log.d(LOG_TAG, "fCurRegDataFull: " + fCurRegDataFull);
//                    gas_level_nkpr.setText(Float.toString(fCurRegDataFull));

//                    float fCurRegDataHighByte = localCopyResponse[curBytePos] & 0xFF;
//                    Log.d(LOG_TAG, "fCurRegDataHighByte: " + fCurRegDataHighByte);
//                    float fCurRegDataLowByte = localCopyResponse[curBytePos + 1] & 0xFF;
//                    Log.d(LOG_TAG, "fCurRegDataLowByte: " + fCurRegDataLowByte);
//                    float fCurRegDataFull = fCurRegDataHighByte + fCurRegDataLowByte;
//                    Log.d(LOG_TAG, "fCurRegDataFull: " + fCurRegDataFull);
//                    gas_level_nkpr.setText(Float.toString(fCurRegDataFull));

                    break;

                case 3: // старший байт: порог 1, младший: порог 2
                    curRegDataHighByte = localCopyResponse[curBytePos] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataHighByte: " + curRegDataHighByte);
                    threshold_1.setText(Integer.toString(curRegDataHighByte));

                    curRegDataLowByte = localCopyResponse[curBytePos + 1] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataLowByte: " + curRegDataLowByte);
                    threshold_2.setText(Integer.toString(curRegDataLowByte));
                    break;

                case 4: // D - приведённое
                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
                    break;

                case 5: // напряжение опорного канала
                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
                    break;

                case 6: // напряжение рабочего канала
                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
                    break;

                case 7: // D - приборное
                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
                    break;

                case 8: // температура, показания встроенного терморезистора
                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
                    break;

                case 9: // серийный номер прибора
                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);
                    serial_number.setText(Integer.toString(curRegDataFull));

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
                    break;

                case 10: // концентрация измеряемого газа в % НКПР * 10 (целое знаковое)
//                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
//                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);
//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));

                    float fCurRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
                    fCurRegDataFull = fCurRegDataFull / 10;
                    Log.d(LOG_TAG, "fCurRegDataFull: " + fCurRegDataFull);
                    gas_level_nkpr.setText(Float.toString(fCurRegDataFull));

                    // объёмные проценты
                    float volumePercent = nkprPercentToVolumePercent(fCurRegDataFull);
                    Log.d(LOG_TAG, "volumePercent: " + volumePercent);
                    gas_level_volume.setText(Float.toString(volumePercent));

                    // ток в мА
                    float current = nkprPercentToCurrent(fCurRegDataFull);
                    Log.d(LOG_TAG, "current: " + current);
                    gas_level_current.setText(Float.toString(current));
                    break;

                case 11: // номер версии ПО прибора (беззнаковое целое)
                    // TODO: 12.04.2020 знаковое\беззнаковое. Возможно иначе надо преобразовывать...
                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
                    break;

                case 12: // старший байт: тип прибора, младший: модификация прибора
                    curRegDataHighByte = localCopyResponse[curBytePos] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataHighByte: " + curRegDataHighByte);
                    curRegDataLowByte = localCopyResponse[curBytePos + 1] & 0xFF;
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

    private float nkprPercentToVolumePercent(float nkprPercent) {
        float volumePercent = (nkprPercent * (float)4.4) / (float)100.0;
        volumePercent = new BigDecimal(volumePercent).setScale(3, RoundingMode.UP).floatValue();
        return volumePercent;
    }

    private float nkprPercentToCurrent(float nkprPercent) {
        float current = ((nkprPercent * (float)16.0) / (float)100.0) + (float)4.0;
        current = new BigDecimal(current).setScale(2, RoundingMode.UP).floatValue();
        return current;
    }

    private static final int REQUEST_ENABLE_BT = 0;
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static String macAddress = "98:D3:71:F5:DA:46";
    private BluetoothSocket btSocket = null;
    Button bt_settings;
    Button bt_connect;
    Button connect_to_sensor;
    Button set_zero;
    private ConnectedThread myThread = null;
    final String LOG_TAG = "myLogs";
    TextView sensor_address;
    TextView serial_number;
    TextView sensor_type;
    TextView gas_level_nkpr;
    TextView gas_level_volume;
    TextView gas_level_current;
    TextView threshold_1;
    TextView threshold_2;
    TextView fault_relay;
    TextView relay_1;
    TextView relay_2;
    TextView sensor_connection_state;
    TextView working_mode;
    EditText input_sensor_address;
    Handler myHandler;
    final int arduinoData = 1; // TODO: 08.04.2020 константа заглавными буквами
    final int sensorConnectionThreadData = 2;
    byte[] request; // текущий запрос
    byte[] response; // текущий ответ
    //int numResponseBytes = 0;  // счётчик байт, полученных в текущем ответе
    boolean sensorConnection = false; // флаг текущего подключения
    //int requestFuncCode = 3; // код функции запроса
    Thread sensorConnectionThread = null;
    enum Commands { // команды с кнопок
        NONE,
        SET_ZERO,
        CALIBRATION_MIDDLE,
        CALIBRATION_HIGH,
        SET_THRESHOLD_1,
        SET_THRESHOLD_2,
        SET_DEFAULT_SETTINGS,
        CHANGE_SENSOR_ADDRESS
    }
    Commands commandFromButton = Commands.NONE;
    enum ConnectionState { // состояние подключения к датчику
        DISCONNECTED,
        WAITING_FOR_RESPONSE,
        NO_RESPONSE,
        CONNECTED
    }
    ConnectionState connectionState = ConnectionState.DISCONNECTED;
    enum BtDeviceConnectionState { // состояние подключения к плате
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }
    BtDeviceConnectionState btDeviceConnectionState = BtDeviceConnectionState.DISCONNECTED;
    enum WorkingMode { // режим работы
        READING_DATA,
        SETTING_ZERO,
        CALIBRATION_MIDDLE,
        CALIBRATION_HIGH,
        SETTING_THRESHOLD_1,
        SETTING_THRESHOLD_2,
        SETTING_DEFAULT_SETTINGS,
        CHANGING_SENSOR_ADDRESS
    }
    WorkingMode workingMode = WorkingMode.READING_DATA;
    int requestCounter = 0;

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
        gas_level_volume = (TextView) findViewById(R.id.gas_level_volume);
        gas_level_current = (TextView) findViewById(R.id.gas_level_current);
        threshold_1 = (TextView) findViewById(R.id.threshold_1);
        threshold_2 = (TextView) findViewById(R.id.threshold_2);
        fault_relay = (TextView) findViewById(R.id.fault_relay);
        relay_1 = (TextView) findViewById(R.id.relay_1);
        relay_2 = (TextView) findViewById(R.id.relay_2);
        sensor_connection_state = (TextView) findViewById(R.id.sensor_connection_state);
        working_mode = (TextView) findViewById(R.id.working_mode);
        input_sensor_address = (EditText) findViewById(R.id.input_sensor_address);

        bt_settings = (Button) findViewById(R.id.bt_settings);
        bt_settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // вызываем экран настроек bluetooth:
                startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
            }
        });

        // TODO: 16.04.2020 блокировать кнопку соединения с датчиком,
        //  а после установления связи с платой - разблокировать.

        // TODO: 16.04.2020 да и остальные кнопки тоже блокировать на всякий случай...

        // TODO: 16.04.2020 возможно часть этих действий можно вынести в отдельный поток,
        //  а то при подключении интерфейс подвисает...
        bt_connect = (Button) findViewById(R.id.bt_connect);
        bt_connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Получаем блютус адаптер
                final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                
                // Если блютус адаптер не обнаружен, то сообщаем об этом и выходим
                if (bluetoothAdapter == null) {
                    Log.d(LOG_TAG, "bluetooth adapter is not detected");
                    Toast.makeText(getApplicationContext(), "bluetooth adapter is not detected", Toast.LENGTH_SHORT).show();
                    return;
                }
                // Toast.makeText(getApplicationContext(), "bluetooth adapter is detected", Toast.LENGTH_SHORT).show();
                
                // Если адаптер не доступен (выключен), то запрашиваем его включение, а пока выходим
                if (!bluetoothAdapter.isEnabled()) {
                    Log.d(LOG_TAG, "bluetooth is disabled");
                    Toast.makeText(getApplicationContext(), "bluetooth is disabled", Toast.LENGTH_SHORT).show();
                    // Запрос на включение bluetooth:
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                    // Пока блютус включится, уже исключение вылетет, поэтому выходим.
                    // TODO Если добавлю кнопке возможность отключения от модуля, то надо будет здесь возвращать исходное значение
                    return;
                    // А при повторном нажатии (с уже включённым блютузом) алгоритм пойдёт дальше   
                }
                //Toast.makeText(getApplicationContext(), "bluetooth is enabled", Toast.LENGTH_SHORT).show();
                //String myDeviceName = bluetoothAdapter.getName();
                //Toast.makeText(getApplicationContext(), myDeviceName, Toast.LENGTH_SHORT).show();

                // TODO переделать: продумать правильную последовательность, чтобы дальнейший код
                //  не выполнялся при отключенном блютусе и тп...

                /*  При первом подключении ("на холодную") смарт с модулем связываются только с 3-4 попытки.
                Поэтому сделал пока подключение в цикле с максимальным количеством попыток
                (чтобы исключить возможность зацикливания). 
                А при успехе, устанавливается соответсвующий флаг. */
                
                // Флаг успешного подключения.
                boolean connectIsOpen = false;
                // Счётчик попыток подключения.
                int countConnectionTries = 0;
                // Максимальное количество попыток
                final int MAX_CONNECTION_TRIES = 5;

                // TODO Возможно эту строку тоже в цикл надо перенести, надо проверить..
                // но тогда убрать выход в проверке на null
                BluetoothDevice bluetoothDevice = bluetoothAdapter.getRemoteDevice(macAddress);
                // Может эта проверка и не нужна, но на всякий случай добавил
                if (bluetoothDevice == null) {
                    Log.d(LOG_TAG, "No device with MAC address: " + macAddress);
                    Toast.makeText(getApplicationContext(), "No device with MAC address: " + macAddress, Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(getApplicationContext(), "bluetoothDevice: " + bluetoothDevice.getName(), Toast.LENGTH_SHORT).show();
                // Log.d(LOG_TAG, "bluetoothDevice: " + bluetoothDevice.getName());

                // Пока не подключились и не достигли макс количества попыток
                while ((!connectIsOpen) && (countConnectionTries < MAX_CONNECTION_TRIES)) {
                    // Пробуем создать сокет
                    try {
                        btSocket = bluetoothDevice.createRfcommSocketToServiceRecord(MY_UUID);
                        Toast.makeText(getApplicationContext(), "socket is created", Toast.LENGTH_SHORT).show();
                    } catch (IOException e) {
                        myError("Fatal Error", "Can't create a socket: " + e.getMessage() + ".");
                    }
                    // TODO что делает эта строка?
                    bluetoothAdapter.cancelDiscovery();
                    
                    // TODO А может оставить в цикле только попытки подключиться?
                    // Открытие сокета вынести выше и выполнять 1 раз, 
                    // закрытие вынести ниже при окончании попыток подключения
                    // Надо попробовать..
                    
                    // Пробуем подключиться к плате
                    try {
                        btSocket.connect();
                        Toast.makeText(getApplicationContext(), "connect is open", Toast.LENGTH_SHORT).show();
                        // Взводим флаг успешного подключения
                        connectIsOpen = true;
                    } catch (IOException e) {
                        Log.d(LOG_TAG, "Can't connect to device: " + e.getMessage());
                        Toast.makeText(getApplicationContext(), "Can't connect to device, show log", Toast.LENGTH_SHORT).show();
                        // Если не получилось, то закрываем сокет (пробуем закрыть)
                        try {
                            btSocket.close();
                            Toast.makeText(getApplicationContext(), "socket is closed", Toast.LENGTH_SHORT).show();
                        } catch (IOException e2) {
                            myError("Fatal Error", "Can't close a socket:" + e2.getMessage() + ".");
                        }
                    }
                    countConnectionTries = countConnectionTries + 1;
                }
                // Если так и не подключились, то выходим
                if (!connectIsOpen) {
                    return;
                }
                // Иначе (всё ок) создаём отдельный поток с подключением
                // для дальнейшего обмена информацией и запускаем его
                myThread = new ConnectedThread(btSocket);
                myThread.start();

                // Меняем статус состояния подключения к плате
                btDeviceConnectionState = BtDeviceConnectionState.CONNECTED;
                // TODO сменить название кнопки
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
                        input_sensor_address.setEnabled(false);
                        connect_to_sensor.setText("Стоп");

                        // сбрасываем команду с кнопок в дефолтное состояние
                        // (на случай, если команда сменилась, а потом остановили соединение)
                        commandFromButton = Commands.NONE;

                        // устанавливаем статусы
                        connectionState = ConnectionState.WAITING_FOR_RESPONSE;
                        sensor_connection_state.setText("СТАТУС: ПОДКЛЮЧЕНИЕ...");
                        workingMode = WorkingMode.READING_DATA;
                        working_mode.setText("РЕЖИМ: ОПРОС");

                        requestCounter = 0;

                        // переключаем флаг текущего подключения
                        sensorConnection = true;

                        // Создаём задачу, которую будем выполнять в отдельном потоке.
                        // Практика показала, что нужно именно каждый раз выполнять эти действия,
                        // поскольку после остановки потока, он уже недоступен
                        // (насколько я это понимаю на данный момент).
                        Runnable task = new Runnable() {
                            @Override
                            public void run() {
                                startSensorConnection();
                            }
                        };
                        // Создаём отдельный поток с этой задачей
                        sensorConnectionThread = new Thread(task);

                        // вроде как с этой строкой поток должен завершиться
                        // при завершении главного потока, но что-то оно не работает...
                        //sensorConnectionThread.setDaemon(true);

                        // Запускаем поток
                        sensorConnectionThread.start();
                    }
                } else {
                    // останавливаем поток отправки запроса
                    // (он останавливается сам, когда sensorConnection == false)
                    sensorConnection = false;

                    // Принудительное прерывание потока.
                    // Вроде как это сейчас лишнее, при текущей логике
/*                    if (sensorConnectionThread != null) {
                        Thread dummy = sensorConnectionThread;
                        sensorConnectionThread = null;
                        dummy.interrupt();
                    }*/

                    connectionState = ConnectionState.DISCONNECTED;
                    sensor_connection_state.setText("СТАТУС: ОТКЛЮЧЕН");
                    workingMode = WorkingMode.READING_DATA;
                    working_mode.setText("РЕЖИМ: ---");

                    connect_to_sensor.setText("Старт");
                    input_sensor_address.setEnabled(true);

                    // Блокируем кнопки команд:
                    set_zero.setEnabled(false);

                    // TODO: 16.04.2020 обнулить поля данных, добавить индикатор состояния (отключено\нет ответа\подключено)
                    //  а может поля не обнулять, иногда полезно может быть, будто на паузу поставил...
                }

//                String outputHexString = "010300000001840A";
//                String outputHexString = "010300010001840A";
//                String outputHexString = "010300000002840A";
//                String outputHexString = "01030000000C45CF";
//                request = hexStringToByteArray(outputHexString);
//                Log.d(LOG_TAG, "outputHexString: " + outputHexString);

                //myThread.sendData(request);
            }
        });

        myHandler = new Handler() {
            public void handleMessage(android.os.Message msg) {
                // если остановили подключение, то не надо уже обрабатывать ответ
                // (предотвращение "инерции")
                if (!sensorConnection) {
                    Log.d(LOG_TAG, "sensorConnection is stopped. Skip this response.");
                    return;
                }
                switch (msg.what) {
                    case arduinoData:
                        // обнуляем счётчик запросов, т.к. что-то получили
                        requestCounter = 0;

                        // TODO: 28.04.2020 тоже борьба с инерционностью стороннего потока.
                        //  Отсекаем ответы на старые запросы, если была какая-нибудь команда с кнопок.
                        if (commandFromButton != Commands.NONE) {
                            Log.d(LOG_TAG, "'Inertial' response Skip it.");
                            return;
                        }

                        Log.d(LOG_TAG, "numResponseBytes:" + msg.arg1);

                        // Добавляем принятые байты в общий массив:
                        // (накапливаем ответ, поскольку за один раз всё принять пока не получается)
                        byte[] readBuf = (byte[]) msg.obj;
                        response = concatArray(response, readBuf);
                        //Log.d(LOG_TAG, "response:" + bytesToHex(response) + "\n ");
//                        Log.d(LOG_TAG, "=================================");

                        checkResponse(request, response);
                        break;
                    case sensorConnectionThreadData:
//                        Log.d(LOG_TAG, "msg.obj: " + msg.obj);
                        if (msg.obj == ConnectionState.NO_RESPONSE) {
                            sensor_connection_state.setText("СТАТУС: НЕТ ОТВЕТА");
                        }
                        break;
                }
            }
        };

        set_zero = (Button) findViewById(R.id.set_zero);
        set_zero.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // TODO: 18.04.2020 спросить подтверждение действия
                commandFromButton = Commands.SET_ZERO;
                Log.d(LOG_TAG, commandFromButton.toString());

                workingMode = WorkingMode.SETTING_ZERO;
                working_mode.setText("РЕЖИМ: УСТАНОВКА НУЛЯ");
                set_zero.setEnabled(false);

                // TODO: 19.04.2020  Долгая задержка показаний после обнуления, 5-6 секунд...
            }
        });
    }

    private void startSensorConnection() {
        Log.d(LOG_TAG, "Start Sensor Connection");
        // пока подключение активно и нет команды прерывания текущего потока
        while ((sensorConnection) && (!Thread.currentThread().isInterrupted())) {
            // создаём запрос
            createRequest(commandFromButton);
            // чистим глобальный ответ
            // добавил на случай, когда пропадает связь с дачиком, потом восстанавливается,
            // а тут часть старого ответа видимо осталась и начинается каша...
            response = null;
            // отправляем запрос
            myThread.sendData(request);

            // увеличиваем счётчик запросов
            requestCounter = requestCounter + 1;
            // проверяем его состояние, если более 3х запросов без ответа, то меняем статус
            if ((requestCounter >= 3) && (workingMode == WorkingMode.READING_DATA)) {
                connectionState = ConnectionState.NO_RESPONSE;
                Log.d(LOG_TAG, "connectionState: " + connectionState.toString());
                // сообщаем главному потоку, чтобы он сменил статус на экране
                myHandler.obtainMessage(sensorConnectionThreadData, connectionState).sendToTarget();
            }

            // ждём некоторое время
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                // если есть внешнее прерывание, то прерываем цикл
                e.printStackTrace();
                Log.d(LOG_TAG, "InterruptedException:" + e);
                break;
            }
        }
        Log.d(LOG_TAG, "Stop Sensor Connection");
    }

    // TODO: 22.04.2020 переименовать connectionState в sensorConnectionState

    // TODO: 22.04.2020  привязать ко всем местам, где меняется этот статус
    private void changeSensorConnectionStateOnScreen() {
        // TODO: 22.04.2020 switch
        if (connectionState == ConnectionState.NO_RESPONSE) {
            sensor_connection_state.setText("СТАТУС: НЕТ ОТВЕТА");
        }
    }

    private void createRequest(Commands _commandFromButton) {
        byte sensorAddress = (byte)Integer.parseInt(input_sensor_address.getText().toString());
        byte[] reqMsg = {};

        switch (_commandFromButton) {
            case NONE: // команды с кнопок нет, обычный запрос данных
                reqMsg = new byte[] {
                        sensorAddress,
                        (byte)0x03, // funcCode
                        (byte)0x00, // firstRegAddressHigh
                        (byte)0x00, // firstRegAddressLow
                        (byte)0x00, // numRegistersHigh
                        (byte)0x0C  // numRegistersLow
                };
                break;
            case SET_ZERO: // установка нуля
                reqMsg = new byte[] {
                        sensorAddress,
                        (byte)0x06, // funcCode
                        (byte)0x00, // firstRegAddressHigh
                        (byte)0x02, // firstRegAddressLow
                        (byte)0x00, // dataHigh
                        (byte)0x00  // dataLow
                };
                // сбрасываем глобальную команду
                commandFromButton = Commands.NONE;
                break;
        }

        // считаем CRC
        byte[] reqCRC = calcCRC(reqMsg);

        // формируем общий массив запроса
        request = concatArray(reqMsg, reqCRC);

//        String outputHexString = "01030000000C45CF";
//        request = hexStringToByteArray(outputHexString);
//        Log.d(LOG_TAG, "outputHexString: " + outputHexString);
        Log.d(LOG_TAG, "request: " + bytesToHex(request));
    }

    private boolean checkInputAddress() {
        // адрес должен быть в диапазоне 1..247
        String inputAddress = input_sensor_address.getText().toString();

        if (inputAddress.length() == 0) {
            Log.d(LOG_TAG, "input_sensor_address is empty");
            Toast.makeText(getApplicationContext(), "Введите адрес датчика от 1 до 247", Toast.LENGTH_LONG).show();
            return false;
        }
        if (Integer.parseInt(inputAddress) < 1) {
            Log.d(LOG_TAG, "input_sensor_address < 1");
            Toast.makeText(getApplicationContext(), "Адрес датчика может быть от 1 до 247", Toast.LENGTH_LONG).show();
            return false;
        }
        if (Integer.parseInt(inputAddress) > 247) {
            Log.d(LOG_TAG, "input_sensor_address > 247");
            Toast.makeText(getApplicationContext(), "Адрес датчика может быть от 1 до 247", Toast.LENGTH_LONG).show();
            return false;
        }

        return true;
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
        Log.d(LOG_TAG, "onDestroy()");

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
                myError("Fatal Error", "onDestroy(): Can't close a socket:" + e2.getMessage() + ".");
            }
        }

        if (sensorConnectionThread != null) {
            Thread dummy = sensorConnectionThread;
            sensorConnectionThread = null;
            dummy.interrupt();
        }
    }

    private void myError(String title, String message) {
        Toast.makeText(getBaseContext(), title + " - " + message, Toast.LENGTH_LONG).show();
        Log.d(LOG_TAG, title + " - " + message);
        finish();
    }
}

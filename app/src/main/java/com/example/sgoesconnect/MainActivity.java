package com.example.sgoesconnect;

import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.bluetooth.*;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.content.SharedPreferences;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
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
            byte[] buffer = new byte[40];
            int numBytes;

            while (true) {
                try {
                    numBytes = inStream.read(buffer);
                    byte[] data = Arrays.copyOf(buffer, numBytes);
                    //Log.d(LOG_TAG, "data:" + bytesToHex(data));
                    myHandler.obtainMessage(SENSOR_DATA, numBytes, -1, data).sendToTarget();
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
                myError("Fatal Error", "Can't close a socket" + e.getMessage() + ".");
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

        // если код функции в текущем запросе == 06, то не обрабатываем пока эти ответы,
        // кроме нескольких исключений))
        int reqFuncCode = _request[1] & 0xFF;
        if (reqFuncCode == 6) {
            // если ещё и режим сброса настроек датчика, то вероятно, что это его ответ
            // (!!! если работаем одновременно только с одним датчиком,
            // иначе может быть и ответ от другого, тогда надо переделывать, но пока норм !!!)
            if (workingMode == WorkingMode.SETTING_DEFAULT_SETTINGS) {
                // В этом случае особо делать нечего, поскольку дефолтного адреса мы не знаем, надо искать.
                // Поэтому просто отключаемся, имитируя нажатие кнопки Стоп
                connect_to_sensor.performClick();
                Log.d(LOG_TAG, "apparently response on SETTING_DEFAULT_SETTINGS, stop sensor connection");
                return;
            } else if (workingMode == WorkingMode.CHANGING_SENSOR_ADDRESS) {
                // А если это вероятный ответ на смену адреса датчика, то обновляем переменную текущего адреса
                if (curSensorAddress != newSensorAddress) {
                    curSensorAddress = newSensorAddress;
                    input_sensor_address.setText(Integer.toString(newSensorAddress));
                    // сохраняем адрес
                    prefEditor.putString("lastConnectedSensorAddress", Integer.toString(newSensorAddress));
                    prefEditor.apply();
                    
                    Log.d(LOG_TAG, "apparently response on CHANGING_SENSOR_ADDRESS, change curSensorAddress");
                    return;
                }
            }
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
//        Log.d(LOG_TAG, "localCopyResponse: " + bytesToHex(localCopyResponse));

        // если контрольная сумма из ответа не совпадает с расчётом, то выходим:
        if (!Arrays.equals(respCRC, calcCRC(respMsg))) {
//            Log.d(LOG_TAG, bytesToHex(respCRC) + " != " + bytesToHex(calcCRC(respMsg)));
            return;
        }

        // Глобальный ответ обнуляем, поскольку проверка ответа пройдена
        // и далее работаем с его копией
        response = null;

        connectionState = ConnectionState.CONNECTED;
        sensor_connection_state.setText("СТАТУС: ПОДКЛЮЧЕН");
        sensor_connection_state.setBackgroundColor(0xFFFFFFFF);

        // Востанавливаем индикацию обычного режима:
        workingMode = WorkingMode.READING_DATA;
        working_mode.setText("РЕЖИМ: ОПРОС");
        working_mode.setBackgroundColor(0xFFFFFFFF);

        if (appMode == AppMode.SEARCH_SENSORS) {
            // добавляем адрес из ответа в массив найденных датчиков
            findedSensors.add(localCopyResponse[0] & 0xFF);
            // увеличиваем счётчик на экране
            finded_sensors.setText(Integer.toString(findedSensors.size()));
            return;
        }

        // Разблокируем кнопки посылки команд:
        set_zero.setEnabled(true);
        main_calibration.setEnabled(true);
        middle_calibration.setEnabled(true);
        set_defaults.setEnabled(true);

        if (confirm_dialog_title.getVisibility() == View.INVISIBLE) {
            change_sensor_address.setEnabled(true);
            threshold_1.setEnabled(true);
            threshold_2.setEnabled(true);
        }

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
            // Log.d(LOG_TAG, "reqFuncCode(" + reqFuncCode + ") == respFuncCode(" + respFuncCode + ") Parsing of data...");
//          Ответ на этот запрос, без ошибки, переходим далее к разбору данных:
            parseRespData(localCopyRequest, localCopyResponse);

        } else { // не совпадают
            Log.d(LOG_TAG, "reqFuncCode(" + reqFuncCode + ") != respFuncCode(" + respFuncCode + ")");
//          Если второй байт ответа равен второму байту запроса с единицей в старшем бите (код ошибки):
            int modReqFuncCode = (localCopyRequest[1] | 0b10000000) & 0xFF; // устанавливаем единицу в старший бит

            if (respFuncCode == modReqFuncCode) {
                // Log.d(LOG_TAG, "respFuncCode(" + respFuncCode + ") == modReqFuncCode(" + modReqFuncCode + ")");
//              значит ответ на этот запрос, но с ошибкой:
//              читаем третий байт ответа и выводим информацию об ошибке...
                int respError = localCopyResponse[2] & 0xFF;
                Log.d(LOG_TAG, "Error in localCopyResponse. Error code: " + respError);
                Toast.makeText(getBaseContext(), "Error in response. Error code: " + respError, Toast.LENGTH_SHORT).show();
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
        // Log.d(LOG_TAG, "reqNumRegisters: " + reqNumRegisters);
        int reqFirstRegAddress = ((localCopyRequest[2] & 0xFF) << 8) | (localCopyRequest[3] & 0xFF); // склеивание двух байт в одно целое число
        // Log.d(LOG_TAG, "reqFirstRegAddress: " + reqFirstRegAddress);

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
//            Log.d(LOG_TAG, "========================");
//          вычисляем адрес регистра относительно адреса первого регистра из запроса
            curRegAddress = reqFirstRegAddress + i;
//            Log.d(LOG_TAG, "curRegAddress: " + curRegAddress);
            curBytePos = curBytePos + 2;
//            Log.d(LOG_TAG, "curBytePos: " + curBytePos);

//          если адрес регистра такой-то:
            switch (curRegAddress) {
                case 0: // старший байт: адрес устройства, младший: скорость обмена

                    // для ГСО пришлось отказаться от этого регистра, дабы уместиться в лимит (10 регистров в запросе)

//                  берём в ответе соответсвующие 2 байта
//                  преобразовываем их в соответсвии со спецификацией!

                    // curRegDataHighByte = localCopyResponse[curBytePos] & 0xFF;
                    // Log.d(LOG_TAG, "curRegDataHighByte: " + curRegDataHighByte);
                    // change_sensor_address.setText("Адрес датчика: " + Integer.toString(curRegDataHighByte));
//                    curSensorAddress = curRegDataHighByte;
//                    input_sensor_address.setText(Integer.toString(curRegDataHighByte));

                    // curRegDataLowByte = localCopyResponse[curBytePos + 1] & 0xFF;
                    // Log.d(LOG_TAG, "curRegDataLowByte: " + curRegDataLowByte);
                    break;

                case 1: // старший байт: тип прибора, младший: флаги состояния
                    curRegDataHighByte = localCopyResponse[curBytePos] & 0xFF;
                    // Log.d(LOG_TAG, "curRegDataHighByte: " + curRegDataHighByte);
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
                        // Log.d(LOG_TAG, "State flag " + flag + ": " + flagState);
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
//                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
                    // Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);

                    // экспериментально для гсо, ну и может для сгоэс будет тоже работать...
                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
//                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);
                    String bits = Integer.toBinaryString(curRegDataFull);
                    // дополняем строку ведущими нулями
                    bits = String.format("%16s", bits).replace(' ', '0');
//                    Log.d(LOG_TAG, "bits: " + bits);
                    float nkprValue;
                    // если старший бит = 1, то значит это отрицательное число
                    if (bits.startsWith("1")) {
                        // Mожно, конечно, инвертировать биты, преобразовывать к целому числу,
                        // и то, что получится делить на 10(для регистра 11, а тут делить не надо, но чтобы не ломать алгоритм, делю на 1), но это дольше.
                        // А так просто вычитаем максимальное значение для 2х байт(65535) и получаем то же самое,
                        // причём уже с нужным знаком. Думаю, это нормальное решение)
                        nkprValue = (curRegDataFull - 65535) / (float)1.0;
                    } else {
                        nkprValue = curRegDataFull / (float)1.0;
                    }
                    gas_level_nkpr.setText(Float.toString(nkprValue));

                    // объёмные проценты
                    float volumePercent = nkprPercentToVolumePercent(nkprValue);
                    // Log.d(LOG_TAG, "volumePercent: " + volumePercent);
                    gas_level_volume.setText(Float.toString(volumePercent));

                    // ток в мА
                    float current = nkprPercentToCurrent(nkprValue);
                    // Log.d(LOG_TAG, "current: " + current);
                    gas_level_current.setText(Float.toString(current));


                    break;

                case 3: // старший байт: порог 1, младший: порог 2
                    curRegDataHighByte = localCopyResponse[curBytePos] & 0xFF;
                    // Log.d(LOG_TAG, "curRegDataHighByte: " + curRegDataHighByte);
                    threshold_1.setText("1 порог:\n" + Integer.toString(curRegDataHighByte));
                    curValueOfThreshold1 = curRegDataHighByte;

                    curRegDataLowByte = localCopyResponse[curBytePos + 1] & 0xFF;
                    // Log.d(LOG_TAG, "curRegDataLowByte: " + curRegDataLowByte);
                    threshold_2.setText("2 порог:\n" + Integer.toString(curRegDataLowByte));
                    curValueOfThreshold2 = curRegDataLowByte;
                    break;

                case 4: // D - приведённое
//                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
                    // Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);
                    break;

                case 5: // напряжение опорного канала
//                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
                    // Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);
                    break;

                case 6: // напряжение рабочего канала
//                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
                    // Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);
                    break;

                case 7: // D - приборное
//                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
                    // Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);
                    break;

                case 8: // температура, показания встроенного терморезистора
//                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
                    // Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);
                    break;

                case 9: // серийный номер прибора
                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
                    // Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);
                    serial_number.setText(Integer.toString(curRegDataFull));
                    break;

                case 10: // концентрация измеряемого газа в % НКПР * 10 (целое знаковое)
                    // для сгоэс это работает, а для гсо нет (у него в этом регистре другие данные), поэтому пока отключил

                    /*
                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
//                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);
                    String bits = Integer.toBinaryString(curRegDataFull);
                    // дополняем строку ведущими нулями
                    bits = String.format("%16s", bits).replace(' ', '0');
//                    Log.d(LOG_TAG, "bits: " + bits);
                    float nkprValue;
                    // если старший бит = 1, то значит это отрицательное число
                    if (bits.startsWith("1")) {
                        // Mожно, конечно, инвертировать биты, преобразовывать к целому числу,
                        // и то, что получится делить на 10, но это дольше.
                        // А так просто вычитаем максимальное значение для 2х байт(65535) и получаем то же самое,
                        // причём уже с нужным знаком. Думаю, это нормальное решение)
                        nkprValue = (curRegDataFull - 65535) / (float)10.0;
                    } else {
                        nkprValue = curRegDataFull / (float)10.0;
                    }
                    gas_level_nkpr.setText(Float.toString(nkprValue));

                    // объёмные проценты
                    float volumePercent = nkprPercentToVolumePercent(nkprValue);
                    // Log.d(LOG_TAG, "volumePercent: " + volumePercent);
                    gas_level_volume.setText(Float.toString(volumePercent));

                    // ток в мА
                    float current = nkprPercentToCurrent(nkprValue);
                    // Log.d(LOG_TAG, "current: " + current);
                    gas_level_current.setText(Float.toString(current));
                    */
                    break;

                case 11: // номер версии ПО прибора (беззнаковое целое)
                    // TODO: 12.04.2020 знаковое\беззнаковое. Возможно иначе надо преобразовывать...
//                    curRegDataFull = ((localCopyResponse[curBytePos] & 0xFF) << 8) | (localCopyResponse[curBytePos + 1] & 0xFF);
                    // Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);
                    break;

                case 12: // старший байт: тип прибора, младший: модификация прибора
//                    curRegDataHighByte = localCopyResponse[curBytePos] & 0xFF;
                    // Log.d(LOG_TAG, "curRegDataHighByte: " + curRegDataHighByte);
//                    curRegDataLowByte = localCopyResponse[curBytePos + 1] & 0xFF;
                    // Log.d(LOG_TAG, "curRegDataLowByte: " + curRegDataLowByte);
                    break;

            }
        }
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
    
    private static final String BT_DEVICE_MAC_ADDRESS_DEFAULT = "98:D3:71:F5:DA:46";
    private static final String BT_DEVICE_NAME_DEFAULT = "HC-05";
    
    private static String btDeviceMacAddress;
    private static String btDeviceName;
    
    private BluetoothSocket btSocket = null;
    Button bt_settings;
    Button bt_connect;
    Button connect_to_sensor;
    Button set_zero;
    Button main_calibration;
    Button middle_calibration;
    Button confirm_dialog_ok;
    Button confirm_dialog_cancel;
    Button threshold_1;
    Button threshold_2;
    Button change_sensor_address;
    Button set_defaults;
    EditText confirm_dialog_input;
    private ConnectedThread myThread = null;
    final String LOG_TAG = "myLogs";

    TextView title_sensor_connection;
    TextView title_serial_number;
    TextView title_sensor_type;
    TextView title_gas_level_nkpr;
    TextView title_gas_level_volume;
    TextView title_gas_level_current;
    TextView title_fault_relay;
    TextView title_relay_1;
    TextView title_relay_2;

    TextView serial_number;
    TextView sensor_type;
    TextView gas_level_nkpr;
    TextView gas_level_volume;
    TextView gas_level_current;
    TextView fault_relay;
    TextView relay_1;
    TextView relay_2;
    TextView sensor_connection_state;
    TextView working_mode;
    TextView confirm_dialog_title;
    EditText input_sensor_address;
    RadioGroup rg_app_modes;
    RadioButton rb_work;
    RadioButton rb_search;
    RadioButton rb_settings;
    Handler myHandler;

    RadioGroup rg_sensor_type;
    RadioButton rb_sgoes;
    RadioButton rb_gso;
    enum SensorType {
        SGOES,
        GSO
    }
    SensorType selected_sensor_type = SensorType.SGOES;

    TextView title_search_range;
    TextView title_search_start;
    EditText input_search_start;
    TextView title_search_end;
    EditText input_search_end;

    TextView title_cur_search_address;
    TextView cur_search_address;

    TextView title_finded_sensors;
    TextView finded_sensors;
    Button search_sensors;

    final int SENSOR_DATA = 1;
    final int SENSOR_CONNECTION_THREAD_DATA = 2;
    final int BT_SOCKET_CONNECTION_THREAD_DATA = 3;
    final int SEARCH_SENSORS_DATA = 4;

    byte[] request; // текущий запрос
    byte[] response; // текущий ответ
    //int numResponseBytes = 0;  // счётчик байт, полученных в текущем ответе
    boolean sensorConnection = false; // флаг текущего подключения
    //int requestFuncCode = 3; // код функции запроса
    Thread sensorConnectionThread = null;
    Thread btSocketConnectionThread = null;
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
    // TODO переименовать в sensorConnectionState
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
    
    enum AppMode { // режимы работы приложения
        WORK, // рабочий режим
        SEARCH_SENSORS, // режим поиска датчиков
        SETTINGS // режим настроек
    }
    AppMode appMode = AppMode.WORK;
    
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

    // Флаг успешного подключения.
    boolean btSocketConnectIsOpen = false;
    // Счётчик попыток подключения.
    int btSocketCountConnectionTries = 0;
    // Максимальное количество попыток
    final int MAX_BT_SOCKET_CONNECTION_TRIES = 1;

    BluetoothAdapter bluetoothAdapter = null;
    BluetoothDevice bluetoothDevice = null;

    int requestPause = 0;
    float highConcentration = (float)0.0;
    float middleConcentration = (float)0.0;
    
    final int REQUEST_PAUSE_DEFAULT = 2000;
    final float HIGH_CONCENTRATION_DEFAULT = (float)4.15;
    final float MIDDLE_CONCENTRATION_DEFAULT = (float)2.2;

    enum ConfirmDialogModes {
        NONE,
        SET_ZERO,
        CALIBRATION_MIDDLE,
        CALIBRATION_HIGH,
        SET_THRESHOLD_1,
        SET_THRESHOLD_2,
        SET_DEFAULT_SETTINGS,
        CHANGE_SENSOR_ADDRESS
    }
    ConfirmDialogModes confirmDialogMode = ConfirmDialogModes.NONE;

    int curValueOfThreshold1 = 0;
    int curValueOfThreshold2 = 0;

    int newValueOfThreshold1 = 0;
    int newValueOfThreshold2 = 0;
    
    int curSensorAddress = 0;
    int newSensorAddress = 0;
    
    String inputSearchStart = "";
    String inputSearchEnd = "";
    int startAddressOfSearchRange = 0;
    int endAddressOfSearchRange = 0;
    int curAddressOfSearchRange = 0;

    LinkedHashSet<Integer> findedSensors = new LinkedHashSet<>();
    ArrayAdapter<String> address_list_adapter;
    TextView title_address_list;
    Spinner address_list;

    TextView title_request_pause;
    EditText input_request_pause;

    TextView title_high_concentration;
    EditText input_high_concentration;

    TextView title_middle_concentration;
    EditText input_middle_concentration;

    TextView title_bt_device_list;
    Spinner bt_device_list;
    Button update_bt_device_list;
    ArrayAdapter<String> bt_device_list_adapter;
    LinkedHashSet<String[]> btPairedDevices = new LinkedHashSet<>();

    Button save_settings;
    Button reset_settings;
    
    final int REQUEST_PAUSE_MIN = 2000;
    final int REQUEST_PAUSE_MAX = 20000;
    
    final float PGS_CONCENTRATION_MIN = (float)0.0;
    final float PGS_CONCENTRATION_MAX = (float)5.0;
    
    private static final String PREFS_FILE = "prog_settings";
    SharedPreferences settings;
    SharedPreferences.Editor prefEditor;

    @SuppressLint("HandlerLeak")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(LOG_TAG, "ready");

        bt_settings = (Button) findViewById(R.id.bt_settings);
        bt_connect = (Button) findViewById(R.id.bt_connect);
        rg_app_modes = (RadioGroup) findViewById(R.id.rg_app_modes);
        rb_work = (RadioButton) findViewById(R.id.rb_work);
        rb_search = (RadioButton) findViewById(R.id.rb_search);
        rb_settings = (RadioButton) findViewById(R.id.rb_settings);

        rg_sensor_type = (RadioGroup) findViewById(R.id.rg_sensor_type);
        rb_sgoes = (RadioButton) findViewById(R.id.rb_sgoes);
        rb_gso = (RadioButton) findViewById(R.id.rb_gso);

        // work screen
        title_sensor_connection = (TextView) findViewById(R.id.title_sensor_connection);
        input_sensor_address = (EditText) findViewById(R.id.input_sensor_address);
        connect_to_sensor = (Button) findViewById(R.id.connect_to_sensor);
        sensor_connection_state = (TextView) findViewById(R.id.sensor_connection_state);
        working_mode = (TextView) findViewById(R.id.working_mode);
        
        change_sensor_address = (Button) findViewById(R.id.change_sensor_address);
        
        title_serial_number = (TextView) findViewById(R.id.title_serial_number);
        serial_number = (TextView) findViewById(R.id.serial_number);
        
        title_sensor_type = (TextView) findViewById(R.id.title_sensor_type);
        sensor_type = (TextView) findViewById(R.id.sensor_type);
        
        title_gas_level_nkpr = (TextView) findViewById(R.id.title_gas_level_nkpr);
        gas_level_nkpr = (TextView) findViewById(R.id.gas_level_nkpr);
        
        title_gas_level_volume = (TextView) findViewById(R.id.title_gas_level_volume);
        gas_level_volume = (TextView) findViewById(R.id.gas_level_volume);
        
        title_gas_level_current = (TextView) findViewById(R.id.title_gas_level_current);
        gas_level_current = (TextView) findViewById(R.id.gas_level_current);
        
        set_zero = (Button) findViewById(R.id.set_zero);
        main_calibration = (Button) findViewById(R.id.main_calibration);
        middle_calibration = (Button) findViewById(R.id.middle_calibration);
        threshold_1 = (Button) findViewById(R.id.threshold_1);
        threshold_2 = (Button) findViewById(R.id.threshold_2);        
        
        title_fault_relay = (TextView) findViewById(R.id.title_fault_relay);
        fault_relay = (TextView) findViewById(R.id.fault_relay);
        
        title_relay_1 = (TextView) findViewById(R.id.title_relay_1);
        relay_1 = (TextView) findViewById(R.id.relay_1);
        
        title_relay_2 = (TextView) findViewById(R.id.title_relay_2);
        relay_2 = (TextView) findViewById(R.id.relay_2);
        
        set_defaults = (Button) findViewById(R.id.set_defaults);

        confirm_dialog_title = (TextView) findViewById(R.id.confirm_dialog_title);
        confirm_dialog_input = (EditText) findViewById(R.id.confirm_dialog_input);
        confirm_dialog_ok = (Button) findViewById(R.id.confirm_dialog_ok);
        confirm_dialog_cancel = (Button) findViewById(R.id.confirm_dialog_cancel);

        // search screen
        title_search_range = (TextView) findViewById(R.id.title_search_range);
        title_search_start = (TextView) findViewById(R.id.title_search_start);
        input_search_start = (EditText) findViewById(R.id.input_search_start);
        title_search_end = (TextView) findViewById(R.id.title_search_end);
        input_search_end = (EditText) findViewById(R.id.input_search_end);

        title_cur_search_address = (TextView) findViewById(R.id.title_cur_search_address);
        cur_search_address = (TextView) findViewById(R.id.cur_search_address);

        title_finded_sensors = (TextView) findViewById(R.id.title_finded_sensors);
        finded_sensors = (TextView) findViewById(R.id.finded_sensors);
        search_sensors = (Button) findViewById(R.id.search_sensors);

        title_address_list = (TextView) findViewById(R.id.title_address_list);
        address_list = (Spinner) findViewById(R.id.address_list);

        address_list_adapter = new ArrayAdapter<String>(MainActivity.this, android.R.layout.simple_spinner_item);
        address_list_adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        address_list.setAdapter(address_list_adapter);
        address_list.setPrompt("Адреса:");

        // settings screen
        title_request_pause = (TextView) findViewById(R.id.title_request_pause);
        input_request_pause = (EditText) findViewById(R.id.input_request_pause);
        
        title_high_concentration = (TextView) findViewById(R.id.title_high_concentration);
        input_high_concentration = (EditText) findViewById(R.id.input_high_concentration);

        title_middle_concentration = (TextView) findViewById(R.id.title_middle_concentration);
        input_middle_concentration = (EditText) findViewById(R.id.input_middle_concentration);

        title_bt_device_list = (TextView) findViewById(R.id.title_bt_device_list);
        bt_device_list = (Spinner) findViewById(R.id.bt_device_list);
        update_bt_device_list = (Button) findViewById(R.id.update_bt_device_list);

        bt_device_list_adapter = new ArrayAdapter<String>(MainActivity.this, android.R.layout.simple_spinner_item);
        bt_device_list_adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bt_device_list.setAdapter(bt_device_list_adapter);
        bt_device_list.setPrompt("Устройства:");

        save_settings = (Button) findViewById(R.id.save_settings);
        reset_settings = (Button) findViewById(R.id.reset_settings);

        settings = getSharedPreferences(PREFS_FILE, MODE_PRIVATE);
        prefEditor = settings.edit();
        loadSettings();

        update_bt_device_list.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                update_bt_device_list.setEnabled(false);

                bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                if (bluetoothAdapter == null) {
                    Log.d(LOG_TAG, "bluetooth adapter is not detected");
                    Toast.makeText(getApplicationContext(), "bluetooth adapter is not detected", Toast.LENGTH_SHORT).show();
                    update_bt_device_list.setEnabled(true);
                    return;
                }

                if (!bluetoothAdapter.isEnabled()) {
                    Log.d(LOG_TAG, "bluetooth is disabled");
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                    update_bt_device_list.setEnabled(true);
                    return;
                }

                Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
                if (pairedDevices.size() > 0) {
                    bt_device_list_adapter.clear();
                    bt_device_list_adapter.add(btDeviceName + " (" + btDeviceMacAddress + ")");
                    btPairedDevices.clear();
                    btPairedDevices.add(new String[] {btDeviceName, btDeviceMacAddress});
                    for (BluetoothDevice device : pairedDevices) {
                        if (!device.getAddress().equals(btDeviceMacAddress)) {
                            bt_device_list_adapter.add(device.getName() + " (" + device.getAddress() + ")");
                            btPairedDevices.add(new String[] {device.getName(), device.getAddress()});
                        }
                    }
                    bt_device_list.setSelection(0);
                }
                update_bt_device_list.setEnabled(true);
            }
        });

        save_settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // пауза между запросами
                String inputRequestPause = input_request_pause.getText().toString();
                if (!checkInputRequestPause(inputRequestPause)) {
                    input_request_pause.requestFocus();
                    return;
                }
                requestPause = Integer.parseInt(inputRequestPause);
                prefEditor.putInt("requestPause", requestPause);
                
                // высокая концентрация
                String inputHighConcentration = input_high_concentration.getText().toString();
                if (!checkInputConcentration(inputHighConcentration, "high")) {
                    input_high_concentration.requestFocus();
                    return;
                }
                highConcentration = Float.parseFloat(inputHighConcentration);
                prefEditor.putFloat("highConcentration", highConcentration);
                
                // средняя концентрация
                String inputMiddleConcentration = input_middle_concentration.getText().toString();
                if (!checkInputConcentration(inputMiddleConcentration, "middle")) {
                    input_middle_concentration.requestFocus();
                    return;
                }
                middleConcentration = Float.parseFloat(inputMiddleConcentration);
                prefEditor.putFloat("middleConcentration", middleConcentration);
                
                // адаптер
                int pos = bt_device_list.getSelectedItemPosition();
                String[][] devices = btPairedDevices.toArray(new String[btPairedDevices.size()][2]);
                String btDeviceNameNew = devices[pos][0];
                String btDeviceMacAddressNew = devices[pos][1];
//                Log.d(LOG_TAG, "selected device: " + btDeviceNameNew + " " + btDeviceMacAddressNew);

                if (!btDeviceMacAddressNew.equals(btDeviceMacAddress)) {
                    if (btDeviceConnectionState == BtDeviceConnectionState.CONNECTED) {
                        bt_connect.performClick();
                    }

                    btDeviceName = btDeviceNameNew;
                    btDeviceMacAddress = btDeviceMacAddressNew;
                    prefEditor.putString("btDeviceName", btDeviceName);
                    prefEditor.putString("btDeviceMacAddress", btDeviceMacAddress);
                }

                prefEditor.apply();
                Toast.makeText(getBaseContext(), "Сохранено", Toast.LENGTH_SHORT).show();
            }
        });

        reset_settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // пауза между запросами
                requestPause = REQUEST_PAUSE_DEFAULT;
                input_request_pause.setText(Integer.toString(requestPause));
                prefEditor.putInt("requestPause", requestPause);
                
                // высокая концентрация
                highConcentration = HIGH_CONCENTRATION_DEFAULT;
                input_high_concentration.setText(Float.toString(highConcentration));
                prefEditor.putFloat("highConcentration", highConcentration);
                
                // средняя концентрация
                middleConcentration = MIDDLE_CONCENTRATION_DEFAULT;
                input_middle_concentration.setText(Float.toString(middleConcentration));
                prefEditor.putFloat("middleConcentration", middleConcentration);
                
                // адаптер
                if (btDeviceConnectionState == BtDeviceConnectionState.CONNECTED) {
                    bt_connect.performClick();
                }

                btDeviceName = BT_DEVICE_NAME_DEFAULT;
                btDeviceMacAddress = BT_DEVICE_MAC_ADDRESS_DEFAULT;

                btPairedDevices.clear();
                btPairedDevices.add(new String[] {btDeviceName, btDeviceMacAddress});
                bt_device_list_adapter.clear();
                bt_device_list_adapter.add(btDeviceName + " (" + btDeviceMacAddress + ")");
                bt_device_list.setSelection(0);

                prefEditor.putString("btDeviceName", btDeviceName);
                prefEditor.putString("btDeviceMacAddress", btDeviceMacAddress);

                prefEditor.apply();
                Toast.makeText(getBaseContext(), "Восстановлены первоначальные значения", Toast.LENGTH_SHORT).show();
            }
        });
        
        address_list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
//                Toast.makeText(getBaseContext(), "Position = " + position, Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onNothingSelected(AdapterView<?> arg0) {
            }
        });

        bt_settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // вызываем экран настроек bluetooth:
                startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
            }
        });
        
        bt_connect.setOnClickListener(new View.OnClickListener() {
            // Процедура приведения кнопки в исходное состояние
            public void resetBtConnectButton() {
                bt_connect.setEnabled(true);
                bt_connect.setText("ПОДКЛЮЧИТЬСЯ");
                btDeviceConnectionState = BtDeviceConnectionState.DISCONNECTED;
            }

            @Override
            public void onClick(View v) {
                // Если подключения к плате нет, то подключаемся
                if (btDeviceConnectionState == BtDeviceConnectionState.DISCONNECTED) {
                    // Блокируем пока кнопку, чтоб повторно не нажимали
                    bt_connect.setEnabled(false);

                    // Меняем заголовок кнопки и статус подключения
                    bt_connect.setText("ПОДКЛЮЧЕНИЕ...");
                    btDeviceConnectionState = BtDeviceConnectionState.CONNECTING;

                    // Получаем блютус адаптер
                    bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

                    // Если блютус адаптер не обнаружен, то сообщаем об этом и выходим
                    if (bluetoothAdapter == null) {
                        Log.d(LOG_TAG, "bluetooth adapter is not detected");
                        Toast.makeText(getApplicationContext(), "bluetooth adapter is not detected", Toast.LENGTH_SHORT).show();
                        resetBtConnectButton();
                        return;
                    }

                    // Если адаптер не доступен (выключен), то запрашиваем его включение, а пока выходим
                    if (!bluetoothAdapter.isEnabled()) {
                        Log.d(LOG_TAG, "bluetooth is disabled");
//                        Toast.makeText(getApplicationContext(), "bluetooth is disabled", Toast.LENGTH_SHORT).show();
                        // Запрос на включение bluetooth:
                        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                        resetBtConnectButton();
                        // Пока блютус включится, уже исключение вылетет, поэтому выходим.
                        return;
                        // А при повторном нажатии (с уже включённым блютузом) алгоритм пойдёт дальше
                    }

                    /*  При первом подключении ("на холодную") смарт с модулем связываются только с 3-4 попытки.
                    Поэтому сделал пока подключение в цикле с максимальным количеством попыток
                    (чтобы исключить возможность зацикливания).
                    А при успехе, устанавливается соответсвующий флаг. */

                    bluetoothDevice = bluetoothAdapter.getRemoteDevice(btDeviceMacAddress);
                    // Может эта проверка и не нужна, но на всякий случай добавил
                    if (bluetoothDevice == null) {
                        Log.d(LOG_TAG, "No device with MAC address: " + btDeviceMacAddress);
                        Toast.makeText(getApplicationContext(), "No device with MAC address: " + btDeviceMacAddress, Toast.LENGTH_SHORT).show();
                        resetBtConnectButton();
                        return;
                    }
//                    Toast.makeText(getApplicationContext(), "bluetoothDevice: " + bluetoothDevice.getName(), Toast.LENGTH_SHORT).show();
                    // Log.d(LOG_TAG, "bluetoothDevice: " + bluetoothDevice.getName());

                    if (bluetoothAdapter.isDiscovering()) {
                        bluetoothAdapter.cancelDiscovery();
                    }

                    // Сбрасываем флаг и счётчик
                    btSocketConnectIsOpen = false;
                    btSocketCountConnectionTries = 0;

                    // Создаём отдельный поток, дабы не тормозить интерфейс,
                    // в котором пробуем подключиться к плате
                    Runnable btConnection = new Runnable() {
                        @Override
                        public void run() {
                            // Пока не подключились и не достигли макс количества попыток
                            while ((!btSocketConnectIsOpen) && (btSocketCountConnectionTries < MAX_BT_SOCKET_CONNECTION_TRIES)) {
                                // Пробуем создать сокет
                                try {
                                    btSocket = bluetoothDevice.createRfcommSocketToServiceRecord(MY_UUID);
                                    Log.d(LOG_TAG, "socket is created");
                                } catch (IOException e) {
                                    myError("Fatal Error", "Can't create a socket: " + e.getMessage() + ".");
                                    return;
                                }

                                // TODO Бывает сразу подключается, а бывает подвисает, причину пока не понял.
                                //  Пробовал делать паузы - также. Пробовал выносить из цикла создание и закрытие сокета,
                                //  но тоже не помогло. Пока так оставил, может и в железке дело (hc-05).

                                // Пробуем подключиться к плате
                                try {
                                    btSocket.connect();
                                    Log.d(LOG_TAG, "connect is open");
                                    // Взводим флаг успешного подключения
                                    btSocketConnectIsOpen = true;
                                    break;
                                } catch (IOException e) {
                                    Log.d(LOG_TAG, "Can't connect to device: " + e.getMessage());
                                    btSocketCountConnectionTries = btSocketCountConnectionTries + 1;
                                    // Шлём сообщение главному потоку (кроме последней попытки)
                                    if (btSocketCountConnectionTries < MAX_BT_SOCKET_CONNECTION_TRIES) {
                                        myHandler.obtainMessage(BT_SOCKET_CONNECTION_THREAD_DATA, "trying_to_connect_again").sendToTarget();
                                    }
                                }

                                // Если так и не подключились, то закрываем сокет (пробуем закрыть)
                                try {
                                    btSocket.close();
                                    Log.d(LOG_TAG, "socket is closed");
                                } catch (IOException e2) {
//                                    Log.d(LOG_TAG, "Can't close a socket");
                                    myError("Fatal Error", "Can't close a socket:" + e2.getMessage() + ".");
                                    return;
                                }

                            }
                            myHandler.obtainMessage(BT_SOCKET_CONNECTION_THREAD_DATA, "btSocketConnectionThread is finished").sendToTarget();
                        }
                    };
                    btSocketConnectionThread = new Thread(btConnection);
                    btSocketConnectionThread.start();

                // иначе, если есть подключение к плате, то отключаемся
                } else if (btDeviceConnectionState == BtDeviceConnectionState.CONNECTED) {
                    // Блокируем кнопку
                    bt_connect.setEnabled(false);
                    // Останавливаем подключение к датчику, если оно есть
                    if (connectionState != ConnectionState.DISCONNECTED) {
                        // имитируем нажатие кнопки Стоп
                        connect_to_sensor.performClick();
                    }
                    // Закрываем подключения
                    closeAllConnections();

                    resetBtConnectButton();
                    // Блокируем кнопку соединения с датчиком
                    connect_to_sensor.setEnabled(false);
                    search_sensors.setEnabled(false);
                    Toast.makeText(getApplicationContext(), "Связь с адаптером \"" + btDeviceName + "\" прервана", Toast.LENGTH_SHORT).show();
                }
            }
        });

        rg_app_modes.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                switch (checkedId) {
                    case R.id.rb_work:
                        appMode = AppMode.WORK;
                        hideSearchScreen();
                        hideSettingsScreen();
                        showWorkScreen();
                        hideConfirmDialog("ok");

                        break;

                    case R.id.rb_search:
                        appMode = AppMode.SEARCH_SENSORS;
                        hideWorkScreen();
                        hideSettingsScreen();
                        showSearchScreen();

                        break;

                    case R.id.rb_settings:
                        appMode = AppMode.SETTINGS;
                        hideWorkScreen();
                        hideSearchScreen();
                        showSettingsScreen();

                        break;

                    default:
                        break;
                }
            }
        });

        rg_sensor_type.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                switch (checkedId) {
                    case R.id.rb_sgoes:
                        selected_sensor_type = SensorType.SGOES;

                        break;

                    case R.id.rb_gso:
                        selected_sensor_type = SensorType.GSO;

                        break;

                    default:
                        break;
                }
            }
        });

        connect_to_sensor.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // если подключения нет
                if (!sensorConnection) {
                    switch (appMode) {
                        case WORK:
                            // проверяем поле адреса, если адрес корректный
                            String inputAddress = input_sensor_address.getText().toString();
                            if (checkInputAddress(inputAddress, "connection")) {
                                curSensorAddress = Integer.parseInt(inputAddress);
                                // сохраняем адрес
                                prefEditor.putString("lastConnectedSensorAddress", inputAddress);
                                prefEditor.apply();
                            } else {
                                return;
                            }
                            break;

                        case SEARCH_SENSORS:
                           inputSearchStart = input_search_start.getText().toString();
                           inputSearchEnd = input_search_end.getText().toString();
                            if ((checkInputAddress(inputSearchStart, "searchStart")) &&
                                    (checkInputAddress(inputSearchEnd, "searchEnd"))) {
                                startAddressOfSearchRange = Integer.parseInt(inputSearchStart);
                                endAddressOfSearchRange = Integer.parseInt(inputSearchEnd);
                                curAddressOfSearchRange = startAddressOfSearchRange;
                                curSensorAddress = startAddressOfSearchRange;

                                findedSensors.clear();
                                finded_sensors.setText("0");
                                address_list_adapter.clear();

//                                String[] s = {""};
//                                address_list_adapter = new ArrayAdapter<String>(MainActivity.this, android.R.layout.simple_spinner_item, s);
//                                address_list.setAdapter(address_list_adapter);
                                address_list.setVisibility(View.INVISIBLE);
                            } else {
                                return;
                            }
                            break;
                    }

                    rg_app_modes.setEnabled(false);
                    rb_work.setEnabled(false);
                    rb_search.setEnabled(false);
                    rb_settings.setEnabled(false);

                    rg_sensor_type.setEnabled(false);
                    rb_sgoes.setEnabled(false);
                    rb_gso.setEnabled(false);

                    input_sensor_address.setEnabled(false);
                    connect_to_sensor.setText("Стоп");
                    search_sensors.setText("Стоп");

                    input_search_start.setEnabled(false);
                    input_search_end.setEnabled(false);

                    // сбрасываем команду с кнопок в дефолтное состояние
                    // (на случай, если команда сменилась, а потом остановили соединение)
                    commandFromButton = Commands.NONE;

                    // устанавливаем статусы
                    connectionState = ConnectionState.WAITING_FOR_RESPONSE;
                    sensor_connection_state.setText("СТАТУС: ПОДКЛЮЧЕНИЕ...");
                    sensor_connection_state.setBackgroundColor(0xFFFFFFFF);
                    workingMode = WorkingMode.READING_DATA;
                    working_mode.setText("РЕЖИМ: ОПРОС");
                    working_mode.setBackgroundColor(0xFFFFFFFF);

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
                    sensor_connection_state.setBackgroundColor(0xFFFFFFFF);
                    workingMode = WorkingMode.READING_DATA;
                    working_mode.setText("РЕЖИМ: ---");
                    working_mode.setBackgroundColor(0xFFFFFFFF);

                    rg_app_modes.setEnabled(true);
                    rb_work.setEnabled(true);
                    rb_search.setEnabled(true);
                    rb_settings.setEnabled(true);

                    rg_sensor_type.setEnabled(true);
                    rb_sgoes.setEnabled(true);
                    rb_gso.setEnabled(true);
                    
                    connect_to_sensor.setText("Старт");
                    input_sensor_address.setEnabled(true);

                    search_sensors.setText("Старт");
                    input_search_start.setEnabled(true);
                    input_search_end.setEnabled(true);

                    if (appMode == AppMode.WORK) {
                        // Блокируем кнопки команд, скрываем диалог подтверждения:
                        hideConfirmDialog("ok");
                    }

                    if (appMode == AppMode.SEARCH_SENSORS) {
                        if (findedSensors.size() > 0) {
                            // todo: покаказать сообщение: Список адресов найденных датчиков доступен в разделе \"Работа\"
                            //  (когда буду это делать)

//                            address_list_adapter.clear();

                            // преобразовываем множество в массив строк для выпадухи
                            Object[] arr = findedSensors.toArray();
                            for (int i = 0; i < arr.length; i++) {
                                address_list_adapter.add(arr[i].toString());
                            }

                            address_list.setSelection(0);
                            address_list.setVisibility(View.VISIBLE);
                        }
                    }

                    // TODO: 16.04.2020 обнулить поля данных, добавить индикатор состояния (отключено\нет ответа\подключено)
                    //  а может поля не обнулять, иногда полезно может быть, будто на паузу поставил...
                }
            }
        });

        search_sensors.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Как-то неудобно программно перемещать кнопку на экране,
                // поэтому сделал другую, а клик на неё будет вызывать клик на первую)
                // (да, очередной костыль=))
                connect_to_sensor.performClick();
            }
        });

        myHandler = new Handler() {
            public void handleMessage(android.os.Message msg) {
                switch (msg.what) {
                    case SENSOR_DATA:
                        // если остановили подключение, то не надо уже обрабатывать ответ
                        // (предотвращение "инерции")
                        if (!sensorConnection) {
                            Log.d(LOG_TAG, "sensorConnection is stopped. Skip this response.");
                            return;
                        }
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
                    case SENSOR_CONNECTION_THREAD_DATA:
//                        Log.d(LOG_TAG, "msg.obj: " + msg.obj);
                        if (msg.obj == ConnectionState.NO_RESPONSE) {
                            sensor_connection_state.setText("СТАТУС: НЕТ ОТВЕТА");
                            sensor_connection_state.setBackgroundColor(0xFFFF0000);
                        }
                        break;
                    case BT_SOCKET_CONNECTION_THREAD_DATA:
//                        Log.d(LOG_TAG, "msg.obj: " + msg.obj);

                        // Сообщаем о новой попытке подключения
                        if (msg.obj == "trying_to_connect_again") {
//                            Log.d(LOG_TAG, "btSocketCountConnectionTries: " + btSocketCountConnectionTries);
                            bt_connect.setText("ПОДКЛЮЧЕНИЕ...\nПОПЫТКА " + (btSocketCountConnectionTries + 1));
                            Toast.makeText(getApplicationContext(), "Не удалось подключиться к адаптеру \"" + btDeviceName + "\". Новая попытка", Toast.LENGTH_SHORT).show();
                            break;
                        }
                        // Если так и не подключились, то возвращаемся к исходному состоянию
                        if (!btSocketConnectIsOpen) {
                            btDeviceConnectionState = BtDeviceConnectionState.DISCONNECTED;
                            bt_connect.setText("ПОДКЛЮЧИТЬСЯ");
                            bt_connect.setEnabled(true);
                            Toast.makeText(getApplicationContext(), "Не удалось подключиться к адаптеру \"" + btDeviceName + "\"", Toast.LENGTH_SHORT).show();
                        } else {
                            // Иначе (всё ок) создаём отдельный поток с подключением
                            // для дальнейшего обмена информацией и запускаем его

                            // TODO переименовать myThread > btConnectionThread
                            myThread = new ConnectedThread(btSocket);
                            myThread.start();

                            // Меняем статус состояния подключения к плате
                            btDeviceConnectionState = BtDeviceConnectionState.CONNECTED;
                            bt_connect.setText("ОТКЛЮЧИТЬСЯ");
                            // Восстанавливаем доступность кнопки
                            bt_connect.setEnabled(true);
                            // И делаем доступной кнопку соединения с датчиком
                            connect_to_sensor.setEnabled(true);
                            search_sensors.setEnabled(true);
                            Toast.makeText(getApplicationContext(), "Адаптер \"" + btDeviceName + "\" подключен", Toast.LENGTH_SHORT).show();
                        }
                        break;
                    case SEARCH_SENSORS_DATA:
//                        Log.d(LOG_TAG, "msg.obj: " + msg.obj);
                        if (msg.obj.toString() == "stop_search") {
                            connect_to_sensor.performClick();
                            break;
                        }

                        cur_search_address.setText(msg.obj.toString());
                        break;
                }
            }
        };

        change_sensor_address.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm_dialog_title.setText("Смена адреса датчика:");
                confirm_dialog_input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_NORMAL);
                confirm_dialog_input.setText(Integer.toString(curSensorAddress));
                confirm_dialog_input.setEnabled(true);

                confirmDialogMode = ConfirmDialogModes.CHANGE_SENSOR_ADDRESS;
                showConfirmDialog();
            }
        });

        set_zero.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm_dialog_title.setText("Установка нуля:");
                confirm_dialog_input.setText("0");
                confirm_dialog_input.setEnabled(false);
                
                confirmDialogMode = ConfirmDialogModes.SET_ZERO;
                showConfirmDialog();
            }
        });

        set_defaults.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm_dialog_title.setText("Установка заводских значений:");
                confirm_dialog_input.setText("");
                confirm_dialog_input.setEnabled(false);
                
                confirmDialogMode = ConfirmDialogModes.SET_DEFAULT_SETTINGS;
                showConfirmDialog();
            }
        });

        main_calibration.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm_dialog_title.setText("Основная калибровка:");
                confirm_dialog_input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                confirm_dialog_input.setText(Float.toString(highConcentration));
                confirm_dialog_input.setEnabled(true);
                
                confirmDialogMode = ConfirmDialogModes.CALIBRATION_HIGH;
                showConfirmDialog();
            }
        });
        
        middle_calibration.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm_dialog_title.setText("Дополнительная калибровка:");
                confirm_dialog_input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                confirm_dialog_input.setText(Float.toString(middleConcentration));
                confirm_dialog_input.setEnabled(true);

                confirmDialogMode = ConfirmDialogModes.CALIBRATION_MIDDLE;
                showConfirmDialog();
            }
        });

        threshold_1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm_dialog_title.setText("Установка порога 1:");
                confirm_dialog_input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_NORMAL);
                confirm_dialog_input.setText(Integer.toString(curValueOfThreshold1));
                confirm_dialog_input.setEnabled(true);
                
                confirmDialogMode = ConfirmDialogModes.SET_THRESHOLD_1;
                showConfirmDialog();
            }
        });

        threshold_2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm_dialog_title.setText("Установка порога 2:");
                confirm_dialog_input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_NORMAL);
                confirm_dialog_input.setText(Integer.toString(curValueOfThreshold2));
                confirm_dialog_input.setEnabled(true);
                
                confirmDialogMode = ConfirmDialogModes.SET_THRESHOLD_2;
                showConfirmDialog();
            }
        });

        confirm_dialog_ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String inputValue = confirm_dialog_input.getText().toString();

                switch (confirmDialogMode) {
                    case CHANGE_SENSOR_ADDRESS:
                        if (checkInputAddress(inputValue, "changing")) {
                            newSensorAddress = Integer.parseInt(inputValue);

                            commandFromButton = Commands.CHANGE_SENSOR_ADDRESS;
                            Log.d(LOG_TAG, commandFromButton.toString());

                            workingMode = WorkingMode.CHANGING_SENSOR_ADDRESS;
                            working_mode.setText("РЕЖИМ: СМЕНА АДРЕСА");
                            working_mode.setBackgroundColor(0xFFFFFF00);

                            hideConfirmDialog("ok");
                        }
                        break;
                        
                    case CALIBRATION_HIGH:
                        if (checkInputConcentration(inputValue, "high")) {
                            highConcentration = Float.parseFloat(inputValue);
                            // сохраняем значение
                            prefEditor.putFloat("highConcentration", highConcentration);
                            prefEditor.apply();

                            commandFromButton = Commands.CALIBRATION_HIGH;
                            Log.d(LOG_TAG, commandFromButton.toString());

                            workingMode = WorkingMode.CALIBRATION_HIGH;
                            working_mode.setText("РЕЖИМ: ОСН. КАЛИБР.");
                            working_mode.setBackgroundColor(0xFFFFFF00);

                            hideConfirmDialog("ok");
                        }
                        break;
                        
                    case CALIBRATION_MIDDLE:
                        if (checkInputConcentration(inputValue, "middle")) {
                            middleConcentration = Float.parseFloat(inputValue);
                            // сохраняем значение
                            prefEditor.putFloat("middleConcentration", middleConcentration);
                            prefEditor.apply();

                            commandFromButton = Commands.CALIBRATION_MIDDLE;
                            Log.d(LOG_TAG, commandFromButton.toString());

                            workingMode = WorkingMode.CALIBRATION_MIDDLE;
                            working_mode.setText("РЕЖИМ: ДОП. КАЛИБР.");
                            working_mode.setBackgroundColor(0xFFFFFF00);

                            hideConfirmDialog("ok");
                        }
                        break;

                    case SET_ZERO:
                        commandFromButton = Commands.SET_ZERO;
                        Log.d(LOG_TAG, commandFromButton.toString());

                        workingMode = WorkingMode.SETTING_ZERO;
                        working_mode.setText("РЕЖИМ: УСТАНОВКА НУЛЯ");
                        working_mode.setBackgroundColor(0xFFFFFF00);

                        hideConfirmDialog("ok");
                        break;

                    case SET_DEFAULT_SETTINGS:
                        commandFromButton = Commands.SET_DEFAULT_SETTINGS;
                        Log.d(LOG_TAG, commandFromButton.toString());

                        workingMode = WorkingMode.SETTING_DEFAULT_SETTINGS;
                        working_mode.setText("РЕЖИМ: УСТ. ЗАВОД.ЗНАЧ.");
                        working_mode.setBackgroundColor(0xFFFFFF00);

                        hideConfirmDialog("ok");
                        break;

                    case SET_THRESHOLD_1:
                        if (checkInputThreshold(inputValue, 1)) {
                            newValueOfThreshold1 = Integer.parseInt(inputValue);

                            commandFromButton = Commands.SET_THRESHOLD_1;
                            Log.d(LOG_TAG, commandFromButton.toString());

                            workingMode = WorkingMode.SETTING_THRESHOLD_1;
                            working_mode.setText("РЕЖИМ: УСТ. ПОРОГА 1");
                            working_mode.setBackgroundColor(0xFFFFFF00);

                            hideConfirmDialog("ok");
                        }
                        break;

                    case SET_THRESHOLD_2:
                        if (checkInputThreshold(inputValue, 2)) {
                            newValueOfThreshold2 = Integer.parseInt(inputValue);

                            commandFromButton = Commands.SET_THRESHOLD_2;
                            Log.d(LOG_TAG, commandFromButton.toString());

                            workingMode = WorkingMode.SETTING_THRESHOLD_2;
                            working_mode.setText("РЕЖИМ: УСТ. ПОРОГА 2");
                            working_mode.setBackgroundColor(0xFFFFFF00);

                            hideConfirmDialog("ok");
                        }
                        break;
                }
            }
            // TODO: 19.04.2020  Долгая задержка показаний после команды, 5-6 секунд...
        });

        confirm_dialog_cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideConfirmDialog("cancel");
            }
        });
        
    }

    public void loadSettings() {
        requestPause = settings.getInt("requestPause", REQUEST_PAUSE_DEFAULT);
        highConcentration = settings.getFloat("highConcentration", HIGH_CONCENTRATION_DEFAULT);
        middleConcentration = settings.getFloat("middleConcentration", MIDDLE_CONCENTRATION_DEFAULT);

        btDeviceName = settings.getString("btDeviceName", BT_DEVICE_NAME_DEFAULT);
        btDeviceMacAddress = settings.getString("btDeviceMacAddress", BT_DEVICE_MAC_ADDRESS_DEFAULT);
        
        String lastConnectedSensorAddress = settings.getString("lastConnectedSensorAddress", "");
        input_sensor_address.setText(lastConnectedSensorAddress);
    }
    
    public void showWorkScreen() {
        title_sensor_connection.setVisibility(View.VISIBLE);

        rg_sensor_type.setVisibility(View.VISIBLE);

        input_sensor_address.setVisibility(View.VISIBLE);

        connect_to_sensor.setVisibility(View.VISIBLE);

        sensor_connection_state.setVisibility(View.VISIBLE);
        working_mode.setVisibility(View.VISIBLE);
        
        change_sensor_address.setVisibility(View.VISIBLE);
        
        title_serial_number.setVisibility(View.VISIBLE);
        serial_number.setVisibility(View.VISIBLE);
        
        title_sensor_type.setVisibility(View.VISIBLE);
        sensor_type.setVisibility(View.VISIBLE);
        
        title_gas_level_nkpr.setVisibility(View.VISIBLE);
        gas_level_nkpr.setVisibility(View.VISIBLE);
        
        title_gas_level_volume.setVisibility(View.VISIBLE);
        gas_level_volume.setVisibility(View.VISIBLE);
        
        title_gas_level_current.setVisibility(View.VISIBLE);
        gas_level_current.setVisibility(View.VISIBLE);
        
        set_zero.setVisibility(View.VISIBLE);
        main_calibration.setVisibility(View.VISIBLE);
        middle_calibration.setVisibility(View.VISIBLE);
        threshold_1.setVisibility(View.VISIBLE);
        threshold_2.setVisibility(View.VISIBLE);
        
        title_fault_relay.setVisibility(View.VISIBLE);
        fault_relay.setVisibility(View.VISIBLE);
        
        title_relay_1.setVisibility(View.VISIBLE);
        relay_1.setVisibility(View.VISIBLE);
        
        title_relay_2.setVisibility(View.VISIBLE);
        relay_2.setVisibility(View.VISIBLE);
        
        set_defaults.setVisibility(View.VISIBLE);

//        confirm_dialog_title.setVisibility(View.VISIBLE);
//        confirm_dialog_input.setVisibility(View.VISIBLE);
//        confirm_dialog_ok.setVisibility(View.VISIBLE);
//        confirm_dialog_cancel.setVisibility(View.VISIBLE);
    }

    public void hideWorkScreen() {
        title_sensor_connection.setVisibility(View.INVISIBLE);

        rg_sensor_type.setVisibility(View.INVISIBLE);

        input_sensor_address.setVisibility(View.INVISIBLE);

        connect_to_sensor.setVisibility(View.INVISIBLE);

        sensor_connection_state.setVisibility(View.INVISIBLE);
        working_mode.setVisibility(View.INVISIBLE);
        
        change_sensor_address.setVisibility(View.INVISIBLE);
        
        title_serial_number.setVisibility(View.INVISIBLE);
        serial_number.setVisibility(View.INVISIBLE);
        
        title_sensor_type.setVisibility(View.INVISIBLE);
        sensor_type.setVisibility(View.INVISIBLE);
        
        title_gas_level_nkpr.setVisibility(View.INVISIBLE);
        gas_level_nkpr.setVisibility(View.INVISIBLE);
        
        title_gas_level_volume.setVisibility(View.INVISIBLE);
        gas_level_volume.setVisibility(View.INVISIBLE);
        
        title_gas_level_current.setVisibility(View.INVISIBLE);
        gas_level_current.setVisibility(View.INVISIBLE);
        
        set_zero.setVisibility(View.INVISIBLE);
        main_calibration.setVisibility(View.INVISIBLE);
        middle_calibration.setVisibility(View.INVISIBLE);
        threshold_1.setVisibility(View.INVISIBLE);
        threshold_2.setVisibility(View.INVISIBLE);
        
        title_fault_relay.setVisibility(View.INVISIBLE);
        fault_relay.setVisibility(View.INVISIBLE);
        
        title_relay_1.setVisibility(View.INVISIBLE);
        relay_1.setVisibility(View.INVISIBLE);
        
        title_relay_2.setVisibility(View.INVISIBLE);
        relay_2.setVisibility(View.INVISIBLE);
        
        set_defaults.setVisibility(View.INVISIBLE);

//        confirm_dialog_title.setVisibility(View.INVISIBLE);
//        confirm_dialog_input.setVisibility(View.INVISIBLE);
//        confirm_dialog_ok.setVisibility(View.INVISIBLE);
//        confirm_dialog_cancel.setVisibility(View.INVISIBLE);
    }

    public void showSearchScreen() {
        title_search_range.setVisibility(View.VISIBLE);
        title_search_start.setVisibility(View.VISIBLE);
        input_search_start.setVisibility(View.VISIBLE);
        title_search_end.setVisibility(View.VISIBLE);
        input_search_end.setVisibility(View.VISIBLE);
        
        title_cur_search_address.setVisibility(View.VISIBLE);
        cur_search_address.setVisibility(View.VISIBLE);
        
        title_finded_sensors.setVisibility(View.VISIBLE);
        finded_sensors.setVisibility(View.VISIBLE);

        search_sensors.setVisibility(View.VISIBLE);

        title_address_list.setVisibility(View.VISIBLE);
        address_list.setVisibility(View.VISIBLE);
    }

    public void hideSearchScreen() {
        title_search_range.setVisibility(View.INVISIBLE);
        title_search_start.setVisibility(View.INVISIBLE);
        input_search_start.setVisibility(View.INVISIBLE);
        title_search_end.setVisibility(View.INVISIBLE);
        input_search_end.setVisibility(View.INVISIBLE);
        
        title_cur_search_address.setVisibility(View.INVISIBLE);
        cur_search_address.setVisibility(View.INVISIBLE);
        
        title_finded_sensors.setVisibility(View.INVISIBLE);
        finded_sensors.setVisibility(View.INVISIBLE);

        search_sensors.setVisibility(View.INVISIBLE);

        title_address_list.setVisibility(View.INVISIBLE);
        address_list.setVisibility(View.INVISIBLE);
    }

    public void showSettingsScreen() {
        title_request_pause.setVisibility(View.VISIBLE);
        input_request_pause.setText(Integer.toString(requestPause));
        input_request_pause.setVisibility(View.VISIBLE);
        
        title_high_concentration.setVisibility(View.VISIBLE);
        input_high_concentration.setText(Float.toString(highConcentration));
        input_high_concentration.setVisibility(View.VISIBLE);

        title_middle_concentration.setVisibility(View.VISIBLE);
        input_middle_concentration.setText(Float.toString(middleConcentration));
        input_middle_concentration.setVisibility(View.VISIBLE);
        
        title_bt_device_list.setVisibility(View.VISIBLE);

        btPairedDevices.clear();
        btPairedDevices.add(new String[] {btDeviceName, btDeviceMacAddress});
        bt_device_list_adapter.clear();
        bt_device_list_adapter.add(btDeviceName + " (" + btDeviceMacAddress + ")");
        bt_device_list.setSelection(0);

        bt_device_list.setVisibility(View.VISIBLE);
        update_bt_device_list.setVisibility(View.VISIBLE);

        save_settings.setVisibility(View.VISIBLE);
        reset_settings.setVisibility(View.VISIBLE);


    }

    public void hideSettingsScreen() {
        title_request_pause.setVisibility(View.INVISIBLE);
        input_request_pause.setVisibility(View.INVISIBLE);

        title_high_concentration.setVisibility(View.INVISIBLE);
        input_high_concentration.setVisibility(View.INVISIBLE);

        title_middle_concentration.setVisibility(View.INVISIBLE);
        input_middle_concentration.setVisibility(View.INVISIBLE);

        title_bt_device_list.setVisibility(View.INVISIBLE);
        bt_device_list.setVisibility(View.INVISIBLE);
        update_bt_device_list.setVisibility(View.INVISIBLE);
        
        save_settings.setVisibility(View.INVISIBLE);
        reset_settings.setVisibility(View.INVISIBLE);

    }

    public void showConfirmDialog() {
        set_zero.setVisibility(View.INVISIBLE);
        main_calibration.setVisibility(View.INVISIBLE);
        middle_calibration.setVisibility(View.INVISIBLE);
        set_defaults.setVisibility(View.INVISIBLE);
        
        confirm_dialog_title.setVisibility(View.VISIBLE);
        confirm_dialog_input.setVisibility(View.VISIBLE);
        confirm_dialog_ok.setVisibility(View.VISIBLE);
        confirm_dialog_cancel.setVisibility(View.VISIBLE);

        change_sensor_address.setEnabled(false);
        threshold_1.setEnabled(false);
        threshold_2.setEnabled(false);
    }

    public void hideConfirmDialog(String mode) {
        confirm_dialog_ok.setVisibility(View.INVISIBLE);
        confirm_dialog_cancel.setVisibility(View.INVISIBLE);
        confirm_dialog_input.setVisibility(View.INVISIBLE);
        confirm_dialog_title.setVisibility(View.INVISIBLE);
        
        if (mode == "ok") {
            set_zero.setEnabled(false);
            main_calibration.setEnabled(false);
            middle_calibration.setEnabled(false);
            set_defaults.setEnabled(false);
            
            change_sensor_address.setEnabled(false);
            threshold_1.setEnabled(false);
            threshold_2.setEnabled(false);
            
        } else if (mode == "cancel") {
            change_sensor_address.setEnabled(true);
            threshold_1.setEnabled(true);
            threshold_2.setEnabled(true);            
        }

        set_zero.setVisibility(View.VISIBLE);
        main_calibration.setVisibility(View.VISIBLE);
        middle_calibration.setVisibility(View.VISIBLE);
        set_defaults.setVisibility(View.VISIBLE);

        confirmDialogMode = ConfirmDialogModes.NONE;
    }

    // TODO rename to sensorConnectionCycle()
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
                myHandler.obtainMessage(SENSOR_CONNECTION_THREAD_DATA, connectionState).sendToTarget();
            }

            // ждём некоторое время
            try {
                Thread.sleep(requestPause);
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

    private byte getSensorAddress() {
        if (appMode == AppMode.SEARCH_SENSORS) {
            // В режиме поиска каждая команда будет с новым адресом датчика
            // Если дошли до конца диапазона поиска
            if (curAddressOfSearchRange > endAddressOfSearchRange) {
                // Если выбран чекбокс о поиске по кругу, 
                // то отдаём первый адрес диапазона и увеличиваем текущий.
                // todo: добавить эту проверку, когда будет чекбокс
                boolean checkbox = false; // temp!!!
                if (checkbox) {
                    curAddressOfSearchRange = startAddressOfSearchRange + 1;
                    curSensorAddress = startAddressOfSearchRange;
                } else {
                    // Иначе останавливаемся,
                    // сообщаем главному потоку, чтобы он остановил поиск (нажал Стоп)
                    myHandler.obtainMessage(SEARCH_SENSORS_DATA, "stop_search").sendToTarget();

                    // Чтобы не было ошибок, отдаём ещё раз тот же адрес
                    curSensorAddress = curAddressOfSearchRange - 1;
                }
            } else {
                // Иначе отдаём новый адрес и увеличиваем текущий в диапазоне
                curSensorAddress = curAddressOfSearchRange;
                curAddressOfSearchRange = curAddressOfSearchRange + 1;
            }
            // сообщаем главному потоку, чтобы он изменил текущий адрес на экране
            myHandler.obtainMessage(SEARCH_SENSORS_DATA, curSensorAddress).sendToTarget();
        }

        return (byte)curSensorAddress;
    }
    
    private void createRequest(Commands _commandFromButton) {
        byte sensorAddress = getSensorAddress();
        byte[] reqMsg = {};
        int concInt = 0;
        String concHex = "";
        byte[] concBytes = {};

        switch (_commandFromButton) {
            case NONE: // команды с кнопок нет, обычный запрос данных
            // обычно запрашиваем 0x0C (12) регистров, 
            // но если в режиме поиска, то достаточно и одного 
            // byte numRegisters = (byte)0x0C; // для СГОЭС
            byte numRegisters = (byte)0x0A; // для ГСО (у него ограничение запроса в 10 регистров)
            if (appMode == AppMode.SEARCH_SENSORS) {
                numRegisters = (byte)0x01;
            }
                reqMsg = new byte[] {
                        sensorAddress,
                        (byte)0x03, // funcCode
                        (byte)0x00, // firstRegAddressHigh
                        // (byte)0x00, // firstRegAddressLow
                        (byte)0x01, // firstRegAddressLow (для ГСО первый регистр не запрашиваем, пожертвуем им)
                        (byte)0x00, // numRegistersHigh
                        numRegisters  // numRegistersLow
                };
                break;
                
            case SET_ZERO: // установка нуля
                reqMsg = new byte[] {
                        sensorAddress,
                        (byte)0x06, // funcCode
                        (byte)0x00, // firstRegAddressHigh
                        (byte)0x01, // firstRegAddressLow
                        (byte)0x00, // dataHigh
                        (byte)0x00  // dataLow
                };
                break;
                
            case SET_DEFAULT_SETTINGS: // установка заводских значений
                reqMsg = new byte[] {
                        sensorAddress,
                        (byte)0x06, // funcCode
                        (byte)0x00, // firstRegAddressHigh
                        (byte)0x04, // firstRegAddressLow
                        (byte)0x00, // dataHigh
                        (byte)0x01  // dataLow
                };
                break;

            case CALIBRATION_HIGH: // калибровка по высокой смеси (основная)
                // Концентрация газа в объёмных % * 1000

                concInt = (int)(highConcentration * 1000);
                concHex = Integer.toHexString(concInt);
//                Log.d(LOG_TAG, "concHex: " + concHex);
                // если ввели маленькое значение, то дополняем спереди нулями
                if (concHex.length() < 4) {
                    int numNulls = 4 - concHex.length();
                    String nulls = new String(new char[numNulls]).replace("\0", "0");
                    concHex = nulls + concHex;
                }
                concBytes = hexStringToByteArray(concHex);
//                Log.d(LOG_TAG, "concBytes: " + bytesToHex(concBytes));

                reqMsg = new byte[] {
                        sensorAddress,
                        (byte)0x06, // funcCode
                        (byte)0x00, // firstRegAddressHigh
                        (byte)0x03, // firstRegAddressLow
                        concBytes[0], // dataHigh
                        concBytes[1]  // dataLow
                };
                break;

            case CALIBRATION_MIDDLE: // калибровка по средней смеси (дополнительная)
                // Концентрация газа в объёмных % * 1000

                concInt = (int)(middleConcentration * 1000);
                concHex = Integer.toHexString(concInt);
//                Log.d(LOG_TAG, "concHex: " + concHex);
                // если ввели маленькое значение, то дополняем спереди нулями
                if (concHex.length() < 4) {
                    int numNulls = 4 - concHex.length();
                    String nulls = new String(new char[numNulls]).replace("\0", "0");
                    concHex = nulls + concHex;
                }
                concBytes = hexStringToByteArray(concHex);
//                Log.d(LOG_TAG, "concBytes: " + bytesToHex(concBytes));

                reqMsg = new byte[] {
                        sensorAddress,
                        (byte)0x06, // funcCode
                        (byte)0x00, // firstRegAddressHigh
                        (byte)0x02, // firstRegAddressLow
                        concBytes[0], // dataHigh
                        concBytes[1]  // dataLow
                };
                break;

            case SET_THRESHOLD_1: // установка порога 1
                reqMsg = new byte[] {
                        sensorAddress,
                        (byte)0x06, // funcCode
                        (byte)0x00, // firstRegAddressHigh
                        (byte)0x05, // firstRegAddressLow
                        (byte)0x00, // dataHigh
                        (byte)newValueOfThreshold1  // dataLow
                };
                break;

            case SET_THRESHOLD_2: // установка порога 2
                reqMsg = new byte[] {
                        sensorAddress,
                        (byte)0x06, // funcCode
                        (byte)0x00, // firstRegAddressHigh
                        (byte)0x06, // firstRegAddressLow
                        (byte)0x00, // dataHigh
                        (byte)newValueOfThreshold2  // dataLow
                };
                break;

            case CHANGE_SENSOR_ADDRESS: // смена адреса датчика
                reqMsg = new byte[] {
                        sensorAddress,
                        (byte)0x06, // funcCode
                        (byte)0x00, // firstRegAddressHigh
                        (byte)0x00, // firstRegAddressLow
                        (byte)newSensorAddress, // dataHigh
                        (byte)0x08  // dataLow
                };
                break;
        }

        // сбрасываем глобальную команду
        commandFromButton = Commands.NONE;
        
        // считаем CRC
        byte[] reqCRC = calcCRC(reqMsg);

        // формируем общий массив запроса
        request = concatArray(reqMsg, reqCRC);

//        String outputHexString = "01030000000C45CF";
//        request = hexStringToByteArray(outputHexString);
//        Log.d(LOG_TAG, "outputHexString: " + outputHexString);
        Log.d(LOG_TAG, "request: " + bytesToHex(request));
    }

    private boolean checkInputAddress(String inputAddress, String mode) {
        // адрес должен быть в диапазоне 1..247

        // todo: заменить литералы на константы (min/max)
        
        // проверка на пустоту
        if (inputAddress.length() == 0) {
            Log.d(LOG_TAG, "inputAddress is empty");
            Toast.makeText(getApplicationContext(), "Введите адрес датчика от 1 до 247", Toast.LENGTH_LONG).show();
            return false;
        }
        
        int inputAddressInt = Integer.parseInt(inputAddress);
//        Log.d(LOG_TAG, "inputAddressInt: " + inputAddressInt);
        
        // проверка на 0
        if (inputAddressInt < 1) {
            Log.d(LOG_TAG, "inputAddressInt < 1");
            Toast.makeText(getApplicationContext(), "Адрес датчика может быть от 1 до 247", Toast.LENGTH_LONG).show();
            return false;
        }

        // сравнение с текущим значением (чтоб зря ресурс регистров не тратить)
        if (mode == "changing") {
            if (inputAddressInt == curSensorAddress) {
                Log.d(LOG_TAG, "inputAddressInt == curSensorAddress");
                Toast.makeText(getApplicationContext(), "Введённый адрес совпадает с текущим значением", Toast.LENGTH_LONG).show();
                return false;
            }
        }

        // сравнение с другой границей диапазона
        if (mode == "searchStart") {
            int inputSearchEndInt = Integer.parseInt(inputSearchEnd);
            if (inputAddressInt >= inputSearchEndInt) {
                Log.d(LOG_TAG, "inputSearchStart >= inputSearchEnd");
                Toast.makeText(getApplicationContext(), "Адрес начала поиска должен быть меньше адреса окончания", Toast.LENGTH_LONG).show();
                return false;
            }
        } else if (mode == "searchEnd") {
            int inputSearchStartInt = Integer.parseInt(inputSearchStart);
            if (inputAddressInt <= inputSearchStartInt) {
                Log.d(LOG_TAG, "inputSearchStart <= inputSearchStart");
                Toast.makeText(getApplicationContext(), "Адрес окончания поиска должен быть больше адреса начала", Toast.LENGTH_LONG).show();
                return false;
            }
        } 
        
        // проверка на максимальное значение
        if (inputAddressInt > 247) {
            Log.d(LOG_TAG, "inputAddressInt > 247");
            Toast.makeText(getApplicationContext(), "Адрес датчика может быть от 1 до 247", Toast.LENGTH_LONG).show();
            return false;
        }

        return true;
    }
    
    private boolean checkInputConcentration(String inputConcentration, String level) {
        // проверка на пустоту
        if (inputConcentration.length() == 0) {
            Log.d(LOG_TAG, "inputConcentration is empty");
            Toast.makeText(getApplicationContext(), "Введите концентрацию газа в объёмных %", Toast.LENGTH_LONG).show();
            return false;
        }

        float inputConcentrationFloat = Float.parseFloat(inputConcentration);
//        Log.d(LOG_TAG, "inputConcentrationFloat: " + inputConcentrationFloat);
        
        // проверка на минимальное значение
        if (inputConcentrationFloat <= PGS_CONCENTRATION_MIN) {
            Log.d(LOG_TAG, "inputConcentrationFloat <= PGS_CONCENTRATION_MIN");
            Toast.makeText(getApplicationContext(), "Концентрация должна быть больше " + Float.toString(PGS_CONCENTRATION_MIN), Toast.LENGTH_LONG).show();
            return false;
        }
        
        // сравнение с другой концентрацией
        if (level == "middle") {
            if (inputConcentrationFloat >= highConcentration) {
                Log.d(LOG_TAG, "inputConcentrationFloat >= highConcentration");
                Toast.makeText(getApplicationContext(), "Средняя концентрация должна быть меньше высокой", Toast.LENGTH_LONG).show();
                return false;                
            }
        } else if (level == "high") {
            if (inputConcentrationFloat <= middleConcentration) {
                Log.d(LOG_TAG, "inputConcentrationFloat <= middleConcentration");
                Toast.makeText(getApplicationContext(), "Высокая концентрация должна быть больше средней", Toast.LENGTH_LONG).show();
                return false;
            }
        }
        
        // проверка на максимальное значение
        if (inputConcentrationFloat >= PGS_CONCENTRATION_MAX) {
            Log.d(LOG_TAG, "inputConcentrationFloat >= PGS_CONCENTRATION_MAX");
            Toast.makeText(getApplicationContext(), "Концентрация должна быть меньше " + Float.toString(PGS_CONCENTRATION_MAX), Toast.LENGTH_LONG).show();
            return false;
        }
        
        return true;
    }

    private boolean checkInputThreshold(String inputThresholdValue, int threshold) {

        // todo: заменить литералы на константы (min/max)    
    
        // проверка на пустоту
        if (inputThresholdValue.length() == 0) {
            Log.d(LOG_TAG, "confirm_dialog_input is empty");
            Toast.makeText(getApplicationContext(), "Введите величину порога в % НКПР", Toast.LENGTH_LONG).show();
            return false;
        }

        int inputThresholdValueInt = Integer.parseInt(inputThresholdValue);
//        Log.d(LOG_TAG, "inputThresholdValueInt: " + inputThresholdValueInt);

        // сравнение с текущим значением (чтоб зря ресурс регистров не тратить)
        int curThresholdValue = 0;
        if (threshold == 1) {
            curThresholdValue = curValueOfThreshold1;
        } else if (threshold == 2) {
            curThresholdValue = curValueOfThreshold2;
        }
        if (inputThresholdValueInt == curThresholdValue) {
            Log.d(LOG_TAG, "confirm_dialog_input == curThresholdValue");
            Toast.makeText(getApplicationContext(), "Введённая величина порога совпадает с текущим значением", Toast.LENGTH_LONG).show();
            return false;
        }

        // проверка на 0
        if (inputThresholdValueInt == 0) {
            Log.d(LOG_TAG, "confirm_dialog_input == 0");
            Toast.makeText(getApplicationContext(), "Величина порога должна быть больше 0", Toast.LENGTH_LONG).show();
            return false;
        }

        // сравнение с другим порогом
        if (threshold == 1) {
            if (inputThresholdValueInt > curValueOfThreshold2) {
                Log.d(LOG_TAG, "confirm_dialog_input > curValueOfThreshold2");
                Toast.makeText(getApplicationContext(), "Порог 1 не должен быть больше порога 2", Toast.LENGTH_LONG).show();
                return false;
            }
        } else if (threshold == 2) {
            if (inputThresholdValueInt < curValueOfThreshold1) {
                Log.d(LOG_TAG, "confirm_dialog_input < curValueOfThreshold1");
                Toast.makeText(getApplicationContext(), "Порог 2 не должен быть меньше порога 1", Toast.LENGTH_LONG).show();
                return false;
            }
        }

        // проверка на максимальное значение (>100)
        if (inputThresholdValueInt > 100) {
            Log.d(LOG_TAG, "confirm_dialog_input > 100");
            Toast.makeText(getApplicationContext(), "Величина порога не должна быть больше 100", Toast.LENGTH_LONG).show();
            return false;
        }

        return true;
    }

    private boolean checkInputRequestPause(String inputValue) {

        // проверка на пустоту
        if (inputValue.length() == 0) {
            Log.d(LOG_TAG, "input_request_pause is empty");
            Toast.makeText(getApplicationContext(), "Введите величину паузы между запросами (от 2000 до 20000)", Toast.LENGTH_LONG).show();
            return false;
        }

        int inputValueInt = Integer.parseInt(inputValue);
        // Log.d(LOG_TAG, "inputValueInt: " + inputValueInt);
        
        // проверка на минимальное значение
        if (inputValueInt < REQUEST_PAUSE_MIN) {
            Log.d(LOG_TAG, "input_request_pause < REQUEST_PAUSE_MIN");
            Toast.makeText(getApplicationContext(), "Пауза не должна быть меньше " + Integer.toString(REQUEST_PAUSE_MIN), Toast.LENGTH_LONG).show();
            return false;
        }
        
        // проверка на максимальное значение
        if (inputValueInt > REQUEST_PAUSE_MAX) {
            Log.d(LOG_TAG, "input_request_pause > REQUEST_PAUSE_MAX");
            Toast.makeText(getApplicationContext(), "Пауза не должна быть больше " + Integer.toString(REQUEST_PAUSE_MAX), Toast.LENGTH_LONG).show();
            return false;
        }

        return true;
    }

    @Override
    public void onDestroy() {
        closeAllConnections();
        Log.d(LOG_TAG, "onDestroy()");
        super.onDestroy();
    }

    private void myError(String title, String message) {
//        Toast.makeText(getBaseContext(), title + " - " + message, Toast.LENGTH_LONG).show();
        Log.d(LOG_TAG, title + " - " + message);
        finish();
    }

    private void closeAllConnections() {
        if (sensorConnectionThread != null) {
            Thread dummy = sensorConnectionThread;
            sensorConnectionThread = null;
            dummy.interrupt();
        }

        if (btSocketConnectionThread != null) {
            Thread dummy = btSocketConnectionThread;
            btSocketConnectionThread = null;
            dummy.interrupt();
        }

        if (myThread != null) {
            if (myThread.status_outStream() != null) {
                myThread.cancel();
            }
        }

        if (btSocket != null) {
            try {
                btSocket.close();
                Log.d(LOG_TAG, "socket is closed");
//                Toast.makeText(getApplicationContext(), "socket is closed", Toast.LENGTH_SHORT).show();
            } catch (IOException e2) {
                myError("Fatal Error", "onDestroy(): Can't close a socket:" + e2.getMessage() + ".");
            }
        }

    }
}

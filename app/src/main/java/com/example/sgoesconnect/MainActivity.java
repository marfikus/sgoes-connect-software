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

    // from: https://gist.github.com/liuyork/6978474
    // liuyork
    private class CRC16Modbus implements Checksum {
        private final int[] TABLE = {
                0x0000, 0xc0c1, 0xc181, 0x0140, 0xc301, 0x03c0, 0x0280, 0xc241,
                0xc601, 0x06c0, 0x0780, 0xc741, 0x0500, 0xc5c1, 0xc481, 0x0440,
                0xcc01, 0x0cc0, 0x0d80, 0xcd41, 0x0f00, 0xcfc1, 0xce81, 0x0e40,
                0x0a00, 0xcac1, 0xcb81, 0x0b40, 0xc901, 0x09c0, 0x0880, 0xc841,
                0xd801, 0x18c0, 0x1980, 0xd941, 0x1b00, 0xdbc1, 0xda81, 0x1a40,
                0x1e00, 0xdec1, 0xdf81, 0x1f40, 0xdd01, 0x1dc0, 0x1c80, 0xdc41,
                0x1400, 0xd4c1, 0xd581, 0x1540, 0xd701, 0x17c0, 0x1680, 0xd641,
                0xd201, 0x12c0, 0x1380, 0xd341, 0x1100, 0xd1c1, 0xd081, 0x1040,
                0xf001, 0x30c0, 0x3180, 0xf141, 0x3300, 0xf3c1, 0xf281, 0x3240,
                0x3600, 0xf6c1, 0xf781, 0x3740, 0xf501, 0x35c0, 0x3480, 0xf441,
                0x3c00, 0xfcc1, 0xfd81, 0x3d40, 0xff01, 0x3fc0, 0x3e80, 0xfe41,
                0xfa01, 0x3ac0, 0x3b80, 0xfb41, 0x3900, 0xf9c1, 0xf881, 0x3840,
                0x2800, 0xe8c1, 0xe981, 0x2940, 0xeb01, 0x2bc0, 0x2a80, 0xea41,
                0xee01, 0x2ec0, 0x2f80, 0xef41, 0x2d00, 0xedc1, 0xec81, 0x2c40,
                0xe401, 0x24c0, 0x2580, 0xe541, 0x2700, 0xe7c1, 0xe681, 0x2640,
                0x2200, 0xe2c1, 0xe381, 0x2340, 0xe101, 0x21c0, 0x2080, 0xe041,
                0xa001, 0x60c0, 0x6180, 0xa141, 0x6300, 0xa3c1, 0xa281, 0x6240,
                0x6600, 0xa6c1, 0xa781, 0x6740, 0xa501, 0x65c0, 0x6480, 0xa441,
                0x6c00, 0xacc1, 0xad81, 0x6d40, 0xaf01, 0x6fc0, 0x6e80, 0xae41,
                0xaa01, 0x6ac0, 0x6b80, 0xab41, 0x6900, 0xa9c1, 0xa881, 0x6840,
                0x7800, 0xb8c1, 0xb981, 0x7940, 0xbb01, 0x7bc0, 0x7a80, 0xba41,
                0xbe01, 0x7ec0, 0x7f80, 0xbf41, 0x7d00, 0xbdc1, 0xbc81, 0x7c40,
                0xb401, 0x74c0, 0x7580, 0xb541, 0x7700, 0xb7c1, 0xb681, 0x7640,
                0x7200, 0xb2c1, 0xb381, 0x7340, 0xb101, 0x71c0, 0x7080, 0xb041,
                0x5000, 0x90c1, 0x9181, 0x5140, 0x9301, 0x53c0, 0x5280, 0x9241,
                0x9601, 0x56c0, 0x5780, 0x9741, 0x5500, 0x95c1, 0x9481, 0x5440,
                0x9c01, 0x5cc0, 0x5d80, 0x9d41, 0x5f00, 0x9fc1, 0x9e81, 0x5e40,
                0x5a00, 0x9ac1, 0x9b81, 0x5b40, 0x9901, 0x59c0, 0x5880, 0x9841,
                0x8801, 0x48c0, 0x4980, 0x8941, 0x4b00, 0x8bc1, 0x8a81, 0x4a40,
                0x4e00, 0x8ec1, 0x8f81, 0x4f40, 0x8d01, 0x4dc0, 0x4c80, 0x8c41,
                0x4400, 0x84c1, 0x8581, 0x4540, 0x8701, 0x47c0, 0x4680, 0x8641,
                0x8201, 0x42c0, 0x4380, 0x8341, 0x4100, 0x81c1, 0x8081, 0x4040
        };


        private int sum = 0xFFFF;

        public long getValue() {
            return sum;
        }

        public void reset() {
            sum = 0xFFFF;
        }

        public void update(byte[] b, int off, int len) {
            for (int i = off; i < off + len; i++)
                update((int) b[i]);
        }

        public void update(int b) {
            sum = (sum >> 8) ^ TABLE[((sum) ^ (b & 0xff)) & 0xff];
        }

        public byte[] getCrcBytes() {
            long crc = (int) this.getValue();
            byte[] byteStr = new byte[2];
            byteStr[0] = (byte) ((crc & 0x000000ff));
            byteStr[1] = (byte) ((crc & 0x0000ff00) >>> 8);
            return byteStr;
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

        // сравниваем последние 2 байта ответа с тем, что вычислит CRC16Modbus
        //CRC16Modbus crc = new CRC16Modbus();
        crc.update(respMsg, 0, respMsg.length);
        //Log.d(LOG_TAG, "crc: " + bytesToHex(crc.getCrcBytes()));

        Log.d(LOG_TAG, "response: " + bytesToHex(response));

        if (!respCRC.equals(crc.getCrcBytes())) {
            Log.d(LOG_TAG, bytesToHex(respCRC) + " != " + bytesToHex(crc.getCrcBytes()));
            return;
        }

//        Log.d(LOG_TAG, "respCRC: " + bytesToHex(respCRC));
//        Log.d(LOG_TAG, "myCRC: " + bytesToHex(myCRC(respMsg, respMsg.length)));
//        Log.d(LOG_TAG, "calcCRC: " + bytesToHex(calcCRC(respMsg)));

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
//          вычисляем адрес регистра относительно адреса первого регистра из запроса
            curRegAddress = reqFirstRegAddress + i;
            Log.d(LOG_TAG, "curRegAddress: " + curRegAddress);
            curBytePos = curBytePos + 2;
            Log.d(LOG_TAG, "curBytePos: " + curBytePos);
            curRegDataFull = 0;
            curRegDataHighByte = 0;
            curRegDataLowByte = 0;
//          если адрес регистра такой-то:
            switch (curRegAddress) {
                case 0: // старший байт: адрес устройства, младший: скорость обмена

//                  берём в ответе соответсвующие 2 байта
//                  преобразовываем их в соответсвии со спецификацией!
                    curRegDataHighByte = response[curBytePos] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataHighByte: " + curRegDataHighByte);
                    curRegDataLowByte = response[curBytePos + 1] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataLowByte: " + curRegDataLowByte);
//                  выводим в соответсвующее поле
//                    gas_level_nkpr.setText(Integer.toString(curRegDataFull));
                    break;

                case 1: // старший байт: тип прибора, младший: флаги состояния
                    curRegDataHighByte = response[curBytePos] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataHighByte: " + curRegDataHighByte);
                    // TODO: 12.04.2020 флаги состояния надо расписать...
                    curRegDataLowByte = response[curBytePos + 1] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataLowByte: " + curRegDataLowByte);

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
                    break;

                case 2: // концентрация измеряемого газа в % НКПР (целое знаковое)
                    curRegDataFull = ((response[curBytePos] & 0xFF) << 8) | (response[curBytePos + 1] & 0xFF);
                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);
                    gas_level_nkpr.setText(Integer.toString(curRegDataFull));
                    break;

                case 3: // старший байт: порог 1, младший: порог 2
                    curRegDataHighByte = response[curBytePos] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataHighByte: " + curRegDataHighByte);
                    curRegDataLowByte = response[curBytePos + 1] & 0xFF;
                    Log.d(LOG_TAG, "curRegDataLowByte: " + curRegDataLowByte);

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
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

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
                    break;

                case 10: // концентрация измеряемого газа в % НКПР * 10 (целое знаковое)
                    // TODO: 12.04.2020 знаковое\беззнаковое. Возможно иначе надо преобразовывать...
                    curRegDataFull = ((response[curBytePos] & 0xFF) << 8) | (response[curBytePos + 1] & 0xFF);
                    Log.d(LOG_TAG, "curRegDataFull: " + curRegDataFull);

//                    gas_level_nkpr.setText(Integer.toString(curRegDataHighByte));
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

    // пока не используется, может и не пригодится
    private byte[] calcCRC(byte[] msg) {
        char accumulator = 0xFFFF;
        int flag;
        byte CRCHigh, CRCLow;

        for (int i = 0; i < msg.length; i++) {
            accumulator = (char) (msg[i] ^ accumulator);

            for (int j = 0; j < 8; j++) {

                if ((accumulator & 0x0001) == 1) {
                    accumulator = (char)(accumulator >> 1);
                    accumulator = (char)(accumulator ^ 0xA001);
                } else {
                    accumulator = (char)(accumulator >> 1);
                }
            }
        }
        //accumulator = (char)(accumulator % 0xFFFF);
        CRCLow = (byte)(accumulator & 0xFFFF);
        CRCHigh = (byte)((accumulator >> 8) & 0xFFFF);
        //0000000000000000
        return new byte[] {CRCLow, CRCHigh};
    }

    // пока не используется, может и не пригодится
    // from: https://www.cyberforum.ru/java-j2se/thread2494525.html
    // kolyasoul
    private byte[] myCRC(byte[] message, int length) {
        byte CRCHigh, CRCLow;
        char CRCFull = (char) 0xFFFF;

        for (int i = 0; i < length; i++) {
            CRCFull = (char)(CRCFull ^ message[i]);

            for (int j = 0; j < 8; j++) {
                if ((CRCFull & 0x0001) == 1) {
                    CRCFull = (char) ((CRCFull >> 1) ^ 0xA001);
                } else {
                    CRCFull = (char) (CRCFull >> 1);
                }
            }
        }
//        Log.d(LOG_TAG, "CRCFull: " + CRCFull);
//        CRCHigh = (byte)((CRCFull >> 8) & 0xFFFF);
//        CRCLow = (byte)(CRCFull & 0x0000FFFF);

        byte[] byteStr = new byte[2];
        byteStr[0] = (byte) ((CRCFull & 0x000000ff));
        byteStr[1] = (byte) ((CRCFull & 0x0000ff00) >>> 8);

//        return new byte[] {CRCLow, CRCHigh};
        return byteStr;
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
    final int arduinoData = 1; // TODO: 08.04.2020 константа заглавными буквами
    byte[] request; // текущий запрос
    byte[] response; // текущий ответ
    int numResponseBytes = 0;  // счётчик байт, полученных в текущем ответе
    CRC16Modbus crc = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(LOG_TAG, "ready");


//        byte[] respMsg = hexStringToByteArray("0103030105A0");
//        Log.d(LOG_TAG, "calcCRC: " + bytesToHex(calcCRC(respMsg)));

        // todo: пока здесь оставлю, потом может перенести, чтобы создавался только при необходимости
        // todo: в метод создания запроса напрмиер перенести
        crc = new CRC16Modbus();
//        crc.update(respMsg, 0, respMsg.length);
//        Log.d(LOG_TAG, "crc: " + bytesToHex(crc.getCrcBytes()));


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
//                String outputHexString = "010300000001840A";
//                String outputHexString = "010300010001840A";
//                String outputHexString = "010300000002840A";
                String outputHexString = "01030000000C840A";
                request = hexStringToByteArray(outputHexString);
                Log.d(LOG_TAG, "outputHexString: " + outputHexString);

                //sensor_address = (EditText) findViewById(R.id.sensor_address);
                //byte[] data = hexStringToByteArray(sensor_address.getText().toString());

                myThread.sendData(request);
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
                        //Log.d(LOG_TAG, "response:" + bytesToHex(response) + "\n ");
//                        Log.d(LOG_TAG, "=================================");

                        checkResponse();

//                        gas_level_nkpr.setText(bytesToHex(response));
                        break;
                }
            }
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

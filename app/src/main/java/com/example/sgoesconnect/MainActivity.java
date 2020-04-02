package com.example.sgoesconnect;

import androidx.appcompat.app.AppCompatActivity;
import android.bluetooth.*;

import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_ENABLE_BT = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter != null) {
            Toast.makeText(getApplicationContext(), "bluetooth adapter is detected", Toast.LENGTH_SHORT).show();

            if (bluetoothAdapter.isEnabled()) {
                Toast.makeText(getApplicationContext(), "bluetooth is enabled", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getApplicationContext(), "bluetooth is disabled", Toast.LENGTH_SHORT).show();
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
        } else {
            Toast.makeText(getApplicationContext(), "bluetooth adapter is not detected", Toast.LENGTH_SHORT).show();
        }


    }
}

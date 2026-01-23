package me.palmarci.pumpconnector;

import android.os.Bundle;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import me.palmarci.pumpconnector.BlePeripheralDevice;


public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    BlePeripheralDevice ble;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        this.ble = new BlePeripheralDevice(MainActivity.this);

        Button btn = (Button) findViewById(R.id.start_gatt);
        btn.setOnClickListener(MainActivity.this);

    }

    @Override
    public void onClick(View v) {

        this.ble.requestBluetoothPermissions();

        if (this.ble.hasBluetoothPermissions()) {
            this.ble.stop();
            this.ble.start();
            Toast.makeText(this, "Peripheral started!", Toast.LENGTH_SHORT).show();
        }

    }

}
package org.openminimed.pumpconnector;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    BlePeripheralDevice ble;

    private EditText txt = null; // need this here for access in onClick


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.main),
                (v, insets) -> {
                    Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(
                            systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
                    return insets;
                });

        this.ble = new BlePeripheralDevice(MainActivity.this);


        // Some ui setup
        // Ui element
        Button btn = (Button) findViewById(R.id.start_gatt);
        btn.setOnClickListener(MainActivity.this);

        txt = (EditText) findViewById(R.id.mobile_name);
        txt.setText(ble.MOBILE_NAME.substring(7)); // set text to the current Mobile name number in BlePeripheralDevice
    }

    @Override
    public void onClick(View v) {

        this.ble.requestBluetoothPermissions();

        if (this.ble.hasBluetoothPermissions()) {
            this.ble.stop();



            String MOBILE_NAME;
            if (!txt.getText().toString().isBlank()) {

                MOBILE_NAME = "Mobile " + (txt.getText().toString() + "       ").substring(0, 7);
                ble.MOBILE_NAME = MOBILE_NAME;

                Log.e("MobileNameChanger", "Mobile name changed to : " + MOBILE_NAME);
            }

            else {Log.e("MobileNameChanger", "Did not changed mobile name because the name was blank");}



            this.ble.start();
            Toast.makeText(this, "Peripheral started!", Toast.LENGTH_SHORT).show();
        }
    }
}

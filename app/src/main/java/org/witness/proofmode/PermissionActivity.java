package org.witness.proofmode;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;

import java.util.ArrayList;

public class PermissionActivity extends AppCompatActivity {

    public static final String ARG_PERMISSIONS = "permissions";
    public static final String ARG_LAYOUT_ID = "layout_id";
    public static final String ARG_BUTTON_CONTINUE_ID = "button_continue_id";
    public static final String ARG_BUTTON_CLOSE_ID = "button_close_id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSupportActionBar().hide();

        int layoutId = getIntent().getIntExtra(ARG_LAYOUT_ID, 0);
        int buttonContinueId = getIntent().getIntExtra(ARG_BUTTON_CONTINUE_ID, R.id.btnContinue);
        int buttonCloseId = getIntent().getIntExtra(ARG_BUTTON_CLOSE_ID, R.id.btnClose);
        if (layoutId != 0 && buttonContinueId != 0 && buttonCloseId != 0) {
            setContentView(layoutId);
            Button btn = findViewById(buttonContinueId);
            if (btn != null) {
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String[] permissions = getIntent().getStringArrayExtra(ARG_PERMISSIONS);
                        String[] missingPermissions = missingPermissions(PermissionActivity.this, permissions);
                        if (missingPermissions == null) {
                            finish();
                        } else {
                            ActivityCompat.requestPermissions(PermissionActivity.this, missingPermissions, 1);
                        }
                    }
                });
            }

            //Close
            ImageButton btnClose = findViewById(buttonCloseId);
            if (btnClose != null) {
                btnClose.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finish();
                    }
                });
            }
        } else {
            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        String[] wantedPermissions = getIntent().getStringArrayExtra(ARG_PERMISSIONS);
        if (missingPermissions(this, wantedPermissions) == null) {
            finish(); // Done!
        }
    }

    /**
     * Given a set of permissions, return those that we don't have permission for.
     * @param permissions
     * @return
     */
    private static String[] missingPermissions(Context context, String[] permissions) {
        if (Build.VERSION.SDK_INT <= 18) {
            return null;
        }

        ArrayList<String> missingPermissions = new ArrayList<>();
        for (String permission : permissions) {
            int permissionCheck = ContextCompat.checkSelfPermission(context, permission);
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }
        if (missingPermissions.size() > 0) {
            return missingPermissions.toArray(new String[0]);
        }
        return null;
    }

    public static boolean hasPermissions(Context context, String[] permissions) {
        String[] missingPermissions = missingPermissions(context, permissions);
        if (missingPermissions != null) {
            return false;
        }
        return true;
    }
}

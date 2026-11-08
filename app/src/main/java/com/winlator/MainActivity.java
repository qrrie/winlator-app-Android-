package com.winlator;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.contentdialog.AboutDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.Callback;
import com.winlator.core.LocaleHelper;
import com.winlator.core.PreloaderDialog;
import com.winlator.usbbridge.UsbBridgeProbe;
import com.winlator.xenvironment.RootFSInstaller;

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener {

    public static final boolean DEBUG_MODE = false;
    public static final @IntRange(from = 1, to = 19)
            byte CONTAINER_PATTERN_COMPRESSION_LEVEL = 9;

    public static final byte
            PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 1;
    public static final byte OPEN_FILE_REQUEST_CODE = 2;
    public static final byte EDIT_INPUT_CONTROLS_REQUEST_CODE = 3;
    public static final byte OPEN_DIRECTORY_REQUEST_CODE = 4;

    private static final int USB_VENDOR_ID = 0x0403;
    private static final int USB_PRODUCT_ID = 0xFA24;

    private static final String ACTION_USB_PERMISSION =
            "com.winlator.USB_BRIDGE_PERMISSION";

    private DrawerLayout drawerLayout;
    public final PreloaderDialog preloaderDialog =
            new PreloaderDialog(this);

    private boolean editInputControls = false;
    private int selectedProfileId;
    private Callback<Uri> openFileCallback;
    private SharedPreferences preferences;
    private Fragment currentFragment;

    private BroadcastReceiver usbPermissionReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        drawerLayout = findViewById(R.id.DrawerLayout);

        NavigationView navigationView =
                findViewById(R.id.NavigationView);

        navigationView.setNavigationItemSelectedListener(this);

        // Add the test option without editing menu XML.
        navigationView.getMenu().add(
                0,
                R.id.menu_item_usb_test,
                100,
                "Test VCDS USB Cable"
        ).setCheckable(false);

        registerUsbPermissionReceiver();

        setSupportActionBar(findViewById(R.id.Toolbar));
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);

        preferences =
                PreferenceManager.getDefaultSharedPreferences(this);

        Intent intent = getIntent();

        editInputControls =
                intent.getBooleanExtra("edit_input_controls", false);

        if (editInputControls) {
            selectedProfileId =
                    intent.getIntExtra("selected_profile_id", 0);

            actionBar.setHomeAsUpIndicator(
                    R.drawable.icon_action_bar_back
            );

            onNavigationItemSelected(
                    navigationView.getMenu().findItem(
                            R.id.menu_item_input_controls
                    )
            );

            navigationView.setCheckedItem(
                    R.id.menu_item_input_controls
            );
        } else {
            boolean showShortcutsFirst =
                    preferences.getBoolean(
                            "show_shortcuts_first", false
                    );

            int selectedMenuItemId =
                    intent.getIntExtra("selected_menu_item_id", 0);

            int menuItemId = selectedMenuItemId > 0
                    ? selectedMenuItemId
                    : (showShortcutsFirst
                    ? R.id.menu_item_shortcuts
                    : R.id.menu_item_containers);

            actionBar.setHomeAsUpIndicator(
                    R.drawable.icon_action_bar_menu
            );

            onNavigationItemSelected(
                    navigationView.getMenu().findItem(menuItemId)
            );

            navigationView.setCheckedItem(menuItemId);

            if (!requestAppPermissions()) {
                RootFSInstaller.installIfNeeded(this);
            }

            int containerId =
                    intent.getIntExtra("container_id", 0);

            String startPath =
                    intent.getStringExtra("start_path");

            if (containerId > 0 && startPath != null) {
                showFragment(
                        new ContainerFileManagerFragment(
                                containerId, startPath
                        )
                );
            }
        }
    }

    private void registerUsbPermissionReceiver() {
        usbPermissionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_USB_PERMISSION.equals(
                        intent.getAction())) {
                    return;
                }

                boolean granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED,
                        false
                );

                if (granted) {
                    showUsbResult(UsbBridgeProbe.test(
                            MainActivity.this
                    ));
                } else {
                    showUsbResult(
                            "Android USB permission was denied."
                    );
                }
            }
        };

        IntentFilter filter =
                new IntentFilter(ACTION_USB_PERMISSION);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                    usbPermissionReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            registerReceiver(usbPermissionReceiver, filter);
        }
    }

    private void testUsbCable() {
        UsbManager manager =
                (UsbManager) getSystemService(
                        Context.USB_SERVICE
                );

        if (manager == null) {
            showUsbResult("USB manager unavailable.");
            return;
        }

        UsbDevice target = null;

        for (UsbDevice device :
                manager.getDeviceList().values()) {

            if (device.getVendorId() == USB_VENDOR_ID &&
                    device.getProductId() == USB_PRODUCT_ID) {
                target = device;
                break;
            }
        }

        if (target == null) {
            showUsbResult(
                    "VCDS cable not detected.\n\n" +
                    "Expected USB ID: 0403:FA24\n" +
                    "Check the OTG adapter and cable."
            );
            return;
        }

        if (manager.hasPermission(target)) {
            showUsbResult(UsbBridgeProbe.test(this));
            return;
        }

        Intent permissionIntent =
                new Intent(ACTION_USB_PERMISSION);
        permissionIntent.setPackage(getPackageName());

        PendingIntent pendingIntent =
                PendingIntent.getBroadcast(
                        this,
                        0,
                        permissionIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT |
                        PendingIntent.FLAG_MUTABLE
                );

        manager.requestPermission(target, pendingIntent);

        showUsbResult(
                "Cable detected: 0403:FA24\n\n" +
                "Android USB permission requested.\n" +
                "Approve the permission dialog."
        );
    }

    private void showUsbResult(String result) {
        if (isFinishing() || isDestroyed()) return;

        new AlertDialog.Builder(this)
                .setTitle("VCDS USB Cable Test")
                .setMessage(result)
                .setPositiveButton("OK", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        if (usbPermissionReceiver != null) {
            unregisterReceiver(usbPermissionReceiver);
            usbPermissionReceiver = null;
        }
        super.onDestroy();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(
                LocaleHelper.setSystemLocale(newBase)
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode, permissions, grantResults
        );

        if (requestCode ==
                PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE) {

            if (grantResults.length > 0 &&
                    grantResults[0] ==
                            PackageManager.PERMISSION_GRANTED) {

                RootFSInstaller.installIfNeeded(this);
            } else {
                finish();
            }
        }
    }

    @Override
    public void onActivityResult(
            int requestCode,
            int resultCode,
            @Nullable Intent data) {

        super.onActivityResult(
                requestCode, resultCode, data
        );

        if (requestCode == OPEN_FILE_REQUEST_CODE &&
                resultCode == Activity.RESULT_OK &&
                data != null) {

            if (openFileCallback != null) {
                openFileCallback.call(data.getData());
                openFileCallback = null;
            }
        }
    }

    @Override
    public void onConfigurationChanged(
            @NonNull Configuration newConfig) {

        super.onConfigurationChanged(newConfig);

        if ((newConfig.orientation ==
                Configuration.ORIENTATION_LANDSCAPE ||
                newConfig.orientation ==
                Configuration.ORIENTATION_PORTRAIT) &&
                currentFragment instanceof BaseFileManagerFragment) {

            ((BaseFileManagerFragment) currentFragment)
                    .onOrientationChanged();
        }
    }

    @Override
    public void onBackPressed() {
        if (currentFragment != null &&
                currentFragment.isVisible()) {

            if (currentFragment instanceof BaseFileManagerFragment) {
                BaseFileManagerFragment fileManagerFragment =
                        (BaseFileManagerFragment) currentFragment;

                if (fileManagerFragment.onBackPressed()) return;
            } else if (currentFragment instanceof ContainersFragment) {
                finish();
            }
        }

        showFragment(new ContainersFragment());
    }

    public void setOpenFileCallback(
            Callback<Uri> openFileCallback) {
        this.openFileCallback = openFileCallback;
    }

    private boolean requestAppPermissions() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                        this, Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        String[] permissions = new String[]{
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
        };

        ActivityCompat.requestPermissions(
                this,
                permissions,
                PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE
        );

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        int itemId = menuItem.getItemId();

        if (itemId == R.id.menu_item_add ||
                itemId == R.id.menu_item_home ||
                itemId == R.id.menu_item_view_style ||
                itemId == R.id.menu_item_new_folder) {

            return super.onOptionsItemSelected(menuItem);
        } else {
            if (editInputControls) {
                setResult(RESULT_OK);
                finish();
            } else {
                if (currentFragment instanceof BaseFileManagerFragment) {
                    BaseFileManagerFragment fileManagerFragment =
                            (BaseFileManagerFragment) currentFragment;

                    if (fileManagerFragment.onOptionsMenuClicked()) {
                        return true;
                    }
                }

                drawerLayout.openDrawer(GravityCompat.START);
            }

            return true;
        }
    }

    @Override
    public boolean onNavigationItemSelected(
            @NonNull MenuItem item) {

        if (item.getItemId() == R.id.menu_item_usb_test) {
            drawerLayout.closeDrawer(GravityCompat.START);
            testUsbCable();
            return true;
        }

        FragmentManager fragmentManager =
                getSupportFragmentManager();

        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack(
                    null,
                    FragmentManager.POP_BACK_STACK_INCLUSIVE
            );
        }

        switch (item.getItemId()) {
            case R.id.menu_item_shortcuts:
                preferences.edit()
                        .putBoolean("show_shortcuts_first", true)
                        .apply();
                showFragment(new ShortcutsFragment());
                break;

            case R.id.menu_item_containers:
                preferences.edit()
                        .putBoolean("show_shortcuts_first", false)
                        .apply();
                showFragment(new ContainersFragment());
                break;

            case R.id.menu_item_input_controls:
                showFragment(
                        new InputControlsFragment(selectedProfileId)
                );
                break;

            case R.id.menu_item_settings:
                showFragment(new SettingsFragment());
                break;

            case R.id.menu_item_about:
                (new AboutDialog(this)).show();
                break;
        }

        return true;
    }

    public void showFragment(Fragment fragment) {
        FragmentManager fragmentManager =
                getSupportFragmentManager();

        fragmentManager.beginTransaction()
                .replace(R.id.FLFragmentContainer, fragment)
                .commit();

        drawerLayout.closeDrawer(GravityCompat.START);
        currentFragment = fragment;
    }
}

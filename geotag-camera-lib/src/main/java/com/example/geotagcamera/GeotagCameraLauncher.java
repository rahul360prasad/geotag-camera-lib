package com.example.geotagcamera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Public API for consuming apps to launch the GeotagCamera library.
 * Shows a "Camera / Gallery" picker dialog on the calling activity's screen.
 *
 * - Camera  → launches CustomCameraActivity (full camera flow)
 * - Gallery → opens the system gallery picker directly on the home screen
 *
 * Usage:
 *   GeotagCameraLauncher launcher = new GeotagCameraLauncher(this);
 *   launcher.launch();
 *
 * In the calling Activity, forward these two callbacks:
 *   @Override
 *   public void onRequestPermissionsResult(int rc, String[] p, int[] g) {
 *       super.onRequestPermissionsResult(rc, p, g);
 *       if (launcher != null) launcher.onRequestPermissionsResult(rc, p, g);
 *   }
 *   @Override
 *   protected void onActivityResult(int rc, int res, Intent data) {
 *       super.onActivityResult(rc, res, data);
 *       if (launcher != null) launcher.onActivityResult(rc, res, data);
 *   }
 */
public class GeotagCameraLauncher {

    public static final int REQUEST_PERMISSIONS = 2001;
    public static final int REQUEST_GALLERY = 2002;
    public static final String EXTRA_MODE = "geotag_camera_mode";
    public static final String MODE_CAMERA = "camera";
    public static final String MODE_GALLERY = "gallery";

    private final AppCompatActivity activity;

    public GeotagCameraLauncher(@NonNull AppCompatActivity activity) {
        this.activity = activity;
    }

    /**
     * Entry point: checks permissions, then shows the image-source dialog.
     */
    public void launch() {
        if (allPermissionsGranted()) {
            showImageSourceDialog();
        } else {
            ActivityCompat.requestPermissions(activity, getRequiredPermissions(), REQUEST_PERMISSIONS);
        }
    }

    /**
     * Must be called from the host Activity's onRequestPermissionsResult.
     */
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                showImageSourceDialog();
            } else {
                Toast.makeText(activity, "Permissions required to use this feature", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Must be called from the host Activity's onActivityResult to handle gallery results.
     */
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_GALLERY) {
            if (resultCode == AppCompatActivity.RESULT_OK && data != null && data.getData() != null) {
                Uri selectedImage = data.getData();
                // Launch CustomCameraActivity in gallery-processing mode with the selected image URI
                Intent intent = new Intent(activity, CustomCameraActivity.class);
                intent.putExtra(EXTRA_MODE, MODE_GALLERY);
                intent.setData(selectedImage);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(intent);
            }
            // If user cancelled gallery, do nothing — stay on home screen
        }
    }

    private boolean allPermissionsGranted() {
        for (String perm : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.READ_MEDIA_IMAGES
            };
        } else {
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
        }
    }

    private void showImageSourceDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(activity);
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_image_source, null);
        builder.setView(dialogView);
        builder.setCancelable(true);

        final android.app.AlertDialog dialog = builder.create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        // Camera — launches CustomCameraActivity directly
        dialogView.findViewById(R.id.option_camera).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                Intent intent = new Intent(activity, CustomCameraActivity.class);
                intent.putExtra(EXTRA_MODE, MODE_CAMERA);
                activity.startActivity(intent);
            }
        });

        // Gallery — opens system gallery picker on the home screen directly
        dialogView.findViewById(R.id.option_gallery).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                Intent galleryIntent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                activity.startActivityForResult(galleryIntent, REQUEST_GALLERY);
            }
        });

        dialog.show();
    }
}

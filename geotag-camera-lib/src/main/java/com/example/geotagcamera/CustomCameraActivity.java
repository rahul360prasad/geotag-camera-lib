package com.example.geotagcamera;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import androidx.cardview.widget.CardView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.animation.ObjectAnimator;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.ScaleGestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class CustomCameraActivity extends AppCompatActivity implements SensorEventListener {

    private PreviewView previewView;
    private View shutterBtnContainer;
    private ImageButton btnSwitchCamera;
    private ImageView btnThumbnail;
    private CardView cvThumbnail;
    private View layoutGridLines;

    // Stores the URI of the last captured/found photo for thumbnail click preview
    private Uri lastCapturedUri = null;

    // Focus & Exposure HUD (iOS style)
    private View layoutFocusHud;
    private View viewFocusRing;
    private View exposureLine;
    private ImageView iconExposureSun;
    private TextView tvTimerCountdown;

    // Top Bar
    private ImageButton btnTopFlash;
    private ImageButton btnTopTimer;
    private ImageButton btnExpandSettings;
    private TextView btnTopRatio;

    // Settings Dock Drawer (horizontal tray)
    private View layoutSettingsDock;
    private View optFlash;
    private ImageView imgDockFlash;
    private TextView tvDockFlash;

    private View optTimer;
    private ImageView imgDockTimer;
    private TextView tvDockTimer;

    private View optRatio;
    private TextView tvDockRatio;

    private View optGrid;
    private TextView tvDockGrid;

    private View optLevel;
    private TextView tvDockLevel;

    // Zoom Pills (0.6x, 1x, 2x style indicator/toggle)
    private TextView btnZoomWide;
    private TextView btnZoom1x;
    private TextView btnZoom2x;

    // Horizon Level UI
    private View layoutHorizonLevel;
    private View viewHorizonLine;

    // Camera states
    private ImageCapture imageCapture;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private android.media.MediaActionSound cameraSound;

    // Mode options states
    private int flashMode = ImageCapture.FLASH_MODE_OFF;
    private int timerSeconds = 0;
    private String currentRatio = "4:3";
    private boolean isGridEnabled = false;
    private boolean isLevelEnabled = false;

    // Location components
    private LocationManager locationManager;
    private LocationListener locationListener;
    private double currentLat = 0.0;
    private double currentLon = 0.0;
    private float currentAccuracy = 0f;
    private String geocodedAddress = "Fetching current location...";

    private TextView tvAddress;
    private TextView tvCoords;
    private TextView tvDateTime;
    private android.webkit.WebView mapWebView;
    private Bitmap mapSnapshot = null;

    private String windVal = "--";
    private String pressVal = "--";
    private String humidVal = "--";
    private String tempVal = "--";
    private boolean weatherFetched = false;

    // Gesture components
    private ScaleGestureDetector scaleGestureDetector;
    private float startY = 0f;
    private float startX = 0f;
    private boolean isDraggingExposure = false;
    private float initialSunTranslationY = 0f;

    private final Handler focusDismissHandler = new Handler(Looper.getMainLooper());
    private final Runnable focusDismissRunnable = new Runnable() {
        @Override
        public void run() {
            layoutFocusHud.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() {
                @Override
                public void run() {
                    layoutFocusHud.setVisibility(View.GONE);
                }
            });
        }
    };

    // Horizon sensor components
    private SensorManager sensorManager;
    private Sensor accelerometer;

    private final Handler timeHandler = new Handler(Looper.getMainLooper());
    private final Runnable timeRunnable = new Runnable() {
        @Override
        public void run() {
            updateLiveDateTime();
            timeHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full immersive: transparent bars + hide nav bar while camera is active
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        setContentView(R.layout.activity_custom_camera);

        // Now that DecorView exists, hide system bars
        hideSystemBars();

        // Re-hide nav bar whenever user touches to reveal it then it hides again
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                getWindow().getDecorView().postDelayed(() -> hideSystemBars(), 2000);
            }
        });

        // Bind layout views
        previewView = findViewById(R.id.viewFinder);
        shutterBtnContainer = findViewById(R.id.btn_shutter_container);
        btnSwitchCamera = findViewById(R.id.btn_switch_camera);
        btnThumbnail = findViewById(R.id.btn_thumbnail);
        cvThumbnail = findViewById(R.id.cv_thumbnail);
        layoutGridLines = findViewById(R.id.layout_grid_lines);

        // Focus & Exposure
        layoutFocusHud = findViewById(R.id.layout_focus_hud);
        viewFocusRing = findViewById(R.id.view_focus_ring);
        exposureLine = findViewById(R.id.exposure_line);
        iconExposureSun = findViewById(R.id.icon_exposure_sun);
        tvTimerCountdown = findViewById(R.id.tv_timer_countdown);

        // Top Bar
        btnTopFlash = findViewById(R.id.btn_top_flash);
        btnTopTimer = findViewById(R.id.btn_top_timer);
        btnExpandSettings = findViewById(R.id.btn_expand_settings);
        btnTopRatio = findViewById(R.id.btn_top_ratio);

        // Settings Dock
        layoutSettingsDock = findViewById(R.id.layout_settings_dock);
        optFlash = findViewById(R.id.opt_flash);
        imgDockFlash = findViewById(R.id.img_dock_flash);
        tvDockFlash = findViewById(R.id.tv_dock_flash);

        optTimer = findViewById(R.id.opt_timer);
        imgDockTimer = findViewById(R.id.img_dock_timer);
        tvDockTimer = findViewById(R.id.tv_dock_timer);

        optRatio = findViewById(R.id.opt_ratio);
        tvDockRatio = findViewById(R.id.tv_dock_ratio);

        optGrid = findViewById(R.id.opt_grid);
        tvDockGrid = findViewById(R.id.tv_dock_grid);

        optLevel = findViewById(R.id.opt_level);
        tvDockLevel = findViewById(R.id.tv_dock_level);

        // Zoom Pills
        btnZoomWide = findViewById(R.id.btn_zoom_wide);
        btnZoom1x = findViewById(R.id.btn_zoom_1x);
        btnZoom2x = findViewById(R.id.btn_zoom_2x);

        // Horizon Level
        layoutHorizonLevel = findViewById(R.id.layout_horizon_level);
        viewHorizonLine = findViewById(R.id.view_horizon_line);

        tvAddress = findViewById(R.id.tv_overlay_address);
        tvCoords = findViewById(R.id.tv_overlay_coords);
        tvDateTime = findViewById(R.id.tv_overlay_datetime);

        mapWebView = findViewById(R.id.map_webview);
        if (mapWebView != null) {
            android.webkit.WebSettings webSettings = mapWebView.getSettings();
            webSettings.setJavaScriptEnabled(true);
            webSettings.setDomStorageEnabled(true);
            webSettings.setAllowFileAccess(true);
            webSettings.setAllowContentAccess(true);
            webSettings.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            webSettings.setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
            webSettings.setLoadWithOverviewMode(false);
            webSettings.setUseWideViewPort(false);
            mapWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            mapWebView.setWebChromeClient(new android.webkit.WebChromeClient());
            mapWebView.setWebViewClient(new android.webkit.WebViewClient());
            
            mapWebView.addJavascriptInterface(new Object() {
                @android.webkit.JavascriptInterface
                public void onMapCaptured(final String base64Data) {
                    if (base64Data == null || !base64Data.contains(",")) return;
                    try {
                        String pureBase64 = base64Data.substring(base64Data.indexOf(",") + 1);
                        byte[] decodedBytes = android.util.Base64.decode(pureBase64, android.util.Base64.DEFAULT);
                        final Bitmap newSnapshot = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (mapSnapshot != null) {
                                    mapSnapshot.recycle();
                                }
                                mapSnapshot = newSnapshot;
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }, "AndroidInterface");

            float dpr = getResources().getDisplayMetrics().density;
            mapWebView.loadUrl("file:///android_asset/camera_map.html?dpr=" + dpr);
        }

        // Show placeholder weather values; real data fetched via Open-Meteo when location is available
        ((TextView) findViewById(R.id.tv_overlay_wind)).setText("-- km/h");
        ((TextView) findViewById(R.id.tv_overlay_pressure)).setText("-- hPa");
        ((TextView) findViewById(R.id.tv_overlay_humidity)).setText("-- %");
        ((TextView) findViewById(R.id.tv_overlay_temp)).setText("-- °C");

        // Apply proper WindowInsets so top bar clears status bar precisely
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.layout_top_bar), (v, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), topInset, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        // Nav bar inset on capture bar — fallback for gesture-nav phones where nav bar may still show
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.layout_capture_bar), (v, insets) -> {
            int navInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            int extraPad = (int) (16 * getResources().getDisplayMetrics().density);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), navInset + extraPad);
            return insets;
        });

        // Start map pin pulse animation
        startMapPinPulse();

        // 1. Expand settings dock with animated slide + fade
        btnExpandSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (layoutSettingsDock.getVisibility() == View.VISIBLE) {
                    // Slide up and fade out
                    layoutSettingsDock.animate()
                            .translationY(-layoutSettingsDock.getHeight())
                            .alpha(0f)
                            .setDuration(220)
                            .withEndAction(() -> {
                                layoutSettingsDock.setVisibility(View.GONE);
                                layoutSettingsDock.setTranslationY(0f);
                                layoutSettingsDock.setAlpha(1f);
                            }).start();
                    btnExpandSettings.animate().rotation(0f).setDuration(220).start();
                } else {
                    // Slide down and fade in
                    layoutSettingsDock.setTranslationY(-layoutSettingsDock.getHeight() - 20f);
                    layoutSettingsDock.setAlpha(0f);
                    layoutSettingsDock.setVisibility(View.VISIBLE);
                    layoutSettingsDock.animate()
                            .translationY(0f)
                            .alpha(1f)
                            .setDuration(240)
                            .setInterpolator(new AccelerateDecelerateInterpolator())
                            .start();
                    btnExpandSettings.animate().rotation(180f).setDuration(240).start();
                }
            }
        });

        // 2. Flash modes toggle listeners
        View.OnClickListener flashListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleFlash();
            }
        };
        btnTopFlash.setOnClickListener(flashListener);
        optFlash.setOnClickListener(flashListener);

        // 3. Timer delay modes listeners
        View.OnClickListener timerListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleTimer();
            }
        };
        btnTopTimer.setOnClickListener(timerListener);
        optTimer.setOnClickListener(timerListener);

        // 4. Aspect Ratio modes listeners
        View.OnClickListener ratioListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleRatio();
            }
        };
        btnTopRatio.setOnClickListener(ratioListener);
        optRatio.setOnClickListener(ratioListener);

        // 5. Grid toggle listener
        optGrid.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isGridEnabled = !isGridEnabled;
                tvDockGrid.setText(isGridEnabled ? "Grid" : "Grid");
                optGrid.setBackgroundResource(isGridEnabled ? R.drawable.bg_dock_chip_active : R.drawable.bg_dock_chip);
                layoutGridLines.setVisibility(isGridEnabled ? View.VISIBLE : View.GONE);
            }
        });

        // 6. Horizon Leveler toggle listener
        optLevel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isLevelEnabled = !isLevelEnabled;
                tvDockLevel.setText("Level");
                optLevel.setBackgroundResource(isLevelEnabled ? R.drawable.bg_dock_chip_active : R.drawable.bg_dock_chip);
                layoutHorizonLevel.setVisibility(isLevelEnabled ? View.VISIBLE : View.GONE);
            }
        });

        // 7. Zoom selector button clicks
        btnZoomWide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setZoomRatio(0.6f);
                updateZoomPillsUI(0.6f);
            }
        });
        btnZoom1x.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setZoomRatio(1.0f);
                updateZoomPillsUI(1.0f);
            }
        });
        btnZoom2x.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setZoomRatio(2.0f);
                updateZoomPillsUI(2.0f);
            }
        });

        // 8. Mode selector with dot indicator animation
        findViewById(R.id.mode_video).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setActiveMode("video");
                Toast.makeText(CustomCameraActivity.this, "VIDEO mode is restricted for surveyor data collection.", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.mode_photo).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setActiveMode("photo");
            }
        });
        findViewById(R.id.mode_pro).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setActiveMode("pro");
                Toast.makeText(CustomCameraActivity.this, "PRO mode is restricted for surveyor data collection.", Toast.LENGTH_SHORT).show();
            }
        });

        // 9. Camera Switcher Click — 360° flip animation
        btnSwitchCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnSwitchCamera.animate()
                        .rotationBy(360f)
                        .setDuration(400)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .start();
                new Handler(Looper.getMainLooper()).postDelayed(() -> toggleCamera(), 150);
            }
        });

        // 10. Shutter — premium bounce animation on press
        shutterBtnContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final View inner = findViewById(R.id.btn_shutter_inner);
                inner.animate()
                        .scaleX(0.82f)
                        .scaleY(0.82f)
                        .setDuration(80)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .withEndAction(() ->
                            inner.animate()
                                .scaleX(1.06f)
                                .scaleY(1.06f)
                                .setDuration(100)
                                .setInterpolator(new OvershootInterpolator(2f))
                                .withEndAction(() ->
                                    inner.animate().scaleX(1f).scaleY(1f).setDuration(60).start()
                                ).start()
                        ).start();

                if (timerSeconds > 0) {
                    startTimerAndCapture(timerSeconds);
                } else {
                    capturePhoto();
                }
            }
        });

        // Set initial aspect ratio masks
        adjustRatioMasks(currentRatio);

        // Initialize accelerometer horizon leveler
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        // Setup touch gestures
        setupViewfinderGestures();

        // Load thumbnail shortcut
        loadLastTakenPhotoThumbnail();

        // Thumbnail click to open last captured image
        cvThumbnail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (lastCapturedUri != null) {
                    try {
                        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                        Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                                CustomCameraActivity.this,
                                getPackageName() + ".fileprovider",
                                new File(lastCapturedUri.getPath())
                        );
                        viewIntent.setDataAndType(contentUri, "image/*");
                        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(viewIntent);
                    } catch (Exception e) {
                        Toast.makeText(CustomCameraActivity.this, "No app found to open image.", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(CustomCameraActivity.this, "No captured photo yet.", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // Setup Location updates
        setupLocationUpdates();

        // Start live date time clock
        timeHandler.post(timeRunnable);

        String intentMode = getIntent().getStringExtra(GeotagCameraLauncher.EXTRA_MODE);
        if (GeotagCameraLauncher.MODE_GALLERY.equals(intentMode)) {
            // Hide camera UI elements
            if (previewView != null) previewView.setVisibility(View.GONE);
            View topBar = findViewById(R.id.layout_top_bar);
            if (topBar != null) topBar.setVisibility(View.GONE);
            View capBar = findViewById(R.id.layout_capture_bar);
            if (capBar != null) capBar.setVisibility(View.GONE);
            // DO NOT hide cv_watermark_overlay because WebView needs to render the map
            View carousel = findViewById(R.id.layout_mode_carousel);
            if (carousel != null) carousel.setVisibility(View.GONE);
            View zoom = findViewById(R.id.layout_zoom_pills);
            if (zoom != null) zoom.setVisibility(View.GONE);

            handleGalleryMode();
        } else {
            cameraSound = new android.media.MediaActionSound();
            cameraSound.load(android.media.MediaActionSound.SHUTTER_CLICK);

            // Bind camera lifecycle
            startCamera();
        }
    }

    private void handleGalleryMode() {
        Uri galleryUri = getIntent().getData();
        if (galleryUri == null) {
            Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Process the gallery image directly
        try {
            java.io.InputStream is = getContentResolver().openInputStream(galleryUri);
            final Bitmap rawBitmap = BitmapFactory.decodeStream(is);
            if (is != null) is.close();

            if (rawBitmap == null) {
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            // Show a loading UI over the black screen
            final View layoutPreview = findViewById(R.id.layout_image_preview);
            final View layoutLoading = findViewById(R.id.layout_preview_loading);
            if (layoutPreview != null) layoutPreview.setVisibility(View.VISIBLE);
            if (layoutLoading != null) layoutLoading.setVisibility(View.VISIBLE);

            // Hide action buttons during loading
            findViewById(R.id.btn_preview_retake).setVisibility(View.GONE);
            findViewById(R.id.btn_preview_use).setVisibility(View.GONE);

            // Wait for mapSnapshot to be populated (max 3 seconds)
            new Thread(new Runnable() {
                @Override
                public void run() {
                    int waits = 0;
                    while (mapSnapshot == null && waits < 15) { // 15 * 200ms = 3.0s
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {}
                        waits++;
                    }
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                            findViewById(R.id.btn_preview_retake).setVisibility(View.VISIBLE);
                            findViewById(R.id.btn_preview_use).setVisibility(View.VISIBLE);
                            
                            processAndReturnGalleryPhoto(rawBitmap, mapSnapshot);
                        }
                    });
                }
            }).start();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void processAndReturnGalleryPhoto(final Bitmap rawBitmap, final Bitmap mapSnapshot) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (rawBitmap == null) return;

                    Bitmap bitmap = rawBitmap;

                    String addressStr = geocodedAddress;
                    if (addressStr.equals("Fetching current location...")) {
                        addressStr = "Location details unavailable";
                    }

                    Bitmap watermarked = applyWatermark(bitmap, currentLat, currentLon, currentAccuracy, addressStr, mapSnapshot);
                    if (watermarked != bitmap) {
                        bitmap.recycle();
                    }
                    if (mapSnapshot != null) {
                        mapSnapshot.recycle();
                    }

                    saveImageToPublicPictures(watermarked);

                    final File photoFile = File.createTempFile("TEMP_CAPTURE_", ".jpg", getExternalCacheDir());
                    FileOutputStream fos = null;
                    try {
                        fos = new FileOutputStream(photoFile);
                        
                        int quality = 100;
                        byte[] bytes = new byte[0];
                        do {
                            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                            watermarked.compress(Bitmap.CompressFormat.JPEG, quality, bos);
                            bytes = bos.toByteArray();
                            quality -= 5;
                        } while (bytes.length > 2 * 1024 * 1024 && quality >= 50);
                        
                        fos.write(bytes);
                    } finally {
                        if (fos != null) {
                            try {
                                fos.close();
                            } catch (java.io.IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    lastCapturedUri = Uri.fromFile(photoFile);
                    watermarked.recycle();

                    showPreviewOverlay(photoFile);

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    // ── UI Helper: Active Mode Dot Indicator ──────────────────────────
    private void setActiveMode(String mode) {
        View videoDot = findViewById(R.id.mode_video_dot);
        View photoDot = findViewById(R.id.mode_photo_dot);
        View proDot   = findViewById(R.id.mode_pro_dot);
        TextView videoText = findViewById(R.id.mode_video);
        TextView photoText = findViewById(R.id.mode_photo);
        TextView proText   = findViewById(R.id.mode_pro);

        // Reset all to inactive
        videoDot.setVisibility(View.INVISIBLE);
        photoDot.setVisibility(View.INVISIBLE);
        proDot.setVisibility(View.INVISIBLE);
        videoText.setTextColor(0x55FFFFFF);
        photoText.setTextColor(0x55FFFFFF);
        proText.setTextColor(0x55FFFFFF);
        videoText.setTextSize(12f);
        photoText.setTextSize(12f);
        proText.setTextSize(12f);

        // Activate selected mode
        switch (mode) {
            case "video":
                videoDot.setVisibility(View.VISIBLE);
                videoDot.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).start();
                videoText.setTextColor(0xFFFFFFFF);
                videoText.setTextSize(13f);
                break;
            case "pro":
                proDot.setVisibility(View.VISIBLE);
                proDot.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).start();
                proText.setTextColor(0xFFFFFFFF);
                proText.setTextSize(13f);
                break;
            default: // photo
                photoDot.setVisibility(View.VISIBLE);
                photoDot.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).start();
                photoText.setTextColor(0xFFFFFFFF);
                photoText.setTextSize(13f);
                break;
        }
    }

    // ── UI Helper: Pulsing Map Pin Animation ───────────────────────────
    private void startMapPinPulse() {
        // Obsolete: Map pin is now handled within the OpenLayers WebView map
    }

    private void cycleFlash() {
        if (flashMode == ImageCapture.FLASH_MODE_OFF) {
            setFlash(ImageCapture.FLASH_MODE_AUTO);
        } else if (flashMode == ImageCapture.FLASH_MODE_AUTO) {
            setFlash(ImageCapture.FLASH_MODE_ON);
        } else {
            setFlash(ImageCapture.FLASH_MODE_OFF);
        }
    }

    private void setFlash(int mode) {
        flashMode = mode;
        if (imageCapture != null) {
            imageCapture.setFlashMode(flashMode);
        }

        if (flashMode == ImageCapture.FLASH_MODE_OFF) {
            btnTopFlash.setImageResource(R.drawable.ic_flash_off);
            imgDockFlash.setImageResource(R.drawable.ic_flash_off);
            tvDockFlash.setText("Flash");
            optFlash.setBackgroundResource(R.drawable.bg_dock_chip);
        } else if (flashMode == ImageCapture.FLASH_MODE_AUTO) {
            btnTopFlash.setImageResource(R.drawable.ic_flash_auto);
            imgDockFlash.setImageResource(R.drawable.ic_flash_auto);
            tvDockFlash.setText("Auto");
            optFlash.setBackgroundResource(R.drawable.bg_dock_chip_active);
        } else {
            btnTopFlash.setImageResource(R.drawable.ic_flash_on);
            imgDockFlash.setImageResource(R.drawable.ic_flash_on);
            tvDockFlash.setText("On");
            optFlash.setBackgroundResource(R.drawable.bg_dock_chip_active);
        }
    }

    private void cycleTimer() {
        if (timerSeconds == 0) {
            setTimer(3);
        } else if (timerSeconds == 3) {
            setTimer(10);
        } else {
            setTimer(0);
        }
    }

    private void setTimer(int seconds) {
        timerSeconds = seconds;
        if (timerSeconds == 0) {
            btnTopTimer.setVisibility(View.GONE);
            imgDockTimer.setImageResource(R.drawable.ic_timer_off);
            tvDockTimer.setText("Timer");
            optTimer.setBackgroundResource(R.drawable.bg_dock_chip);
        } else if (timerSeconds == 3) {
            btnTopTimer.setVisibility(View.VISIBLE);
            btnTopTimer.setImageResource(R.drawable.ic_timer_3s);
            imgDockTimer.setImageResource(R.drawable.ic_timer_3s);
            tvDockTimer.setText("3s");
            optTimer.setBackgroundResource(R.drawable.bg_dock_chip_active);
        } else {
            btnTopTimer.setVisibility(View.VISIBLE);
            btnTopTimer.setImageResource(R.drawable.ic_timer_10s);
            imgDockTimer.setImageResource(R.drawable.ic_timer_10s);
            tvDockTimer.setText("10s");
            optTimer.setBackgroundResource(R.drawable.bg_dock_chip_active);
        }
    }

    private void cycleRatio() {
        if ("4:3".equals(currentRatio)) {
            setRatio("16:9");
        } else if ("16:9".equals(currentRatio)) {
            setRatio("1:1");
        } else {
            setRatio("4:3");
        }
    }

    private void setRatio(String ratio) {
        currentRatio = ratio;
        btnTopRatio.setText(ratio);
        tvDockRatio.setText(ratio);
        adjustRatioMasks(ratio);
        bindCameraUseCases();
    }

    private void adjustRatioMasks(String ratio) {
        View maskTop = findViewById(R.id.mask_top);
        View maskBottom = findViewById(R.id.mask_bottom);
        if (maskTop == null || maskBottom == null) return;

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        int targetPreviewHeight;
        if ("1:1".equals(ratio)) {
            targetPreviewHeight = screenWidth;
        } else if ("4:3".equals(ratio)) {
            targetPreviewHeight = screenWidth * 4 / 3;
        } else {
            targetPreviewHeight = screenWidth * 16 / 9;
        }

        int remainingHeight = screenHeight - targetPreviewHeight;
        if (remainingHeight < 0) remainingHeight = 0;

        int topMaskHeight;
        int bottomMaskHeight;

        if ("1:1".equals(ratio)) {
            topMaskHeight = remainingHeight / 2;
            bottomMaskHeight = remainingHeight - topMaskHeight;
        } else if ("4:3".equals(ratio)) {
            int topBarPx = (int) (56 * getResources().getDisplayMetrics().density);
            topMaskHeight = Math.min(remainingHeight, topBarPx);
            bottomMaskHeight = remainingHeight - topMaskHeight;
        } else {
            topMaskHeight = 0;
            bottomMaskHeight = 0;
        }

        maskTop.getLayoutParams().height = topMaskHeight;
        maskTop.requestLayout();

        maskBottom.getLayoutParams().height = bottomMaskHeight;
        maskBottom.requestLayout();

        if ("16:9".equals(ratio)) {
            maskTop.setBackgroundColor(Color.parseColor("#40000000"));
            maskBottom.setBackgroundColor(Color.parseColor("#40000000"));
        } else {
            maskTop.setBackgroundColor(Color.BLACK);
            maskBottom.setBackgroundColor(Color.BLACK);
        }
    }

    private void startTimerAndCapture(final int seconds) {
        shutterBtnContainer.setEnabled(false);
        View vignette = findViewById(R.id.vignette_timer);
        if (vignette != null) {
            vignette.setAlpha(0f);
            vignette.setVisibility(View.VISIBLE);
            vignette.animate().alpha(1f).setDuration(300).start();
        }
        tvTimerCountdown.setVisibility(View.VISIBLE);
        tvTimerCountdown.setText(String.valueOf(seconds));

        final Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            int count = seconds;
            @Override
            public void run() {
                if (count > 0) {
                    tvTimerCountdown.setText(String.valueOf(count));
                    // Scale pulse animation each tick
                    tvTimerCountdown.setScaleX(1.5f);
                    tvTimerCountdown.setScaleY(1.5f);
                    tvTimerCountdown.setAlpha(0.5f);
                    tvTimerCountdown.animate()
                            .scaleX(1.0f).scaleY(1.0f).alpha(1.0f)
                            .setDuration(400)
                            .setInterpolator(new OvershootInterpolator(1.5f))
                            .start();
                    count--;
                    handler.postDelayed(this, 1000);
                } else {
                    tvTimerCountdown.setVisibility(View.GONE);
                    if (vignette != null) {
                        vignette.animate().alpha(0f).setDuration(200).withEndAction(() ->
                            vignette.setVisibility(View.GONE)).start();
                    }
                    shutterBtnContainer.setEnabled(true);
                    capturePhoto();
                }
            }
        });
    }

    private void setupViewfinderGestures() {
        final float touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (camera == null) return true;
                CameraInfo cameraInfo = camera.getCameraInfo();
                float currentZoom = cameraInfo.getZoomState().getValue().getZoomRatio();
                float delta = detector.getScaleFactor();
                float nextZoom = currentZoom * delta;

                float min = cameraInfo.getZoomState().getValue().getMinZoomRatio();
                float max = cameraInfo.getZoomState().getValue().getMaxZoomRatio();
                nextZoom = Math.max(min, Math.min(nextZoom, max));

                camera.getCameraControl().setZoomRatio(nextZoom);
                updateZoomPillsUI(nextZoom);
                return true;
            }
        });

        previewView.setOnTouchListener(new View.OnTouchListener() {
            private int touchSlop = android.view.ViewConfiguration.get(CustomCameraActivity.this).getScaledTouchSlop();
            
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                View maskTop = findViewById(R.id.mask_top);
                View maskBottom = findViewById(R.id.mask_bottom);

                // Ignore touches on the black masks (top/bottom bars)
                if (maskTop != null && maskBottom != null) {
                    if (event.getY() <= maskTop.getHeight() || event.getY() >= previewView.getHeight() - maskBottom.getHeight()) {
                        return false;
                    }
                }

                scaleGestureDetector.onTouchEvent(event);

                if (event.getPointerCount() > 1) {
                    isDraggingExposure = false;
                    return true;
                }

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getX();
                        startY = event.getY();
                        isDraggingExposure = false;
                        initialSunTranslationY = iconExposureSun.getTranslationY();
                        break;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getX() - startX;
                        float dy = event.getY() - startY;

                        if (layoutFocusHud.getVisibility() == View.VISIBLE) {
                            if (!isDraggingExposure && Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx)) {
                                isDraggingExposure = true;
                            }

                            if (isDraggingExposure) {
                                float maxTransPx = 45f * getResources().getDisplayMetrics().density;
                                float translation = initialSunTranslationY + dy * 0.8f;
                                if (translation < -maxTransPx) translation = -maxTransPx;
                                if (translation > maxTransPx) translation = maxTransPx;

                                iconExposureSun.setTranslationY(translation);

                                float progressPercent = (translation + maxTransPx) / (2f * maxTransPx);
                                float progress = 1f - progressPercent;

                                setExposureFromProgress(progress);

                                focusDismissHandler.removeCallbacks(focusDismissRunnable);
                                focusDismissHandler.postDelayed(focusDismissRunnable, 4000);
                            }
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!isDraggingExposure) {
                            float x = event.getX();
                            float y = event.getY();
                            showFocusHud(x, y);
                            triggerFocus(x, y);
                        }
                        isDraggingExposure = false;
                        break;
                }
                return true;
            }
        });
    }

    private void showFocusHud(float x, float y) {
        focusDismissHandler.removeCallbacks(focusDismissRunnable);

        layoutFocusHud.setAlpha(1f);
        layoutFocusHud.setVisibility(View.VISIBLE);

        int hudW = layoutFocusHud.getWidth();
        int hudH = layoutFocusHud.getHeight();
        if (hudW == 0) hudW = (int) (120 * getResources().getDisplayMetrics().density);
        if (hudH == 0) hudH = (int) (120 * getResources().getDisplayMetrics().density);

        layoutFocusHud.setX(x - hudW / 2.0f);
        layoutFocusHud.setY(y - hudH / 2.0f);

        iconExposureSun.setTranslationY(0f);
        setExposureFromProgress(0.5f);

        viewFocusRing.animate().cancel();
        viewFocusRing.setScaleX(1.5f);
        viewFocusRing.setScaleY(1.5f);
        viewFocusRing.setAlpha(0f);
        viewFocusRing.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                .start();

        focusDismissHandler.postDelayed(focusDismissRunnable, 3000);
    }

    private void triggerFocus(float x, float y) {
        if (camera == null) return;
        try {
            MeteringPointFactory factory = previewView.getMeteringPointFactory();
            MeteringPoint point = factory.createPoint(x, y);
            FocusMeteringAction action = new FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build();
            camera.getCameraControl().startFocusAndMetering(action);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setExposureFromProgress(float progress) {
        if (camera == null) return;
        try {
            CameraInfo info = camera.getCameraInfo();
            android.util.Range<Integer> range = info.getExposureState().getExposureCompensationRange();
            if (range != null && range.getLower() != null && range.getUpper() != null) {
                int min = range.getLower();
                int max = range.getUpper();
                int index = Math.round(min + progress * (max - min));
                camera.getCameraControl().setExposureCompensationIndex(index);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setZoomRatio(float ratio) {
        if (camera == null) return;
        try {
            float min = camera.getCameraInfo().getZoomState().getValue().getMinZoomRatio();
            float max = camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio();
            float target = Math.max(min, Math.min(ratio, max));
            camera.getCameraControl().setZoomRatio(target);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateZoomPillsUI(float zoomRatio) {
        if (btnZoomWide == null || btnZoom1x == null || btnZoom2x == null) return;

        btnZoomWide.setBackgroundResource(android.R.color.transparent);
        btnZoomWide.setTextColor(Color.WHITE);
        btnZoom1x.setBackgroundResource(android.R.color.transparent);
        btnZoom1x.setTextColor(Color.WHITE);
        btnZoom2x.setBackgroundResource(android.R.color.transparent);
        btnZoom2x.setTextColor(Color.WHITE);

        btnZoomWide.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        btnZoom1x.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        btnZoom2x.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));

        if (zoomRatio < 0.8f) {
            btnZoomWide.setBackgroundResource(R.drawable.bg_circle_white);
            btnZoomWide.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#33FFFFFF")));
            btnZoomWide.setTextColor(Color.parseColor("#FFD700"));
        } else if (zoomRatio < 1.5f) {
            btnZoom1x.setBackgroundResource(R.drawable.bg_circle_white);
            btnZoom1x.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#33FFFFFF")));
            btnZoom1x.setTextColor(Color.parseColor("#FFD700"));
        } else {
            btnZoom2x.setBackgroundResource(R.drawable.bg_circle_white);
            btnZoom2x.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#33FFFFFF")));
            btnZoom2x.setTextColor(Color.parseColor("#FFD700"));
        }
    }

    private void toggleCamera() {
        if (cameraProvider == null) return;
        lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK) ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        bindCameraUseCases();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    cameraProvider = cameraProviderFuture.get();
                    bindCameraUseCases();
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(CustomCameraActivity.this, "Failed to start camera.", Toast.LENGTH_SHORT).show();
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        Preview.Builder previewBuilder = new Preview.Builder();
        ImageCapture.Builder captureBuilder = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(flashMode);

        if ("16:9".equals(currentRatio)) {
            previewBuilder.setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9);
            captureBuilder.setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9);
        } else {
            previewBuilder.setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3);
            captureBuilder.setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3);
        }

        Preview preview = previewBuilder.build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        imageCapture = captureBuilder.build();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build();

        try {
            cameraProvider.unbindAll();
            androidx.camera.core.ViewPort viewPort = previewView.getViewPort();
            if (viewPort != null) {
                androidx.camera.core.UseCaseGroup useCaseGroup = new androidx.camera.core.UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(imageCapture)
                        .setViewPort(viewPort)
                        .build();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, useCaseGroup);
            } else {
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
            }

            setFlash(flashMode);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setupLocationUpdates() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return;

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                currentLat = location.getLatitude();
                currentLon = location.getLongitude();
                currentAccuracy = location.getAccuracy();

                String coordStr = "Lat: " + String.format(Locale.US, "%.6f", currentLat) +
                        "   Long: " + String.format(Locale.US, "%.7f", currentLon) +
                        "   Acc: " + String.format(Locale.US, "%.1f", currentAccuracy) + "m";
                if (tvCoords != null) {
                    tvCoords.setText(coordStr);
                }

                if (mapWebView != null) {
                    mapWebView.post(new Runnable() {
                        @Override
                        public void run() {
                            mapWebView.evaluateJavascript("javascript:updateLocation(" + currentLat + ", " + currentLon + ");", null);
                        }
                    });
                }

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Geocoder geocoder = new Geocoder(CustomCameraActivity.this, Locale.getDefault());
                            List<Address> addresses = geocoder.getFromLocation(currentLat, currentLon, 1);
                            if (addresses != null && !addresses.isEmpty()) {
                                Address address = addresses.get(0);
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i <= address.getMaxAddressLineIndex(); i++) {
                                    sb.append(address.getAddressLine(i));
                                    if (i < address.getMaxAddressLineIndex()) sb.append(", ");
                                }
                                geocodedAddress = sb.toString();
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (tvAddress != null) {
                                            tvAddress.setText(geocodedAddress);
                                        }
                                    }
                                });
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }).start();

                // Fetch real weather data from Open-Meteo (only once per session)
                if (!weatherFetched && currentLat != 0.0 && currentLon != 0.0) {
                    weatherFetched = true;
                    fetchWeatherData(currentLat, currentLon);
                }
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override
            public void onProviderEnabled(@NonNull String provider) {}
            @Override
            public void onProviderDisabled(@NonNull String provider) {}
        };

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                Location cachedGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                Location cachedNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                Location best = (cachedGps != null) ? cachedGps : cachedNet;
                if (best != null) {
                    locationListener.onLocationChanged(best);
                }

                startLocationUpdates();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateLiveDateTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy   hh:mm:ss a   Z", Locale.US);
        Date now = new Date();
        String zoneStr = new SimpleDateFormat("Z", Locale.US).format(now);
        String zoneFormatted = (zoneStr.startsWith("+") || zoneStr.startsWith("-")) ? "GMT" + zoneStr : zoneStr;

        SimpleDateFormat sdfBase = new SimpleDateFormat("dd-MM-yyyy   hh:mm:ss a", Locale.US);
        if (tvDateTime != null) {
            tvDateTime.setText(sdfBase.format(now) + "   " + zoneFormatted);
        }
    }

    /**
     * Fetches real-time weather data from Open-Meteo (free, no API key required).
     * Updates both the live preview overlay and the internal values used in watermarks.
     */
    private void fetchWeatherData(final double lat, final double lon) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.net.HttpURLConnection connection = null;
                try {
                    String urlStr = "https://api.open-meteo.com/v1/forecast"
                            + "?latitude=" + String.format(Locale.US, "%.4f", lat)
                            + "&longitude=" + String.format(Locale.US, "%.4f", lon)
                            + "&current=temperature_2m,relative_humidity_2m,surface_pressure,wind_speed_10m";

                    java.net.URL url = new java.net.URL(urlStr);
                    connection = (java.net.HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000);

                    int responseCode = connection.getResponseCode();
                    if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(connection.getInputStream()));
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        reader.close();

                        org.json.JSONObject json = new org.json.JSONObject(response.toString());
                        if (json.has("current")) {
                            org.json.JSONObject current = json.getJSONObject("current");

                            final double temp = current.optDouble("temperature_2m", 0);
                            final int humidity = current.optInt("relative_humidity_2m", 0);
                            final double pressure = current.optDouble("surface_pressure", 0);
                            final double wind = current.optDouble("wind_speed_10m", 0);

                            // Format values
                            final String tempStr = String.format(Locale.US, "%.0f", temp);
                            final String humStr = String.valueOf(humidity);
                            final String presStr = String.format(Locale.US, "%.0f", pressure);
                            final String windStr = String.format(Locale.US, "%.0f", wind);

                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    // Update internal values for watermark
                                    tempVal = tempStr;
                                    humidVal = humStr;
                                    pressVal = presStr;
                                    windVal = windStr;

                                    // Update live preview overlay
                                    TextView tvWind = findViewById(R.id.tv_overlay_wind);
                                    TextView tvPres = findViewById(R.id.tv_overlay_pressure);
                                    TextView tvHum = findViewById(R.id.tv_overlay_humidity);
                                    TextView tvTemp = findViewById(R.id.tv_overlay_temp);
                                    if (tvWind != null) tvWind.setText(windStr + " km/h");
                                    if (tvPres != null) tvPres.setText(presStr + " hPa");
                                    if (tvHum != null) tvHum.setText(humStr + " %");
                                    if (tvTemp != null) tvTemp.setText(tempStr + " °C");
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    // On failure, weather values remain as "--" (graceful degradation)
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }).start();
    }

    private void loadLastTakenPhotoThumbnail() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File cacheDir = getExternalCacheDir();
                    if (cacheDir == null) return;
                    File[] files = cacheDir.listFiles();
                    if (files != null && files.length > 0) {
                        File latestFile = null;
                        for (File file : files) {
                            if (file.getName().endsWith(".jpg")) {
                                if (latestFile == null || file.lastModified() > latestFile.lastModified()) {
                                    latestFile = file;
                                }
                            }
                        }
                        if (latestFile != null) {
                            lastCapturedUri = Uri.fromFile(latestFile);
                            BitmapFactory.Options options = new BitmapFactory.Options();
                            options.inSampleSize = 8;
                            final Bitmap thumb = BitmapFactory.decodeFile(latestFile.getAbsolutePath(), options);
                            if (thumb != null) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        btnThumbnail.setImageBitmap(thumb);
                                    }
                                });
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void capturePhoto() {
        if (imageCapture == null) return;

        // Visual and audio feedback for capture
        if (cameraSound != null) {
            cameraSound.play(android.media.MediaActionSound.SHUTTER_CLICK);
        }

        android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(30);
            }
        }

        final android.view.View flashView = new android.view.View(this);
        flashView.setBackgroundColor(android.graphics.Color.WHITE);
        flashView.setAlpha(0.8f);
        ((android.view.ViewGroup) getWindow().getDecorView().getRootView()).addView(flashView,
                new android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        flashView.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        ((android.view.ViewGroup) getWindow().getDecorView().getRootView()).removeView(flashView);
                    }
                }).start();

        try {
            imageCapture.takePicture(ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
                @Override
                public void onCaptureSuccess(@NonNull androidx.camera.core.ImageProxy imageProxy) {
                    Bitmap mapCopy = null;
                    if (mapSnapshot != null && !mapSnapshot.isRecycled()) {
                        try {
                            mapCopy = mapSnapshot.copy(Bitmap.Config.ARGB_8888, false);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    processAndReturnCapturedPhoto(imageProxy, mapCopy);
                }

                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    exception.printStackTrace();
                    Toast.makeText(CustomCameraActivity.this, "Capture failed: " + exception.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processAndReturnCapturedPhoto(androidx.camera.core.ImageProxy imageProxy, final Bitmap mapSnapshot) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
                    android.graphics.Rect cropRect = imageProxy.getCropRect();
                    Bitmap rawBitmap = imageProxy.toBitmap();
                    imageProxy.close();
                    if (rawBitmap == null) return;

                    if (cropRect != null && cropRect.width() > 0 && cropRect.height() > 0 && 
                        (cropRect.width() < rawBitmap.getWidth() || cropRect.height() < rawBitmap.getHeight())) {
                        Bitmap cropped = Bitmap.createBitmap(rawBitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height());
                        rawBitmap.recycle();
                        rawBitmap = cropped;
                    }

                    Bitmap bitmap = rawBitmap;
                    if (rotationDegrees != 0) {
                        android.graphics.Matrix matrix = new android.graphics.Matrix();
                        matrix.postRotate(rotationDegrees);
                        bitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.getWidth(), rawBitmap.getHeight(), matrix, true);
                        if (bitmap != rawBitmap) {
                            rawBitmap.recycle();
                        }
                    }

                    String addressStr = geocodedAddress;
                    if (addressStr.equals("Fetching current location...")) {
                        addressStr = "Location details unavailable";
                    }

                    Bitmap watermarked = applyWatermark(bitmap, currentLat, currentLon, currentAccuracy, addressStr, mapSnapshot);
                    if (watermarked != bitmap) {
                        bitmap.recycle();
                    }
                    if (mapSnapshot != null) {
                        mapSnapshot.recycle();
                    }

                    saveImageToPublicPictures(watermarked);

                    final File photoFile = File.createTempFile("TEMP_CAPTURE_", ".jpg", getExternalCacheDir());
                    FileOutputStream fos = null;
                    try {
                        fos = new FileOutputStream(photoFile);
                        
                        int quality = 100;
                        byte[] bytes = new byte[0];
                        do {
                            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                            watermarked.compress(Bitmap.CompressFormat.JPEG, quality, bos);
                            bytes = bos.toByteArray();
                            quality -= 5;
                        } while (bytes.length > 2 * 1024 * 1024 && quality >= 50);
                        
                        fos.write(bytes);
                    } finally {
                        if (fos != null) {
                            try {
                                fos.close();
                            } catch (java.io.IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    lastCapturedUri = Uri.fromFile(photoFile);
                    watermarked.recycle();

                    showPreviewOverlay(photoFile);

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }
    
    private void showPreviewOverlay(final File photoFile) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                View layoutPreview = findViewById(R.id.layout_image_preview);
                ImageView imgPreview = findViewById(R.id.img_preview);
                Button btnRetake = findViewById(R.id.btn_preview_retake);
                Button btnUse = findViewById(R.id.btn_preview_use);

                if (layoutPreview == null) return;

                imgPreview.setImageURI(null);
                imgPreview.setImageURI(Uri.fromFile(photoFile));

                layoutPreview.setVisibility(View.VISIBLE);

                btnRetake.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        layoutPreview.setVisibility(View.GONE);
                        String intentMode = getIntent().getStringExtra(GeotagCameraLauncher.EXTRA_MODE);
                        if (GeotagCameraLauncher.MODE_GALLERY.equals(intentMode)) {
                            setResult(RESULT_CANCELED);
                            finish();
                        }
                    }
                });

                btnUse.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent resultIntent = new Intent();
                        resultIntent.setData(Uri.fromFile(photoFile));
                        setResult(RESULT_OK, resultIntent);
                        finish();
                    }
                });
            }
        });
    }

    private Bitmap applyWatermark(Bitmap src, double lat, double lon, float accuracy, String address, Bitmap mapSnapshot) {
        int w = src.getWidth();
        int h = src.getHeight();

        Bitmap mutableBitmap = src.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(mutableBitmap);

        // Watermark styling based strictly on the live preview UI XML layout.
        // The container is a rounded dark rectangle placed at the absolute bottom.
        float margin = w * 0.0388f; // matching 14dp on 360dp width
        float cardW = w - 2 * margin;
        float cardH = cardW * 0.35f; // Exact aspect ratio matching 116dp height / 332dp width
        float cardBottom = h - margin; // Shifted to absolute bottom
        float cardTop = cardBottom - cardH;
        float cardRadius = cardH * (16f / 116f); // 16dp radius

        // Glass background
        Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardPaint.setColor(Color.parseColor("#D9101418")); // matching R.drawable.bg_geotag_glass dark base
        cardPaint.setStyle(Paint.Style.FILL);
        RectF cardRect = new RectF(margin, cardTop, margin + cardW, cardBottom);
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, cardPaint);

        // Subtle white border
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(Color.parseColor("#25FFFFFF")); // 15% white stroke
        borderPaint.setStyle(Paint.Style.STROKE);
        float borderWidth = w * 0.0015f; // thin border
        borderPaint.setStrokeWidth(borderWidth);
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, borderPaint);

        // Cyan left accent bar
        Paint cyanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cyanPaint.setColor(Color.parseColor("#00D4FF"));
        cyanPaint.setStyle(Paint.Style.FILL);
        float barWidth = w * 0.0083f; // 3dp width
        float barMargin = cardH * 0.103f; // 12dp vertical padding
        float barTop = cardTop + barMargin;
        float barBottom = cardBottom - barMargin;
        float barLeft = margin + borderWidth / 2f;
        float barRight = barLeft + barWidth;
        RectF barRect = new RectF(barLeft, barTop, barRight, barBottom);
        float barRadius = barWidth * 0.5f;
        canvas.drawRoundRect(barRect, barRadius, barRadius, cyanPaint);

        // Map layout
        float mapPadding = cardH * 0.103f; // 12dp padding
        float mapW = cardH * 0.793f; // 92dp size
        float mapH = mapW;
        float mapLeft = barRight + w * 0.0083f; // 3dp start margin from cyan bar
        float mapTop = cardTop + mapPadding;
        RectF mapRect = new RectF(mapLeft, mapTop, mapLeft + mapW, mapTop + mapH);
        float mapRadius = cardH * 0.086f; // 10dp corner radius

        // Draw Map with corner clipping
        canvas.save();
        Path clipPath = new Path();
        clipPath.addRoundRect(mapRect, mapRadius, mapRadius, Path.Direction.CW);
        canvas.clipPath(clipPath);

        if (mapSnapshot != null && !mapSnapshot.isRecycled()) {
            Paint filterPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            canvas.drawBitmap(mapSnapshot, null, mapRect, filterPaint);
        } else {
            // Fallback static map background if WebView wasn't ready
            Paint mapBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mapBgPaint.setColor(Color.parseColor("#1B3A2D"));
            mapBgPaint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(mapRect, mapRadius, mapRadius, mapBgPaint);
            
            // Draw "NO MAP" text in center
            Paint mapTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mapTextPaint.setColor(Color.parseColor("#50FFFFFF"));
            mapTextPaint.setTextSize(mapH * 0.12f);
            mapTextPaint.setTypeface(Typeface.create("sans-serif-bold", Typeface.NORMAL));
            mapTextPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("NO MAP", mapRect.centerX(), mapRect.centerY() + mapH * 0.04f, mapTextPaint);
        }
        canvas.restore();

        // Right side data area
        float textLeft = mapLeft + mapW + w * 0.033f; // 12dp margin
        float textRight = margin + cardW - w * 0.033f; // 12dp padding
        float textW = textRight - textLeft;
        float textTop = cardTop + cardH * 0.138f; // ~16dp top padding

        // Address (White, bold, size 11dp relative)
        Paint addrPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        addrPaint.setColor(Color.WHITE);
        addrPaint.setTextSize(cardH * 0.095f); // 11dp size
        addrPaint.setTypeface(Typeface.create("sans-serif-bold", Typeface.NORMAL));
        float nextY = drawWrappedText(canvas, address, textLeft, textTop, textW, addrPaint);

        // Coordinates (Cyan, monospace, size 9.5dp)
        Paint coordPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        coordPaint.setColor(Color.parseColor("#00D4FF"));
        coordPaint.setTextSize(cardH * 0.082f); // 9.5dp size
        coordPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        String coordStr = "Lat: " + String.format(Locale.US, "%.6f", lat) + "   Long: " + String.format(Locale.US, "%.7f", lon);
        if (accuracy > 0f) {
            coordStr += "   Acc: " + String.format(Locale.US, "%.1f", accuracy) + "m";
        }
        float coordY = nextY + w * 0.011f; // 4dp top margin
        canvas.drawText(coordStr, textLeft, coordY, coordPaint);

        // DateTime (9.5dp, 60% opacity white)
        Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        datePaint.setColor(Color.parseColor("#99FFFFFF"));
        datePaint.setTextSize(cardH * 0.082f); // 9.5dp size
        datePaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        SimpleDateFormat sdfDate = new SimpleDateFormat("dd-MM-yyyy", Locale.US);
        SimpleDateFormat sdfTime = new SimpleDateFormat("hh:mm a", Locale.US);
        SimpleDateFormat sdfTz = new SimpleDateFormat("Z", Locale.US);
        Date now = new Date();
        String tzStr = sdfTz.format(now);
        String tzFormatted = (tzStr.startsWith("+") || tzStr.startsWith("-")) ? "GMT" + tzStr : tzStr;
        String dateTimeStr = sdfDate.format(now) + "   " + sdfTime.format(now) + "   " + tzFormatted;
        float dateY = coordY + datePaint.getFontSpacing() + w * 0.0055f; // 2dp top margin
        canvas.drawText(dateTimeStr, textLeft, dateY, datePaint);

        // Divider (20% white, 0.5dp thickness)
        float divY = dateY + w * 0.0166f; // 6dp top margin
        Paint divPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        divPaint.setColor(Color.parseColor("#20FFFFFF"));
        divPaint.setStrokeWidth(Math.max(1f, w * 0.0014f)); // 0.5dp thickness
        canvas.drawLine(textLeft, divY, textRight, divY, divPaint);

        // Weather Grid (Open-Meteo data)
        Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(Color.parseColor("#70FFFFFF")); // 44% white
        labelPaint.setTextSize(cardH * 0.073f); // 8.5dp size
        labelPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        Paint valPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        valPaint.setColor(Color.WHITE);
        valPaint.setTextSize(cardH * 0.073f); // 8.5dp size
        valPaint.setTypeface(Typeface.create("sans-serif-bold", Typeface.NORMAL));

        float gridLabelY1 = divY + w * 0.0138f + labelPaint.getFontSpacing(); // 5dp padding
        float gridLabelY2 = gridLabelY1 + labelPaint.getFontSpacing() + w * 0.0083f; // 3dp padding
        float col2X = textLeft + textW * 0.5f;

        // Row 1: Wind & Pressure
        canvas.drawText("Wind  ", textLeft, gridLabelY1, labelPaint);
        canvas.drawText(windVal + " km/h", textLeft + labelPaint.measureText("Wind  "), gridLabelY1, valPaint);

        canvas.drawText("Pres  ", col2X, gridLabelY1, labelPaint);
        canvas.drawText(pressVal + " hPa", col2X + labelPaint.measureText("Pres  "), gridLabelY1, valPaint);

        // Row 2: Humidity & Temp
        canvas.drawText("Hum  ", textLeft, gridLabelY2, labelPaint);
        canvas.drawText(humidVal + " %", textLeft + labelPaint.measureText("Hum  "), gridLabelY2, valPaint);

        canvas.drawText("Temp  ", col2X, gridLabelY2, labelPaint);
        canvas.drawText(tempVal + " °C", col2X + labelPaint.measureText("Temp  "), gridLabelY2, valPaint);

        return mutableBitmap;
    }

    private float drawWrappedText(Canvas canvas, String text, float x, float y, float width, Paint paint) {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        float currentY = y;
        float leading = paint.getFontSpacing();
        int lineCount = 0;

        for (String word : words) {
            String testLine = line.length() == 0 ? word : line.toString() + " " + word;
            float testWidth = paint.measureText(testLine);
            if (testWidth > width) {
                canvas.drawText(line.toString(), x, currentY, paint);
                line = new StringBuilder(word);
                currentY += leading;
                lineCount++;
                if (lineCount >= 2) {
                    break;
                }
            } else {
                line.append(line.length() == 0 ? "" : " ").append(word);
            }
        }
        if (lineCount < 2 && line.length() > 0) {
            canvas.drawText(line.toString(), x, currentY, paint);
            currentY += leading;
        }
        return currentY;
    }

    private void saveImageToPublicPictures(Bitmap bitmap) {
        String fileName = "AquaStack_" + System.currentTimeMillis() + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri itemUri = getContentResolver().insert(collection, values);

        if (itemUri != null) {
            OutputStream out = null;
            try {
                out = getContentResolver().openOutputStream(itemUri);
                if (out != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(itemUri, values, null, null);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (java.io.IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float[] gravity = event.values;
            float ax = gravity[0];
            float ay = gravity[1];
            float az = gravity[2];
            double roll = Math.atan2(-ax, Math.sqrt(ay * ay + az * az)) * 180 / Math.PI;

            float rollAngle = (float) roll;
            if (layoutHorizonLevel != null && layoutHorizonLevel.getVisibility() == View.VISIBLE) {
                if (viewHorizonLine != null) {
                    viewHorizonLine.setRotation(-rollAngle);
                    if (Math.abs(rollAngle) < 1.0f) {
                        viewHorizonLine.setBackgroundColor(Color.parseColor("#FFD700"));
                        findViewById(R.id.level_static_left).setBackgroundColor(Color.parseColor("#FFD700"));
                        findViewById(R.id.level_static_right).setBackgroundColor(Color.parseColor("#FFD700"));
                    } else {
                        viewHorizonLine.setBackgroundColor(Color.WHITE);
                        findViewById(R.id.level_static_left).setBackgroundColor(Color.parseColor("#80FFFFFF"));
                        findViewById(R.id.level_static_right).setBackgroundColor(Color.parseColor("#80FFFFFF"));
                    }
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
        // Re-apply immersive mode when returning to the camera
        hideSystemBars();
        startLocationUpdates();
    }

    private void startLocationUpdates() {
        if (locationManager != null && locationListener != null) {
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 1, locationListener);
                    }
                    if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1, locationListener);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraSound != null) {
            cameraSound.release();
            cameraSound = null;
        }
        timeHandler.removeCallbacks(timeRunnable);
        focusDismissHandler.removeCallbacks(focusDismissRunnable);
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        if (mapSnapshot != null) {
            mapSnapshot.recycle();
            mapSnapshot = null;
        }
        if (mapWebView != null) {
            try {
                android.view.ViewParent parent = mapWebView.getParent();
                if (parent instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) parent).removeView(mapWebView);
                }
                mapWebView.removeAllViews();
                mapWebView.destroy();
            } catch (Exception e) {
                e.printStackTrace();
            }
            mapWebView = null;
        }
    }

    // ── Full immersive sticky mode: hides nav bar ──────────────────────────────
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.navigationBars()
                        | android.view.WindowInsets.Type.statusBars());
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }
}

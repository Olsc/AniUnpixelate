package com.olsc.removemosaic;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {
    private MosaicView mosaicView;
    private ImageProcessor imageProcessor;
    
    private View step1Container, step2Container, step3Container;
    private View loadingOverlay;
    private TextView txtLoadingStatus;
    private com.github.chrisbanes.photoview.PhotoView imgResult;
    
    private Bitmap originalBitmap;
    private Bitmap finalBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 开启全屏沉浸式体验
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // 处理状态栏和导航栏的边距偏移，确保 Toolbar 不被系统栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.app_bar), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, systemBars.top, 0, 0);
            return insets;
        });

        mosaicView = findViewById(R.id.mosaic_view);
        imageProcessor = new ImageProcessor(this);

        step1Container = findViewById(R.id.step_1_container);
        step2Container = findViewById(R.id.step_2_container);
        step3Container = findViewById(R.id.step_3_container);
        loadingOverlay = findViewById(R.id.loading_overlay);
        txtLoadingStatus = findViewById(R.id.txt_loading_status);
        imgResult = findViewById(R.id.img_result);

        // 第一步：导入页面监听
        findViewById(R.id.btn_main_pick).setOnClickListener(v -> pickImage());
        
        // 顶部工具栏监听
        findViewById(R.id.btn_toolbar_pick).setOnClickListener(v -> pickImage());

        // 第二步：框选页面监听
        findViewById(R.id.btn_undo).setOnClickListener(v -> mosaicView.undoLast());
        findViewById(R.id.btn_clear).setOnClickListener(v -> mosaicView.clearSelection());
        findViewById(R.id.btn_to_step3).setOnClickListener(v -> showStep3());

        // 第三步：预览与处理页面监听
        findViewById(R.id.btn_back_to_selection).setOnClickListener(v -> showStep2());
        findViewById(R.id.btn_process_action).setOnClickListener(v -> processImage());
        findViewById(R.id.btn_save).setOnClickListener(v -> saveImage());

        // 默认显示第一步
        showStep1();

        // 检查并显示免责声明
        checkDisclaimer();
    }

    private void checkDisclaimer() {
        android.content.SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        boolean agreed = prefs.getBoolean("disclaimer_agreed", false);

        if (!agreed) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.disclaimer_title)
                    .setMessage(R.string.disclaimer_content)
                    .setCancelable(false)
                    .setPositiveButton(R.string.btn_agree, (dialog, which) -> {
                        prefs.edit().putBoolean("disclaimer_agreed", true).apply();
                    })
                    .setNegativeButton(R.string.btn_exit, (dialog, which) -> {
                        finish();
                    })
                    .show();
        }
    }

    // 图片选择回调
    private final ActivityResultLauncher<Intent> pickImageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    try {
                        InputStream is = getContentResolver().openInputStream(uri);
                        originalBitmap = BitmapFactory.decodeStream(is);
                        mosaicView.setImage(originalBitmap);
                        showStep2();
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(this, getString(R.string.error_load_image), Toast.LENGTH_SHORT).show();
                    }
                }
            }
    );

    // 打开系统相册
    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        pickImageLauncher.launch(intent);
    }

    // 切换到第一步：导入
    private void showStep1() {
        step1Container.setVisibility(View.VISIBLE);
        step2Container.setVisibility(View.GONE);
        step3Container.setVisibility(View.GONE);
        findViewById(R.id.btn_toolbar_pick).setVisibility(View.GONE);
    }

    // 切换到第二步：框选
    private void showStep2() {
        step1Container.setVisibility(View.GONE);
        step2Container.setVisibility(View.VISIBLE);
        step3Container.setVisibility(View.GONE);
        findViewById(R.id.btn_toolbar_pick).setVisibility(View.VISIBLE);
    }

    // 切换到第三步：结果预览
    private void showStep3() {
        if (mosaicView.getRectangles().isEmpty()) {
            Toast.makeText(this, getString(R.string.msg_select_area), Toast.LENGTH_SHORT).show();
            return;
        }
        finalBitmap = mosaicView.getImage();
        imgResult.setImageBitmap(finalBitmap);
        step1Container.setVisibility(View.GONE);
        step2Container.setVisibility(View.GONE);
        step3Container.setVisibility(View.VISIBLE);
        findViewById(R.id.btn_toolbar_pick).setVisibility(View.VISIBLE);
    }

    // AI 修复的核心逻辑
    private void processImage() {
        loadingOverlay.setVisibility(View.VISIBLE);
        txtLoadingStatus.setText(getString(R.string.status_waking_ai));

        new Thread(() -> {
            try {
                // 加载 ONNX 模型
                runOnUiThread(() -> txtLoadingStatus.setText(getString(R.string.status_loading_weights)));
                imageProcessor.loadModel(this);

                // 执行修复算法
                runOnUiThread(() -> txtLoadingStatus.setText(getString(R.string.status_performing_magic)));
                Bitmap resultBitmap = imageProcessor.process(originalBitmap, mosaicView.getRectangles());

                runOnUiThread(() -> {
                    finalBitmap = resultBitmap;
                    imgResult.setImageBitmap(finalBitmap);
                    loadingOverlay.setVisibility(View.GONE);
                    Toast.makeText(this, getString(R.string.status_complete), Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    String errorMsg = getString(R.string.error_process_fail_ext, e.getMessage());
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
                    loadingOverlay.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    // 保存修复后的图像到系统相册
    private void saveImage() {
        if (finalBitmap == null) return;

        String filename = "Unpixelated_" + System.currentTimeMillis() + ".jpg";
        OutputStream fos;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore 保存（Pictures 目录）
                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);
                Uri imageUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
                fos = getContentResolver().openOutputStream(imageUri);
            } else {
                // 旧版本 Android 使用 File 直接保存
                String imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString();
                java.io.File image = new java.io.File(imagesDir, filename);
                fos = new java.io.FileOutputStream(image);
            }

            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            fos.flush();
            fos.close();
            Toast.makeText(this, getString(R.string.msg_save_success), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            e.printStackTrace();
            String errorMsg = getString(R.string.error_save_fail, e.getMessage());
            Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show();
        }
    }
}


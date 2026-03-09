package com.olsc.removemosaic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * ImageProcessor 负责通过 ONNX 运行时执行 AI 图像修复算法。
 */
public class ImageProcessor {

    private OrtSession session;
    private OrtEnvironment env;

    public ImageProcessor(Context context) {
        // 初始化 ONNX 运行环境
        env = OrtEnvironment.getEnvironment();
    }

    /**
     * 从 assets 目录加载 ONNX 模型
     */
    public synchronized void loadModel(Context context) throws Exception {
        if (session != null) return;
        try (InputStream is = context.getAssets().open("model.onnx")) {
            byte[] modelBytes = new byte[is.available()];
            is.read(modelBytes);
            
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            session = env.createSession(modelBytes, options);
        } catch (Exception e) {
            throw new Exception("模型加载失败，请检查 assets 目录下是否存在 model.onnx 文件");
        }
    }


    /**
     * 核心修复逻辑：对选定区域进行局部剪裁、缩放、AI 推理并无缝融合回原图。
     */
    public Bitmap process(Bitmap origImage, List<RectF> rectangles) throws Exception {
        if (session == null) {
            throw new Exception("ONNX 模型未加载。请将 model.onnx 放入 app/src/main/assets/ 目录。");
        }

        int width = origImage.getWidth();
        int height = origImage.getHeight();

        // 创建遮罩层，黑色为保留背景，白色为需要修复的区域
        Bitmap maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas maskCanvas = new Canvas(maskBitmap);
        maskCanvas.drawColor(Color.BLACK);
        Paint whitePaint = new Paint();
        whitePaint.setColor(Color.WHITE);
        whitePaint.setStyle(Paint.Style.FILL);

        int minX = width, minY = height, maxX = 0, maxY = 0;

        // 绘制所有选区并计算外接矩形
        for (RectF rect : rectangles) {
            maskCanvas.drawRect(rect, whitePaint);
            if (rect.left < minX) minX = (int) rect.left;
            if (rect.top < minY) minY = (int) rect.top;
            if (rect.right > maxX) maxX = (int) rect.right;
            if (rect.bottom > maxY) maxY = (int) rect.bottom;
        }
        
        // 如果没有有效选区，直接返回原图
        if (minX >= maxX || minY >= maxY) {
            return origImage;
        }

        // 添加外扩边距，使 AI 推理能感知周围环境
        int padding = 32;
        minX = Math.max(0, minX - padding);
        minY = Math.max(0, minY - padding);
        maxX = Math.min(width, maxX + padding);
        maxY = Math.min(height, maxY + padding);

        int cropW = maxX - minX;
        int cropH = maxY - minY;

        // 裁剪出需要处理的图像块和遮罩块
        Bitmap cropImg = Bitmap.createBitmap(origImage, minX, minY, cropW, cropH);
        Bitmap cropMask = Bitmap.createBitmap(maskBitmap, minX, minY, cropW, cropH);

        // 缩放到模型要求的 256x256 分辨率
        Bitmap resizedImg = Bitmap.createScaledBitmap(cropImg, 256, 256, true);
        Bitmap resizedMask = Bitmap.createScaledBitmap(cropMask, 256, 256, true);

        // 准备 Tensor 数据（FloatBuffer）
        FloatBuffer imgBuffer = FloatBuffer.allocate(1 * 3 * 256 * 256);
        FloatBuffer maskBuffer = FloatBuffer.allocate(1 * 1 * 256 * 256);

        int[] pixelsImg = new int[256 * 256];
        int[] pixelsMask = new int[256 * 256];
        resizedImg.getPixels(pixelsImg, 0, 256, 0, 0, 256, 256);
        resizedMask.getPixels(pixelsMask, 0, 256, 0, 0, 256, 256);

        // 转换数据格式为 NCHW (1, 3, 256, 256)
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < 256 * 256; i++) {
                int color = pixelsImg[i];
                float value = 0;
                if (c == 0) value = Color.red(color) / 255.0f;
                else if (c == 1) value = Color.green(color) / 255.0f;
                else if (c == 2) value = Color.blue(color) / 255.0f;
                imgBuffer.put(c * 256 * 256 + i, value);
            }
        }

        for (int i = 0; i < 256 * 256; i++) {
            int color = pixelsMask[i];
            float value = Color.red(color) / 255.0f > 0.5f ? 1.0f : 0.0f;
            maskBuffer.put(i, value);
        }

        // 创建 ONNX Tensor
        OnnxTensor tImage = OnnxTensor.createTensor(env, imgBuffer, new long[]{1, 3, 256, 256});
        OnnxTensor tMask = OnnxTensor.createTensor(env, maskBuffer, new long[]{1, 1, 256, 256});

        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("image", tImage);
        inputs.put("mask", tMask);

        // 执行 AI 推理
        OrtSession.Result result = session.run(inputs);
        float[][][][] outputArr = (float[][][][]) result.get(0).getValue();
        
        // 从输出提取像素数组 [1, 3, 256, 256]
        int[] outPixels = new int[256 * 256];
        for (int i = 0; i < 256 * 256; i++) {
            float rf = outputArr[0][0][i / 256][i % 256];
            float gf = outputArr[0][1][i / 256][i % 256];
            float bf = outputArr[0][2][i / 256][i % 256];
            
            int r = Math.min(255, Math.max(0, (int) (rf * 255)));
            int g = Math.min(255, Math.max(0, (int) (gf * 255)));
            int b = Math.min(255, Math.max(0, (int) (bf * 255)));
            outPixels[i] = Color.argb(255, r, g, b);
        }

        Bitmap outBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        outBitmap.setPixels(outPixels, 0, 256, 0, 0, 256, 256);

        // 将由于推理导致的 256x256 输出图缩放回裁剪前的大小
        Bitmap resizedOutBitmap = Bitmap.createScaledBitmap(outBitmap, cropW, cropH, true);
        
        // 无缝 Alpha 混合：根据遮罩的灰度图进行线性融合
        Bitmap finalResult = origImage.copy(Bitmap.Config.ARGB_8888, true);
        
        int[] cropOrigPixels = new int[cropW * cropH];
        int[] outResizedPixels = new int[cropW * cropH];
        int[] maskCropPixels = new int[cropW * cropH];
        
        cropImg.getPixels(cropOrigPixels, 0, cropW, 0, 0, cropW, cropH);
        resizedOutBitmap.getPixels(outResizedPixels, 0, cropW, 0, 0, cropW, cropH);
        cropMask.getPixels(maskCropPixels, 0, cropW, 0, 0, cropW, cropH);
        
        for (int i = 0; i < cropW * cropH; i++) {
            float m = Color.red(maskCropPixels[i]) / 255.0f; // 遮罩值
            int origC = cropOrigPixels[i];
            int outC = outResizedPixels[i];
            
            int r = (int) (Color.red(origC) * (1 - m) + Color.red(outC) * m);
            int g = (int) (Color.green(origC) * (1 - m) + Color.green(outC) * m);
            int b = (int) (Color.blue(origC) * (1 - m) + Color.blue(outC) * m);
            
            cropOrigPixels[i] = Color.argb(255, r, g, b);
        }
        
        // 将处理完的小块放回并替换原图中对应的位置
        finalResult.setPixels(cropOrigPixels, 0, cropW, minX, minY, cropW, cropH);

        // 释放资源
        tImage.close();
        tMask.close();
        result.close();

        return finalResult;
    }
}

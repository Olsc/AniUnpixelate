package com.olsc.removemosaic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * MosaicView 用于显示图片并允许用户通过手势进行缩放、平移以及框选区域。
 */
public class MosaicView extends View {
    private Bitmap image;
    private final Matrix baseMatrix = new Matrix(); // 初始适配屏幕的矩阵
    private final Matrix drawMatrix = new Matrix(); // 当前变换矩阵（含缩放平移）
    private final Matrix inverseMatrix = new Matrix(); // 用于将屏幕坐标映射回图片原始坐标

    private final List<RectF> rectangles = new ArrayList<>(); // 已保存的选区列表
    private RectF currentRect = null; // 当前正在绘制的选区

    private final Paint paint = new Paint(); // 选区边界画笔
    private final Paint fillPaint = new Paint(); // 选区填充画笔

    private float startX, startY;
    private ScaleGestureDetector scaleDetector; // 缩放手势检测器
    private boolean isTransforming = false; // 是否正在进行手势变换（缩放或平移）

    // 平移操作的坐标追踪
    private float lastMidX, lastMidY;

    public MosaicView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // 初始化画笔：醒目的红色边框
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5f);
        paint.setAlpha(220);

        fillPaint.setColor(Color.RED);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setAlpha(60);

        // 设置双指缩放监听器
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float scaleFactor = detector.getScaleFactor();
                drawMatrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
                invalidate();
                return true;
            }
        });
    }

    /**
     * 设置显示的图片并重置状态
     */
    public void setImage(Bitmap bitmap) {
        this.image = bitmap;
        rectangles.clear();
        calculateInitialMatrix();
        invalidate();
    }

    public Bitmap getImage() {
        return image;
    }

    public List<RectF> getRectangles() {
        return rectangles;
    }

    /**
     * 计算初始矩阵，使图片居中并完整适配视图
     */
    private void calculateInitialMatrix() {
        if (image == null || getWidth() == 0 || getHeight() == 0) return;

        baseMatrix.reset();
        float scaleX = (float) getWidth() / image.getWidth();
        float scaleY = (float) getHeight() / image.getHeight();
        float scale = Math.min(scaleX, scaleY);

        float dx = (getWidth() - image.getWidth() * scale) / 2f;
        float dy = (getHeight() - image.getHeight() * scale) / 2f;

        baseMatrix.postScale(scale, scale);
        baseMatrix.postTranslate(dx, dy);

        drawMatrix.set(baseMatrix);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (image != null && drawMatrix.isIdentity()) {
            calculateInitialMatrix();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (image != null) {
            // 绘制底图
            canvas.drawBitmap(image, drawMatrix, null);

            // 应用变换矩阵后绘制选区，确保选区跟随图片移动
            canvas.save();
            canvas.concat(drawMatrix);
            for (RectF r : rectangles) {
                canvas.drawRect(r, fillPaint);
                canvas.drawRect(r, paint);
            }
            if (currentRect != null) {
                canvas.drawRect(currentRect, fillPaint);
                canvas.drawRect(currentRect, paint);
            }
            canvas.restore();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (image == null) return false;

        // 优先处理缩放手势
        scaleDetector.onTouchEvent(event);

        int pointerCount = event.getPointerCount();

        // 双指或多指操作：处理平移
        if (pointerCount >= 2) {
            isTransforming = true;
            float midX = (event.getX(0) + event.getX(1)) / 2f;
            float midY = (event.getY(0) + event.getY(1)) / 2f;

            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = midX - lastMidX;
                float dy = midY - lastMidY;
                drawMatrix.postTranslate(dx, dy);
                invalidate();
            }
            lastMidX = midX;
            lastMidY = midY;
            currentRect = null; // 平移过程中取消当前正在绘制的矩形
            return true;
        }

        // 单指逻辑：处理绘图
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            isTransforming = false;
        }

        if (isTransforming) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                isTransforming = false;
            }
            return true;
        }

        // 将触模点从屏幕坐标转换为图片坐标
        drawMatrix.invert(inverseMatrix);
        float[] pts = new float[]{event.getX(), event.getY()};
        inverseMatrix.mapPoints(pts);
        float x = pts[0];
        float y = pts[1];

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                startX = x;
                startY = y;
                currentRect = new RectF(startX, startY, startX, startY);
                break;
            case MotionEvent.ACTION_MOVE:
                if (currentRect != null) {
                    currentRect.set(Math.min(startX, x), Math.min(startY, y),
                            Math.max(startX, x), Math.max(startY, y));
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (currentRect != null) {
                    currentRect.set(Math.min(startX, x), Math.min(startY, y),
                            Math.max(startX, x), Math.max(startY, y));
                    // 只有面积足够大的矩形才被保存
                    if (currentRect.width() > 5 && currentRect.height() > 5) {
                        rectangles.add(new RectF(currentRect));
                    }
                    currentRect = null;
                    invalidate();
                }
                break;
        }
        return true;
    }

    /**
     * 撤销上一个选区
     */
    public void undoLast() {
        if (!rectangles.isEmpty()) {
            rectangles.remove(rectangles.size() - 1);
            invalidate();
        }
    }

    /**
     * 清空所有选区
     */
    public void clearSelection() {
        rectangles.clear();
        invalidate();
    }
    
    /**
     * 重置缩放和平移状态
     */
    public void resetZoom() {
        drawMatrix.set(baseMatrix);
        invalidate();
    }
}


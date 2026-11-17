package fi.nyja.ventapp;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** Pinch-zoom and drag for a single preview bitmap. */
public class ZoomImageView extends ImageView {
    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final ScaleGestureDetector scaleDetector;
    private float lastX;
    private float lastY;
    private int activePointer = -1;

    public ZoomImageView(Context context) {
        this(context, null);
    }

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        post(new Runnable() {
            @Override
            public void run() {
                fit();
            }
        });
    }

    private void fit() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        float width = drawable.getIntrinsicWidth();
        float height = drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        float scale = Math.min(viewWidth / width, viewHeight / height);
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate((viewWidth - width * scale) / 2f, (viewHeight - height * scale) / 2f);
        setImageMatrix(matrix);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            lastX = event.getX();
            lastY = event.getY();
            activePointer = event.getPointerId(0);
        } else if (action == MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress()) {
            int index = event.findPointerIndex(activePointer);
            if (index >= 0) {
                float x = event.getX(index);
                float y = event.getY(index);
                matrix.postTranslate(x - lastX, y - lastY);
                setImageMatrix(matrix);
                lastX = x;
                lastY = y;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            activePointer = -1;
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            int gone = event.getActionIndex();
            if (event.getPointerId(gone) == activePointer) {
                int next = gone == 0 ? 1 : 0;
                if (next < event.getPointerCount()) {
                    lastX = event.getX(next);
                    lastY = event.getY(next);
                    activePointer = event.getPointerId(next);
                }
            }
        }
        return true;
    }

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            matrix.getValues(values);
            float current = values[Matrix.MSCALE_X];
            float next = current * detector.getScaleFactor();
            if (next < 0.4f || next > 8f) {
                return true;
            }
            matrix.postScale(detector.getScaleFactor(), detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
            setImageMatrix(matrix);
            return true;
        }
    }
}

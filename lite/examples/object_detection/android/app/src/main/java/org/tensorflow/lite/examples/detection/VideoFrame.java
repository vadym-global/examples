package org.tensorflow.lite.examples.detection;

import android.graphics.Bitmap;

public interface VideoFrame {
    void onVideoFrameData(Bitmap bmp);
}

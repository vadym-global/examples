/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite.examples.detection;

import static org.opencv.core.Core.BORDER_CONSTANT;
import static org.opencv.core.Core.copyMakeBorder;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.SystemClock;
import android.util.Size;
import android.util.TypedValue;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.tensorflow.lite.examples.detection.customview.OverlayView;
import org.tensorflow.lite.examples.detection.customview.OverlayView.DrawCallback;
import org.tensorflow.lite.examples.detection.env.BorderedText;
import org.tensorflow.lite.examples.detection.env.ImageUtils;
import org.tensorflow.lite.examples.detection.env.Logger;
import org.tensorflow.lite.examples.detection.tflite.Detector;
import org.tensorflow.lite.examples.detection.tflite.TFLiteObjectDetectionAPIModel;
import org.tensorflow.lite.examples.detection.tracking.MultiBoxTracker;

/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
public class DetectorActivity extends CameraActivity implements OnImageAvailableListener {
  private static final Logger LOGGER = new Logger();

  // Configuration values for the prepackaged SSD model.
  private static final int TF_OD_API_INPUT_SIZE = 300;
  private static final boolean TF_OD_API_IS_QUANTIZED = true;
  private static final String TF_OD_API_MODEL_FILE = "detect.tflite";
  private static final String TF_OD_API_LABELS_FILE = "labelmap.txt";
  private static final DetectorMode MODE = DetectorMode.TF_OD_API;
  // Minimum detection confidence to track a detection.
  private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.5f;
  private static final boolean MAINTAIN_ASPECT = false;
  private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);
  private static final boolean SAVE_PREVIEW_BITMAP = false;
  private static final float TEXT_SIZE_DIP = 10;
  OverlayView trackingOverlay;
  private Integer sensorOrientation;
  private Detector detector;
  private long lastProcessingTimeMs;
  private Bitmap rgbFrameBitmap = null;
  private Bitmap croppedBitmap = null;
  private Bitmap cropCopyBitmap = null;
  private boolean computingDetection = false;
  private long timestamp = 0;
  private MultiBoxTracker tracker;
  private BorderedText borderedText;
  private Mat mRgbImageMat;
  private int mPaddingImageSize;
  ToggleButton toggleCrop;
  ToggleButton toggleDisplayCropRegion;
  EditText editTextNumberTopX;
  EditText editTextNumberTopY;
  EditText editTextWidth;
  EditText editTextHeight;

  int topX, topY, width, height;

  int mBottom = 0, mTop = 0, mRight = 0, mLeft = 0;

  private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
    @Override
    public void onManagerConnected(int status) {
      switch (status) {
        case LoaderCallbackInterface.SUCCESS:
        {
          LOGGER.i("OpenCV: " + "OpenCV loaded successfully");
        } break;
        default:
        {
          super.onManagerConnected(status);
        } break;
      }
    }
  };

  @Override
  public void onPreviewSizeChosen(final Size size, final int rotation) {
    LOGGER.d("NDBG onPreviewSizeChosen");
    final float textSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);
    borderedText.setTypeface(Typeface.MONOSPACE);

    tracker = new MultiBoxTracker(this);

    int cropSize = TF_OD_API_INPUT_SIZE;

    try {
      detector =
          TFLiteObjectDetectionAPIModel.create(
              this,
              TF_OD_API_MODEL_FILE,
              TF_OD_API_LABELS_FILE,
              TF_OD_API_INPUT_SIZE,
              TF_OD_API_IS_QUANTIZED);
      cropSize = TF_OD_API_INPUT_SIZE;
    } catch (final IOException e) {
      e.printStackTrace();
      LOGGER.e(e, "Exception initializing Detector!");
      Toast toast =
          Toast.makeText(
              getApplicationContext(), "Detector could not be initialized", Toast.LENGTH_SHORT);
      toast.show();
      finish();
    }

    previewWidth = size.getWidth();
    previewHeight = size.getHeight();

    sensorOrientation = 0;//rotation - getScreenOrientation();
    LOGGER.i("Camera orientation relative to screen canvas: [%d - %d] = %d", rotation, getScreenOrientation(), sensorOrientation);

    LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
    rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
    croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

    // Check OpenCV is loaded!!!
    if (!OpenCVLoader.initDebug()) {
      LOGGER.i("OpenCV: " + "Internal OpenCV library not found. Using OpenCV Manager for initialization");
      OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0, this, mLoaderCallback);
    } else {
      LOGGER.i("OpenCV: " + "OpenCV library found inside package. Using it!");
      mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
    }

    trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
    trackingOverlay.addCallback(
        new DrawCallback() {
          @Override
          public void drawCallback(final Canvas canvas) {
            tracker.draw(canvas);
            if (isDebug()) {
              tracker.drawDebug(canvas);
            }
          }
        });

    tracker.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation);

    editTextNumberTopX = (EditText) findViewById(R.id.editTextNumberTopX);
    editTextNumberTopY = (EditText) findViewById(R.id.editTextNumberTopY);
    editTextWidth = (EditText) findViewById(R.id.editTextNumberWidth);
    editTextHeight = (EditText) findViewById(R.id.editTextNumberHeight);
    toggleCrop = (ToggleButton) findViewById(R.id.toggleButton);
    toggleDisplayCropRegion = (ToggleButton) findViewById(R.id.toggleButtonShowCropRect);


    toggleCrop.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (isChecked) {
          LOGGER.i("Enable crop");
          String x, y, w, h;
          x = editTextNumberTopX.getText().toString();
          y = editTextNumberTopY.getText().toString();
          w = editTextWidth.getText().toString();
          h = editTextHeight.getText().toString();

          if (x == null || x.isEmpty() || y == null || y.isEmpty() ||
                  y == null || y.isEmpty() || y == null || y.isEmpty()) {
            Toast.makeText(
                   getApplicationContext(),
                   "Empty crop data",
                   Toast.LENGTH_LONG).show();
            buttonView.setChecked(false);
            tracker.setCropRectangle(null, false);
            toggleDisplayCropRegion.setChecked(false);
            return;
          }
          topX = Integer.parseInt(x);
          topY = Integer.parseInt(y);
          width = Integer.parseInt(w);
          height = Integer.parseInt(h);

          if ( (topX + width > previewWidth) || (topY + height > previewHeight)) {
            Toast.makeText(
                   getApplicationContext(),
                   "Crop rectangle exceeds the borders of the frame",
                   Toast.LENGTH_SHORT).show();
            buttonView.setChecked(false);
            tracker.setCropRectangle(null, false);
            toggleDisplayCropRegion.setChecked(false);
          } else {
            final RectF cropRectangle = new RectF(topX, topY, topX + width, topY + height);
            tracker.setCropRectangle(cropRectangle, false);
          }

        } else {
          LOGGER.i("Disable crop");
          tracker.setCropRectangle(null, false);
          toggleDisplayCropRegion.setChecked(false);
        }
      }
    });

    toggleDisplayCropRegion.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        final RectF cropRectangle = new RectF(topX, topY, topX + width, topY + height);

        if (isChecked) {
          LOGGER.i("Display crop region");
          if (toggleCrop.isChecked()) {
            // Path cropped rectangle for rendering
            //final RectF cropRectangle = new RectF(160, 120, 480, 360);

            tracker.setCropRectangle(cropRectangle, true);
          } else {
            toggleDisplayCropRegion.setChecked(false);
            Toast.makeText(
                    getApplicationContext(),
                    "Enable crop to display the cropping region",
                    Toast.LENGTH_SHORT).show();
          }
        } else {
          LOGGER.i("Undisplay crop region");
          if (toggleCrop.isChecked()) {
            tracker.setCropRectangle(cropRectangle, false);
          }
        }
      }
    });
  }



  @Override
  protected void processImage() {
    ++timestamp;
    final long currTimestamp = timestamp;
    trackingOverlay.postInvalidate();

    // No mutex needed as this method is not reentrant.
    if (computingDetection) {
      readyForNextImage();
      return;
    }
    computingDetection = true;
    LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread. prevW " + previewWidth + " prevH" + previewHeight);

    rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

    mRgbImageMat = new Mat();
    Utils.bitmapToMat(rgbFrameBitmap, mRgbImageMat);

    int imageWidth, imageHeight;
    Mat cropMat = null, processImage;

    if (toggleCrop.isChecked()) {
      LOGGER.i("toggleCrop is checked!");

      Rect rectCrop = new Rect(topX, topY, width, height);
      cropMat = new Mat(mRgbImageMat, rectCrop);

      Bitmap cropBitmap = Bitmap.createBitmap(width, height, Config.ARGB_8888);
      Utils.matToBitmap(cropMat, cropBitmap);
      ImageUtils.saveBitmap(cropBitmap, "cropped.png");


      imageWidth = width;
      imageHeight = height;
    } else {
      LOGGER.i("toggleCrop is not checked!");
      imageWidth = previewWidth;
      imageHeight = previewHeight;
    }

    Scalar color = new Scalar( 0.0, 0.0, 0.0, 255.0 );
    Mat bordersImage = new Mat();
    Mat resizeImage = new Mat();

    mBottom = 0;
    mTop = 0;
    mRight = 0;
    mLeft = 0;
    if (imageWidth > imageHeight) {
      mBottom = mTop = (imageWidth - imageHeight) / 2;
    } else if (imageWidth < imageHeight){
      mRight = mLeft = (imageHeight - imageWidth) / 2;
    }

    processImage = toggleCrop.isChecked() ? cropMat : mRgbImageMat;
    Core.copyMakeBorder(processImage, bordersImage, mTop, mBottom, mLeft, mRight, BORDER_CONSTANT, color);
    org.opencv.core.Size size = new org.opencv.core.Size(300, 300);
    Imgproc.resize(bordersImage, resizeImage, size );
    Bitmap opencvResizeimageBitmap = Bitmap.createBitmap(300, 300, Config.ARGB_8888);
    Utils.matToBitmap(resizeImage, opencvResizeimageBitmap);

    mPaddingImageSize = Math.max(imageWidth, imageHeight);

    readyForNextImage();

    Utils.matToBitmap(resizeImage, croppedBitmap);

    // For examining the actual TF input.
    if (SAVE_PREVIEW_BITMAP) {

      VideoPlaybackFragment.SaveImage(opencvResizeimageBitmap, (int)timestamp);
      //ImageUtils.saveBitmap(rgbFrameBitmap, "original.png");
      //ImageUtils.saveBitmap(opencvResizeimageBitmap, "opencvresize.png");
    }

    runInBackground(
        new Runnable() {
          @Override
          public void run() {
            LOGGER.i("Running detection on image " + currTimestamp);
            final long startTime = SystemClock.uptimeMillis();
            final List<Detector.Recognition> results = detector.recognizeImage(croppedBitmap);
            lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

            cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
            final Canvas canvas = new Canvas(cropCopyBitmap);
            final Paint paint = new Paint();
            paint.setColor(Color.RED);
            paint.setStyle(Style.STROKE);
            paint.setStrokeWidth(2.0f);

            float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
            switch (MODE) {
              case TF_OD_API:
                minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                break;
            }

            final List<Detector.Recognition> mappedRecognitions =
                new ArrayList<Detector.Recognition>();

            for (final Detector.Recognition result : results) {
              final RectF location = result.getLocation();
              if (location != null && result.getConfidence() >= minimumConfidence) {
                canvas.drawRect(location, paint);

                /////cropToFrameTransform.mapRect(location);
                float resizeRatio = (float)mPaddingImageSize / (float)TF_OD_API_INPUT_SIZE;
                location.top = location.top * resizeRatio - mTop;
                location.bottom = location.bottom * resizeRatio - mBottom;
                location.right = location.right * resizeRatio - mRight;
                location.left = location.left * resizeRatio - mLeft;

                if (toggleCrop.isChecked()) {
                  location.top = location.top + topY;
                  location.bottom = location.bottom + topY;
                  location.right = location.right + topX;
                  location.left = location.left + topX;
                }

                result.setLocation(location);
                mappedRecognitions.add(result);
              }
            }

            tracker.trackResults(mappedRecognitions, currTimestamp);
            trackingOverlay.postInvalidate();

            computingDetection = false;

            runOnUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    showFrameInfo(previewWidth + "x" + previewHeight);
                    showCropInfo(cropCopyBitmap.getWidth() + "x" + cropCopyBitmap.getHeight());
                    showInference(lastProcessingTimeMs + "ms");
                  }
                });
          }
        });
  }

  @Override
  protected int getLayoutId() {
    return R.layout.tfe_od_camera_connection_fragment_tracking;
  }

  @Override
  protected Size getDesiredPreviewFrameSize() {
    return DESIRED_PREVIEW_SIZE;
  }

  // Which detection model to use: by default uses Tensorflow Object Detection API frozen
  // checkpoints.
  private enum DetectorMode {
    TF_OD_API;
  }

  @Override
  protected void setUseNNAPI(final boolean isChecked) {
    runInBackground(
        () -> {
          try {
            detector.setUseNNAPI(isChecked);
          } catch (UnsupportedOperationException e) {
            LOGGER.e(e, "Failed to set \"Use NNAPI\".");
            runOnUiThread(
                () -> {
                  Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
          }
        });
  }

  @Override
  protected void setNumThreads(final int numThreads) {
    runInBackground(
        () -> {
          try {
            detector.setNumThreads(numThreads);
          } catch (IllegalArgumentException e) {
            LOGGER.e(e, "Failed to set multithreads.");
            runOnUiThread(
                () -> {
                  Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
          }
        });
  }

  @Override
  public synchronized void onStop() {
    super.onStop();
    //toggleCrop.setChecked(false);
  }

  @Override
  protected void disableCrop() {
    toggleCrop.setChecked(false);
  }
}

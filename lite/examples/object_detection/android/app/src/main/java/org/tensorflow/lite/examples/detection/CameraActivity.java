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

import android.Manifest;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Trace;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;

import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.tensorflow.lite.examples.detection.env.ImageUtils;
import org.tensorflow.lite.examples.detection.env.Logger;
import org.tensorflow.lite.examples.detection.VideoFrame;

import com.serenegiant.usb.IFrameCallback;

public abstract class CameraActivity extends AppCompatActivity
    implements OnImageAvailableListener,
        Camera.PreviewCallback,
        CompoundButton.OnCheckedChangeListener,
        View.OnClickListener,
        IFrameCallback,
        AdapterView.OnItemSelectedListener,
        VideoFrame {
  private static final Logger LOGGER = new Logger();

  private static final int PERMISSIONS_REQUEST = 1;

  private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
  private static final String PERMISSION_SDCARD = Manifest.permission.READ_EXTERNAL_STORAGE;
  protected int previewWidth = 0;
  protected int previewHeight = 0;
  private boolean debug = false;
  private Handler handler;
  private HandlerThread handlerThread;
  private boolean useCamera2API;
  private boolean isProcessingFrame = false;
  private byte[][] yuvBytes = new byte[3][];
  private int[] rgbBytes = null;
  private int yRowStride;
  private Runnable postInferenceCallback;
  private Runnable imageConverter;

  private LinearLayout bottomSheetLayout;
  private LinearLayout gestureLayout;
  private BottomSheetBehavior<LinearLayout> sheetBehavior;

  protected TextView frameValueTextView, cropValueTextView, inferenceTimeTextView;
  protected ImageView bottomSheetArrowImageView;
  private ImageView plusImageView, minusImageView;
  private SwitchCompat apiSwitchCompat;
  private TextView threadsTextView;
  private Spinner resolutionSpinner;
  private ArrayAdapter<String> adapter;
  private Size cameraResolution;
  private Button openFileButton;
  private Button openCameraButton;
  List<String> supportedResolutions;
  private String currVideofile = "";
  private Fragment mFragment;

  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    LOGGER.d("onCreate " + this);
    super.onCreate(null);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    setContentView(R.layout.tfe_od_activity_camera);
    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    getSupportActionBar().setDisplayShowTitleEnabled(false);


    if (hasPermission()) {
      setFragment(true, null);
    } else {
      requestPermission();
    }

    threadsTextView = findViewById(R.id.threads);
    plusImageView = findViewById(R.id.plus);
    minusImageView = findViewById(R.id.minus);
    apiSwitchCompat = findViewById(R.id.api_info_switch);
    bottomSheetLayout = findViewById(R.id.bottom_sheet_layout);
    gestureLayout = findViewById(R.id.gesture_layout);
    sheetBehavior = BottomSheetBehavior.from(bottomSheetLayout);
    bottomSheetArrowImageView = findViewById(R.id.bottom_sheet_arrow);
    resolutionSpinner = findViewById(R.id.resolutionSpinner);
    openFileButton = findViewById(R.id.button_open_video);
    openCameraButton = findViewById(R.id.button_open_camera);

    cameraResolution = new Size(640, 480);

    supportedResolutions = new ArrayList<String>();
    supportedResolutions.add("" + cameraResolution.getWidth() + "x" + cameraResolution.getHeight());

    adapter = new ArrayAdapter<String>(getApplicationContext(),
            android.R.layout.simple_spinner_item, supportedResolutions);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    resolutionSpinner.setAdapter(adapter);
    resolutionSpinner.setOnItemSelectedListener(this);
    openFileButton.setOnClickListener(buttonOpenClick);
    openCameraButton.setOnClickListener(buttonCameraOpenClick);

    ViewTreeObserver vto = gestureLayout.getViewTreeObserver();
    vto.addOnGlobalLayoutListener(
        new ViewTreeObserver.OnGlobalLayoutListener() {
          @Override
          public void onGlobalLayout() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
              gestureLayout.getViewTreeObserver().removeGlobalOnLayoutListener(this);
            } else {
              gestureLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
            //                int width = bottomSheetLayout.getMeasuredWidth();
            int height = gestureLayout.getMeasuredHeight();

            sheetBehavior.setPeekHeight(height);
          }
        });
    sheetBehavior.setHideable(false);

    sheetBehavior.setBottomSheetCallback(
        new BottomSheetBehavior.BottomSheetCallback() {
          @Override
          public void onStateChanged(@NonNull View bottomSheet, int newState) {
            switch (newState) {
              case BottomSheetBehavior.STATE_HIDDEN:
                break;
              case BottomSheetBehavior.STATE_EXPANDED:
                {
                  bottomSheetArrowImageView.setImageResource(R.drawable.icn_chevron_down);
                }
                break;
              case BottomSheetBehavior.STATE_COLLAPSED:
                {
                  bottomSheetArrowImageView.setImageResource(R.drawable.icn_chevron_up);
                }
                break;
              case BottomSheetBehavior.STATE_DRAGGING:
                break;
              case BottomSheetBehavior.STATE_SETTLING:
                bottomSheetArrowImageView.setImageResource(R.drawable.icn_chevron_up);
                break;
            }
          }

          @Override
          public void onSlide(@NonNull View bottomSheet, float slideOffset) {}
        });

    frameValueTextView = findViewById(R.id.frame_info);
    cropValueTextView = findViewById(R.id.crop_info);
    inferenceTimeTextView = findViewById(R.id.inference_info);

    apiSwitchCompat.setOnCheckedChangeListener(this);

    plusImageView.setOnClickListener(this);
    minusImageView.setOnClickListener(this);
  }

  @Override
  public void onItemSelected(AdapterView<?> parent, View view,
          int pos, long id) {
    // An item was selected. You can retrieve the selected item using
    // parent.getItemAtPosition(pos)
    String currentResolution = resolutionToString(cameraResolution);
    String resolution = (String) parent.getItemAtPosition(pos);
    if (resolution.equals(currentResolution) == false) {
      resolutionSpinner.setSelection(pos);
      adapter.notifyDataSetChanged();
      String [] res = resolution.split("x");
      cameraResolution = resolutionFromString(resolution);

      disableCrop();
      setFragment(true, cameraResolution);
    }
  }

  private Size resolutionFromString(String input) {
    String [] res = input.split("x");
    return new Size(Integer.parseInt(res[0]), Integer.parseInt(res[1]));
  }

  private String resolutionToString(Size input) {
    return "" + input.getWidth() + "x" + input.getHeight();
  }

  @Override
  public void onNothingSelected(AdapterView<?> parent) {
  }

  protected int[] getRgbBytes() {
    imageConverter.run();
    return rgbBytes;
  }

  protected int getLuminanceStride() {
    return yRowStride;
  }

  protected byte[] getLuminance() {
    return yuvBytes[0];
  }

  protected  View.OnClickListener buttonOpenClick = new View.OnClickListener() {
    @Override
    public void onClick(View v) {
      LOGGER.d("Button on click");
      OpenFileDialog fileDialog = new OpenFileDialog(CameraActivity.this);
      OpenFileDialog.OpenDialogListener  listener = new OpenFileDialog.OpenDialogListener() {
        @Override
        public void OnSelectedFile(String fileName) {
          currVideofile = fileName;
          LOGGER.d("Video file " + currVideofile);
          setFragment(false, cameraResolution);
        }
      };
      fileDialog.setOpenDialogListener(listener);
      fileDialog.show();
    }
  };

  protected  View.OnClickListener buttonCameraOpenClick = new View.OnClickListener() {
    @Override
    public void onClick(View v) {
      LOGGER.d("Button camera on click");
      disableCrop();
      setFragment(true, cameraResolution);
    }
  };

  /** Callback for android.hardware.Camera API */
  @Override
  public void onPreviewFrame(final byte[] bytes, final Camera camera) {
    if (isProcessingFrame) {
      LOGGER.w("Dropping frame!");
      return;
    }

    try {
      // Initialize the storage bitmaps once when the resolution is known.
      if (rgbBytes == null) {
        Camera.Size previewSize = camera.getParameters().getPreviewSize();
        previewHeight = previewSize.height;
        previewWidth = previewSize.width;
        rgbBytes = new int[previewWidth * previewHeight];
        onPreviewSizeChosen(new Size(previewSize.width, previewSize.height), 90);
      }
    } catch (final Exception e) {
      LOGGER.e(e, "Exception!");
      return;
    }

    isProcessingFrame = true;
    yuvBytes[0] = bytes;
    yRowStride = previewWidth;

    imageConverter =
        new Runnable() {
          @Override
          public void run() {
            ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes);
          }
        };

    postInferenceCallback =
        new Runnable() {
          @Override
          public void run() {
            camera.addCallbackBuffer(bytes);
            isProcessingFrame = false;
          }
        };
    processImage();
  }

  /** Callback for Camera2 API */
  @Override
  public void onImageAvailable(final ImageReader reader) {
    // We need wait until we have some size from onPreviewSizeChosen

    if (previewWidth == 0 || previewHeight == 0) {
      return;
    }
    if (rgbBytes == null) {
      rgbBytes = new int[previewWidth * previewHeight];
    }
    try {
      final Image image = reader.acquireLatestImage();

      if (image == null) {
        return;
      }

      if (isProcessingFrame) {
        image.close();
        return;
      }
      isProcessingFrame = true;
      Trace.beginSection("imageAvailable");
      final Plane[] planes = image.getPlanes();
      fillBytes(planes, yuvBytes);
      yRowStride = planes[0].getRowStride();
      final int uvRowStride = planes[1].getRowStride();
      final int uvPixelStride = planes[1].getPixelStride();

      imageConverter =
          new Runnable() {
            @Override
            public void run() {
              ImageUtils.convertYUV420ToARGB8888(
                  yuvBytes[0],
                  yuvBytes[1],
                  yuvBytes[2],
                  previewWidth,
                  previewHeight,
                  yRowStride,
                  uvRowStride,
                  uvPixelStride,
                  rgbBytes);
            }
          };

      postInferenceCallback =
          new Runnable() {
            @Override
            public void run() {
              image.close();
              isProcessingFrame = false;
            }
          };

      processImage();
    } catch (final Exception e) {
      LOGGER.e(e, "Exception!");
      Trace.endSection();
      return;
    }
    Trace.endSection();
  }

  @Override
  public void onFrame(final ByteBuffer frame) {
    LOGGER.d("onFrame start");
    if (previewWidth == 0 || previewHeight == 0) {
      LOGGER.d("We need wait until we have some size from onPreviewSizeChosen");
      return;
    }
    if (rgbBytes == null) {
      rgbBytes = new int[previewWidth * previewHeight];
    }
    try {
      if (frame == null) {
        LOGGER.d("frame == null");
        return;
      }

      if (isProcessingFrame) {
        LOGGER.d("Drop frame");
        frame.clear();
        return;
      }
      isProcessingFrame = true;
      LOGGER.d("imageAvailable");
      Trace.beginSection("imageAvailable");

      imageConverter =
              new Runnable() {
                @Override
                public void run() {
                  byte [] input = new byte[frame.capacity()];
                  frame.get(input, 0, input.length);
                  ImageUtils.convertYUV420SPToARGB8888(
                          input,
                          previewWidth,
                          previewHeight,
                          rgbBytes);

                  LOGGER.d("frame: " + frame.getInt(0) + " " + frame.getInt(1) + " " + frame.getInt(2) + " " + frame.getInt(3));
                  LOGGER.d("rgbBytes: " + rgbBytes[0] + " " + rgbBytes[1] + " " + rgbBytes[2] + " " + rgbBytes[3]);
                }
              };

      postInferenceCallback =
              new Runnable() {
                @Override
                public void run() {
                  frame.clear();
                  isProcessingFrame = false;
                }
              };

      processImage();
      LOGGER.d("onFrame end");
    } catch (final Exception e) {
      LOGGER.e(e, "onFrame Exception!");
      Trace.endSection();
      return;
    }
    Trace.endSection();
  }

  @Override
  public void onVideoFrameData(Bitmap bmp) {
    LOGGER.d("onVideoFrame start prev pW " + previewWidth +" ph " + previewHeight);
    if (bmp == null ) {
      LOGGER.d("VidoeFrame == null");
      return;
    }
    previewHeight = bmp.getHeight();
    previewWidth = bmp.getWidth();

    if (previewWidth == 0 || previewHeight == 0) {
      LOGGER.d("We need wait until we have some size from onPreviewSizeChosen");
      return;
    }
    if (rgbBytes == null) {
      rgbBytes = new int[bmp.getByteCount()/4];
    }

    if (isProcessingFrame) {
      LOGGER.d("Drop frame");
      return;
    }

    isProcessingFrame = true;
    LOGGER.d("VideoFrame: imageAvailable");

    imageConverter =
            new Runnable() {
              @Override
              public void run() {
                LOGGER.d("converter not needed ");
                int imageSize = bmp.getRowBytes() * bmp.getHeight();
                IntBuffer uncompressedBuffer = IntBuffer.allocate( imageSize);
                bmp.copyPixelsToBuffer(uncompressedBuffer);

                rgbBytes = Arrays.copyOfRange(uncompressedBuffer.array(),0,uncompressedBuffer.array().length);
              }
            };

    postInferenceCallback =
            new Runnable() {
              @Override
              public void run() {
                  isProcessingFrame = false;
              }
            };

    processImage();
    LOGGER.d("onVideoFrame end");
  }


  @Override
  public synchronized void onStart() {
    LOGGER.d("onStart " + this);
    super.onStart();
  }

  @Override
  public synchronized void onResume() {
    LOGGER.d("onResume " + this);
    super.onResume();

    handlerThread = new HandlerThread("inference");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());

    setFragment(true, cameraResolution);
  }

  @Override
  public synchronized void onPause() {
    LOGGER.d("onPause " + this);

    handlerThread.quitSafely();
    try {
      handlerThread.join();
      handlerThread = null;
      handler = null;
    } catch (final InterruptedException e) {
      LOGGER.e(e, "Exception!");
    }

    super.onPause();
  }

  @Override
  public synchronized void onStop() {
    LOGGER.d("onStop " + this);
    super.onStop();
  }

  @Override
  public synchronized void onDestroy() {
    LOGGER.d("onDestroy " + this);
    super.onDestroy();
  }

  protected synchronized void runInBackground(final Runnable r) {
    if (handler != null) {
      handler.post(r);
    }
  }

  @Override
  public void onRequestPermissionsResult(
      final int requestCode, final String[] permissions, final int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == PERMISSIONS_REQUEST) {
      if (allPermissionsGranted(grantResults)) {
        setFragment(true, null);
      } else {
        requestPermission();
      }
    }
  }

  private static boolean allPermissionsGranted(final int[] grantResults) {
    for (int result : grantResults) {
      if (result != PackageManager.PERMISSION_GRANTED) {
        return false;
      }
    }
    return true;
  }

  private boolean hasPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      int camera = checkSelfPermission(PERMISSION_CAMERA);
      int sdcard = checkCallingPermission(PERMISSION_SDCARD);
      return (camera == PackageManager.PERMISSION_GRANTED) && (sdcard == PackageManager.PERMISSION_GRANTED);
    } else {
      return true;
    }
  }

  private void requestPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA)) {
        Toast.makeText(
                CameraActivity.this,
                "Camera permission is required for this demo",
                Toast.LENGTH_LONG)
            .show();
      }
      if (shouldShowRequestPermissionRationale(PERMISSION_SDCARD)) {
        Toast.makeText(
                CameraActivity.this,
                "Read SDCARD permission is required for this demo",
                Toast.LENGTH_LONG)
                .show();
      }
      requestPermissions(new String[] {PERMISSION_CAMERA, PERMISSION_SDCARD}, PERMISSIONS_REQUEST);
    }
  }

  // Returns true if the device supports the required hardware level, or better.
  private boolean isHardwareLevelSupported(
      CameraCharacteristics characteristics, int requiredLevel) {
    int deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
    if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
      return requiredLevel == deviceLevel;
    }
    // deviceLevel is not LEGACY, can use numerical sort
    return requiredLevel <= deviceLevel;
  }

  private String chooseCamera() {

    final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

    try {
      for (final String cameraId : manager.getCameraIdList()) {
        final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

        // We don't use a front facing camera in this sample.
        final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
          continue;
        }

        final StreamConfigurationMap map =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        if (map == null) {
          continue;
        }

        // Fallback to camera1 API for internal cameras that don't have full support.
        // This should help with legacy situations where using the camera2 API causes
        // distorted or otherwise broken previews.
        useCamera2API =
            (facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
                || isHardwareLevelSupported(
                    characteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
        LOGGER.i("Camera API lv2?: %s", useCamera2API);
        return cameraId;
      }
    } catch (CameraAccessException e) {
      LOGGER.e(e, "Not allowed to access camera");
    }

    LOGGER.i("Return camera NULL");
    return null;
  }


  protected void setFragment(boolean isCamera, Size cameraResolution) {
    String cameraId = chooseCamera();

    if (isCamera == false) {
      if (!"".equals(currVideofile)) {
        LOGGER.e("Set current file name =  " + currVideofile);

        VideoPlaybackFragment video2Fragment = VideoPlaybackFragment.newInstance(
                new VideoPlaybackFragment.ConnectionCallback() {
                  @Override
                  public void onPreviewSizeChosen(Size size, int cameraRotation) {
                    previewHeight = size.getHeight();
                    previewWidth = size.getWidth();
                    rgbBytes = null;
                    CameraActivity.this.onPreviewSizeChosen(size, cameraRotation);
                  }
                },
                this,
                this,
                getLayoutId(),
                getDesiredPreviewFrameSize(),
                currVideofile
        );
        mFragment = video2Fragment;
      }
    } else {

        ExternalCameraConnectionFragment externalCameraFrag = ExternalCameraConnectionFragment.newInstance(
              new ExternalCameraConnectionFragment.ConnectionCallback() {
                @Override
                public void onPreviewSizeChosen(final Size size, final int rotation) {
                  previewHeight = size.getHeight();
                  previewWidth = size.getWidth();
                  rgbBytes = null;
                  CameraActivity.this.onPreviewSizeChosen(size, rotation);
                }
              },
              this,
              getLayoutId(),
              cameraResolution == null ? getDesiredPreviewFrameSize() : cameraResolution,
              new ExternalCameraConnectionFragment.SupportedResolutionsCallback() {
                @Override
                public void onSupportedResolutionsFound(List<Size> resolutions) {
                  for (int i = 0; i < resolutions.size(); i++) {
                    String resolution = "" + resolutions.get(i).getWidth()  + "x" + resolutions.get(i).getHeight();
                    if (supportedResolutions.contains(resolution) == false) {
                      adapter.add(resolution);
                    }

                  }
                  adapter.notifyDataSetChanged();
                }
              });

        mFragment = externalCameraFrag;
    }

    if (mFragment != null) {
      getFragmentManager().beginTransaction().replace(R.id.container, mFragment).commit();
    }
  }

  protected void fillBytes(final Plane[] planes, final byte[][] yuvBytes) {
    // Because of the variable row stride it's not possible to know in
    // advance the actual necessary dimensions of the yuv planes.
    for (int i = 0; i < planes.length; ++i) {
      final ByteBuffer buffer = planes[i].getBuffer();
      if (yuvBytes[i] == null) {
        LOGGER.d("Initializing buffer %d at size %d", i, buffer.capacity());
        yuvBytes[i] = new byte[buffer.capacity()];
      }
      buffer.get(yuvBytes[i]);
    }
  }

  public boolean isDebug() {
    return debug;
  }

  protected void readyForNextImage() {
    if (postInferenceCallback != null) {
      postInferenceCallback.run();
    }
  }

  protected int getScreenOrientation() {
    switch (getWindowManager().getDefaultDisplay().getRotation()) {
      case Surface.ROTATION_270:
        return 270;
      case Surface.ROTATION_180:
        return 180;
      case Surface.ROTATION_90:
        return 90;
      default:
        return 0;
    }
  }

  @Override
  public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
    setUseNNAPI(isChecked);
    if (isChecked) apiSwitchCompat.setText("NNAPI");
    else apiSwitchCompat.setText("TFLITE");
  }

  @Override
  public void onClick(View v) {
    if (v.getId() == R.id.plus) {
      String threads = threadsTextView.getText().toString().trim();
      int numThreads = Integer.parseInt(threads);
      if (numThreads >= 9) return;
      numThreads++;
      threadsTextView.setText(String.valueOf(numThreads));
      setNumThreads(numThreads);
    } else if (v.getId() == R.id.minus) {
      String threads = threadsTextView.getText().toString().trim();
      int numThreads = Integer.parseInt(threads);
      if (numThreads == 1) {
        return;
      }
      numThreads--;
      threadsTextView.setText(String.valueOf(numThreads));
      setNumThreads(numThreads);
    }
  }

  protected void showFrameInfo(String frameInfo) {
    frameValueTextView.setText(frameInfo);
  }

  protected void showCropInfo(String cropInfo) {
    cropValueTextView.setText(cropInfo);
  }

  protected void showInference(String inferenceTime) {
    inferenceTimeTextView.setText(inferenceTime);
  }

  protected abstract void processImage();

  protected abstract void onPreviewSizeChosen(final Size size, final int rotation);

  protected abstract int getLayoutId();

  protected abstract Size getDesiredPreviewFrameSize();

  protected abstract void setNumThreads(int numThreads);

  protected abstract void setUseNNAPI(boolean isChecked);

  protected abstract void disableCrop();
}

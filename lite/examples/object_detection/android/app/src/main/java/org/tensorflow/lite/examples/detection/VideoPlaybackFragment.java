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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Fragment;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.tensorflow.lite.examples.detection.customview.AutoFitTextureView;
import org.tensorflow.lite.examples.detection.env.Logger;

@SuppressLint("ValidFragment")
public class VideoPlaybackFragment extends Fragment {
    private static final Logger LOGGER = new Logger();

    /**
     * The camera preview size will be chosen to be the smallest frame by pixel size capable of
     * containing a DESIRED_SIZE x DESIRED_SIZE square.
     */
    private static final int MINIMUM_PREVIEW_SIZE = 320;

    /** Conversion from screen rotation to JPEG orientation. */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    private static final String FRAGMENT_DIALOG = "dialog";

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private MediaPlayer xMediaPlayer;
    private MediaPlayer xediaPlayer2;
    private ImageReader mImageReader;
    AssetFileDescriptor fileDescriptor;

    /** A {@link OnImageAvailableListener} to receive frames as they are available. */
    private final OnImageAvailableListener imageListener;
    /** The input size in pixels desired by TensorFlow (width and height of a square bitmap). */
    private final Size inputSize;
    /** The layout identifier to inflate for this Fragment. */
    private final int layout;
    /** An {@link AutoFitTextureView} for camera preview. */
    private AutoFitTextureView textureView;
    /** An additional thread for running tasks that shouldn't block the UI. */
    private HandlerThread backgroundThread;
    /** A {@link Handler} for running tasks in the background. */
    private Handler backgroundHandler;
    /** The {@link Size} of camera preview. */
    private Size previewSize;
    private final VideoFrame mFrameCallback;
    private final String vidname;


    CountDownTimer mFrameTimer;
    int framecount = 0;

    public interface ConnectionCallback {
        void onPreviewSizeChosen(Size size, int cameraRotation);
    }

    private final ConnectionCallback playbackConnectionCallback;

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a {@link
     * TextureView}.
     */
    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @SuppressLint("SdCardPath")
                @Override
                public void onSurfaceTextureAvailable(
                        final SurfaceTexture texture, final int width, final int height) {
                    //openCamera(width, height);
                    previewSize = new Size(640, 480);

                    textureView.setAspectRatio(640,480);
                    LOGGER.d("width = " + width + " height = " + height);
                    Surface surface = new Surface(texture);
                    try {
                        xMediaPlayer.setSurface(surface);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            //xMediaPlayer.setDataSource( Environment.getExternalStorageDirectory()+"/Movies/t3.mp4");

                            AssetManager assetManager = getContext().getAssets();
                            String[] files = null;

                            if(vidname != null && !vidname.trim().isEmpty()) {
                                xMediaPlayer.setDataSource(vidname);
                            } else {
                                LOGGER.e("Video file does not exist.");
                                return;
                            }

                            configureTransform(width, height);

                            xMediaPlayer.prepareAsync();
                            xMediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                                @Override
                                public void onPrepared(MediaPlayer mp) {
                                    mFrameTimer = new CountDownTimer(xMediaPlayer.getDuration(),50) {

                                        @Override
                                        public void onTick(long millisUntilFinished) {
                                            framecount++;
                                            Bitmap bmp = textureView.getBitmap();
                                            LOGGER.d("Loop BMP count = " + bmp.getByteCount() +
                                                    "config = " + bmp.getConfig() +
                                                    "H " + bmp.getHeight() + " W " + bmp.getWidth());
                                            Bitmap retBmp = Bitmap.createScaledBitmap(bmp, 640, 480, false);

                                            //SaveImage(retBmp, framecount);

                                            mFrameCallback.onVideoFrameData(retBmp);
                                        }

                                        @Override
                                        public void onFinish() {

                                        }
                                    };
                                    mFrameTimer.start();
                                    xMediaPlayer.start();
                                }
                            });

                            playbackConnectionCallback.onPreviewSizeChosen(previewSize, 0);
                        }

                    }
                    catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                        final SurfaceTexture texture, final int width, final int height) {
                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(final SurfaceTexture texture) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(final SurfaceTexture texture) {}
            };

    private VideoPlaybackFragment(
            final ConnectionCallback connectionCallback,
            final OnImageAvailableListener imageListener,
            final VideoFrame frameCallback,
            final int layout,
            final Size inputSize,
            final String name) {
        this.playbackConnectionCallback = connectionCallback;
        this.imageListener = imageListener;
        this.mFrameCallback = frameCallback;
        this.layout = layout;
        this.inputSize = inputSize;
        this.vidname = name;
    }

    public static void SaveImage(Bitmap finalBitmap, int cnt) {

        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
        File myDir = new File(root + "/saved_images");
        myDir.mkdirs();

        String fname = "Image-"+ cnt +".jpg";
        File file = new File (myDir, fname);
        LOGGER.d("save file !!! " + file.getAbsolutePath());
        if (file.exists ()) file.delete ();
        try {
            FileOutputStream out = new FileOutputStream(file);
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.flush();
            out.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public static VideoPlaybackFragment newInstance(
            final ConnectionCallback callback,
            final OnImageAvailableListener imageListener,
            final VideoFrame frameCallback,
            final int layout,
            final Size inputSize,
            final String name) {
        return new VideoPlaybackFragment(callback,imageListener, frameCallback, layout, inputSize,name);
    }

    @Override
    public View onCreateView(
            final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        return inflater.inflate(layout, container, false);
    }

    @Override
    public void onViewCreated(final View view, final Bundle savedInstanceState) {
        textureView = (AutoFitTextureView) view.findViewById(R.id.texture);
    }

    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        xMediaPlayer = new MediaPlayer();
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (textureView.isAvailable()) {
            //openCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        mFrameTimer.cancel();
        stopBackgroundThread();
        super.onPause();
    }


    public Bitmap getBitmap(){
        return  textureView.getBitmap();
    }

    /** Starts a background thread and its {@link Handler}. */
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("ImageListener");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    /** Stops the background thread and its {@link Handler}. */
    private void stopBackgroundThread() {
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (final InterruptedException e) {
            LOGGER.e(e, "Exception!");
        }
    }

    /**
     * Configures the necessary {@link Matrix} transformation to `mTextureView`. This method should be
     * called after the camera preview size is determined in setUpCameraOutputs and also the size of
     * `mTextureView` is fixed.
     *
     * @param viewWidth The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(final int viewWidth, final int viewHeight) {
        final Activity activity = getActivity();
        if (null == textureView || null == previewSize || null == activity) {
            return;
        }
        final int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        final Matrix matrix = new Matrix();
        final RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        final RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        final float centerX = viewRect.centerX();
        final float centerY = viewRect.centerY();
        /*if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            final float scale =
                    Math.max(
                            (float) viewHeight / previewSize.getHeight(),
                            (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {*/
            matrix.postRotate(0, centerX, centerY);
        //}
        textureView.setTransform(matrix);
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the minimum of both, or an exact match if possible.
     *
     * @param choices The list of sizes that the camera supports for the intended output class
     * @param width The minimum desired width
     * @param height The minimum desired height
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    protected static Size chooseOptimalSize(final Size[] choices, final int width, final int height) {
        final int minSize = Math.max(Math.min(width, height), MINIMUM_PREVIEW_SIZE);
        final Size desiredSize = new Size(width, height);

        // Collect the supported resolutions that are at least as big as the preview Surface
        boolean exactSizeFound = false;
        final List<Size> bigEnough = new ArrayList<Size>();
        final List<Size> tooSmall = new ArrayList<Size>();
        for (final Size option : choices) {
            if (option.equals(desiredSize)) {
                // Set the size but don't return yet so that remaining sizes will still be logged.
                exactSizeFound = true;
            }

            if (option.getHeight() >= minSize && option.getWidth() >= minSize) {
                bigEnough.add(option);
            } else {
                tooSmall.add(option);
            }
        }

        LOGGER.i("Desired size: " + desiredSize + ", min size: " + minSize + "x" + minSize);
        LOGGER.i("Valid preview sizes: [" + TextUtils.join(", ", bigEnough) + "]");
        LOGGER.i("Rejected preview sizes: [" + TextUtils.join(", ", tooSmall) + "]");

        if (exactSizeFound) {
            LOGGER.i("Exact size match found.");
            return desiredSize;
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            final Size chosenSize = Collections.min(bigEnough, new CameraConnectionFragment.CompareSizesByArea());
            LOGGER.i("Chosen size: " + chosenSize.getWidth() + "x" + chosenSize.getHeight());
            return chosenSize;
        } else {
            LOGGER.e("Couldn't find any suitable preview size");
            return choices[0];
        }
    }
}

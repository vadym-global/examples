
package org.tensorflow.lite.examples.detection;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.usb.UsbDevice;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import org.tensorflow.lite.examples.detection.customview.AutoFitTextureView;

import java.nio.ByteBuffer;
import java.util.List;

@SuppressLint("ValidFragment")
public class ExternalCameraConnectionFragment extends Fragment {

    private static final String TAG = "ExternalCameraFragment";
    /** The input size in pixels desired by TensorFlow (width and height of a square bitmap). */
    private final Size inputSize;
    /** The {@link Size} of camera preview. */
    private Size previewSize;
    /** The layout identifier to inflate for this Fragment. */
    private final int layout;
    /** An {@link AutoFitTextureView} for camera preview. */
    private AutoFitTextureView textureView;
    /**
     * for accessing USB
     */
    private USBMonitor mUSBMonitor;

    private UVCCamera mUVCCamera;
    private Surface mPreviewSurface;
    private final Object mSync = new Object();

    private final IFrameCallback mIFrameCallback;

    private final ConnectionCallback cameraConnectionCallback;

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a {@link
     * TextureView}.
     */
    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(
                        final SurfaceTexture texture, final int width, final int height) {
                    Log.v(TAG, "onSurfaceTextureAvailable(): " + width + "x" + height);
                    //openCamera(width, height);
                    setUpCameraOutputs();
                    configureTransform(previewSize.getWidth(), previewSize.getHeight());

                    synchronized (mSync) {
                        if (mUVCCamera != null) {
                            mUVCCamera.startPreview();
                        }
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                        final SurfaceTexture texture, final int width, final int height) {
                    Log.v(TAG, "onSurfaceTextureSizeChanged(): " + width + "x" + height);
                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(final SurfaceTexture texture) {
                    Log.v(TAG, "onSurfaceTextureDestroyed()");
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(final SurfaceTexture texture) {
                    Log.v(TAG, "onSurfaceTextureUpdated()");
                }
            };

    private ExternalCameraConnectionFragment(
            final ConnectionCallback connectionCallback,
            final IFrameCallback frameCallback,
            final int layout,
            final Size inputSize) {
        this.cameraConnectionCallback = connectionCallback;
        this.mIFrameCallback = frameCallback;
        this.layout = layout;
        this.inputSize = inputSize;
        Log.v(TAG, "ExternalCameraConnectionFragment()");
    }

    public static ExternalCameraConnectionFragment newInstance(
            final ConnectionCallback connectionCallback,
            final IFrameCallback frameCallback,
            final int layout,
            final Size inputSize) {
        Log.v(TAG, "newInstance:");
        return new ExternalCameraConnectionFragment(connectionCallback, frameCallback, layout, inputSize);
    }

    @Override
    public View onCreateView(
            final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        Log.v(TAG, "onCreateView");
        return inflater.inflate(layout, container, false);
    }

    @Override
    public void onViewCreated(final View view, final Bundle savedInstanceState) {
        Log.v(TAG, "onViewCreated");
        textureView = (AutoFitTextureView) view.findViewById(R.id.texture);
    }

    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        Log.v(TAG, "onActivityCreated");
        super.onActivityCreated(savedInstanceState);
    }

    @Override
    public void onResume() {
        Log.v(TAG, "onResume");
        super.onResume();

        final Activity activity = getActivity();
        Log.v(TAG, "mUSBMonitor()");
        mUSBMonitor = new USBMonitor(activity, mOnDeviceConnectListener);
        Log.v(TAG, "mUSBMonitor() end");
        mUSBMonitor.register();



        //final int orientation = getResources().getConfiguration().orientation;
        //if (orientation != Configuration.ORIENTATION_LANDSCAPE) {
        //    textureView.setAspectRatio(inputSize.getWidth(), inputSize.getHeight());
        //} else {
        //    textureView.setAspectRatio(inputSize.getHeight(), inputSize.getWidth());
        //}



        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (textureView.isAvailable()) {
            //openCamera(textureView.getWidth(), textureView.getHeight());
            setUpCameraOutputs();
            configureTransform(previewSize.getWidth(), previewSize.getHeight());

            synchronized (mSync) {
                if (mUVCCamera != null) {
                    mUVCCamera.startPreview();
                }
            }
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        synchronized (mSync) {
            if (mUVCCamera != null) {
                mUVCCamera.stopPreview();
            }
            if (mUSBMonitor != null) {
                mUSBMonitor.unregister();
            }
        }
        Log.v(TAG, "onPause 5");
        super.onPause();
    }

    @Override
    public void onStop() {
        Log.v(TAG, "onStop");
        synchronized (mSync) {
            releaseCamera();
            if (mUSBMonitor != null) {
                mUSBMonitor.unregister();
                mUSBMonitor.destroy();
                mUSBMonitor = null;
            }
        }

        super.onStop();
    }

    private final USBMonitor.OnDeviceConnectListener mOnDeviceConnectListener
            = new USBMonitor.OnDeviceConnectListener() {

        @Override
        public void onAttach(final UsbDevice device) {
            Log.v(TAG, "USBMonitor onAttach");
            final Activity activity = getActivity();
            Toast.makeText(activity, "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show();
            synchronized (activity) {
                Log.v(TAG, "USBMonitor requestPermission");
                mUSBMonitor.requestPermission(device);
            }
        }

        @Override
        public void onConnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock, final boolean createNew) {
            Log.v(TAG, "USBMonitor onConnect() start");
            releaseCamera();
            Log.v(TAG, "releaseCamera end");
            final UVCCamera camera = new UVCCamera();
            camera.open(ctrlBlock);
//			camera.setPreviewTexture(camera.getSurfaceTexture());
            if (mPreviewSurface != null) {
                mPreviewSurface.release();
                mPreviewSurface = null;
            }
            try {
                camera.setPreviewSize(inputSize.getWidth(), inputSize.getHeight(),
                        UVCCamera.PIXEL_FORMAT_YUV420SP/*UVCCamera.FRAME_FORMAT_MJPEG*/);
            } catch (final IllegalArgumentException e) {
                // fallback to YUV mode
                Log.v(TAG, "fallback to YUV mode");
                try {
                    camera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.DEFAULT_PREVIEW_MODE);
                } catch (final IllegalArgumentException e1) {
                    camera.destroy();
                    return;
                }
            }
            final SurfaceTexture st = textureView.getSurfaceTexture();
            if (st != null) {

                // We configure the size of default buffer to be the size of camera preview we want.
                st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

                mPreviewSurface = new Surface(st);
                camera.setPreviewDisplay(mPreviewSurface);
				camera.setFrameCallback(mIFrameCallback, UVCCamera.PIXEL_FORMAT_YUV420SP);

                /////////////////////
                Log.v(TAG, "get supported sizes list...");
                List<com.serenegiant.usb.Size> supportedSizes = camera.getSupportedSizeList();
                for(com.serenegiant.usb.Size size : supportedSizes) {
                    Log.v(TAG, "supported size: " + "[" + size.width + ":" + size.height + "]");
                }

                //////////////////////

                camera.startPreview();
            }
            synchronized (mSync) {
                mUVCCamera = camera;
            }
        }

        @Override
        public void onDisconnect(final UsbDevice device,
                final USBMonitor.UsbControlBlock ctrlBlock) {
            Log.v(TAG, "USBMonitor onDisconnect()");
            synchronized (mSync) {
                if (mUVCCamera != null) {
                    mUVCCamera.stopPreview();
                }
            }
        }

        @Override
        public void onDettach(final UsbDevice device) {
            Log.v(TAG, "USBMonitor onDettach()");
            synchronized (mSync) {
                if (mUVCCamera != null) {
                    mUVCCamera.stopPreview();
                }
            }
            //releaseCamera();
            final Activity activity = getActivity();
            Toast.makeText(activity,
                    "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCancel(final UsbDevice device) {
            Log.v(TAG, "USBMonitor onCancel()");
        }
    };

    private synchronized void releaseCamera() {
        synchronized (mSync) {
            if (mUVCCamera != null) {
                try {
                    mUVCCamera.setStatusCallback(null);
                    mUVCCamera.setButtonCallback(null);
                    mUVCCamera.close();
                    mUVCCamera.destroy();
                } catch (final Exception e) {
                    //
                }
                mUVCCamera = null;
            }
            if (mPreviewSurface != null) {
                mPreviewSurface.release();
                mPreviewSurface = null;
            }
        }
    }

    /**
     * Callback for Activities to use to initialize their data once the selected preview size is
     * known.
     */
    public interface ConnectionCallback {
        void onPreviewSizeChosen(Size size, int cameraRotation);
    }

    private void setUpCameraOutputs() {

        previewSize = inputSize;
        // We fit the aspect ratio of TextureView to the size of preview we picked.
        final int orientation = getResources().getConfiguration().orientation;
        if (orientation != Configuration.ORIENTATION_LANDSCAPE) {
            textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
        } else {
            textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
        }


        cameraConnectionCallback.onPreviewSizeChosen(previewSize, 0);
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
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            final float scale =
                    Math.max(
                            (float) viewHeight / previewSize.getHeight(),
                            (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }
}

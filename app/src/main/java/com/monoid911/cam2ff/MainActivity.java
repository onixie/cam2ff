package com.monoid911.cam2ff;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static java.util.concurrent.Executors.newFixedThreadPool;

public class MainActivity extends Activity {
    private Camera mCamera;
    private SurfaceView mSurfaceView;
    private Process mffmpegProc;
    private boolean mffmpegProcExit;
    private SurfaceHolder.Callback mSHCallback;
    private TextView mFFmpegLog;
    private String log;
    private ExecutorService mPool = newFixedThreadPool(1);
    public MainActivity() {
    }

    private Camera.PreviewCallback newPreviewCallback() {
        return new Camera.PreviewCallback() {
            public OutputStream output;
            private int format;
            private Rect rect;
            private long last;
            @Override
            public void onPreviewFrame(byte[] bytes, Camera camera) {
                if (rect == null) {
                    Camera.Parameters param = camera.getParameters();
                    Camera.Size size = param.getPreviewSize();
                    format = param.getPreviewFormat();
                    int bufferSize = size.width * size.height * ImageFormat.getBitsPerPixel(format);
                    rect = new Rect(0, 0, size.width, size.height);
                    output = mffmpegProc.getOutputStream();
                }

                // JPEGに変換してffmpegに転送する
                new YuvImage(bytes, format, rect.width(), rect.height(), null)
                        .compressToJpeg(rect, 100, output);
                if (mffmpegProcExit) {
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            }
        };
    }

    private SurfaceHolder.Callback newSurfaceHolderCallback() {
        return new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder surfaceHolder) {
                // Start ffmpeg
                String cwd = "/data/data/" + getPackageName();
                String ffmpeg = cwd + "/lib/libffmpeg.so"; // lollipopまで*.soはここにインストールするそうだ。
                String output = cwd + "/output.flv";

                try {
                    mffmpegProcExit = false;
                    mffmpegProc = new ProcessBuilder()
                            .command(ffmpeg, "-f", "image2pipe", "-i", "-", "-f", "ffm", "http://192.168.100.105:8090/feed")
                            .directory(new File(cwd))
                            .redirectErrorStream(true)
                            .start();

                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            BufferedReader br = new BufferedReader(new InputStreamReader(mffmpegProc.getInputStream()));
                            try {
                                while (true) {
                                    if (br.ready()) {
                                        log = br.readLine();
                                        System.out.println(log);
                                    }
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }).start();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                mffmpegProc.waitFor();
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            mffmpegProcExit = true;
                        }
                    }).start();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // Open camera
                mCamera = Camera.open(1);
                setCameraResolution(mCamera);
                setCameraDisplayOrientation(1, mCamera);
                try {
                    mCamera.setPreviewDisplay(surfaceHolder);
                    mCamera.setPreviewCallback(newPreviewCallback());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i2, int i3) {
                setCameraDisplayOrientation(1, mCamera);
                mCamera.startPreview();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
                if (mCamera != null) {
                    mCamera.setPreviewCallback(null);
                    try {
                        mCamera.setPreviewDisplay(null);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    mCamera.release();
                    mCamera = null;
                }

                if (mffmpegProc != null) {
                    mffmpegProc.destroy();
                    mffmpegProc = null;
                }
            }
        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.monoid911.cam2ff.R.layout.activity_main);

        final Handler updateLog = new Handler();
        final int delay = 1000; //milliseconds
        updateLog.postDelayed(new Runnable() {
            public void run() {

                mFFmpegLog = (TextView) findViewById(com.monoid911.cam2ff.R.id.ffmpegOutput);
                if (mFFmpegLog != null) {
                    mFFmpegLog.setText(log);
                }
                updateLog.postDelayed(this, delay);
            }
        }, delay);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mSurfaceView = (SurfaceView) findViewById(com.monoid911.cam2ff.R.id.surfaceView);
        mSurfaceView.setKeepScreenOn(true);
        SurfaceHolder holder = mSurfaceView.getHolder();
        mSHCallback = newSurfaceHolderCallback();
        holder.addCallback(mSHCallback);
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mCamera != null) {
            mCamera.setPreviewCallback(null);
            try {
                mCamera.setPreviewDisplay(null);
            } catch (IOException e) {
                e.printStackTrace();
            }
            mCamera.release();
            mCamera = null;
        }

        if (mffmpegProc != null) {
            mffmpegProc.destroy();
            mffmpegProc = null;
        }

        mSurfaceView = (SurfaceView) findViewById(com.monoid911.cam2ff.R.id.surfaceView);
        SurfaceHolder holder = mSurfaceView.getHolder();
        holder.removeCallback(mSHCallback);
    }

    private void setCameraResolution(android.hardware.Camera camera) {
        Camera.Parameters params = mCamera.getParameters();
        List<Camera.Size> supported = params.getSupportedPreviewSizes();
        params.setPreviewSize(320, 240);
        mCamera.setParameters(params);
    }
    private void setCameraDisplayOrientation(int cameraId, android.hardware.Camera camera) {
        android.hardware.Camera.CameraInfo info =
                new android.hardware.Camera.CameraInfo();
        android.hardware.Camera.getCameraInfo(cameraId, info);
        int rotation = this.getWindowManager().getDefaultDisplay()
                .getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }

        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }
        camera.setDisplayOrientation(result);
    }
}

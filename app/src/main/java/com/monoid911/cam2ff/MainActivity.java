package com.monoid911.cam2ff;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.File;
import java.io.IOException;

public class MainActivity extends Activity {
    private Camera mCamera;
    private SurfaceView mSurfaceView;
    private Process mffmpegProc;
    private SurfaceHolder.Callback mSHCallback;

    public MainActivity() {
    }

    private Camera.PreviewCallback newPreviewCallback() {
        return new Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(byte[] bytes, Camera camera) {
                Camera.Parameters param = camera.getParameters();
                Camera.Size size = param.getPreviewSize();
                int format = param.getPreviewFormat();
                int bufferSize = size.width * size.height * ImageFormat.getBitsPerPixel(format);

                // JPEGに変換してffmpegに転送する
                new YuvImage(bytes, format, size.width, size.height, null)
                        .compressToJpeg(new Rect(0, 0, size.width, size.height), 50, mffmpegProc.getOutputStream());

                camera.addCallbackBuffer(new byte[bufferSize]);
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
                    mffmpegProc = new ProcessBuilder()
                            .command(ffmpeg, "-y", "-f", "image2pipe", "-i", "-", "-f", "flv", output)
                            .directory(new File(cwd))
                            .redirectErrorStream(true)
                            .start();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // Open camera
                mCamera = Camera.open(1);
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
                //プレビュースタート（Changedは最初にも1度は呼ばれる）
                setCameraDisplayOrientation(1, mCamera);
                mCamera.startPreview();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
                //片付け
                try {
                    mCamera.setPreviewDisplay(null);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                mCamera.setPreviewCallback(null);
                mCamera.release();
                mffmpegProc.destroy();
            }
        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.monoid911.cam2ff.R.layout.activity_main);
    }

    @Override
    protected void onResume() {
        super.onResume();

        mSurfaceView = (SurfaceView) findViewById(com.monoid911.cam2ff.R.id.surfaceView);
        SurfaceHolder holder = mSurfaceView.getHolder();
        mSHCallback = newSurfaceHolderCallback();
        holder.addCallback(mSHCallback);
    }

    @Override
    protected void onPause() {
        super.onPause();

        mCamera.setPreviewCallback(null);
        try {
            mCamera.setPreviewDisplay(null);
        } catch (IOException e) {
            e.printStackTrace();
        }
        mCamera.release();
        mffmpegProc.destroy();

        mSurfaceView = (SurfaceView) findViewById(com.monoid911.cam2ff.R.id.surfaceView);
        SurfaceHolder holder = mSurfaceView.getHolder();
        holder.removeCallback(mSHCallback);
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

package com.codelphi.airplay;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MirrorActivity extends Activity {

    private SurfaceView surfaceView;
    private TextView status;
    private View pinPanel;
    private TextView[] pinDigits;
    private final Handler uiHandler = new Handler();
    private final NativeBridge.SessionEndListener sessionEndListener = this::endMirroringSession;
    private long lastBack;
    private static final long WAIT_TIMEOUT_MS = 30000;
    /** When the waiting screen closes itself; 0 while the countdown is not running */
    private long waitDeadline;

    /* 有帧时隐藏等待层；无帧时按服务状态显示"初始化/等待连接/失败"，只有 conn_destroy 才回到等待界面 */
    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            if (NativeBridge.videoIdleMs() < 3000) {
                status.setVisibility(View.GONE);
                pinPanel.setVisibility(View.GONE);
                waitDeadline = 0;
            } else {
                String pin = NativeBridge.getPendingPin();
                if (pin != null) {
                    status.setVisibility(View.GONE);
                    pinPanel.setVisibility(View.VISIBLE);
                    for (int i = 0; i < pinDigits.length; i++) {
                        pinDigits[i].setText(i < pin.length() ? pin.substring(i, i + 1) : "");
                    }
                    waitDeadline = 0;
                    uiHandler.postDelayed(this, 1000);
                    return;
                }
                pinPanel.setVisibility(View.GONE);
                status.setVisibility(View.VISIBLE);
                int state = NativeBridge.getServiceState();
                if (state == NativeBridge.STATE_FAILED) {
                    status.setText(R.string.start_failed);
                    waitDeadline = 0;
                } else if (state == NativeBridge.STATE_READY) {
                    /* Only with no session: a connected client may send no frames while its screen is still */
                    if (NativeBridge.videoIdleMs() == Long.MAX_VALUE) {
                        long now = System.currentTimeMillis();
                        if (waitDeadline == 0) waitDeadline = now + WAIT_TIMEOUT_MS;
                        int seconds = (int) ((waitDeadline - now + 999) / 1000);
                        if (seconds <= 0) {
                            /* The service keeps running, so clients can still find the TV and reopen this screen */
                            finish();
                            return;
                        }
                        status.setText(getResources().getQuantityString(
                                R.plurals.waiting_for_connection_seconds, seconds, seconds));
                    } else {
                        waitDeadline = 0;
                        status.setText(R.string.waiting_for_connection);
                    }
                }
                /* STATE_STARTING：保持"初始化…" */
            }
            uiHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.mirror);

        surfaceView = findViewById(R.id.surface);
        status = findViewById(R.id.status);
        pinPanel = findViewById(R.id.pin_panel);
        pinDigits = new TextView[] {
                findViewById(R.id.pin_digit_1), findViewById(R.id.pin_digit_2),
                findViewById(R.id.pin_digit_3), findViewById(R.id.pin_digit_4)};

        NativeBridge.setSessionEndListener(sessionEndListener);
        /* 解码器报出真实分辨率后按比例重排 SurfaceView：保持宽高比居中，多余部分留黑边，绝不拉伸 */
        VideoSink.setVideoSizeListener((w, h) -> runOnUiThread(() -> applyLetterbox(w, h)));

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                NativeBridge.setSurface(holder.getSurface());
                NativeBridge.nativeConnectSurface(holder.getSurface());
                VideoSink sink = NativeBridge.getVideoSink();
                if (!sink.isRunning()) sink.start(NativeBridge.isVideoH265(), holder.getSurface());
                AudioSink sink2 = NativeBridge.getAudioSink();
                if (!sink2.isRunning()) sink2.start();          /* 音频解码驱动（此前漏启动） */
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                NativeBridge.nativeDisconnectSurface();
                NativeBridge.setSurface(null);
                VideoSink sink = NativeBridge.getVideoSink();
                sink.stop();
                sink.clearSurface();
                NativeBridge.getAudioSink().stop();
            }
        });

        uiHandler.postDelayed(uiTick, 1200);
        AirPlayService.start(this);
    }

    @Override
    public void onBackPressed() {
        if (System.currentTimeMillis() - lastBack < 2000) {
            stopService(new Intent(this, AirPlayService.class));
            finish();
        } else {
            lastBack = System.currentTimeMillis();
            Toast.makeText(this, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        NativeBridge.clearSessionEndListener(sessionEndListener);   /* 静态槽位持 Activity 引用，销毁时清掉防泄漏 */
        VideoSink.setVideoSizeListener(null);   /* Activity 可重建，避免持着旧实例泄漏 */
        super.onDestroy();
    }

    /** letterbox 等比缩放：视频比例与屏幕一致时恢复满屏；不一致时居中 + 黑边（更宽→上下黑边，更窄→左右黑边） */
    private void applyLetterbox(int vw, int vh) {
        if (vw <= 0 || vh <= 0 || surfaceView == null) return;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int sw = dm.widthPixels, sh = dm.heightPixels;
        if (sw <= 0 || sh <= 0) return;
        double videoAr = (double) vw / vh;
        double screenAr = (double) sw / sh;
        int w, h;
        if (videoAr > screenAr) {
            w = sw;
            h = (int) Math.round(sw / videoAr);
        } else {
            h = sh;
            w = (int) Math.round(sh * videoAr);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER;
        surfaceView.setLayoutParams(lp);
        android.util.Log.i("MirrorActivity", "letterbox: 视频 " + vw + "x" + vh + " → 显示 " + w + "x" + h + " (屏 " + sw + "x" + sh + ")");
    }

    /** 真正断开时才清参数集并显示等待层 */
    private void endMirroringSession(boolean hadVideo) {
        NativeBridge.getVideoSink().endSession();
        runOnUiThread(() -> {
            if (hadVideo) {
                finish();
                return;
            }
            status.setText(R.string.waiting_for_connection);
            status.setVisibility(View.VISIBLE);
        });
    }
}

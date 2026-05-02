package com.haifeng;

import android.Manifest;
import android.os.Bundle;

import android.content.ActivityNotFoundException;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import android.os.SystemClock;

import android.content.BroadcastReceiver;

import android.os.Handler;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.RemoteException;
import android.support.v4.media.session.MediaSessionCompat;
import android.provider.Settings;
import android.content.ComponentName;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.List;

import androidx.annotation.Nullable;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.content.ActivityNotFoundException;
import android.support.v4.media.MediaMetadataCompat;

public class MainActivity extends AppCompatActivity {

    // ========================= 成员变量声明 =========================
    private TextView titleTv; // 歌名显示
    private MediaControllerCompat activeCtrl; // 当前活动的源控制器
    private android.support.v4.media.MediaBrowserCompat mBrowser; // 🎯 Internal Service Hotline
    private BroadcastReceiver tokenReceiver; // 广播接收器：接收 QqSessionSniffer 发送的 Token

    private final Handler progressHandler = new Handler(); // 用于进度更新
    private Runnable progressRunnable; // 进度任务
        // activeCtrl is the main binding to whichever source (Lazy/Qishui/QQ) the user selected.
        // We keep it as a field so all callbacks (tokenReceiver, connectSourceForTest) can update it.

    private Handler tickerHandler = new Handler(); // 播放进度模拟器
    private Runnable tickerRunnable;
    private long currentPositionMs = 0; // 当前播放位置（ms）
    private int lastPlaybackState = PlaybackStateCompat.STATE_NONE; // 上一次播放状态，用于避免重复重启ticker

    private static final String ACTION_CONTROLLER = "com.haifeng.ACTION_CONTROLLER";

    private static final String ACTION_SELECTION_CHANGED = "com.haifeng.ACTION_SELECTION_CHANGED";

    private @Nullable Intent buildLaunchIntent(String pkg) {
        PackageManager pm = getPackageManager();

        // 1) 官方推荐：找该包的 LAUNCHER Activity（最稳）
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        main.setPackage(pkg);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        if (list != null && !list.isEmpty()) {
            ResolveInfo ri = list.get(0);
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LAUNCHER);
            launch.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return launch;
        }

        // 2) 兜底
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return i;
        }
        return null;
    }

    private void openMarketOrToast(String pkg, String appName) {
        try {
            Intent market = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + pkg));
            market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(market);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "未检测到 " + appName + "，请先安装。", Toast.LENGTH_SHORT).show();
        }
    }

    // ========================= 生命周期入口 =========================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 检查是否开启了通知使用权，未开启则弹窗引导
        if (!isNlEnabled()) {
            promptForNlPermission();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                promptForPostNotificationsPermission();
            }
        }

        // 设置布局
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // ✅ 在这里插入首次使用说明弹窗
        SharedPreferences sp1 = getSharedPreferences("settings", MODE_PRIVATE);
        boolean shown = sp1.getBoolean("guideShown", false);
        if (!shown) {
            new AlertDialog.Builder(this)
                    .setTitle("使用说明")
                    .setMessage("📱 初次使用：\n\n"
                            + "1. 在接下来的页面授权通知权限，需要从通知获得歌曲信息\n\n"
                            + "2. 打开你要使用的音乐 App，让它在后台播放，然后点击“选择播放器”按钮\n\n"
                            + "3. 点击“刷新”按钮，正在播放的 App 会显示名称和图标，点击选中\n"
                            + "   （只有第一次录入新 App 时需要，以后切换时可直接点击切换）\n\n"
                            + "4. 这时你能看到手机端播放器展示当前歌曲信息，表示已成功 🎶\n\n"
                            + "🚗 Android Auto：\n\n"
                            + "1. 在手机 Android Auto 中开启开发者模式，并允许未知来源应用\n\n"
                            + "2. 例如使用 QQ 音乐：手机连接车机 Android Auto → 确保海风播放器在后台运行 → 打开 QQ 音乐播放\n\n"
                            + "3. 车机端海风播放器会自动显示歌曲。如果显示“没有任何内容”，请在手机端点击暂停再播放等待1~2 秒")
                    .setPositiveButton("我知道了", (d, w) -> {
                        sp1.edit().putBoolean("guideShown", true).apply();
                        d.dismiss();
                    })
                    .setCancelable(false)

                    .setPositiveButton("我知道了", (d, w) -> {
                        sp1.edit().putBoolean("guideShown", true).apply(); // 标记已展示
                        d.dismiss();
                    })
                    .setCancelable(false)
                    .show();
        }

        Button pickBtn = findViewById(R.id.btn_pick_session);
        findViewById(R.id.btn_test_lazy).setOnClickListener(v -> testLazyAudioProxy());
        findViewById(R.id.btn_test_qishui).setOnClickListener(v -> testQishuiMusicProxy());
        findViewById(R.id.btn_test_qq).setOnClickListener(v -> testQQMusicProxy());
        pickBtn.setOnClickListener(v -> {
            // 先检查是否已授予通知监听权限
            if (!com.haifeng.NotifAccessHelper.isEnabled(this)) {
                Toast.makeText(this, "请先开启“通知使用权”，再返回此页", Toast.LENGTH_LONG).show();
                com.haifeng.NotifAccessHelper.openSettings(this);
                return;
            }
            // 打开底部弹窗
            new com.haifeng.SessionPickerSheet()
                    .show(getSupportFragmentManager(), "session_picker");
        });

        // 沉浸式状态栏处理
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main),
                (v, insets) -> {
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return insets;
                });

        // 5) 注册广播接收器：仅采纳“当前选中的 App”
            // Why: Multiple sources may broadcast their tokens concurrently. We only accept
            // tokens from the source the user explicitly selected (stored in SharedPrefs).
            // This prevents race conditions where Sniffer from source A overrides source B.
        tokenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                // 只处理通用 Action
                if (!ACTION_CONTROLLER.equals(i.getAction()))
                    return;

                // 广播里携带的来源包名（由 Sniffer 填入）
                String sourcePkg = i.getStringExtra("pkg");
                if (sourcePkg == null)
                    return;

                // 读取当前用户选中的包名
                                // Why: Cross-check the incoming broadcast package with the user's preference.
                                // If they don't match, ignore the broadcast (e.g., user has QQ selected,
                                // but Lazy Audio's Sniffer fires its token broadcast).
                SharedPreferences sp = getSharedPreferences("session_pref", MODE_PRIVATE);
                String chosenPkg = sp.getString("last_pkg", null);
                if (chosenPkg == null || !chosenPkg.equals(sourcePkg)) {
                    // 不是当前选中的来源，忽略
                    return;
                }

                MediaSessionCompat.Token tk = i.getParcelableExtra("binder");
                if (tk == null)
                    return;

                if (activeCtrl != null)
                    activeCtrl.unregisterCallback(cb);
                try {
                    activeCtrl = new MediaControllerCompat(MainActivity.this, tk);
                    activeCtrl.registerCallback(cb, null);
                    MediaControllerCompat.setMediaController(MainActivity.this, activeCtrl);

                    MediaMetadataCompat meta = activeCtrl.getMetadata();
                    if (meta != null)
                        cb.onMetadataChanged(meta);
                } catch (Exception e) {
                    Log.e("QqSniffer", "设置控制器失败", e);
                }
            }
        };

        // 🎯 统一注册广播 (Internal Only)
        registerReceiver(tokenReceiver, new IntentFilter(ACTION_CONTROLLER), Context.RECEIVER_NOT_EXPORTED);

        // 🚀 ACTIVATE HOTLINE: Connect to our own service to keep it alive
            // Why: The MyMusicService is a MediaBrowserServiceCompat that mirrors playback from
            // external apps to Android Auto. We must keep this service alive and connected so it
            // can receive subscriptions from car UIs. By connecting to it from MainActivity,
            // we ensure the service is not garbage-collected by the system.
        mBrowser = new android.support.v4.media.MediaBrowserCompat(this,
                new ComponentName(this, com.haifeng.shared.MyMusicService.class),
                new android.support.v4.media.MediaBrowserCompat.ConnectionCallback() {
                    @Override
                    public void onConnected() {
                        Log.i("MainActivity", "✅ Internal Service Hotline Connected!");
                    }
                }, null);
        mBrowser.connect();

    }

    private android.support.v4.media.MediaBrowserCompat proxyBrowser;

    private void connectSourceForTest(String pkg, String serviceClass, String label, String logTag) {
        getSharedPreferences("session_pref", Context.MODE_PRIVATE)
                .edit()
                .putString("last_pkg", pkg)
                .putString("last_label", label)
                .apply();

        sendBroadcast(new Intent(ACTION_SELECTION_CHANGED)
                .setPackage(getPackageName())
                .putExtra("pkg", pkg)
                .putExtra("label", label));

        sendBroadcast(new Intent("com.haifeng.REQUEST_TOKEN")
                .setPackage(getPackageName()));
    // Disconnect the old proxy browser to free resources before creating a new one.
    // Why: MediaBrowser holds a connection to the remote service. If we don't disconnect
    // before creating a new one, we leak the old connection.

        if (proxyBrowser != null && proxyBrowser.isConnected()) {
            proxyBrowser.disconnect();
        }

        android.content.ComponentName component = new android.content.ComponentName(pkg, serviceClass);
        proxyBrowser = new android.support.v4.media.MediaBrowserCompat(this, component,
                new android.support.v4.media.MediaBrowserCompat.ConnectionCallback() {
                    @Override
                    public void onConnected() {
                        Log.i(logTag, "✅ Connected: " + pkg);
                        try {
                            if (activeCtrl != null) {
                                activeCtrl.unregisterCallback(cb);
                            }
                            activeCtrl = new MediaControllerCompat(MainActivity.this, proxyBrowser.getSessionToken());
                            activeCtrl.registerCallback(cb, null);
                            MediaControllerCompat.setMediaController(MainActivity.this, activeCtrl);

                            MediaMetadataCompat meta = activeCtrl.getMetadata();
                            if (meta != null) {
                                cb.onMetadataChanged(meta);
                            }
                            PlaybackStateCompat state = activeCtrl.getPlaybackState();
                            if (state != null) {
                                cb.onPlaybackStateChanged(state);
                            }
                        } catch (Exception e) {
                            Log.e(logTag, "❌ Failed to bind controller", e);
                        }
                    }

                    @Override
                    public void onConnectionSuspended() {
                        Log.w(logTag, "⚠️ Connection suspended: " + pkg);
                    }

                    @Override
                    public void onConnectionFailed() {
                        Log.e(logTag, "❌ Connection failed: " + pkg);
                    }
                }, null);
        proxyBrowser.connect();
        Toast.makeText(this, "已自动切换到：" + label, Toast.LENGTH_SHORT).show();
    }

    private void testLazyAudioProxy() {
        connectSourceForTest("bubei.tingshu.international",
                "tingshu.bubei.mediasupport.service.MediaSessionBrowserService", "懒人听书", "SourceProxy");
    }

    @Override
    protected void onDestroy() {
        if (activeCtrl != null)
            activeCtrl.unregisterCallback(cb);
        if (proxyBrowser != null && proxyBrowser.isConnected()) {
            proxyBrowser.disconnect();
        }
        if (mBrowser != null && mBrowser.isConnected()) {
            mBrowser.disconnect();
        }

        // 🛡️ Global Unregister
        try {
            unregisterReceiver(tokenReceiver);
        } catch (Exception ignored) {
        }

        progressHandler.removeCallbacksAndMessages(null); // 停止进度更新
        super.onDestroy();
    }

    // ========================= 权限检测相关 =========================

    /** 判断通知使用权是否开启 */
    private boolean isNlEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        if (enabled == null)
            return false;
        String flat = new ComponentName(getPackageName(),
                MusicSessionSniffer.class.getName()).flattenToString();
        return enabled.contains(flat);
    }

    /** 弹窗提示用户开启通知监听权限 */
    private void promptForNlPermission() {
        new AlertDialog.Builder(this)
                .setTitle("启用通知读取权限")
                .setMessage("请在接下来的页面中勾选本应用，否则将无法获取正在播放的歌曲信息。")
                .setPositiveButton("去授权", (d, w) -> {
                    Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                    startActivity(i);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 弹窗提示用户开启 Android 13+ 通知权限 */
    private void promptForPostNotificationsPermission() {
        new AlertDialog.Builder(this)
                .setTitle("允许通知权限")
                .setMessage("为了保证海风播放器在后台正常运行，本应用需要在状态栏权限"
                        + "🎵。\n\n"
                        + "在 Android 13 及以上系统，如果不允许通知权限，应用可能会被系统限制后台运行。")
                .setPositiveButton("去允许", (d, w) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermissions(
                                new String[] { Manifest.permission.POST_NOTIFICATIONS },
                                1001 // 自定义请求码
                        );
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ========================= 控制器回调 =========================

    /** QQ 控制器的元数据与播放状态监听回调 */
    // Unified callback that handles metadata and playback state changes from any source.
    // Why: Instead of three separate callbacks for Lazy/Qishui/QQ, we use one callback
    // bound to activeCtrl (whichever source is selected) and update UI fragments accordingly.
    private final MediaControllerCompat.Callback cb = new MediaControllerCompat.Callback() {

        @Override
        public void onMetadataChanged(MediaMetadataCompat meta) {
            if (meta != null) {
                String title = meta.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
                    Log.i("Mirror", "歌曲标题更新为：" + title);

                // 更新控制面板（歌名、歌手、封面、总时长）
                Fragment fragment = getSupportFragmentManager()
                        .findFragmentById(R.id.playbackControlsFragment);
                if (fragment instanceof PlaybackControlsFragment) {
                    ((PlaybackControlsFragment) fragment).updateTitle(title);
                }

                // 封面位图优先：ALBUM_ART → DISPLAY_ICON → ART
                Bitmap cover = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
                if (cover == null)
                    cover = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON);
                if (cover == null)
                    cover = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ART);

                if (cover != null) {
                    AlbumCoverFragment frag2 = (AlbumCoverFragment) getSupportFragmentManager()
                            .findFragmentById(R.id.playerAlbumCoverFragment);
                    if (frag2 != null) {
                        frag2.updateCover(cover);
                    } else {
                        Log.w("Mirror", "封面Fragment未初始化");
                    }
                } else {
                    Log.w("Mirror", "未获取到封面图");
                }

                String artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
                PlaybackControlsFragment frag1 = (PlaybackControlsFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.playbackControlsFragment);
                if (frag1 != null) {
                    frag1.updateTitle(title);
                    frag1.updateArtist(artist);
                }

                long durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
                PlaybackControlsFragment frag = (PlaybackControlsFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.playbackControlsFragment);
                if (frag != null) {
                    frag.updateTotalTime(durationMs);
                }

            }

        }

        @Override
        public void onPlaybackStateChanged(@NonNull PlaybackStateCompat state) {
            long position = state.getPosition();
            int newState = state.getState();
                Log.i("Mirror", "State → " + newState + " | position = " + position);

            PlaybackControlsFragment frag = (PlaybackControlsFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.playbackControlsFragment);
            if (frag != null) {
                frag.updateProgressTime(position);
                frag.updatePlayPauseButton(state.getState());
            }

            // 开始/停止进度模拟器
                        // Start/stop local progress simulation based on playback state.
                        // Why: Some sources send position updates infrequently. By simulating +1s every second
                        // when PLAYING, the UI feels responsive even if the remote app only sends updates
                        // periodically. We stop the ticker when paused to match the real position.
            if (newState == PlaybackStateCompat.STATE_PLAYING) {
                if (lastPlaybackState != PlaybackStateCompat.STATE_PLAYING) {
                    startProgressTicker(position);
                } else {
                    // Keep ticker running and only re-sync baseline to avoid restart jitter.
                    currentPositionMs = position;
                }
            } else {
                stopProgressTicker();
            }

            lastPlaybackState = newState;
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
    }

    // ========================= 播放进度模拟器 =========================

    /** 启动进度模拟器：每秒将 position +1s */
    private void startProgressTicker(long startPos) {
        currentPositionMs = startPos;
        stopProgressTicker(); // 防止重复任务

        tickerRunnable = new Runnable() {
            @Override
            public void run() {
                currentPositionMs += 1000;

                PlaybackControlsFragment frag = (PlaybackControlsFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.playbackControlsFragment);

                if (frag != null) {
                    frag.updateProgressTime(currentPositionMs);
                }

                tickerHandler.postDelayed(this, 1000);
            }
        };
        tickerHandler.postDelayed(tickerRunnable, 1000);
    }

    /** 停止进度模拟器 */
    private void stopProgressTicker() {
        tickerHandler.removeCallbacksAndMessages(null);
    }

    private void testQishuiMusicProxy() {
        connectSourceForTest("com.luna.music",
                "com.luna.biz.playing.player.PlayerService", "汽水音乐", "SourceProxy");
    }

    private void testQQMusicProxy() {
        connectSourceForTest("com.tencent.qqmusic",
                "com.tencent.qqmusic.MediaSessionBrowserService", "QQ音乐", "SourceProxy");
    }
}
                    // Cascade updates to multiple UI fragments.
                    // Why: The phone UI is composed of multiple fragments (AlbumCoverFragment,
                    // PlaybackControlsFragment). Each manages its own part of the display.
                    // When metadata changes, we push updates to all relevant fragments.
                    // Why: Different sources use different metadata keys for album art.
                    // We check them in priority order to maximize the chance of finding a bitmap.
                            // Unbind the old controller and bind the new one from this source.
                            // Why: The MediaControllerCompat is the key to querying and controlling
                            // a remote MediaSession. We bind it to UI callbacks (cb) so metadata/state
                            // changes automatically update the phone UI fragments.
    // Simulates playback progress locally by incrementing position every 1s.
    // Why: Remote sources may send position updates only at intervals (e.g., every 5s).
    // Local simulation fills the gap to make progress bar feel smooth and responsive.
    // Unified helper: connect to a music source (Lazy/Qishui/QQ) and bind its MediaController.
    // Why: All three sources follow the same pattern—save preferences, notify the service,
    // create a MediaBrowser, bind the controller, and update UI. This consolidates that logic.

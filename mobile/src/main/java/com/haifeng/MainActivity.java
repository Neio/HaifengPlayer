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
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
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
    private TextView titleTv;                    // 歌名显示
    private MediaControllerCompat qqCtrl;        // QQ 音乐控制器
    private android.support.v4.media.MediaBrowserCompat mBrowser; // 🎯 Internal Service Hotline
    private BroadcastReceiver tokenReceiver;     // 广播接收器：接收 QqSessionSniffer 发送的 Token

    private final Handler progressHandler = new Handler();  // 用于进度更新
    private Runnable progressRunnable;                      // 进度任务

    private Handler tickerHandler = new Handler();          // 播放进度模拟器
    private Runnable tickerRunnable;
    private long currentPositionMs = 0;                     // 当前播放位置（ms）

    private static final String ACTION_CONTROLLER = "com.haifeng.ACTION_CONTROLLER";

    // 来源标识
    private static final String SRC_QQ  = "QQ";
    private static final String SRC_NCM = "NCM";

    private String activeSource = SRC_QQ; // 当前捕获来源（默认 QQ）

    private boolean suppressLyricsToggle = false;

    private static final String ACTION_SELECTION_CHANGED = "com.haifeng.ACTION_SELECTION_CHANGED";


    private BroadcastReceiver selectionChangedRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {

            // ↓↓↓ 新增：会话变更时同步歌词开关
            SwitchCompat sw = findViewById(R.id.switch_lyrics_mode);
            boolean autoLyrics = getSharedPreferences("settings", MODE_PRIVATE)
                    .getBoolean("autoLyrics", false);
            String pkg = getSharedPreferences("session_pref", MODE_PRIVATE)
                    .getString("last_pkg", null);
            boolean isQQ = "com.tencent.qqmusic".equals(pkg);

            suppressLyricsToggle = true;
            sw.setChecked(autoLyrics && isQQ);  // 非QQ时自动回拨为关；回到QQ且autoLyrics=true时自动打开
            suppressLyricsToggle = false;
        }
    };

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
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
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
                            + "2. 例如使用 QQ 音乐：手机连接车机 Android Auto → 确保糯米播放器在后台运行 → 打开 QQ 音乐播放\n\n"
                            + "3. 车机端糯米播放器会自动显示歌曲。如果显示“没有任何内容”，请在手机端点击暂停再播放等待1~2 秒")
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

        // 3) 读取偏好
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        boolean autoLyrics = prefs.getBoolean("autoLyrics", false);
        String savedSrc = prefs.getString("activeSource", SRC_QQ);
        activeSource = SRC_NCM.equals(savedSrc) ? SRC_NCM : SRC_QQ;

        // 4) 初始化两个开关
        // 4.1 歌词模式开关（仅 QQ 音乐允许，其他 App 一律禁用）
        SwitchCompat switchLyrics = findViewById(R.id.switch_lyrics_mode);

        // 读取用户在 SessionPicker 里选择的 App
        SharedPreferences selSp = getSharedPreferences("session_pref", MODE_PRIVATE);
        String chosenPkg = selSp.getString("last_pkg", null);
        boolean isQQSelected = "com.tencent.qqmusic".equals(chosenPkg);

        // 只有当选择的是 QQ 且偏好为 true 才默认勾选
        switchLyrics.setChecked(autoLyrics && isQQSelected);

        switchLyrics.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressLyricsToggle) return;

            // 实时确认当前所选 App（避免用户刚切换了选择）
            SharedPreferences curSel = getSharedPreferences("session_pref", MODE_PRIVATE);
            String currentPkg = curSel.getString("last_pkg", null);
            boolean isQQ = "com.tencent.qqmusic".equals(currentPkg);

            // 非 QQ 音乐：禁止开启歌词模式并回拨
            if (!isQQ && isChecked) {
                suppressLyricsToggle = true;
                switchLyrics.setChecked(false);   // 立刻回拨
                suppressLyricsToggle = false;
                Toast.makeText(MainActivity.this, "当前选择的 App 不支持歌词模式（仅 QQ 音乐）", Toast.LENGTH_SHORT).show();
                prefs.edit().putBoolean("autoLyrics", false).apply();
                return;
            }

            // QQ 音乐：正常落盘与通知
            prefs.edit().putBoolean("autoLyrics", isChecked).apply();
            if (isChecked) {
                // 用户开启后立即激活歌词模式（由 MyMusicService 监听本地广播）
                Intent intent = new Intent("com.haifeng.ACTION_TOGGLE_LYRICS_MODE");
                LocalBroadcastManager.getInstance(MainActivity.this).sendBroadcast(intent);
            }
        });




        // 5) 注册广播接收器：仅采纳“当前选中的 App”
        tokenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                // 只处理通用 Action
                if (!ACTION_CONTROLLER.equals(i.getAction())) return;

                // 广播里携带的来源包名（由 Sniffer 填入）
                String sourcePkg = i.getStringExtra("pkg");
                if (sourcePkg == null) return;

                // 读取当前用户选中的包名
                SharedPreferences sp = getSharedPreferences("session_pref", MODE_PRIVATE);
                String chosenPkg = sp.getString("last_pkg", null);
                if (chosenPkg == null || !chosenPkg.equals(sourcePkg)) {
                    // 不是当前选中的来源，忽略
                    return;
                }

                MediaSessionCompat.Token tk = i.getParcelableExtra("binder");
                if (tk == null) return;

                if (qqCtrl != null) qqCtrl.unregisterCallback(cb);
                try {
                    qqCtrl = new MediaControllerCompat(MainActivity.this, tk);
                    qqCtrl.registerCallback(cb, null);
                    MediaControllerCompat.setMediaController(MainActivity.this, qqCtrl);

                    MediaMetadataCompat meta = qqCtrl.getMetadata();
                    if (meta != null) cb.onMetadataChanged(meta);
                } catch (Exception e) {
                    Log.e("QqSniffer", "设置控制器失败", e);
                }
            }
        };

        // 🎯 统一注册广播 (Internal Only)
        registerReceiver(tokenReceiver, new IntentFilter(ACTION_CONTROLLER), Context.RECEIVER_NOT_EXPORTED);
        registerReceiver(selectionChangedRx, new IntentFilter(ACTION_SELECTION_CHANGED), Context.RECEIVER_NOT_EXPORTED);

        // 🚀 ACTIVATE HOTLINE: Connect to our own service to keep it alive
        mBrowser = new android.support.v4.media.MediaBrowserCompat(this,
                new ComponentName(this, com.haifeng.shared.MyMusicService.class),
                new android.support.v4.media.MediaBrowserCompat.ConnectionCallback() {
                    @Override public void onConnected() {
                        Log.i("MainActivity", "✅ Internal Service Hotline Connected!");
                    }
                }, null);
        mBrowser.connect();









    }

    private android.support.v4.media.MediaBrowserCompat lazyBrowser;

    private void testLazyAudioProxy() {
        String pkg = "bubei.tingshu.international";
        String label = "懒人听书";

        // 1. 保存到 SharedPreferences，这样 Sniffer 和 Service 才知道要监听谁
        getSharedPreferences("session_pref", Context.MODE_PRIVATE)
                .edit()
                .putString("last_pkg", pkg)
                .putString("last_label", label)
                .apply();

        // 2. 发送选择变更广播，通知 MainActivity 更新“打开应用”按钮内容
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent("com.haifeng.ACTION_SELECTION_CHANGED")
                        .putExtra("pkg", pkg)
                        .putExtra("label", label));

        // 3. 发送请求 Token 广播，让 Sniffer 立即去寻找该应用的 MediaSession
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent("com.haifeng.REQUEST_TOKEN"));

        // 4. 连接 MediaBrowser 以通过 Data Proxy 方式唤醒应用
        android.content.ComponentName component = new android.content.ComponentName(
                pkg, "tingshu.bubei.mediasupport.service.MediaSessionBrowserService");
        lazyBrowser = new android.support.v4.media.MediaBrowserCompat(this, component,
                new android.support.v4.media.MediaBrowserCompat.ConnectionCallback() {
                    @Override
                    public void onConnected() {
                        android.util.Log.i("LazyProxy", "✅ Connected to Lazy Audio!");
                        try {
                            // Reuse the same qqCtrl/cb pattern so the phone UI updates correctly
                            if (qqCtrl != null) qqCtrl.unregisterCallback(cb);
                            qqCtrl = new MediaControllerCompat(
                                    MainActivity.this, lazyBrowser.getSessionToken());
                            qqCtrl.registerCallback(cb, null);
                            MediaControllerCompat.setMediaController(MainActivity.this, qqCtrl);

                            // Immediately refresh phone UI with current chapter info
                            MediaMetadataCompat meta = qqCtrl.getMetadata();
                            if (meta != null) cb.onMetadataChanged(meta);

                            android.util.Log.i("LazyProxy", "🎧 Phone UI now shows Lazy Audio chapter");
                        } catch (Exception e) {
                            android.util.Log.e("LazyProxy", "❌ Failed to set MediaController", e);
                        }

                        // Log the content tree for debugging
                        String root = lazyBrowser.getRoot();
                        android.util.Log.i("LazyProxy", "Root ID: " + root);
                        lazyBrowser.subscribe(root, new android.support.v4.media.MediaBrowserCompat.SubscriptionCallback() {
                            @Override
                            public void onChildrenLoaded(String parentId, java.util.List<android.support.v4.media.MediaBrowserCompat.MediaItem> children) {
                                android.util.Log.i("LazyProxy", "📂 Children of " + parentId + ": " + children.size());
                                for (android.support.v4.media.MediaBrowserCompat.MediaItem item : children) {
                                    android.util.Log.i("LazyProxy", "  - [" + (item.isBrowsable() ? "DIR" : "FILE") + "] " 
                                        + item.getDescription().getTitle() + " (ID: " + item.getMediaId() + ")");
                                }
                            }
                        });
                    }
                    @Override
                    public void onConnectionFailed() {
                        android.util.Log.e("LazyProxy", "❌ Connection Failed");
                    }
                }, null);
        lazyBrowser.connect();

        Toast.makeText(this, "已自动切换到：懒人听书", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (qqCtrl != null) qqCtrl.unregisterCallback(cb);
        if (lazyBrowser != null && lazyBrowser.isConnected()) {
            lazyBrowser.disconnect();
        }
        if (mBrowser != null && mBrowser.isConnected()) {
            mBrowser.disconnect();
        }
        
        // 🛡️ Global Unregister
        try {
            unregisterReceiver(tokenReceiver);
            unregisterReceiver(selectionChangedRx);
        } catch (Exception ignored) {}
        
        progressHandler.removeCallbacksAndMessages(null);  // 停止进度更新
        super.onDestroy();
    }

    // ========================= 权限检测相关 =========================

    /** 判断通知使用权是否开启 */
    private boolean isNlEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
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
                .setMessage("为了保证糯米播放器在后台正常运行，本应用需要在状态栏权限"
                        + "🎵。\n\n"
                        + "在 Android 13 及以上系统，如果不允许通知权限，应用可能会被系统限制后台运行。")
                .setPositiveButton("去允许", (d, w) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermissions(
                                new String[]{ Manifest.permission.POST_NOTIFICATIONS },
                                1001  // 自定义请求码
                        );
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }


    // ========================= 控制器回调 =========================

    /** QQ 控制器的元数据与播放状态监听回调 */
    private final MediaControllerCompat.Callback cb = new MediaControllerCompat.Callback() {

        @Override
        public void onMetadataChanged(MediaMetadataCompat meta) {
            if (meta != null) {
                String title = meta.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
                Log.i("QqSniffer", "歌曲标题更新为：" + title);

                // 更新控制面板（歌名、歌手、封面、总时长）
                Fragment fragment = getSupportFragmentManager()
                        .findFragmentById(R.id.playbackControlsFragment);
                if (fragment instanceof PlaybackControlsFragment) {
                    ((PlaybackControlsFragment) fragment).updateTitle(title);
                }

                // 封面位图优先：ALBUM_ART → DISPLAY_ICON → ART
                Bitmap cover = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
                if (cover == null) cover = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON);
                if (cover == null) cover = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ART);

                if (cover != null) {
                    AlbumCoverFragment frag2 = (AlbumCoverFragment)
                            getSupportFragmentManager().findFragmentById(R.id.playerAlbumCoverFragment);
                    if (frag2 != null) {
                        frag2.updateCover(cover);
                    } else {
                        Log.w("QqSniffer", "封面Fragment未初始化");
                    }
                } else {
                    Log.w("QqSniffer", "未获取到封面图");
                }


                String artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
                PlaybackControlsFragment frag1 = (PlaybackControlsFragment)
                        getSupportFragmentManager().findFragmentById(R.id.playbackControlsFragment);
                if (frag1 != null) {
                    frag1.updateTitle(title);
                    frag1.updateArtist(artist);
                }

                long durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
                PlaybackControlsFragment frag = (PlaybackControlsFragment)
                        getSupportFragmentManager().findFragmentById(R.id.playbackControlsFragment);
                if (frag != null) {
                    frag.updateTotalTime(durationMs);
                }


            }
            PlaybackStateCompat state = qqCtrl.getPlaybackState();
            if (state != null) {
                onPlaybackStateChanged(state);
            }

        }

        @Override
        public void onPlaybackStateChanged(@NonNull PlaybackStateCompat state) {
            long position = state.getPosition();
            Log.i("QqSniffer", "State → " + state.getState() + " | position = " + position);

            PlaybackControlsFragment frag = (PlaybackControlsFragment)
                    getSupportFragmentManager().findFragmentById(R.id.playbackControlsFragment);
            if (frag != null) {
                frag.updateProgressTime(position);
                frag.updatePlayPauseButton(state.getState());
            }

            // 开始/停止进度模拟器
            if (state.getState() == PlaybackStateCompat.STATE_PLAYING) {
                startProgressTicker(position);
            } else {
                stopProgressTicker();
            }
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
        stopProgressTicker();  // 防止重复任务

        tickerRunnable = new Runnable() {
            @Override
            public void run() {
                currentPositionMs += 1000;

                PlaybackControlsFragment frag = (PlaybackControlsFragment)
                        getSupportFragmentManager().findFragmentById(R.id.playbackControlsFragment);

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
        String pkg = "com.luna.music";
        String label = "汽水音乐";

        getSharedPreferences("session_pref", Context.MODE_PRIVATE)
                .edit()
                .putString("last_pkg", pkg)
                .putString("last_label", label)
                .apply();

        sendBroadcast(new Intent("com.haifeng.ACTION_SELECTION_CHANGED")
                .setPackage(getPackageName())
                .putExtra("pkg", pkg)
                .putExtra("label", label));

        sendBroadcast(new Intent("com.haifeng.REQUEST_TOKEN")
                .setPackage(getPackageName()));

        if (lazyBrowser != null && lazyBrowser.isConnected()) {
            lazyBrowser.disconnect();
        }

        android.content.ComponentName component = new android.content.ComponentName(
                pkg, "com.luna.biz.playing.player.PlayerService");

        lazyBrowser = new android.support.v4.media.MediaBrowserCompat(this, component,
                new android.support.v4.media.MediaBrowserCompat.ConnectionCallback() {
                    @Override
                    public void onConnected() {
                        android.support.v4.media.session.MediaSessionCompat.Token token = lazyBrowser.getSessionToken();
                        Log.i("LazyProxy", "✅ Qishui Music Connected! Token=" + token);

                        try {
                            MediaControllerCompat controller = new MediaControllerCompat(MainActivity.this, token);
                            MediaControllerCompat.setMediaController(MainActivity.this, controller);
                            controller.registerCallback(cb);
                            cb.onMetadataChanged(controller.getMetadata());
                            cb.onPlaybackStateChanged(controller.getPlaybackState());
                        } catch (Exception e) {
                            Log.e("LazyProxy", "❌ Qishui Controller failed", e);
                        }
                    }

                    @Override
                    public void onConnectionSuspended() {
                        Log.w("LazyProxy", "⚠️ Qishui Connection Suspended");
                    }

                    @Override
                    public void onConnectionFailed() {
                        Log.e("LazyProxy", "❌ Qishui Connection Failed");
                    }
                }, null);
        lazyBrowser.connect();
    }

    private void testQQMusicProxy() {
        String pkg = "com.tencent.qqmusic";
        String label = "QQ音乐";
        Log.i("MainActivity", "🔘 [QQ Switch] Tapped. Targeting: " + pkg);

        getSharedPreferences("session_pref", Context.MODE_PRIVATE)
                .edit()
                .putString("last_pkg", pkg)
                .putString("last_label", label)
                .apply();

        sendBroadcast(new Intent("com.haifeng.ACTION_SELECTION_CHANGED")
                .setPackage(getPackageName())
                .putExtra("pkg", pkg)
                .putExtra("label", label));

        sendBroadcast(new Intent("com.haifeng.REQUEST_TOKEN")
                .setPackage(getPackageName()));

        // For QQ, we don't need a browser proxy, just sniff the token
        if (lazyBrowser != null && lazyBrowser.isConnected()) {
            lazyBrowser.disconnect();
        }
    }
}

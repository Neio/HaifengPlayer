package com.haifeng.shared;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.KeyEvent;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;

import androidx.media.MediaBrowserServiceCompat;

import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MyMusicService extends MediaBrowserServiceCompat {

    private MediaSessionCompat mSession; // 本地 MediaSession
    private MediaControllerCompat remoteCtrl; // 指向外部播放器的控制器（QQ 或 NCM）
    private final MediaControllerCompat.Callback remoteCb = new RemoteCallback(); // 监听状态变化

    private static final String CUSTOM_ACTION_SWITCH_LAZY = "ACTION_LAZY";
    private static final String CUSTOM_ACTION_SWITCH_QISHUI = "ACTION_QISHUI";
    private static final String CUSTOM_ACTION_SWITCH_QQ = "ACTION_QQ";

    private static final String LAZY_PKG  = "bubei.tingshu.international";
    private static final String LAZY_SVC  = "tingshu.bubei.mediasupport.service.MediaSessionBrowserService";

    private static final String QISHUI_PKG = "com.luna.music";
    private static final String QISHUI_SVC = "com.luna.biz.playing.player.PlayerService";

    private static final String QQ_PKG = "com.tencent.qqmusic";
    private static final String QQ_SVC = "com.tencent.qqmusic.MediaSessionBrowserService";

    private static final String ACTION_CONTROLLER = "com.haifeng.ACTION_CONTROLLER";
    private final Handler handler = new Handler(Looper.getMainLooper());

    private static final String TAG = "Mirror";

    // =========================================================
    // 🗺️ Source registry — one entry per music app
    // =========================================================
    private static final class SourceConfig {
        final String pkg;
        final String svc;
        final String label;
        final String namespace;    // ID prefix, e.g. "LAZY_", "QISHUI_", "QQ_"
        final String rootId;       // browse root parentId; null = not browseable
        final String customAction; // custom action string sent by AA, e.g. "ACTION_LAZY"
        final String switchMediaId;// media-id used to trigger a switch, e.g. "SWITCH_LAZY"
        final Runnable wakeUp;     // source-specific wake-up signal; may be null

        android.support.v4.media.MediaBrowserCompat browser;
        boolean connected;
        boolean connecting;

        SourceConfig(String pkg, String svc, String label, String namespace,
                     String rootId, String customAction, String switchMediaId,
                     Runnable wakeUp) {
            this.pkg = pkg; this.svc = svc; this.label = label;
            this.namespace = namespace; this.rootId = rootId;
            this.customAction = customAction; this.switchMediaId = switchMediaId;
            this.wakeUp = wakeUp;
        }
    }

    /** Keyed by package name; insertion order preserved (Lazy → Qishui → QQ). */
    private java.util.Map<String, SourceConfig> sources;

    // Pending deferred results waiting for connections
    private final java.util.concurrent.ConcurrentHashMap<String, Result<List<MediaBrowserCompat.MediaItem>>> pendingResults = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 🎯 START/UPDATE FOREGROUND: Mandatory for Android 14+ Custom Buttons
     */
    private void updateForegroundNotification() {
        androidx.core.app.NotificationCompat.Builder builder = new androidx.core.app.NotificationCompat.Builder(this,
                CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lyrics_24dp)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);

        MediaMetadataCompat sessionMeta = mSession == null ? null : mSession.getController().getMetadata();
        if (sessionMeta != null) {
            builder.setContentTitle(sessionMeta.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
                .setContentText(sessionMeta.getString(MediaMetadataCompat.METADATA_KEY_ARTIST));
        } else {
            builder.setContentTitle("海风播放器")
                    .setContentText("正在同步车机内容...");
        }

        android.app.Notification notification = builder.build();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    private void updateSessionActive(String debugTag) {
        if (!mSession.isActive()) {
            mSession.setActive(true);
            Log.i(TAG, "🟢 Session ACTIVATE [" + debugTag + "]");
        }

        // ⚡ JOLT: Update extras to force Android Auto UI refresh
        Bundle extras = new Bundle();
        extras.putLong("haifeng.refresh_token", System.currentTimeMillis());
        mSession.setExtras(extras);
    }

    private PlaybackStateCompat buildMinimalState(int state, long pos, float speed) {
        long ACTIONS = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_FAST_FORWARD
                | PlaybackStateCompat.ACTION_REWIND;

        return new PlaybackStateCompat.Builder()
                .setState(state, pos, speed, SystemClock.elapsedRealtime())
                .setActions(ACTIONS)
                .build();
    }

    // =========================================================
    // 🔁 外部播放器回调：将元数据 / 播放状态同步给本地 Session
    // =========================================================
    private class RemoteCallback extends MediaControllerCompat.Callback {
        @Override
        public void onMetadataChanged(MediaMetadataCompat m) {
            mirror(m, null);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
            mirror(remoteCtrl == null ? null : remoteCtrl.getMetadata(), state);
        }
    };

    private final Runnable progressHeartbeat = new Runnable() {
        @Override
        public void run() {
            if (remoteCtrl != null) {
                mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
            }
        }
    };

    // =========================================================
    // 🪞 同步信息到本地 Session
    // =========================================================
    private void mirror(MediaMetadataCompat meta, PlaybackStateCompat st) {

        // --- 1. 同步元数据 ---
        if (meta != null) {
            String title = meta.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
            String artist = meta.getString(MediaMetadataCompat.METADATA_KEY_ARTIST);
            long duration = meta.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);

            String lastTitle = mSession.getController().getMetadata() == null ? null
                    : mSession.getController().getMetadata().getString(MediaMetadataCompat.METADATA_KEY_TITLE);
            String lastArtist = mSession.getController().getMetadata() == null ? null
                    : mSession.getController().getMetadata().getString(MediaMetadataCompat.METADATA_KEY_ARTIST);
            boolean songChanged = !java.util.Objects.equals(title, lastTitle) ||
                    !java.util.Objects.equals(artist, lastArtist);

            if (songChanged) {
                MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder(meta);
                if (duration <= 0 && st != null && st.getExtras() != null) {
                    duration = st.getExtras().getLong("android.media.metadata.DURATION");
                }
                builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration > 0 ? duration : 3600000L);

                Bitmap art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
                if (art == null)
                    art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON);
                if (art == null)
                    art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ART);
                if (art != null)
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);

                mSession.setMetadata(builder.build());
                updateForegroundNotification();
                updateSessionActive("mirror_song_changed");
            }
        } else {
            // 🛡️ FALLBACK: If app provides no metadata, show the App Label to clear
            // "Switching..."
            SharedPreferences sp = getSharedPreferences("session_pref", MODE_PRIVATE);
            String label = sp.getString("last_label", "海风播放器");
            mSession.setMetadata(new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, label)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "已准备就绪")
                    .build());
            Log.i(TAG, "ℹ️ Mirror: 远端无元数据，使用占位符 [" + label + "]");
        }

        // --- 2. 同步播放状态 ---
        if (st != null) {
            int code = st.getState();
            if (code == PlaybackStateCompat.STATE_NONE || code == PlaybackStateCompat.STATE_STOPPED) {
                code = PlaybackStateCompat.STATE_PAUSED; // 避免 AA 跳回浏览页
            }

            float speed = st.getPlaybackSpeed();
            if (speed == 0f) {
                speed = 1.0f;
            }

            PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                    .setState(code, st.getPosition(), speed,
                            SystemClock.elapsedRealtime())
                    .setActions(
                            PlaybackStateCompat.ACTION_PLAY |
                                    PlaybackStateCompat.ACTION_PAUSE |
                                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                    PlaybackStateCompat.ACTION_SEEK_TO |
                                    PlaybackStateCompat.ACTION_PLAY_PAUSE |
                                    PlaybackStateCompat.ACTION_PREPARE |
                                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID |
                                    PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH |
                                    PlaybackStateCompat.ACTION_SET_RATING);

            /* 🧹 Switch buttons removed - handled by Browser menu now */
            mSession.setPlaybackState(builder.build());

            // 💓 PROGRESS HEARTBEAT: Force a refresh every 1s if playing
            handler.removeCallbacks(progressHeartbeat);
            if (code == PlaybackStateCompat.STATE_PLAYING) {
                handler.postDelayed(progressHeartbeat, 1000);
            }
        } else {
            // 🛡️ FALLBACK: If app provides no state, force PAUSED to clear BUFFERING
            // animation in AA
            PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f)
                    .setActions(
                            PlaybackStateCompat.ACTION_PLAY |
                                    PlaybackStateCompat.ACTION_PAUSE |
                                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                    PlaybackStateCompat.ACTION_SEEK_TO |
                                    PlaybackStateCompat.ACTION_PLAY_PAUSE |
                                    PlaybackStateCompat.ACTION_PREPARE |
                                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID |
                                    PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH |
                                    PlaybackStateCompat.ACTION_SET_RATING);
            mSession.setPlaybackState(builder.build());
            Log.i(TAG, "ℹ️ Mirror: 远端无播放状态，强制 PAUSED 以清除 Buffer");
        }
    }

    // =========================================================
    // 📡 接收 QQ / NCM 的 Token 并构建 Controller（切源）
    // =========================================================
    private final BroadcastReceiver tokenRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Log.i("Mirror", "🎯 [tokenRx] Received Broadcast: " + i.getAction());
            if (!ACTION_CONTROLLER.equals(i.getAction()))
                return;

            // 1) 广播来源的包名（Sniffer 填入）
            String sourcePkg = i.getStringExtra("pkg");
            Log.i("Mirror", "🎯 [tokenRx] Source Package: " + sourcePkg);
            if (sourcePkg == null) {
                Log.w("Mirror", "⚠️ 收到控制广播但缺少 pkg");
                return;
            }

            // 2) 只采纳“当前选中的包名”
            SharedPreferences sp = getSharedPreferences("session_pref", MODE_PRIVATE);
            String chosenPkg = sp.getString("last_pkg", null);
            Log.i("Mirror", "🎯 [tokenRx] Chosen Package in Prefs: " + chosenPkg);
            if (chosenPkg == null || !chosenPkg.equals(sourcePkg)) {
                Log.i("Mirror", "ℹ️ 忽略不同来源广播，当前选择=" + chosenPkg + "，广播来自=" + sourcePkg);
                return;
            }

            // 3) 取 Token → 绑定 Controller
            MediaSessionCompat.Token tk = i.getParcelableExtra("binder");
            if (tk == null) {
                Log.i("Mirror", "ℹ️ 收到空 Token，尝试通过 Browser 主动连接来源=" + sourcePkg);
                SourceConfig nullSrc = sources.get(sourcePkg);
                if (nullSrc != null) connectSource(nullSrc);
                mirror(null, null);
                return;
            }

            // 🚀 TOKEN STABILITY: Only jolt if the session identity actually changed.
            // If it's the same token, don't trigger updateSessionActive to prevent AA
            // flicker.
            boolean isNewToken = (remoteCtrl == null || !remoteCtrl.getSessionToken().equals(tk));
            if (isNewToken) {
                updateSessionActive("sourceChanged:" + sourcePkg);
            }

            // 🚀 SNIFFER PRIORITY: Always accept Sniffer tokens even if bridged,
            // because the Sniffer finds the ACTIVE player session which is more accurate.
            if (remoteCtrl != null && remoteCtrl.getSessionToken().equals(tk)) {
                Log.i("Mirror", "ℹ️ 已绑定到相同 Controller，忽略重复 Sniffer 广播: " + sourcePkg);
                return;
            }

            try {
                if (remoteCtrl != null) {
                    remoteCtrl.unregisterCallback(remoteCb);
                }
                remoteCtrl = new MediaControllerCompat(MyMusicService.this, tk);
                remoteCtrl.registerCallback(remoteCb);

                // 5) 同步一次
                mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
                Log.i("Mirror", "✅ 绑定远端控制器 [Sniffer Path], pkg=" + sourcePkg);

                updateSessionActive("snifferUpdate");
            } catch (Exception e) {
                Log.e("Mirror", "❌ 绑定远端控制器失败 [Sniffer Path]", e);
            }
        }
    };

    // =========================================================
    // 🚀 启动服务：初始化本地 MediaSession 并设置转发逻辑
    // =========================================================
    private static final String CHANNEL_ID = "haifeng_mirror_channel";
    private static final int NOTIF_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();

        // Build source registry (done here so wake-up lambdas can capture 'this')
        sources = new java.util.LinkedHashMap<>();
        sources.put(LAZY_PKG, new SourceConfig(
                LAZY_PKG, LAZY_SVC, "懒人听书", "LAZY_", "LAZY_ROOT",
                CUSTOM_ACTION_SWITCH_LAZY, "SWITCH_LAZY", null));
        sources.put(QISHUI_PKG, new SourceConfig(
                QISHUI_PKG, QISHUI_SVC, "汽水音乐", "QISHUI_", "QISHUI_ROOT",
                CUSTOM_ACTION_SWITCH_QISHUI, "SWITCH_QISHUI", () -> {
                    try {
                        Intent wake = new Intent("android.media.browse.MediaBrowserService");
                        wake.setComponent(new android.content.ComponentName(QISHUI_PKG, QISHUI_SVC));
                        startService(wake);
                        Intent pulse = new Intent(Intent.ACTION_MEDIA_BUTTON);
                        pulse.setPackage(QISHUI_PKG);
                        pulse.putExtra(Intent.EXTRA_KEY_EVENT, new android.view.KeyEvent(
                                android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY));
                        sendBroadcast(pulse);
                    } catch (Exception ignored) {}
                }));
        sources.put(QQ_PKG, new SourceConfig(
                QQ_PKG, QQ_SVC, "QQ音乐", "QQ_", null,
                CUSTOM_ACTION_SWITCH_QQ, "SWITCH_QQ", () -> {
                    try {
                        Intent pulse = new Intent(Intent.ACTION_MEDIA_BUTTON);
                        pulse.setPackage(QQ_PKG);
                        pulse.putExtra(Intent.EXTRA_KEY_EVENT, new android.view.KeyEvent(
                                android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY));
                        sendBroadcast(pulse);
                    } catch (Exception ignored) {}
                }));


        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    CHANNEL_ID, "海风播放器 · 车机同步",
                    android.app.NotificationManager.IMPORTANCE_LOW);
            android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(
                    NOTIFICATION_SERVICE);
            if (manager != null)
                manager.createNotificationChannel(channel);
        }

        mSession = new MediaSessionCompat(this, "MirrorSession");

        // 🎯 LINK THE RECEIVER
        Intent mbrIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mbrIntent.setComponent(
                new android.content.ComponentName(this, androidx.media.session.MediaButtonReceiver.class));
        android.app.PendingIntent mbrPendingIntent = android.app.PendingIntent.getBroadcast(this, 0, mbrIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE);
        mSession.setMediaButtonReceiver(mbrPendingIntent);

        mSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        setSessionToken(mSession.getSessionToken());

        // 🎯 START FOREGROUND: This is the "Key" to unlock buttons on Android 14+
        updateForegroundNotification();

        mSession.setPlaybackState(buildMinimalState(
                PlaybackStateCompat.STATE_NONE, 0, 0f));
        updateSessionActive("onCreate");

        mSession.setCallback(new MediaSessionCompat.Callback() {

            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
                KeyEvent keyEvent = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (keyEvent != null) {
                    Log.i(TAG, "⌨️ RAW KEY DETECTED: " + keyEvent.getKeyCode() + " (Action: " + keyEvent.getAction()
                            + ")");
                }
                return super.onMediaButtonEvent(mediaButtonEvent);
            }

            @Override
            public void onPlay() {
                Log.i(TAG, "💓 HEARTBEAT: Play Clicked");
                if (remoteCtrl != null)
                    remoteCtrl.getTransportControls().play();
            }

            @Override
            public void onPause() {
                Log.i(TAG, "💓 HEARTBEAT: Pause Clicked");
                if (remoteCtrl != null)
                    remoteCtrl.getTransportControls().pause();
            }

            @Override
            public void onSkipToNext() {
                if (remoteCtrl != null)
                    remoteCtrl.getTransportControls().skipToNext();
            }

            @Override
            public void onSkipToPrevious() {
                if (remoteCtrl != null)
                    remoteCtrl.getTransportControls().skipToPrevious();
            }

            @Override
            public void onFastForward() {
                if (remoteCtrl != null)
                    remoteCtrl.getTransportControls().fastForward();
            }

            @Override
            public void onRewind() {
                if (remoteCtrl != null)
                    remoteCtrl.getTransportControls().rewind();
            }

            @Override
            public void onPlayFromMediaId(String mediaId, Bundle extras) {
                Log.i(TAG, "🎯 onPlayFromMediaId: " + mediaId);

                // 🚀 EXPLICIT SWITCHING — look up by switchMediaId
                for (SourceConfig s : sources.values()) {
                    if (s.switchMediaId.equals(mediaId)) {
                        performManualSwitch(s.pkg, s.label);
                        return;
                    }
                }

                // Strip namespace prefix to obtain the real upstream media ID
                String realId = mediaId;
                for (SourceConfig s : sources.values()) {
                    if (mediaId.startsWith(s.namespace)) {
                        realId = mediaId.substring(s.namespace.length());
                        break;
                    }
                }
                if (remoteCtrl != null) {
                    remoteCtrl.getTransportControls().playFromMediaId(realId, extras);
                    Log.i(TAG, "▶️ playFromMediaId → " + realId);
                }
            }

            @Override
            public void onSeekTo(long positionMs) {
                if (remoteCtrl != null) {
                    remoteCtrl.getTransportControls().seekTo(positionMs);
                    handler.postDelayed(() -> mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState()), 250);
                }
                updateSessionActive("seekTo");
            }

            @Override
            public void onCustomAction(String action, Bundle extras) {
                Log.i(TAG, "🎯>>> RECEIVED CUSTOM ACTION: [" + action + "]");

                for (SourceConfig s : sources.values()) {
                    if (s.customAction.equals(action)) {
                        performManualSwitch(s.pkg, s.label);
                        return;
                    }
                }
            }
        });

        // 注册 Token 广播 (Internal)
        registerReceiver(tokenRx, new IntentFilter(ACTION_CONTROLLER), Context.RECEIVER_NOT_EXPORTED);
        updateSessionActive("onCreate");
    }

    private void connectSource(SourceConfig src) {
        if (src.connecting || (src.browser != null && src.browser.isConnected())) {
            return;
        }
        src.connecting = true;
        Log.i(TAG, "🔌 正在连接来源... pkg=" + src.pkg + " label=" + src.label);

        android.content.ComponentName cn = new android.content.ComponentName(src.pkg, src.svc);
        android.support.v4.media.MediaBrowserCompat browser = new android.support.v4.media.MediaBrowserCompat(
                this, cn,
                new android.support.v4.media.MediaBrowserCompat.ConnectionCallback() {
                    @Override
                    public void onConnected() {
                        src.connected = true;
                        src.connecting = false;
                        Log.i(TAG, "✅ 已连接来源 MediaBrowserService, pkg=" + src.pkg
                                + " root=" + src.browser.getRoot());

                        try {
                            MediaSessionCompat.Token token = src.browser.getSessionToken();
                            if (remoteCtrl != null) {
                                remoteCtrl.unregisterCallback(remoteCb);
                            }
                            remoteCtrl = new MediaControllerCompat(MyMusicService.this, token);
                            remoteCtrl.registerCallback(remoteCb);

                            getSharedPreferences("session_pref", MODE_PRIVATE)
                                    .edit()
                                    .putString("last_pkg", src.pkg)
                                    .putString("last_label", src.label)
                                    .apply();

                            handler.postDelayed(() -> {
                                mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
                                Log.i(TAG, "🎧 已绑定来源控制器并同步状态, pkg=" + src.pkg);
                            }, 500);
                        } catch (Exception e) {
                            Log.e(TAG, "❌ 绑定来源控制器失败, pkg=" + src.pkg, e);
                        }

                        String rootPendingKey = src.namespace + "ROOT_PENDING";
                        java.util.Iterator<java.util.Map.Entry<String, Result<List<MediaBrowserCompat.MediaItem>>>> it =
                                pendingResults.entrySet().iterator();
                        while (it.hasNext()) {
                            java.util.Map.Entry<String, Result<List<MediaBrowserCompat.MediaItem>>> e = it.next();
                            if (e.getKey().startsWith(src.namespace)) {
                                String resolvedId = rootPendingKey.equals(e.getKey()) ? src.rootId : e.getKey();
                                onLoadChildren(resolvedId, e.getValue());
                                it.remove();
                            }
                        }
                    }

                    @Override
                    public void onConnectionFailed() {
                        src.connected = false;
                        src.connecting = false;
                        Log.e(TAG, "❌ 连接来源失败, pkg=" + src.pkg);
                        flushPendingError(src.namespace);
                    }

                    @Override
                    public void onConnectionSuspended() {
                        src.connected = false;
                        src.connecting = false;
                        Log.w(TAG, "⚠️ 来源连接挂起, pkg=" + src.pkg);
                    }
                }, null);

        src.browser = browser;
        browser.connect();
    }

    private void flushPendingError(String prefix) {
        java.util.Iterator<java.util.Map.Entry<String, Result<List<MediaBrowserCompat.MediaItem>>>> it = pendingResults
                .entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, Result<List<MediaBrowserCompat.MediaItem>>> e = it.next();
            if (e.getKey().startsWith(prefix)) {
                try {
                    e.getValue().sendResult(java.util.Collections.emptyList());
                } catch (Exception ignored) {
                }
                it.remove();
            }
        }
    }

    private void pauseCurrentSourceBeforeSwitch(String nextPkg) {
        if (remoteCtrl == null) {
            return;
        }

        String currentPkg = getSharedPreferences("session_pref", MODE_PRIVATE)
                .getString("last_pkg", null);
        if (currentPkg == null || currentPkg.equals(nextPkg)) {
            return;
        }

        try {
            PlaybackStateCompat state = remoteCtrl.getPlaybackState();
            if (state == null) {
                return;
            }

            int currentState = state.getState();
            if (currentState == PlaybackStateCompat.STATE_PLAYING
                    || currentState == PlaybackStateCompat.STATE_BUFFERING
                    || currentState == PlaybackStateCompat.STATE_CONNECTING) {
                Log.i(TAG, "⏸️ Pre-switch pause current source: " + currentPkg + " -> " + nextPkg);
                remoteCtrl.getTransportControls().pause();
            }
        } catch (Exception e) {
            Log.w(TAG, "⚠️ Pre-switch pause failed for pkg=" + currentPkg, e);
        }
    }

    private void subscribeAndDeliver(MediaBrowserCompat browser, String parentId, String namespace,
            Result<List<MediaBrowserCompat.MediaItem>> result) {
        browser.unsubscribe(parentId);
        browser.subscribe(parentId,
                new android.support.v4.media.MediaBrowserCompat.SubscriptionCallback() {
                    @Override
                    public void onChildrenLoaded(String loadedParentId,
                            List<android.support.v4.media.MediaBrowserCompat.MediaItem> children) {
                        java.util.List<MediaBrowserCompat.MediaItem> proxied = new java.util.ArrayList<>();
                        for (android.support.v4.media.MediaBrowserCompat.MediaItem item : children) {
                            String namespacedId = namespace + item.getMediaId();
                            android.support.v4.media.MediaDescriptionCompat desc = new android.support.v4.media.MediaDescriptionCompat.Builder()
                                    .setMediaId(namespacedId)
                                    .setTitle(item.getDescription().getTitle())
                                    .setSubtitle(item.getDescription().getSubtitle())
                                    .setIconUri(item.getDescription().getIconUri())
                                    .setIconBitmap(item.getDescription().getIconBitmap())
                                    .build();
                            proxied.add(new MediaBrowserCompat.MediaItem(desc, item.getFlags()));
                            Log.i(TAG, "  [" + (item.isBrowsable() ? "DIR" : "FILE") + "] "
                                    + item.getDescription().getTitle() + " id=" + item.getMediaId());
                        }
                        result.sendResult(proxied);
                        browser.unsubscribe(loadedParentId);
                    }

                    @Override
                    public void onError(String loadedParentId) {
                        Log.e(TAG, "❌ 订阅失败 parentId=" + loadedParentId + " namespace=" + namespace);
                        result.sendResult(Collections.emptyList());
                    }
                });
    }

    // =========================================================
    // 🧹 资源释放
    // =========================================================
    @Override
    public void onDestroy() {
        handler.removeCallbacks(progressHeartbeat);
        if (remoteCtrl != null) {
            remoteCtrl.unregisterCallback(remoteCb);
        }
        for (SourceConfig src : sources.values()) {
            if (src.browser != null && src.browser.isConnected()) {
                src.browser.disconnect();
            }
        }

        try {
            unregisterReceiver(tokenRx);
        } catch (Exception ignored) {
        }

        mSession.release();
        super.onDestroy();
    }

    // =========================================================
    // 🚪 MediaBrowser 接口（供 Android Auto 探测）
    // =========================================================
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName,
            int clientUid,
            Bundle rootHints) {
        return new BrowserRoot("root", null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId,
            @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        if ("root".equals(parentId)) {
            List<MediaBrowserCompat.MediaItem> root = new ArrayList<>();

            // 🎯 SINGLE FOLDER: "Command Center"
            MediaDescriptionCompat cmdDesc = new MediaDescriptionCompat.Builder()
                    .setMediaId("FOLDER_SWITCH")
                    .setTitle("🔄 切换播放源")
                    .setSubtitle("QQ / 懒人 / 汽水")
                    .setIconUri(Uri.parse("android.resource://" + getPackageName() + "/" + R.drawable.ic_qishui_vec))
                    .build();
            root.add(new MediaBrowserCompat.MediaItem(cmdDesc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));

            result.sendResult(root);
            return;
        }

        // 🎯 CONTENT OF THE SWITCH FOLDER
        if (parentId.equals("FOLDER_SWITCH")) {
            List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();

            // 1. QQ
            items.add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                    .setMediaId("SWITCH_QQ")
                    .setTitle("切换至: QQ音乐")
                    .setIconUri(Uri.parse("android.resource://" + getPackageName() + "/" + R.drawable.ic_qq_vec))
                    .build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));

            // 2. Lazy
            items.add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                    .setMediaId("SWITCH_LAZY")
                    .setTitle("切换至: 懒人听书")
                    .setIconUri(Uri.parse("android.resource://" + getPackageName() + "/" + R.drawable.ic_lazy_vec))
                    .build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));

            // 3. Qishui
            items.add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                    .setMediaId("SWITCH_QISHUI")
                    .setTitle("切换至: 汽水音乐")
                    .setIconUri(Uri.parse("android.resource://" + getPackageName() + "/" + R.drawable.ic_qishui_vec))
                    .build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));

            result.sendResult(items);
            return;
        }

        // 🎯 HANDLE SWITCH COMMANDS FROM BROWSER
        if (parentId.equals("SWITCH_QQ")) {
            performManualSwitch("com.tencent.qqmusic", "QQ音乐");
            List<MediaBrowserCompat.MediaItem> feedback = new ArrayList<>();
            feedback.add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                    .setMediaId("SWITCH_DONE")
                    .setTitle("✅ 已切换至 QQ音乐")
                    .build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
            result.sendResult(feedback);
            return;
        }

        // Handle Proxied Browsing: look up source by rootId or namespace prefix
        SourceConfig browseSource = null;
        for (SourceConfig s : sources.values()) {
            if (s.rootId != null && (parentId.equals(s.rootId) || parentId.startsWith(s.namespace))) {
                browseSource = s;
                break;
            }
        }
        if (browseSource != null) {
            final SourceConfig src = browseSource;
            String realParentId;
            if (parentId.equals(src.rootId)) {
                if (!src.connected || src.browser == null) {
                    if (!pendingResults.containsValue(result)) {
                        result.detach();
                        pendingResults.put(src.namespace + "ROOT_PENDING", result);
                    }
                    if (src.browser == null || !src.browser.isConnected()) {
                        connectSource(src);
                    }
                    return;
                }
                realParentId = src.browser.getRoot();
            } else {
                realParentId = parentId.substring(src.namespace.length());
            }
            if (!pendingResults.containsValue(result)) {
                try {
                    result.detach();
                } catch (Exception ignored) {
                }
            }
            subscribeAndDeliver(src.browser, realParentId, src.namespace, result);
            return;
        }

        result.sendResult(java.util.Collections.emptyList());
    }

    private void performManualSwitch(String pkg, String label) {
        Log.i(TAG, "🔄 [Universal Sync] Switching to: " + label + " (" + pkg + ")");
        pauseCurrentSourceBeforeSwitch(pkg);
        getSharedPreferences("session_pref", MODE_PRIVATE)
                .edit()
                .putString("last_pkg", pkg)
                .putString("last_label", label)
                .commit();

        // 🎯 Signal the Sniffer and MainActivity
        sendBroadcast(new Intent("com.haifeng.ACTION_SELECTION_CHANGED")
                .setPackage(getPackageName())
                .putExtra("pkg", pkg)
                .putExtra("label", label));

        // 🚀 DEEP REFRESH: Force car screen to clear old info
        Log.i(TAG, "🌪️ [DEEP REFRESH] Clearing car screen for: " + label);
        mSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "正在切换至: " + label)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "海风播放器 · 魔法桥接中...")
                .build());
        mSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_BUFFERING, 0, 1.0f)
                .build());

        updateSessionActive("manual_switch");

        SourceConfig src = sources.get(pkg);
        if (src != null) {
            if (src.wakeUp != null) src.wakeUp.run();
            src.connecting = false; // reset so connectSource will re-connect
            connectSource(src);
        }
        sendBroadcast(new Intent("com.haifeng.REQUEST_TOKEN").setPackage(getPackageName()));
    }
}

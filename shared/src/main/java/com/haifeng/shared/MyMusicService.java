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

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.media.MediaBrowserServiceCompat;

import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.util.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MyMusicService extends MediaBrowserServiceCompat {

    private MediaSessionCompat mSession;                       // 本地 MediaSession
    private MediaControllerCompat remoteCtrl;                  // 指向外部播放器的控制器（QQ 或 NCM）
    private final MediaControllerCompat.Callback remoteCb = new RemoteCallback(); // 监听状态变化

    private static final String CUSTOM_ACTION_SHOW_LYRICS = "ACTION_LYRICS";
    private static final String CUSTOM_ACTION_REPEAT_MODE = "ACTION_REPEAT";
    private static final String CUSTOM_ACTION_SWITCH_LAZY = "ACTION_LAZY";
    private static final String CUSTOM_ACTION_SWITCH_QISHUI = "ACTION_QISHUI";
    private static final String CUSTOM_ACTION_SWITCH_QQ = "ACTION_QQ";
    private static final String LAZY_ROOT = "LAZY_ROOT";
    private static final String QISHUI_ROOT = "QISHUI_ROOT";

    private static final String LAZY_PKG = "bubei.tingshu.international";
    private static final String LAZY_SVC = "tingshu.bubei.mediasupport.service.MediaSessionBrowserService";

    private static final String QISHUI_PKG = "com.luna.music";
    private static final String QISHUI_SVC = "com.luna.biz.playing.player.PlayerService";

    private static final String QQ_PKG = "com.tencent.qqmusic";
    private static final String QQ_SVC = "com.tencent.qqmusic.MediaSessionBrowserService";

    private static final String ACTION_CONTROLLER = "com.haifeng.ACTION_CONTROLLER";

    private static final String ACTION_TOGGLE_LYRICS_MODE = "com.haifeng.ACTION_TOGGLE_LYRICS_MODE";

    private List<Pair<Long, String>> parsedLyrics = new ArrayList<>();
    private boolean isLyricsMode = false; // 仅 QQ 模式可用；NCM 模式强制关闭
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int lastPlayMode = 0; // QQ 的播放模式缓存

    // ===== 仅在“QQ 歌词模式”下使用的缓存/本地时钟 =====
    private MediaMetadataCompat lastRemoteMeta = null;
    private PlaybackStateCompat lastRemoteState = null;

    private boolean suppressRemoteState = false; // 拖动后的保护期：忽略短期旧状态回写
    private final Runnable clearSuppression = () -> suppressRemoteState = false;

    private long basePosMs = 0L;
    private long baseUpdateElapsed = 0L;
    private float baseSpeed = 0f;
    private int baseState = PlaybackStateCompat.STATE_NONE;
    private long durationMs = 0L;

    private String lastLyricsRaw = null;

    // 新增：当前是否处于“网易云模式”
    private boolean isNcmMode = false;  // false=QQ 模式；true=非QQ（任意播放器）模式

    // 新增：防止重复激活
    private boolean sessionActivated = false;

    // 放在成员里
    private static final String TAG = "Mirror";

    private android.support.v4.media.MediaBrowserCompat lazyBrowser;
    private boolean lazyConnected = false;
    private boolean lazyConnecting = false;

    private android.support.v4.media.MediaBrowserCompat qishuiBrowser;
    private boolean qishuiConnected = false;
    private boolean qishuiConnecting = false;

    private android.support.v4.media.MediaBrowserCompat qqBrowser;
    private boolean qqConnected = false;
    private boolean qqConnecting = false;

    // Pending deferred results waiting for connections
    private final java.util.concurrent.ConcurrentHashMap<String, Result<List<MediaBrowserCompat.MediaItem>>>
            pendingResults = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 🎯 START/UPDATE FOREGROUND: Mandatory for Android 14+ Custom Buttons
     */
    private void updateForegroundNotification() {
        androidx.core.app.NotificationCompat.Builder builder = new androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lyrics_24dp)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);

        if (lastRemoteMeta != null) {
            builder.setContentTitle(lastRemoteMeta.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
                   .setContentText(lastRemoteMeta.getString(MediaMetadataCompat.METADATA_KEY_ARTIST));
        } else {
            builder.setContentTitle("糯米播放器")
                   .setContentText("正在同步车机内容...");
        }

        android.app.Notification notification = builder.build();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
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



    // 以“基准位置+基准时间+速度”推算当前 position（只在 QQ 歌词模式用）
    private long clockPosition() {
        if (baseState == PlaybackStateCompat.STATE_PLAYING) {
            long elapsed = SystemClock.elapsedRealtime() - baseUpdateElapsed;
            long pos = basePosMs + (long) (elapsed * baseSpeed);
            return Math.max(0L, durationMs > 0 ? Math.min(pos, durationMs) : pos);
        } else {
            return basePosMs;
        }
    }

    // 每秒刷新（仅 QQ 歌词模式）
    private final Runnable lyricsUpdater = new Runnable() {
        @Override
        public void run() {
            if (!isNcmMode && isLyricsMode && remoteCtrl != null) {
                applyLyricsOverlay(lastRemoteMeta);
                handler.postDelayed(this, 1000);
            }
        }
    };

    // “自动开启歌词模式”广播，仅 QQ 模式生效
    private final BroadcastReceiver autoLyricsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i("Mirror", "📨 收到自动开启歌词模式请求");
            if (isNcmMode) { // 非QQ模式
                Log.i("Mirror", "ℹ️ 当前为【非 QQ 模式】，忽略开启歌词模式请求");
                return;
            }
            if (!isLyricsMode) {
                isLyricsMode = true;
                Log.i("Mirror", "🎵 已开启歌词模式（QQ）");

                if (lastRemoteState != null) {
                    basePosMs = lastRemoteState.getPosition();
                    baseSpeed = lastRemoteState.getPlaybackSpeed();
                    baseState = lastRemoteState.getState();
                    baseUpdateElapsed = SystemClock.elapsedRealtime();
                }

                handler.post(lyricsUpdater);
                if (remoteCtrl != null) {
                    applyLyricsOverlay(lastRemoteMeta);
                    mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
                }
            }
        }
    };


    // =========================================================
    // 🔁 外部播放器回调：将元数据 / 播放状态同步给本地 Session
    // =========================================================
    private class RemoteCallback extends MediaControllerCompat.Callback {
        @Override public void onMetadataChanged(MediaMetadataCompat m) {
            lastRemoteMeta = m; // 缓存给 QQ 歌词模式
            mirror(m, null);
        }

        @Override public void onPlaybackStateChanged(PlaybackStateCompat state) {
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
    // 🪞 同步信息到本地 Session（根据当前来源分支）
    // =========================================================
    private void mirror(MediaMetadataCompat meta, PlaybackStateCompat st) {

        // --- 1. 同步元数据 ---
        if (meta != null) {
            if (!isNcmMode && isLyricsMode) {
                // QQ 歌词模式：覆盖为“当前句/下一句”
                applyLyricsOverlay(meta);
            } else {
                String title = meta.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
                String artist = meta.getString(MediaMetadataCompat.METADATA_KEY_ARTIST);
                long duration = meta.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);

                // 🚀 LYRIC-STABLE DEDUPLICATION:
                // We compare against the "Real" Title/Artist, ignoring the overlaid lyrics.
                String lastTrueTitle = mSession.getController().getMetadata() == null ? null : 
                                      mSession.getController().getMetadata().getString("ucar.media.metadata.ORIGINAL_TITLE");
                String lastTrueArtist = mSession.getController().getMetadata() == null ? null :
                                       mSession.getController().getMetadata().getString("ucar.media.metadata.ORIGINAL_ARTIST");
                
                boolean realSongChanged = !java.util.Objects.equals(title, lastTrueTitle) || 
                                          !java.util.Objects.equals(artist, lastTrueArtist);

                // We also check if the *rendered* metadata changed (to know if we should update the lyric text)
                String lastRenderedTitle = mSession.getController().getMetadata() == null ? null :
                                          mSession.getController().getMetadata().getString(MediaMetadataCompat.METADATA_KEY_TITLE);
                boolean renderedChanged = !java.util.Objects.equals(title, lastRenderedTitle);

                if (renderedChanged) {
                    MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder(meta);
                    // Store the "Real" identity for the next comparison
                    builder.putString("ucar.media.metadata.ORIGINAL_TITLE", title);
                    builder.putString("ucar.media.metadata.ORIGINAL_ARTIST", artist);

                    if (duration <= 0 && st != null && st.getExtras() != null) {
                        duration = st.getExtras().getLong("android.media.metadata.DURATION");
                    }
                    builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration > 0 ? duration : 3600000L);

                    // Bitmap Logic
                    Bitmap art = null;
                    if (isNcmMode) {
                        art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
                        if (art == null) art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON);
                        if (art == null) art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ART);
                    } else {
                        art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
                    }
                    if (art != null) builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);

                    mSession.setMetadata(builder.build());
                    updateForegroundNotification();
                    
                    // 🚀 CRITICAL: Only Jolt if the REAL song changed. 
                    // Lyric updates remain "Silent" to keep buttons stable.
                    if (realSongChanged) {
                        updateSessionActive("mirror_song_changed");
                    }
                }
            }
        } else {
            // 🛡️ FALLBACK: If app provides no metadata, show the App Label to clear "Switching..."
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
            // 🎯 SHARED STATE LOGIC: Update our clock for ALL modes
            if (!suppressRemoteState) {
                basePosMs = st.getPosition();
                baseSpeed = st.getPlaybackSpeed();
                baseState = st.getState();
                baseUpdateElapsed = SystemClock.elapsedRealtime();
            }

            int code = st.getState();
            if (code == PlaybackStateCompat.STATE_NONE || code == PlaybackStateCompat.STATE_STOPPED) {
                code = PlaybackStateCompat.STATE_PAUSED; // 避免 AA 跳回浏览页
            }

            PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                    .setState(code, clockPosition(), (baseSpeed == 0f ? 1.0f : baseSpeed), SystemClock.elapsedRealtime())
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
                            PlaybackStateCompat.ACTION_SET_RATING
                    );

            // 🎤 Custom Actions (Lyrics & Repeat) - 🚀 STABLE BUTTONS
            if (!isNcmMode) {
                int lyricsIconRes = isLyricsMode ? R.drawable.ic_lyrics_24dp : R.drawable.ic_lyrics_outline_24dp;
                int repeatIconRes = R.drawable.ic_repeat_24dp;
                if (meta != null) {
                    long playMode = meta.getLong("ucar.media.metadata.PLAY_MODE");
                    if (playMode == 1) repeatIconRes = R.drawable.ic_repeat_one_24dp;
                    else if (playMode == 0) repeatIconRes = R.drawable.ic_shuffle_24dp;
                }

                // Optimization: Custom actions cause UI redraws. 
                // Only re-add them if they actually changed or if this is the initial metadata mirror.
                builder.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                        CUSTOM_ACTION_SHOW_LYRICS, "歌词", lyricsIconRes).build());
                builder.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                        CUSTOM_ACTION_REPEAT_MODE, "循环", repeatIconRes).build());
            }

            /* 🧹 Switch buttons removed - handled by Browser menu now */
            mSession.setPlaybackState(builder.build());
            
            // 💓 PROGRESS HEARTBEAT: Force a refresh every 1s if playing
            handler.removeCallbacks(progressHeartbeat);
            if (code == PlaybackStateCompat.STATE_PLAYING) {
                handler.postDelayed(progressHeartbeat, 1000);
            }
        } else {
            // 🛡️ FALLBACK: If app provides no state, force PAUSED to clear BUFFERING animation in AA
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
                            PlaybackStateCompat.ACTION_SET_RATING
                    );
            mSession.setPlaybackState(builder.build());
            Log.i(TAG, "ℹ️ Mirror: 远端无播放状态，强制 PAUSED 以清除 Buffer");
        }
    }

    // 仅在“QQ 歌词模式”调用：把当前/下一句覆盖到元数据
    private void applyLyricsOverlay(MediaMetadataCompat meta) {
        if (isNcmMode || !isLyricsMode || meta == null) return;

        long playMode = meta.getLong("ucar.media.metadata.PLAY_MODE");
        lastPlayMode = (int) playMode;
        long dur = meta.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
        if (dur > 0) durationMs = dur;

        String lyricsWhole = meta.getString("ucar.media.metadata.LYRICS_WHOLE");
        if (lyricsWhole != null && !lyricsWhole.equals(lastLyricsRaw)) {
            lastLyricsRaw = lyricsWhole;
            parseLyrics(lyricsWhole);
        }

        long t = clockPosition();
        String current = "", next = "";
        if (!parsedLyrics.isEmpty()) {
            int lo = 0, hi = parsedLyrics.size() - 1, ans = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (parsedLyrics.get(mid).first <= t) { ans = mid; lo = mid + 1; }
                else hi = mid - 1;
            }
            if (ans >= 0) current = parsedLyrics.get(ans).second;
            if (ans + 1 < parsedLyrics.size()) next = parsedLyrics.get(ans + 1).second;
        }

        MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder();
        b.putString(MediaMetadataCompat.METADATA_KEY_TITLE, current);
        b.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, next);

        Bitmap art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
        if (art != null) b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
        if (durationMs > 0) b.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);

        mSession.setMetadata(b.build());

        int code = (baseState == PlaybackStateCompat.STATE_NONE || baseState == PlaybackStateCompat.STATE_STOPPED)
                ? PlaybackStateCompat.STATE_PAUSED : baseState;

        PlaybackStateCompat.Builder ps = new PlaybackStateCompat.Builder()
                .setState(code, clockPosition(), (baseSpeed == 0f ? 1.0f : baseSpeed))
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                                PlaybackStateCompat.ACTION_PAUSE |
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                PlaybackStateCompat.ACTION_SEEK_TO |
                                PlaybackStateCompat.ACTION_PLAY_PAUSE |
                                PlaybackStateCompat.ACTION_FAST_FORWARD |
                                PlaybackStateCompat.ACTION_REWIND |
                                PlaybackStateCompat.ACTION_PREPARE |
                                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID |
                                PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH |
                                PlaybackStateCompat.ACTION_SET_RATING
                );

        /* 🧹 Switch buttons removed - handled by Browser menu now */
        mSession.setPlaybackState(ps.build());
    }

    // =========================================================
    // 📡 接收 QQ / NCM 的 Token 并构建 Controller（切源）
    // =========================================================
    private final BroadcastReceiver tokenRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Log.i("Mirror", "🎯 [tokenRx] Received Broadcast: " + i.getAction());
            if (!ACTION_CONTROLLER.equals(i.getAction())) return;

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

            // 3) 根据来源是否 QQ 切换模式（非 QQ → 旧 NCM 逻辑）
            boolean toNonQqMode = !"com.tencent.qqmusic".equals(sourcePkg);
            if (toNonQqMode != isNcmMode) {
                isNcmMode = toNonQqMode;
                if (isNcmMode) {
                    Log.i("Mirror", "🔄 切换为【非 QQ 模式】（禁用歌词/自定义按钮），来源=" + sourcePkg);
                    if (isLyricsMode) {
                        isLyricsMode = false;
                        handler.removeCallbacks(lyricsUpdater);
                        suppressRemoteState = false;
                        Log.i("Mirror", "🧹 已关闭歌词模式并清理定时任务（进入非QQ）");
                    }
                } else {
                    Log.i("Mirror", "🔄 切换为【QQ 模式】（可用歌词/自定义按钮）");
                }
            }

            // 4) 取 Token → 绑定 Controller
            MediaSessionCompat.Token tk = i.getParcelableExtra("binder");
            if (tk == null) {
                Log.i("Mirror", "ℹ️ 收到空 Token，可能是应用未启动。强制刷新状态以清除 Switching 占位符。");
                mirror(null, null);
                return;
            }

            // 🚀 TOKEN STABILITY: Only jolt if the session identity actually changed.
            // If it's the same token, don't trigger updateSessionActive to prevent AA flicker.
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


    private void parseLyrics(String rawLyrics) {
        parsedLyrics.clear();
        Pattern pattern = Pattern.compile("\\[(\\d{2}):(\\d{2}\\.\\d{2})\\](.*)");
        for (String line : rawLyrics.split("\n")) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                int min = Integer.parseInt(matcher.group(1));
                float sec = Float.parseFloat(matcher.group(2));
                long timeMs = (long) ((min * 60 + sec) * 1000);
                String text = matcher.group(3).trim();
                parsedLyrics.add(new Pair<>(timeMs, text));
            }
        }
    }

    // =========================================================
    // 🚀 启动服务：初始化本地 MediaSession 并设置转发逻辑
    // =========================================================
    private static final String CHANNEL_ID = "haifeng_mirror_channel";
    private static final int NOTIF_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();

        // 1. Create Notification Channel for Android 8.0+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    CHANNEL_ID, "海风播放器 · 车机同步",
                    android.app.NotificationManager.IMPORTANCE_LOW);
            android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }

        mSession = new MediaSessionCompat(this, "MirrorSession");
        
        // 🎯 LINK THE RECEIVER
        Intent mbrIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mbrIntent.setComponent(new android.content.ComponentName(this, androidx.media.session.MediaButtonReceiver.class));
        android.app.PendingIntent mbrPendingIntent = android.app.PendingIntent.getBroadcast(this, 0, mbrIntent, android.app.PendingIntent.FLAG_IMMUTABLE);
        mSession.setMediaButtonReceiver(mbrPendingIntent);

        mSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
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
                    Log.i(TAG, "⌨️ RAW KEY DETECTED: " + keyEvent.getKeyCode() + " (Action: " + keyEvent.getAction() + ")");
                }
                return super.onMediaButtonEvent(mediaButtonEvent);
            }

            @Override public void onPlay() {
                Log.i(TAG, "💓 HEARTBEAT: Play Clicked");
                if (remoteCtrl != null)
                    remoteCtrl.getTransportControls().play();
            }

            @Override public void onPause() {
                Log.i(TAG, "💓 HEARTBEAT: Pause Clicked");
                if (remoteCtrl != null)
                    remoteCtrl.getTransportControls().pause();
            }

            @Override public void onSkipToNext() {
                if (remoteCtrl != null)
                    remoteCtrl.getTransportControls().skipToNext();
            }

            @Override public void onSkipToPrevious() {
                if (remoteCtrl != null)
                    remoteCtrl.getTransportControls().skipToPrevious();
            }

            @Override public void onFastForward() {
                if (remoteCtrl != null)
                    remoteCtrl.getTransportControls().fastForward();
            }

            @Override public void onRewind() {
                if (remoteCtrl != null)
                    remoteCtrl.getTransportControls().rewind();
            }

            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
                Log.i(TAG, "🎯 onPlayFromMediaId: " + mediaId);
                
                // 🚀 EXPLICIT SWITCHING
                if ("SWITCH_QQ".equals(mediaId)) {
                    performManualSwitch("com.tencent.qqmusic", "QQ音乐");
                    return;
                } else if ("SWITCH_LAZY".equals(mediaId)) {
                    performManualSwitch(LAZY_PKG, "懒人听书");
                    return;
                } else if ("SWITCH_QISHUI".equals(mediaId)) {
                    performManualSwitch(QISHUI_PKG, "汽水音乐");
                    return;
                }

                String realId = mediaId;
                if (mediaId.startsWith("LAZY_")) {
                    realId = mediaId.substring(5);
                } else if (mediaId.startsWith("QISHUI_")) {
                    realId = mediaId.substring(7);
                }
                if (remoteCtrl != null) {
                    remoteCtrl.getTransportControls().playFromMediaId(realId, extras);
                    Log.i(TAG, "▶️ playFromMediaId → " + realId);
                }
            }

            @Override public void onSeekTo(long positionMs) {
                if (remoteCtrl != null) {
                    remoteCtrl.getTransportControls().seekTo(positionMs);
                }

                if (!isNcmMode && isLyricsMode) {
                    suppressRemoteState = true;
                    handler.removeCallbacks(clearSuppression);
                    handler.postDelayed(clearSuppression, 1200);

                    long now = SystemClock.elapsedRealtime();
                    PlaybackStateCompat rs = lastRemoteState;
                    baseState = (rs != null) ? rs.getState() : PlaybackStateCompat.STATE_PLAYING;
                    baseSpeed = (rs != null) ? rs.getPlaybackSpeed() : 1.0f;
                    basePosMs = positionMs;
                    baseUpdateElapsed = now;

                    applyLyricsOverlay(lastRemoteMeta);
                } else {
                    PlaybackStateCompat remoteState =
                            (remoteCtrl != null) ? remoteCtrl.getPlaybackState() : null;

                    if (remoteState != null) {
                        mSession.setPlaybackState(remoteState);
                    }
                    updateSessionActive("seekTo");
                }
            }

            @Override
            public void onCustomAction(String action, Bundle extras) {
                // 🕵️‍♂️ THE "TRUTH" LOG: If the car sends ANYTHING, we see it here first
                Log.i(TAG, "🎯>>> RECEIVED CUSTOM ACTION: [" + action + "]");
                
                if (CUSTOM_ACTION_SHOW_LYRICS.equals(action)) {
                    isLyricsMode = !isLyricsMode;

                    if (isLyricsMode) {
                        if (lastRemoteState != null) {
                            basePosMs = lastRemoteState.getPosition();
                            baseSpeed = lastRemoteState.getPlaybackSpeed();
                            baseState = lastRemoteState.getState();
                            baseUpdateElapsed = SystemClock.elapsedRealtime();
                        }
                        handler.post(lyricsUpdater);
                        applyLyricsOverlay(lastRemoteMeta);
                    } else {
                        handler.removeCallbacks(lyricsUpdater);
                        suppressRemoteState = false;
                    }

                    if (remoteCtrl != null) {
                        mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
                    }
                    updateSessionActive("toggleLyrics=" + isLyricsMode);

                } else if (CUSTOM_ACTION_REPEAT_MODE.equals(action)) {
                    // 仅 QQ 模式发送 QQ 的切换广播
                    Intent intent = new Intent("com.tencent.qqmusic.ACTION_SERVICE_PLAY_MODE_WIDGET.QQMusicPhone");
                    intent.setPackage("com.tencent.qqmusic");
                    sendBroadcast(intent);

                    handler.postDelayed(() -> {
                        if (remoteCtrl != null) {
                            mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
                        }
                    }, 500);
                } else if (CUSTOM_ACTION_SWITCH_LAZY.equals(action)) {
                    performManualSwitch(LAZY_PKG, "懒人听书");
                } else if (CUSTOM_ACTION_SWITCH_QISHUI.equals(action)) {
                    performManualSwitch(QISHUI_PKG, "汽水音乐");
                } else if (CUSTOM_ACTION_SWITCH_QQ.equals(action)) {
                    performManualSwitch("com.tencent.qqmusic", "QQ音乐");
                }
            }
        });

        // 注册 Token 广播 (Internal)
        registerReceiver(tokenRx, new IntentFilter(ACTION_CONTROLLER), Context.RECEIVER_NOT_EXPORTED);

        // 注册“自动歌词模式”广播 (Internal)
        registerReceiver(autoLyricsReceiver, new IntentFilter(ACTION_TOGGLE_LYRICS_MODE), Context.RECEIVER_NOT_EXPORTED);

        // 连接懒人听书 MediaBrowserService（Data Proxy）
        connectLazyAudio();


        // 自动歌词模式：仅在 QQ 模式下可自动开启（NCM 模式忽略）
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        boolean autoLyrics = prefs.getBoolean("autoLyrics", false);
        Log.i("Mirror", "🎚 autoLyrics 开关状态 = " + autoLyrics);
        if (autoLyrics && !isNcmMode && !isLyricsMode) {
            isLyricsMode = true;

            if (lastRemoteState != null) {
                basePosMs = lastRemoteState.getPosition();
                baseSpeed = lastRemoteState.getPlaybackSpeed();
                baseState = lastRemoteState.getState();
                baseUpdateElapsed = SystemClock.elapsedRealtime();
            }

        }
        handler.post(lyricsUpdater);
        if (remoteCtrl != null) {
            applyLyricsOverlay(lastRemoteMeta);
            mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
        }
        updateSessionActive("onCreate");
    }

    // =========================================================
    // 懒人听书 Data Proxy 连接管理
    // =========================================================
    private void connectLazyAudio() {
        if (lazyConnecting || (lazyBrowser != null && lazyBrowser.isConnected())) return;
        lazyConnecting = true;
        Log.i(TAG, "🔌 正在连接懒人听书...");

        android.content.ComponentName cn = new android.content.ComponentName(LAZY_PKG, LAZY_SVC);
        lazyBrowser = new android.support.v4.media.MediaBrowserCompat(
                this, cn,
                new android.support.v4.media.MediaBrowserCompat.ConnectionCallback() {
                    @Override public void onConnected() {
                        lazyConnected = true;
                        lazyConnecting = false;
                        Log.i(TAG, "✅ 已连接懒人听书 MediaBrowserService，root=" + lazyBrowser.getRoot());

                        // 🎯 Key: grab the current MediaSession token directly from the browser.
                        // This immediately gives us the chapter title, cover, and progress
                        // without waiting for the Sniffer notification.
                        try {
                            MediaSessionCompat.Token token = lazyBrowser.getSessionToken();
                            if (remoteCtrl != null) remoteCtrl.unregisterCallback(remoteCb);
                            remoteCtrl = new MediaControllerCompat(MyMusicService.this, token);
                            remoteCtrl.registerCallback(remoteCb);

                            // Switch to NCM (non-QQ) mode so metadata passes through cleanly
                            isNcmMode = true;

                            // Save preference so Sniffer also knows to follow Lazy Audio
                            getSharedPreferences("session_pref", MODE_PRIVATE)
                                    .edit()
                                    .putString("last_pkg", LAZY_PKG)
                                    .putString("last_label", "懒人听书")
                                    .apply();

                            // 🎯 MIRROR ONLY with Delay
                            handler.postDelayed(() -> {
                                mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
                                Log.i(TAG, "🎧 已绑定懒人听书控制器并同步状态 [Delayed Mirror]");
                            }, 500);
                        } catch (Exception e) {
                            Log.e(TAG, "❌ 绑定懒人听书控制器失败", e);
                        }

                        // Flush any pending results that arrived before the connection was ready
                        java.util.Iterator<java.util.Map.Entry<String, Result<List<MediaBrowserCompat.MediaItem>>>> it = pendingResults.entrySet().iterator();
                        while (it.hasNext()) {
                            java.util.Map.Entry<String, Result<List<MediaBrowserCompat.MediaItem>>> e = it.next();
                            if (e.getKey().startsWith("LAZY_")) {
                                onLoadChildren(e.getKey().equals("LAZY_ROOT_PENDING") ? LAZY_ROOT : e.getKey(), e.getValue());
                                it.remove();
                            }
                        }
                    }
                    @Override public void onConnectionFailed() {
                        lazyConnected = false;
                        lazyConnecting = false;
                        Log.e(TAG, "❌ 连接懒人听书失败");
                        flushPendingError("LAZY_");
                    }
                    @Override public void onConnectionSuspended() {
                        lazyConnected = false;
                        lazyConnecting = false;
                        Log.w(TAG, "⚠️ 懒人听书连接已挂起");
                    }
                }, null);
        lazyBrowser.connect();
    }

    private void connectQishuiMusic() {
        if (qishuiConnecting || (qishuiBrowser != null && qishuiBrowser.isConnected())) return;
        qishuiConnecting = true;
        Log.i(TAG, "🔌 正在连接汽水音乐...");

        android.content.ComponentName cn = new android.content.ComponentName(QISHUI_PKG, QISHUI_SVC);
        qishuiBrowser = new android.support.v4.media.MediaBrowserCompat(
                this, cn,
                new android.support.v4.media.MediaBrowserCompat.ConnectionCallback() {
                    @Override public void onConnected() {
                        qishuiConnected = true;
                        qishuiConnecting = false;
                        Log.i(TAG, "✅ 已连接汽水音乐 MediaBrowserService，root=" + qishuiBrowser.getRoot());

                        try {
                            MediaSessionCompat.Token token = qishuiBrowser.getSessionToken();
                            if (remoteCtrl != null) remoteCtrl.unregisterCallback(remoteCb);
                            remoteCtrl = new MediaControllerCompat(MyMusicService.this, token);
                            remoteCtrl.registerCallback(remoteCb);

                            isNcmMode = true;

                            getSharedPreferences("session_pref", MODE_PRIVATE)
                                    .edit()
                                    .putString("last_pkg", QISHUI_PKG)
                                    .putString("last_label", "汽水音乐")
                                    .apply();

                            // 🎯 MIRROR ONLY with Delay
                            handler.postDelayed(() -> {
                                mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
                                Log.i(TAG, "🎧 已绑定汽水音乐控制器并同步状态 [Delayed Mirror]");
                            }, 500);
                        } catch (Exception e) {
                            Log.e(TAG, "❌ 绑定汽水音乐控制器失败", e);
                        }

                        // Flush pending results for Qishui
                        java.util.Iterator<java.util.Map.Entry<String, Result<List<MediaBrowserCompat.MediaItem>>>> it = pendingResults.entrySet().iterator();
                        while (it.hasNext()) {
                            java.util.Map.Entry<String, Result<List<MediaBrowserCompat.MediaItem>>> e = it.next();
                            if (e.getKey().startsWith("QISHUI_")) {
                                onLoadChildren(e.getKey().equals("QISHUI_ROOT_PENDING") ? QISHUI_ROOT : e.getKey(), e.getValue());
                                it.remove();
                            }
                        }
                    }
                    @Override public void onConnectionFailed() {
                        qishuiConnected = false;
                        qishuiConnecting = false;
                        Log.e(TAG, "❌ 连接汽水音乐失败");
                        flushPendingError("QISHUI_");
                    }
                    @Override public void onConnectionSuspended() {
                        qishuiConnected = false;
                        qishuiConnecting = false;
                    }
                }, null);
        qishuiBrowser.connect();
    }

    private void connectQQMusic() {
        if (qqConnecting || (qqBrowser != null && qqBrowser.isConnected())) return;
        qqConnecting = true;
        Log.i(TAG, "🔌 正在通过 Direct Bridge 连接 QQ 音乐...");

        android.content.ComponentName cn = new android.content.ComponentName(QQ_PKG, QQ_SVC);
        qqBrowser = new android.support.v4.media.MediaBrowserCompat(
                this, cn,
                new android.support.v4.media.MediaBrowserCompat.ConnectionCallback() {
                    @Override public void onConnected() {
                        qqConnected = true;
                        qqConnecting = false;
                        Log.i(TAG, "✅ 已连接 QQ 音乐 MediaBrowserService");

                        try {
                            MediaSessionCompat.Token token = qqBrowser.getSessionToken();
                            if (remoteCtrl != null) remoteCtrl.unregisterCallback(remoteCb);
                            remoteCtrl = new MediaControllerCompat(MyMusicService.this, token);
                            remoteCtrl.registerCallback(remoteCb);

                            // QQ 模式使用特殊的歌词处理，所以 isNcmMode = false
                            isNcmMode = false;

                            getSharedPreferences("session_pref", MODE_PRIVATE)
                                    .edit()
                                    .putString("last_pkg", QQ_PKG)
                                    .putString("last_label", "QQ音乐")
                                    .apply();

                            // 🎯 MIRROR ONLY with Delay
                            handler.postDelayed(() -> {
                                mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
                                Log.i(TAG, "🎧 已通过 Direct Bridge 绑定 QQ 音乐控制器 [Delayed Mirror]");
                            }, 500);
                        } catch (Exception e) {
                            Log.e(TAG, "❌ 绑定 QQ 音乐控制器失败", e);
                        }
                    }
                    @Override public void onConnectionSuspended() {
                        qqConnected = false;
                        Log.w(TAG, "⚠️ QQ 音乐连接中断");
                    }
                    @Override public void onConnectionFailed() {
                        qqConnected = false;
                        qqConnecting = false;
                        Log.e(TAG, "❌ QQ 音乐连接失败 (可能是应用未安装或不支持 MediaBrowser)");
                    }
                }, null);
        qqBrowser.connect();
    }

    private void flushPendingError(String prefix) {
        java.util.Iterator<java.util.Map.Entry<String, Result<List<MediaBrowserCompat.MediaItem>>>> it = pendingResults.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, Result<List<MediaBrowserCompat.MediaItem>>> e = it.next();
            if (e.getKey().startsWith(prefix)) {
                try { e.getValue().sendResult(java.util.Collections.emptyList()); } catch (Exception ignored) {}
                it.remove();
            }
        }
    }

    /**
     * Subscribe to a Lazy Audio path via our browser and forward the results to
     * the Android Auto result object.
     */
    private void subscribeAndDeliver(String lazyParentId,
                                     Result<List<MediaBrowserCompat.MediaItem>> result) {
        // NOTE: result.detach() should have been called before calling this if async
        lazyBrowser.unsubscribe(lazyParentId); // clear any stale subscription first
        lazyBrowser.subscribe(lazyParentId,
                new android.support.v4.media.MediaBrowserCompat.SubscriptionCallback() {
                    @Override
                    public void onChildrenLoaded(String parentId,
                                                List<android.support.v4.media.MediaBrowserCompat.MediaItem> children) {
                        // Namespace every ID so we can distinguish Lazy Audio items
                        java.util.List<MediaBrowserCompat.MediaItem> proxied = new java.util.ArrayList<>();
                        for (android.support.v4.media.MediaBrowserCompat.MediaItem item : children) {
                            String namespacedId = "LAZY_" + item.getMediaId();
                            android.support.v4.media.MediaDescriptionCompat desc =
                                    new android.support.v4.media.MediaDescriptionCompat.Builder()
                                            .setMediaId(namespacedId)
                                            .setTitle(item.getDescription().getTitle())
                                            .setSubtitle(item.getDescription().getSubtitle())
                                            .setIconUri(item.getDescription().getIconUri())
                                            .setIconBitmap(item.getDescription().getIconBitmap())
                                            .build();
                            int flags = item.isBrowsable()
                                    ? MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                                    : MediaBrowserCompat.MediaItem.FLAG_PLAYABLE;
                            proxied.add(new MediaBrowserCompat.MediaItem(desc, flags));
                            Log.i(TAG, "  [" + (item.isBrowsable() ? "DIR" : "FILE") + "] "
                                    + item.getDescription().getTitle() + " id=" + item.getMediaId());
                        }
                        result.sendResult(proxied);
                        lazyBrowser.unsubscribe(parentId);
                    }
                    @Override
                    public void onError(String parentId) {
                        Log.e(TAG, "❌ 懒人听书订阅失败 parentId=" + parentId);
                        result.sendResult(Collections.emptyList());
                    }
                });
    }

    // =========================================================
    // 🧹 资源释放
    // =========================================================
    @Override
    public void onDestroy() {
        handler.removeCallbacks(lyricsUpdater);
        if (remoteCtrl != null) {
            remoteCtrl.unregisterCallback(remoteCb);
        }
        if (lazyBrowser != null && lazyBrowser.isConnected()) {
            lazyBrowser.disconnect();
        }
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.unregisterReceiver(tokenRx);
        lbm.unregisterReceiver(autoLyricsReceiver);

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

        // Handle Proxied Browsing
        if (parentId.equals(LAZY_ROOT) || parentId.startsWith("LAZY_")) {
            String lazyParentId;
            if (LAZY_ROOT.equals(parentId)) {
                if (!lazyConnected || lazyBrowser == null) {
                    if (!pendingResults.containsValue(result)) {
                        result.detach();
                        pendingResults.put("LAZY_ROOT_PENDING", result);
                    }
                    if (lazyBrowser == null || !lazyBrowser.isConnected()) connectLazyAudio();
                    return;
                }
                lazyParentId = lazyBrowser.getRoot();
            } else {
                lazyParentId = parentId.substring(5);
            }
            if (parentId.startsWith("LAZY_") || !pendingResults.containsValue(result)) {
                try { result.detach(); } catch (Exception ignored) {}
            }
            subscribeAndDeliver(lazyParentId, result);
        } else if (parentId.equals(QISHUI_ROOT) || parentId.startsWith("QISHUI_")) {
            if (!qishuiConnected || qishuiBrowser == null) {
                if (!pendingResults.containsValue(result)) {
                    result.detach();
                    pendingResults.put("QISHUI_ROOT_PENDING", result);
                }
                if (qishuiBrowser == null || !qishuiBrowser.isConnected()) connectQishuiMusic();
                return;
            }
            String qishuiParentId = QISHUI_ROOT.equals(parentId) ? qishuiBrowser.getRoot() : parentId.substring(7);
            // Ensure result is detached before async subscribe
            // But check if it was already detached (when it was pending)
            if (!pendingResults.containsValue(result)) {
                try { result.detach(); } catch (Exception ignored) {}
            }
            qishuiBrowser.subscribe(qishuiParentId, new android.support.v4.media.MediaBrowserCompat.SubscriptionCallback() {
                @Override
                public void onChildrenLoaded(@NonNull String pId, @NonNull java.util.List<android.support.v4.media.MediaBrowserCompat.MediaItem> children) {
                    java.util.List<android.support.v4.media.MediaBrowserCompat.MediaItem> proxied = new java.util.ArrayList<>();
                    for (android.support.v4.media.MediaBrowserCompat.MediaItem item : children) {
                        android.support.v4.media.MediaDescriptionCompat d = item.getDescription();
                        android.support.v4.media.MediaDescriptionCompat newD = new android.support.v4.media.MediaDescriptionCompat.Builder()
                                .setMediaId("QISHUI_" + d.getMediaId())
                                .setTitle(d.getTitle())
                                .setSubtitle(d.getSubtitle())
                                .setIconUri(d.getIconUri())
                                .build();
                        proxied.add(new android.support.v4.media.MediaBrowserCompat.MediaItem(newD, item.getFlags()));
                    }
                    result.sendResult(proxied);
                }
                @Override public void onError(@NonNull String pId) {
                    result.sendResult(java.util.Collections.emptyList());
                }
            });
        } else {
            result.sendResult(java.util.Collections.emptyList());
        }
    }

    private void performManualSwitch(String pkg, String label) {
        Log.i(TAG, "🔄 [Universal Sync] Switching to: " + label + " (" + pkg + ")");
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

        // If we already have a browser, FORCE a re-bind to sync metadata immediately
        if (LAZY_PKG.equals(pkg) && lazyBrowser != null && lazyBrowser.isConnected()) {
            bindControllerFromBrowser(lazyBrowser, pkg, label);
        } else if (QISHUI_PKG.equals(pkg) && qishuiBrowser != null && qishuiBrowser.isConnected()) {
            bindControllerFromBrowser(qishuiBrowser, pkg, label);
        } else if (QQ_PKG.equals(pkg) && qqBrowser != null && qqBrowser.isConnected()) {
            bindControllerFromBrowser(qqBrowser, pkg, label);
        } else if (LAZY_PKG.equals(pkg)) {
            connectLazyAudio();
        } else if (QISHUI_PKG.equals(pkg)) {
            // 🚀 UNIVERSAL WAKE-UP QISHUI
            try {
                // 1) Intent Wake-up
                Intent wake = new Intent("android.media.browse.MediaBrowserService");
                wake.setComponent(new android.content.ComponentName(QISHUI_PKG, "com.luna.biz.playing.player.PlayerService"));
                startService(wake);
                
                // 2) Media Button Pulse
                Intent mediaIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                mediaIntent.setPackage(QISHUI_PKG);
                mediaIntent.putExtra(Intent.EXTRA_KEY_EVENT, new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY));
                sendBroadcast(mediaIntent);
            } catch (Exception ignored) {}
            
            qishuiConnecting = false;
            connectQishuiMusic();
        } else if (QQ_PKG.equals(pkg)) {
            // 🚀 UNIVERSAL WAKE-UP QQ
            try {
                // Media Button Pulse (Works for QQ even without service name)
                Intent mediaIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                mediaIntent.setPackage(QQ_PKG);
                mediaIntent.putExtra(Intent.EXTRA_KEY_EVENT, new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY));
                sendBroadcast(mediaIntent);
            } catch (Exception ignored) {}
            
            qqConnecting = false;
            connectQQMusic();
        } else {
            // Fallback for other apps
            sendBroadcast(new Intent("com.haifeng.REQUEST_TOKEN").setPackage(getPackageName()));
        }
    }

    private void bindControllerFromBrowser(MediaBrowserCompat browser, String pkg, String label) {
        try {
            MediaSessionCompat.Token token = browser.getSessionToken();
            if (remoteCtrl != null) remoteCtrl.unregisterCallback(remoteCb);
            remoteCtrl = new MediaControllerCompat(this, token);
            remoteCtrl.registerCallback(remoteCb);

            isNcmMode = true;
            mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
            
            // 🚀 SYNC: Update car screen metadata
            mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
            Log.i(TAG, "✅ 已通过 Browser 绑定 " + label + " 控制器并同步状态 [Passive Switch]");
            
            // 📡 GLOBAL NOTIFY: Tell Sniffer and UI we switched
            sendBroadcast(new Intent("com.haifeng.ACTION_SELECTION_CHANGED")
                    .setPackage(getPackageName())
                    .putExtra("pkg", pkg)
                    .putExtra("label", label));
            sendBroadcast(new Intent("com.haifeng.REQUEST_TOKEN")
                    .setPackage(getPackageName()));
        } catch (Exception e) {
            Log.e(TAG, "❌ 绑定 " + label + " 控制器失败", e);
        }
    }
}

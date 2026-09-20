package com.haifeng.shared;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.CommandButton;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaConstants;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@UnstableApi
public class MyMusicService extends MediaLibraryService {

    private static final String TAG = "Mirror";
    private static final String CHANNEL_ID = "haifeng_mirror_channel";
    private static final int NOTIF_ID = 1001;

    private static final String CUSTOM_ACTION_SWITCH_LAZY = "ACTION_LAZY";
    private static final String CUSTOM_ACTION_SWITCH_QISHUI = "ACTION_QISHUI";
    private static final String CUSTOM_ACTION_SWITCH_QQ = "ACTION_QQ";

    private static final String LAZY_PKG = "bubei.tingshu.international";
    private static final String LAZY_SVC = "tingshu.bubei.mediasupport.service.MediaSessionBrowserService";

    private static final String QISHUI_PKG = "com.luna.music";
    private static final String QISHUI_SVC = "com.luna.biz.playing.player.PlayerService";

    private static final String QQ_PKG = "com.tencent.qqmusic";
    private static final String QQ_SVC = "com.tencent.qqmusic.MediaSessionBrowserService";

    private static final String ACTION_CONTROLLER = "com.haifeng.ACTION_CONTROLLER";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaLibrarySession mediaLibrarySession;
    private RemoteMirrorPlayer mirrorPlayer;

    private MediaControllerCompat remoteCtrl; // 指向外部播放器的控制器（QQ / 汽水 / 懒人）
    private final MediaControllerCompat.Callback remoteCb = new RemoteCallback();

    // 缓存当前镜像的元数据与状态
    private String currentTitle = "海风播放器";
    private String currentArtist = "已准备就绪";
    private long currentDurationMs = 3600000L;
    private byte[] currentArtworkData = null;
    private boolean isPlaying = false;
    private int playbackStateCode = Player.STATE_READY;
    private long currentPositionMs = 0L;
    private float playbackSpeed = 1.0f;
    private long positionUpdateTimeMs = SystemClock.elapsedRealtime();

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

        MediaBrowserCompat browser;
        boolean connected;
        boolean connecting;

        SourceConfig(String pkg, String svc, String label, String namespace,
                     String rootId, String customAction, String switchMediaId,
                     Runnable wakeUp) {
            this.pkg = pkg;
            this.svc = svc;
            this.label = label;
            this.namespace = namespace;
            this.rootId = rootId;
            this.customAction = customAction;
            this.switchMediaId = switchMediaId;
            this.wakeUp = wakeUp;
        }
    }

    private Map<String, SourceConfig> sources;
    private final ConcurrentHashMap<String, List<SettableFuture<LibraryResult<ImmutableList<MediaItem>>>>> pendingBrowseFutures =
            new ConcurrentHashMap<>();

    // =========================================================
    // 🪞 SimpleBasePlayer implementation: RemoteMirrorPlayer
    // =========================================================
    private class RemoteMirrorPlayer extends SimpleBasePlayer {

        protected RemoteMirrorPlayer(Looper looper) {
            super(looper);
        }

        @Override
        protected State getState() {
            Player.Commands commands = new Player.Commands.Builder()
                    .addAll(
                            Player.COMMAND_PLAY_PAUSE,
                            Player.COMMAND_PREPARE,
                            Player.COMMAND_STOP,
                            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                            Player.COMMAND_SEEK_TO_NEXT,
                            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                            Player.COMMAND_SEEK_TO_PREVIOUS,
                            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                            Player.COMMAND_GET_TIMELINE,
                            Player.COMMAND_GET_METADATA,
                            Player.COMMAND_SET_MEDIA_ITEM,
                            Player.COMMAND_CHANGE_MEDIA_ITEMS
                    )
                    .build();

            MediaMetadata.Builder metaBuilder = new MediaMetadata.Builder()
                    .setTitle(currentTitle)
                    .setArtist(currentArtist)
                    .setDisplayTitle(currentTitle)
                    .setSubtitle(currentArtist)
                    .setIsPlayable(true)
                    .setFolderType(MediaMetadata.FOLDER_TYPE_NONE);

            if (currentDurationMs > 0) {
                metaBuilder.setDurationMs(currentDurationMs);
            }
            if (currentArtworkData != null) {
                metaBuilder.setArtworkData(currentArtworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER);
            }
            MediaMetadata mediaMetadata = metaBuilder.build();

            MediaItem currentItem = new MediaItem.Builder()
                    .setMediaId("haifeng_mirrored_stream")
                    .setMediaMetadata(mediaMetadata)
                    .build();

            SimpleBasePlayer.MediaItemData itemData = new SimpleBasePlayer.MediaItemData.Builder(
                    "haifeng_mirrored_stream")
                    .setMediaItem(currentItem)
                    .setMediaMetadata(mediaMetadata)
                    .setDurationUs(currentDurationMs > 0 ? currentDurationMs * 1000L : 3600000L * 1000L)
                    .setIsSeekable(true)
                    .build();

            PositionSupplier posSupplier = isPlaying
                    ? PositionSupplier.getExtrapolating(currentPositionMs, playbackSpeed)
                    : PositionSupplier.getConstant(currentPositionMs);

            return new State.Builder()
                    .setAvailableCommands(commands)
                    .setPlayWhenReady(isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                    .setPlaybackState(playbackStateCode)
                    .setPlaylist(Collections.singletonList(itemData))
                    .setCurrentMediaItemIndex(0)
                    .setContentPositionMs(posSupplier)
                    .setPlaybackParameters(new PlaybackParameters(playbackSpeed))
                    .setPlaylistMetadata(mediaMetadata)
                    .build();
        }

        @Override
        protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
            Log.i(TAG, "💓 MirrorPlayer handleSetPlayWhenReady: " + playWhenReady);
            if (remoteCtrl != null) {
                if (playWhenReady) {
                    remoteCtrl.getTransportControls().play();
                } else {
                    remoteCtrl.getTransportControls().pause();
                }
            }
            isPlaying = playWhenReady;
            playbackStateCode = Player.STATE_READY;
            invalidateState();
            return Futures.immediateVoidFuture();
        }

        @Override
        protected ListenableFuture<?> handlePrepare() {
            Log.i(TAG, "💓 MirrorPlayer handlePrepare");
            if (remoteCtrl != null) {
                remoteCtrl.getTransportControls().prepare();
            }
            return Futures.immediateVoidFuture();
        }

        @Override
        protected ListenableFuture<?> handleStop() {
            Log.i(TAG, "💓 MirrorPlayer handleStop");
            if (remoteCtrl != null) {
                remoteCtrl.getTransportControls().stop();
            }
            isPlaying = false;
            playbackStateCode = Player.STATE_IDLE;
            invalidateState();
            return Futures.immediateVoidFuture();
        }

        @Override
        protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
            Log.i(TAG, "💓 MirrorPlayer handleSeek to " + positionMs + " ms");
            if (remoteCtrl != null) {
                remoteCtrl.getTransportControls().seekTo(positionMs);
                handler.postDelayed(() -> {
                    if (remoteCtrl != null) {
                        mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
                    }
                }, 250);
            }
            currentPositionMs = positionMs;
            invalidateState();
            updateSessionActive("handleSeek");
            return Futures.immediateVoidFuture();
        }

        @Override
        protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
            if (mediaItems != null && !mediaItems.isEmpty()) {
                MediaItem first = mediaItems.get(0);
                if (first.mediaId != null) {
                    onMediaIdSelected(first.mediaId, null);
                }
            }
            return Futures.immediateVoidFuture();
        }

        public void notifyStateChanged() {
            invalidateState();
        }
    }

    // =========================================================
    // 🔔 Notification Management
    // =========================================================
    private void updateForegroundNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lyrics_24dp)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentTitle(currentTitle)
                .setContentText(currentArtist);

        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    private void updateSessionActive(String debugTag) {
        if (mediaLibrarySession != null) {
            Bundle extras = new Bundle();
            extras.putLong("haifeng.refresh_token", System.currentTimeMillis());
            mediaLibrarySession.setSessionExtras(extras);
            Log.i(TAG, "🟢 Session JOLT [" + debugTag + "]");
        }
    }

    // =========================================================
    // 🔁 外部播放器回调：将元数据 / 播放状态同步给 Media3 Player
    // =========================================================
    private class RemoteCallback extends MediaControllerCompat.Callback {
        @Override
        public void onMetadataChanged(MediaMetadataCompat m) {
            mirrorMetadata(m, remoteCtrl == null ? null : remoteCtrl.getPlaybackState());
        }

        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
            mirror(remoteCtrl == null ? null : remoteCtrl.getMetadata(), state);
        }
    }

    private final Runnable progressHeartbeat = new Runnable() {
        @Override
        public void run() {
            if (remoteCtrl != null) {
                mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
            }
        }
    };

    private void mirror(MediaMetadataCompat meta, PlaybackStateCompat st) {
        mirrorMetadata(meta, st);
        mirrorPlaybackState(st);
    }

    private void mirrorMetadata(MediaMetadataCompat meta, PlaybackStateCompat st) {
        if (meta != null) {
            String title = meta.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
            String artist = meta.getString(MediaMetadataCompat.METADATA_KEY_ARTIST);
            long duration = meta.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);

            boolean songChanged = !Objects.equals(title, currentTitle) || !Objects.equals(artist, currentArtist);

            currentTitle = (title != null && !title.trim().isEmpty()) ? title : "海风播放器";
            currentArtist = (artist != null && !artist.trim().isEmpty()) ? artist : "已准备就绪";

            if (duration <= 0 && st != null && st.getExtras() != null) {
                duration = st.getExtras().getLong("android.media.metadata.DURATION");
            }
            currentDurationMs = duration > 0 ? duration : 3600000L;

            Bitmap art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
            if (art == null) {
                art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON);
            }
            if (art == null) {
                art = meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ART);
            }

            if (art != null) {
                try {
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    art.compress(Bitmap.CompressFormat.PNG, 100, stream);
                    currentArtworkData = stream.toByteArray();
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to compress artwork bitmap", t);
                    currentArtworkData = null;
                }
            }

            if (mirrorPlayer != null) {
                mirrorPlayer.notifyStateChanged();
            }

            if (songChanged) {
                updateForegroundNotification();
                updateSessionActive("mirror_song_changed");
            }
        } else {
            SharedPreferences sp = getSharedPreferences("session_pref", MODE_PRIVATE);
            String label = sp.getString("last_label", "海风播放器");
            currentTitle = label;
            currentArtist = "已准备就绪";
            currentArtworkData = null;
            if (mirrorPlayer != null) {
                mirrorPlayer.notifyStateChanged();
            }
            Log.i(TAG, "ℹ️ Mirror: 远端无元数据，使用占位符 [" + label + "]");
        }
    }

    private void mirrorPlaybackState(PlaybackStateCompat st) {
        if (st != null) {
            int code = st.getState();
            if (code == PlaybackStateCompat.STATE_NONE || code == PlaybackStateCompat.STATE_STOPPED) {
                code = PlaybackStateCompat.STATE_PAUSED;
            }

            float speed = st.getPlaybackSpeed();
            if (speed == 0f) {
                speed = 1.0f;
            }
            playbackSpeed = speed;

            currentPositionMs = st.getPosition();
            positionUpdateTimeMs = SystemClock.elapsedRealtime();

            switch (code) {
                case PlaybackStateCompat.STATE_PLAYING:
                    isPlaying = true;
                    playbackStateCode = Player.STATE_READY;
                    break;
                case PlaybackStateCompat.STATE_BUFFERING:
                case PlaybackStateCompat.STATE_CONNECTING:
                    isPlaying = true;
                    playbackStateCode = Player.STATE_BUFFERING;
                    break;
                case PlaybackStateCompat.STATE_PAUSED:
                default:
                    isPlaying = false;
                    playbackStateCode = Player.STATE_READY;
                    break;
            }

            if (mirrorPlayer != null) {
                mirrorPlayer.notifyStateChanged();
            }

            handler.removeCallbacks(progressHeartbeat);
            if (code == PlaybackStateCompat.STATE_PLAYING) {
                handler.postDelayed(progressHeartbeat, 1000);
            }
        } else {
            isPlaying = false;
            playbackStateCode = Player.STATE_READY;
            currentPositionMs = 0L;
            if (mirrorPlayer != null) {
                mirrorPlayer.notifyStateChanged();
            }
            Log.i(TAG, "ℹ️ Mirror: 远端无播放状态，强制 PAUSED");
        }
    }

    // =========================================================
    // 📡 接收 QQ / 汽水 / 懒人 的 Token 并构建 Controller
    // =========================================================
    private final BroadcastReceiver tokenRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Log.i("Mirror", "🎯 [tokenRx] Received Broadcast: " + i.getAction());
            if (!ACTION_CONTROLLER.equals(i.getAction()))
                return;

            String sourcePkg = i.getStringExtra("pkg");
            Log.i("Mirror", "🎯 [tokenRx] Source Package: " + sourcePkg);
            if (sourcePkg == null) {
                Log.w("Mirror", "⚠️ 收到控制广播但缺少 pkg");
                return;
            }

            SharedPreferences sp = getSharedPreferences("session_pref", MODE_PRIVATE);
            String chosenPkg = sp.getString("last_pkg", null);
            Log.i("Mirror", "🎯 [tokenRx] Chosen Package in Prefs: " + chosenPkg);
            if (chosenPkg == null || !chosenPkg.equals(sourcePkg)) {
                Log.i("Mirror", "ℹ️ 忽略不同来源广播，当前选择=" + chosenPkg + "，广播来自=" + sourcePkg);
                return;
            }

            MediaSessionCompat.Token tk = i.getParcelableExtra("binder");
            if (tk == null) {
                Log.i("Mirror", "ℹ️ 收到空 Token，尝试通过 Browser 主动连接来源=" + sourcePkg);
                SourceConfig nullSrc = sources.get(sourcePkg);
                if (nullSrc != null) connectSource(nullSrc);
                mirror(null, null);
                return;
            }

            boolean isNewToken = (remoteCtrl == null || !remoteCtrl.getSessionToken().equals(tk));
            if (isNewToken) {
                updateSessionActive("sourceChanged:" + sourcePkg);
            }

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

                mirror(remoteCtrl.getMetadata(), remoteCtrl.getPlaybackState());
                Log.i("Mirror", "✅ 绑定远端控制器 [Sniffer Path], pkg=" + sourcePkg);

                updateSessionActive("snifferUpdate");
            } catch (Exception e) {
                Log.e("Mirror", "❌ 绑定远端控制器失败 [Sniffer Path]", e);
            }
        }
    };

    // =========================================================
    // 🚀 生命周期管理: onCreate / onDestroy / onGetSession
    // =========================================================
    @Override
    public void onCreate() {
        super.onCreate();

        sources = new LinkedHashMap<>();
        sources.put(LAZY_PKG, new SourceConfig(
                LAZY_PKG, LAZY_SVC, "懒人听书", "LAZY_", "LAZY_ROOT",
                CUSTOM_ACTION_SWITCH_LAZY, "SWITCH_LAZY", null));
        sources.put(QISHUI_PKG, new SourceConfig(
                QISHUI_PKG, QISHUI_SVC, "汽水音乐", "QISHUI_", "QISHUI_ROOT",
                CUSTOM_ACTION_SWITCH_QISHUI, "SWITCH_QISHUI", () -> {
            try {
                Intent wake = new Intent("android.media.browse.MediaBrowserService");
                wake.setComponent(new ComponentName(QISHUI_PKG, QISHUI_SVC));
                startService(wake);
                Intent pulse = new Intent(Intent.ACTION_MEDIA_BUTTON);
                pulse.setPackage(QISHUI_PKG);
                pulse.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
                sendBroadcast(pulse);
            } catch (Exception ignored) {}
        }));
        sources.put(QQ_PKG, new SourceConfig(
                QQ_PKG, QQ_SVC, "QQ音乐", "QQ_", null,
                CUSTOM_ACTION_SWITCH_QQ, "SWITCH_QQ", () -> {
            try {
                Intent wake = new Intent("android.media.browse.MediaBrowserService");
                wake.setComponent(new ComponentName(QQ_PKG, QQ_SVC));
                startService(wake);
                Intent pulse = new Intent(Intent.ACTION_MEDIA_BUTTON);
                pulse.setPackage(QQ_PKG);
                pulse.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
                sendBroadcast(pulse);
            } catch (Exception ignored) {}
        }));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "海风播放器 · 车机同步",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        mirrorPlayer = new RemoteMirrorPlayer(Looper.getMainLooper());

        mediaLibrarySession = new MediaLibrarySession.Builder(this, mirrorPlayer, new LibrarySessionCallback())
                .setId("MirrorSession")
                .build();

        updateForegroundNotification();

        registerReceiver(tokenRx, new IntentFilter(ACTION_CONTROLLER), Context.RECEIVER_NOT_EXPORTED);
        updateSessionActive("onCreate");
    }

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return mediaLibrarySession;
    }

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

        if (mediaLibrarySession != null) {
            mediaLibrarySession.release();
            mediaLibrarySession = null;
        }

        if (mirrorPlayer != null) {
            mirrorPlayer.release();
            mirrorPlayer = null;
        }

        super.onDestroy();
    }

    // =========================================================
    // 🚪 MediaLibrarySession.Callback
    // =========================================================
    private class LibrarySessionCallback implements MediaLibrarySession.Callback {

        @NonNull
        @Override
        public MediaSession.ConnectionResult onConnect(@NonNull MediaSession session,
                                                      @NonNull MediaSession.ControllerInfo controller) {
            SessionCommands.Builder sessionCmdsBuilder = new SessionCommands.Builder()
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_SUBSCRIBE)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_UNSUBSCRIBE)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT)
                    .add(new SessionCommand(CUSTOM_ACTION_SWITCH_LAZY, Bundle.EMPTY))
                    .add(new SessionCommand(CUSTOM_ACTION_SWITCH_QISHUI, Bundle.EMPTY))
                    .add(new SessionCommand(CUSTOM_ACTION_SWITCH_QQ, Bundle.EMPTY));

            Player.Commands.Builder playerCmdsBuilder = new Player.Commands.Builder()
                    .addAll(
                            Player.COMMAND_PLAY_PAUSE,
                            Player.COMMAND_PREPARE,
                            Player.COMMAND_STOP,
                            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                            Player.COMMAND_SEEK_TO_PREVIOUS,
                            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                            Player.COMMAND_SEEK_TO_NEXT,
                            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                            Player.COMMAND_SEEK_BACK,
                            Player.COMMAND_SEEK_FORWARD,
                            Player.COMMAND_SET_SPEED_AND_PITCH,
                            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                            Player.COMMAND_GET_TIMELINE,
                            Player.COMMAND_GET_METADATA,
                            Player.COMMAND_SET_MEDIA_ITEM,
                            Player.COMMAND_CHANGE_MEDIA_ITEMS
                    );

            return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCmdsBuilder.build())
                    .setAvailablePlayerCommands(playerCmdsBuilder.build())
                    .build();
        }

        @NonNull
        @Override
        public ListenableFuture<SessionResult> onCustomCommand(@NonNull MediaSession session,
                                                             @NonNull MediaSession.ControllerInfo controller,
                                                             @NonNull SessionCommand customCommand,
                                                             @NonNull Bundle args) {
            Log.i(TAG, "🎯>>> RECEIVED CUSTOM ACTION: [" + customCommand.customAction + "]");
            for (SourceConfig s : sources.values()) {
                if (s.customAction.equals(customCommand.customAction)) {
                    performManualSwitch(s.pkg, s.label);
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                }
            }
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED));
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
                @NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo controller,
                @Nullable LibraryParams params) {
            Bundle extras = new Bundle();
            extras.putBoolean(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, true);
            extras.putBoolean(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, true);

            MediaItem rootItem = new MediaItem.Builder()
                    .setMediaId("root")
                    .setMediaMetadata(new MediaMetadata.Builder()
                            .setTitle("root")
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                            .setExtras(extras)
                            .build())
                    .build();

            LibraryParams libraryParams = new LibraryParams.Builder()
                    .setExtras(extras)
                    .build();

            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, libraryParams));
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(
                @NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo controller,
                @NonNull String parentId,
                int page,
                int pageSize,
                @Nullable LibraryParams params) {

            // 🎯 ROOT LEVEL
            if ("root".equals(parentId)) {
                MediaItem cmdFolder = new MediaItem.Builder()
                        .setMediaId("FOLDER_SWITCH")
                        .setMediaMetadata(new MediaMetadata.Builder()
                                .setTitle("🔄 切换播放源")
                                .setSubtitle("QQ / 懒人 / 汽水")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                                .setArtworkUri(Uri.parse("android.resource://" + getPackageName() + "/" + R.drawable.ic_qishui_vec))
                                .build())
                        .build();

                return Futures.immediateFuture(LibraryResult.ofItemList(
                        ImmutableList.of(cmdFolder), params));
            }

            // 🎯 CONTENT OF THE SWITCH FOLDER
            if ("FOLDER_SWITCH".equals(parentId)) {
                MediaItem qq = new MediaItem.Builder()
                        .setMediaId("SWITCH_QQ")
                        .setMediaMetadata(new MediaMetadata.Builder()
                                .setTitle("QQ音乐")
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
                                .setArtworkUri(Uri.parse("android.resource://" + getPackageName() + "/" + R.drawable.ic_qq_vec))
                                .build())
                        .build();

                MediaItem lazy = new MediaItem.Builder()
                        .setMediaId("SWITCH_LAZY")
                        .setMediaMetadata(new MediaMetadata.Builder()
                                .setTitle("懒人听书")
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
                                .setArtworkUri(Uri.parse("android.resource://" + getPackageName() + "/" + R.drawable.ic_lazy_vec))
                                .build())
                        .build();

                MediaItem qishui = new MediaItem.Builder()
                        .setMediaId("SWITCH_QISHUI")
                        .setMediaMetadata(new MediaMetadata.Builder()
                                .setTitle("汽水音乐")
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
                                .setArtworkUri(Uri.parse("android.resource://" + getPackageName() + "/" + R.drawable.ic_qishui_vec))
                                .build())
                        .build();

                return Futures.immediateFuture(LibraryResult.ofItemList(
                        ImmutableList.of(qq, lazy, qishui), params));
            }

            // 🎯 HANDLE SWITCH COMMAND FROM BROWSER
            if ("SWITCH_QQ".equals(parentId)) {
                performManualSwitch(QQ_PKG, "QQ音乐");
                MediaItem feedback = new MediaItem.Builder()
                        .setMediaId("SWITCH_DONE")
                        .setMediaMetadata(new MediaMetadata.Builder()
                                .setTitle("✅ 已切换至 QQ音乐")
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
                                .build())
                        .build();
                return Futures.immediateFuture(LibraryResult.ofItemList(
                        ImmutableList.of(feedback), params));
            }

            // 🎯 Handle Proxied Browsing: look up source by rootId or namespace prefix
            SourceConfig browseSource = null;
            for (SourceConfig s : sources.values()) {
                if (s.rootId != null && (parentId.equals(s.rootId) || parentId.startsWith(s.namespace))) {
                    browseSource = s;
                    break;
                }
            }

            if (browseSource != null) {
                final SourceConfig src = browseSource;
                SettableFuture<LibraryResult<ImmutableList<MediaItem>>> future = SettableFuture.create();

                String realParentId;
                if (parentId.equals(src.rootId)) {
                    if (!src.connected || src.browser == null) {
                        String pendingKey = src.namespace + "ROOT_PENDING";
                        registerPendingBrowseFuture(pendingKey, future);
                        if (src.browser == null || !src.browser.isConnected()) {
                            connectSource(src);
                        }
                        return future;
                    }
                    realParentId = src.browser.getRoot();
                } else {
                    realParentId = parentId.substring(src.namespace.length());
                }

                subscribeAndDeliverFuture(src.browser, realParentId, src.namespace, future, params);
                return future;
            }

            return Futures.immediateFuture(LibraryResult.ofItemList(
                    ImmutableList.of(), params));
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
                @NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo controller,
                @NonNull String mediaId) {
            if ("root".equals(mediaId)) {
                return onGetLibraryRoot(session, controller, null);
            }
            if ("FOLDER_SWITCH".equals(mediaId)) {
                MediaItem cmdFolder = new MediaItem.Builder()
                        .setMediaId("FOLDER_SWITCH")
                        .setMediaMetadata(new MediaMetadata.Builder()
                                .setTitle("🔄 切换播放源")
                                .setSubtitle("QQ / 懒人 / 汽水")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                                .setArtworkUri(Uri.parse("android.resource://" + getPackageName() + "/" + R.drawable.ic_qishui_vec))
                                .build())
                        .build();
                return Futures.immediateFuture(LibraryResult.ofItem(cmdFolder, null));
            }
            return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE));
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<Void>> onSubscribe(
                @NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo controller,
                @NonNull String parentId,
                @Nullable LibraryParams params) {
            return Futures.immediateFuture(LibraryResult.ofVoid());
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<Void>> onUnsubscribe(
                @NonNull MediaLibrarySession session,
                @NonNull MediaSession.ControllerInfo controller,
                @NonNull String parentId) {
            return Futures.immediateFuture(LibraryResult.ofVoid());
        }
    }

    // =========================================================
    // 🔌 连接远端第三方音乐源
    // =========================================================
    private void registerPendingBrowseFuture(String key, SettableFuture<LibraryResult<ImmutableList<MediaItem>>> future) {
        pendingBrowseFutures.computeIfAbsent(key, k -> new ArrayList<>()).add(future);
    }

    private void connectSource(SourceConfig src) {
        if (src.connecting || (src.browser != null && src.browser.isConnected())) {
            return;
        }

        ComponentName cn = new ComponentName(src.pkg, src.svc);
        if (!isServiceDeclared(cn)) {
            Log.e(TAG, "❌ 来源服务不存在或不可见: " + cn.flattenToShortString());
            flushPendingFuturesError(src.namespace);
            sendBroadcast(new Intent("com.haifeng.REQUEST_TOKEN").setPackage(getPackageName()));
            return;
        }

        src.connecting = true;
        Log.i(TAG, "🔌 正在连接来源... pkg=" + src.pkg + " label=" + src.label);

        MediaBrowserCompat browser = new MediaBrowserCompat(
                this, cn,
                new MediaBrowserCompat.ConnectionCallback() {
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

                        // Resolve pending futures
                        String rootPendingKey = src.namespace + "ROOT_PENDING";
                        List<SettableFuture<LibraryResult<ImmutableList<MediaItem>>>> futures =
                                pendingBrowseFutures.remove(rootPendingKey);
                        if (futures != null) {
                            for (SettableFuture<LibraryResult<ImmutableList<MediaItem>>> f : futures) {
                                subscribeAndDeliverFuture(src.browser, src.browser.getRoot(), src.namespace, f, null);
                            }
                        }
                    }

                    @Override
                    public void onConnectionFailed() {
                        src.connected = false;
                        src.connecting = false;
                        Log.e(TAG, "❌ 连接来源失败, pkg=" + src.pkg);
                        flushPendingFuturesError(src.namespace);

                        if (src.wakeUp != null) {
                            try {
                                src.wakeUp.run();
                            } catch (Exception ignored) {
                            }
                        }
                        handler.postDelayed(
                                () -> sendBroadcast(new Intent("com.haifeng.REQUEST_TOKEN").setPackage(getPackageName())),
                                300);
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

    private boolean isServiceDeclared(ComponentName component) {
        try {
            getPackageManager().getServiceInfo(component, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "⚠️ 服务声明预检异常: " + component.flattenToShortString(), t);
            return true;
        }
    }

    private void flushPendingFuturesError(String prefix) {
        for (Map.Entry<String, List<SettableFuture<LibraryResult<ImmutableList<MediaItem>>>>> entry :
                pendingBrowseFutures.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                List<SettableFuture<LibraryResult<ImmutableList<MediaItem>>>> list = entry.getValue();
                if (list != null) {
                    for (SettableFuture<LibraryResult<ImmutableList<MediaItem>>> f : list) {
                        f.set(LibraryResult.ofItemList(ImmutableList.of(), null));
                    }
                }
                pendingBrowseFutures.remove(entry.getKey());
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

    private void subscribeAndDeliverFuture(MediaBrowserCompat browser,
                                          String parentId,
                                          String namespace,
                                          SettableFuture<LibraryResult<ImmutableList<MediaItem>>> future,
                                          @Nullable LibraryParams params) {
        browser.unsubscribe(parentId);
        browser.subscribe(parentId,
                new MediaBrowserCompat.SubscriptionCallback() {
                    @Override
                    public void onChildrenLoaded(String loadedParentId,
                                                List<MediaBrowserCompat.MediaItem> children) {
                        ImmutableList.Builder<MediaItem> proxiedBuilder = ImmutableList.builder();
                        for (MediaBrowserCompat.MediaItem item : children) {
                            String namespacedId = namespace + item.getMediaId();
                            MediaDescriptionCompat desc = item.getDescription();

                            MediaMetadata.Builder metaB = new MediaMetadata.Builder()
                                    .setTitle(desc.getTitle())
                                    .setSubtitle(desc.getSubtitle())
                                    .setIsBrowsable(item.isBrowsable())
                                    .setIsPlayable(item.isPlayable())
                                    .setFolderType(item.isBrowsable()
                                            ? MediaMetadata.FOLDER_TYPE_MIXED
                                            : MediaMetadata.FOLDER_TYPE_NONE);

                            if (desc.getIconUri() != null) {
                                metaB.setArtworkUri(desc.getIconUri());
                            } else if (desc.getIconBitmap() != null) {
                                try {
                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                    desc.getIconBitmap().compress(Bitmap.CompressFormat.PNG, 100, baos);
                                    metaB.setArtworkData(baos.toByteArray(), MediaMetadata.PICTURE_TYPE_FRONT_COVER);
                                } catch (Throwable ignored) {}
                            }

                            MediaItem mItem = new MediaItem.Builder()
                                    .setMediaId(namespacedId)
                                    .setMediaMetadata(metaB.build())
                                    .build();
                            proxiedBuilder.add(mItem);
                            Log.i(TAG, "  [" + (item.isBrowsable() ? "DIR" : "FILE") + "] "
                                    + desc.getTitle() + " id=" + item.getMediaId());
                        }

                        future.set(LibraryResult.ofItemList(proxiedBuilder.build(), params));
                        browser.unsubscribe(loadedParentId);
                    }

                    @Override
                    public void onError(String loadedParentId) {
                        Log.e(TAG, "❌ 订阅失败 parentId=" + loadedParentId + " namespace=" + namespace);
                        future.set(LibraryResult.ofItemList(ImmutableList.of(), params));
                    }
                });
    }

    private void onMediaIdSelected(String mediaId, @Nullable Bundle extras) {
        Log.i(TAG, "🎯 onMediaIdSelected: " + mediaId);

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

    private void performManualSwitch(String pkg, String label) {
        Log.i(TAG, "🔄 [Universal Sync] Switching to: " + label + " (" + pkg + ")");
        pauseCurrentSourceBeforeSwitch(pkg);
        getSharedPreferences("session_pref", MODE_PRIVATE)
                .edit()
                .putString("last_pkg", pkg)
                .putString("last_label", label)
                .commit();

        sendBroadcast(new Intent("com.haifeng.ACTION_SELECTION_CHANGED")
                .setPackage(getPackageName())
                .putExtra("pkg", pkg)
                .putExtra("label", label));

        Log.i(TAG, "🌪️ [DEEP REFRESH] Clearing car screen for: " + label);
        currentTitle = "正在切换至: " + label;
        currentArtist = "海风播放器 · 魔法桥接中...";
        currentArtworkData = null;
        isPlaying = true;
        playbackStateCode = Player.STATE_BUFFERING;

        if (mirrorPlayer != null) {
            mirrorPlayer.notifyStateChanged();
        }

        if (mediaLibrarySession != null) {
            mediaLibrarySession.notifyChildrenChanged("FOLDER_SWITCH", 3, null);
        }

        updateSessionActive("manual_switch");

        SourceConfig src = sources.get(pkg);
        if (src != null) {
            if (src.wakeUp != null) src.wakeUp.run();
            src.connecting = false;
            connectSource(src);
        }
        sendBroadcast(new Intent("com.haifeng.REQUEST_TOKEN").setPackage(getPackageName()));
    }
}

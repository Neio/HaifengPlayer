# AGENTS.md - Haifeng Player (海风播放器)

Guidelines and repository context for AI coding assistants and autonomous agents working on Haifeng Player.

---

## 1. Project Overview & Architecture

**Haifeng Player (海风播放器)** is an Android & Android Auto media bridge application designed to connect third-party Chinese media/audiobook services (QQ Music, Qishui Music, Lazy Audio) to in-car head units via Android Auto and native Android Automotive OS.

### Multi-Module Structure
- [`:mobile`](file:///Users/neio/source/HaifengPlayer/mobile): Phone-side user interface and session sniffer.
  - [`MainActivity`](file:///Users/neio/source/HaifengPlayer/mobile/src/main/java/com/haifeng/MainActivity.java): UI controls, source switcher sheet, permissions handling.
  - [`MusicSessionSniffer`](file:///Users/neio/source/HaifengPlayer/mobile/src/main/java/com/haifeng/MusicSessionSniffer.java): `NotificationListenerService` that sniffs active playback tokens/sessions across running music apps on the device.
  - [`SessionRepo`](file:///Users/neio/source/HaifengPlayer/mobile/src/main/java/com/haifeng/SessionRepo.java), [`SessionPickerSheet`](file:///Users/neio/source/HaifengPlayer/mobile/src/main/java/com/haifeng/SessionPickerSheet.java): Manages detected media sessions and displays selection bottom sheet.
- [`:shared`](file:///Users/neio/source/HaifengPlayer/shared): Core media bridge logic shared between mobile and automotive.
  - [`MyMusicService`](file:///Users/neio/source/HaifengPlayer/shared/src/main/java/com/haifeng/shared/MyMusicService.java): `MediaBrowserServiceCompat` implementation that acts as an Android Auto media host. Proxies playback commands (`play`, `pause`, `skipToNext`, `seekTo`, etc.), metadata, and lyrics to/from external target media apps.
- [`:automotive`](file:///Users/neio/source/HaifengPlayer/automotive): Android Automotive OS module for standalone head-unit deployment.

---

## 2. Environment & Toolchain Requirements

- **JDK Version**: OpenJDK 17 required.
- **Android Target**:
  - `minSdk`: 33 (mobile), 28 (shared)
  - `compileSdk`: 36, `targetSdk`: 36
- **Gradle**: Gradle Wrapper (`./gradlew`) with AGP 8.11.1.

### Environment Setup

Always ensure JDK 17 is active in shell sessions:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH=$JAVA_HOME/bin:$PATH
```

---

## 3. Common Development Commands

### Building & Installing
```bash
# Set JDK 17
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH=$JAVA_HOME/bin:$PATH

# Build debug APK
./gradlew :mobile:assembleDebug

# Install debug APK to connected device
./gradlew :mobile:installDebug

# Run unit tests
./gradlew test
```

### Logcat & Debugging
```bash
# Stream filtered logs for Haifeng Player
adb logcat --pid=$(adb shell pidof -s com.haifeng) -v time

# Filter by tags used in the app
adb logcat -s Mirror Sniffer
```

---

## 4. Key Conventions & Implementation Notes

### Media Integration Bridge
- **Target Apps**:
  - QQ Music: `com.tencent.qqmusic` (`com.tencent.qqmusic.MediaSessionBrowserService`)
  - Qishui Music (汽水音乐): `com.luna.music` (`com.luna.biz.playing.player.PlayerService`)
  - Lazy Audio (懒人听书): `bubei.tingshu.international` (`tingshu.bubei.mediasupport.service.MediaSessionBrowserService`)
- **Permissions**:
  - Requires `NotificationListenerService` (`android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`) to sniff active media controllers via `MediaSessionManager`.
  - Android Auto connects to [`MyMusicService`](file:///Users/neio/source/HaifengPlayer/shared/src/main/java/com/haifeng/shared/MyMusicService.java) exposed with action `android.media.browse.MediaBrowserService`.

### Code Style & Guidelines
- Maintain Java / Kotlin interoperability (shared module is Java 17; UI utilities in mobile include Kotlin components like [`MusicSlider.kt`](file:///Users/neio/source/HaifengPlayer/mobile/src/main/java/com/haifeng/MusicSlider.kt) and [`SquigglyProgress.kt`](file:///Users/neio/source/HaifengPlayer/mobile/src/main/java/com/haifeng/SquigglyProgress.kt)).
- Preserve existing comments and docstrings.
- Always check that any modifications in [`MyMusicService`](file:///Users/neio/source/HaifengPlayer/shared/src/main/java/com/haifeng/shared/MyMusicService.java) correctly notify `remoteCtrl` and update `mSession.setPlaybackState()` and `mSession.setMetadata()`.

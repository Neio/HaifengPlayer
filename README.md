# 🌊 Haifeng Player (海风播放器)

Haifeng Player is a premium, minimalist music bridge for Android Auto, designed to bring your favorite music and audiobook apps to your car's dashboard with style and precision.

![Haifeng Player Icon](mobile/src/main/res/drawable/ic_launcher_new.png)

## ✨ Features

- **Full Lazy Audio (懒人听书) Integration**: Browse your bookshelf and recent books directly from the Android Auto interface.
- **Data Proxy Technology**: Seamlessly sync chapter progress and metadata between your phone and car.
- **Smart Mirroring**: Supports QQ Music, NetEase Cloud Music, and more via high-fidelity session sniffer technology.
- **Futuristic UI**: Modern, high-contrast design optimized for automotive safety and aesthetics.
- **Custom Actions**: Toggle features like auto-lyrics or app switching directly from the steering wheel or head unit.

## 🚀 Getting Started

1. **Install**: Deploy the app to your Android device.
2. **Permissions**: Grant "Notification Listener" permission when prompted.
3. **Connect**: Plug into your car's USB port or connect via Wireless Android Auto.
4. **Enjoy**: Your audiobooks and music will appear automatically in the media list.

## 🏗️ Architecture
Haifeng Player utilizes a dual-path integration strategy to ensure maximum compatibility:

- **Direct Bridge**: For apps like **Lazy Audio (懒人听书)** and **Qishui Music**, we connect directly to their `MediaBrowserService`. This allows full browsing of your library and bookshelves from the car screen.
- **Session Sniffing**: For apps like **QQ Music** and **NetEase Cloud Music**, we use a `NotificationListenerService` to capture the active `MediaSession` token. This provides universal control even if the app doesn't officially support Android Auto browsing.
- **QQ Mode**: A specialized engine that parses LRC lyrics from metadata and overlays them onto the car's title/artist fields for synchronized display.

## 🛠️ Build Requirements
To build this project from source, ensure you have:
- **JDK 17**: This project requires OpenJDK 17 (recommended: `brew install openjdk@17`).
- **Android SDK**: API Level 33+ components.

```bash
# Example build command
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew assembleDebug
```

---

*Haifeng Player - Smooth as the ocean breeze.*

> [!NOTE]
> This project is a specialized fork and rebrand of the original [NuomiPlayer](https://github.com/charlottejas/NuomiPlayer).

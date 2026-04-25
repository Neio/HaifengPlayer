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

## 🛠️ Development

This project uses a hybrid architecture combining a `MediaBrowserService` proxy with a `NotificationListener` session sniffer.

- **Mobile Module**: Phone-side UI and session monitoring.
- **Automotive Module**: Car-side manifest and automotive configurations.
- **Shared Module**: Core logic for MediaSession mirroring and third-party app bridges.

---

*Haifeng Player - Smooth as the ocean breeze.*

> [!NOTE]
> This project is a specialized fork and rebrand of the original [NuomiPlayer](https://github.com/charlottejas/NuomiPlayer).

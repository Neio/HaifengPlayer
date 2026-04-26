# 🌊 Haifeng Player (海风播放器)

Haifeng Player is a premium, minimalist music bridge for Android Auto, designed to bring your favorite music and audiobook apps to your car's dashboard with style and precision.

![Haifeng Player Icon](mobile/src/main/res/drawable/ic_launcher_new.png)

## ✨ Features

- **Direct Integration**: Specialized bridges for **QQ Music (QQ音乐)**, **Lazy Audio (懒人听书)**, and **Qishui Music (汽水音乐)**.
- **Smart Mirroring**: Supports a wide range of music apps via high-fidelity session sniffer technology.
- **Synchronized Lyrics**: Advanced engine to display real-time lyrics on your car's dashboard (optimized for QQ Music).
- **In-Car Switching**: Change music sources directly from the Android Auto menu or head unit buttons.

## 🚀 Getting Started

1. **Install**: Deploy the app to your Android device.
2. **Permissions**: Grant "Notification Listener" permission when prompted.
3. **Connect**: Plug into your car's USB port or connect via Wireless Android Auto.
4. **Enjoy**: Your audiobooks and music will appear automatically in the media list.

## 🛠️ Build Requirements
To build this project from source, ensure you have:
- **JDK 17**: This project requires OpenJDK 17 (recommended: `brew install openjdk@17`).
- **Android SDK**: API Level 33+ components.

```bash
# Example build command
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew assembleDebug
```

## ⚖️ License

This project is licensed under the **MIT License**. See the [LICENSE](LICENSE) file for details.

## 📦 Third-Party Compliance & Integrations

Haifeng Player is designed to interface with third-party audio applications. 

### Open Source Dependencies
All major dependencies (AndroidX, Material Components, Kotlin) are licensed under the **Apache License 2.0** or **EPL 1.0**. A full list can be found in the [NOTICE](NOTICE) file.



---

*Haifeng Player - Smooth as the ocean breeze.*

> [!NOTE]
> This project is a specialized fork and rebrand of the original [NuomiPlayer](https://github.com/charlottejas/NuomiPlayer).

### ⚠️ Trademark Disclaimer
All product names, logos, and brands mentioned in this project (including **QQ Music**, **Lazy Audio**, and **Qishui Music**) are property of their respective owners. All company, product, and service names used in this website/app are for identification purposes only. Use of these names, logos, and brands does not imply endorsement or affiliation.

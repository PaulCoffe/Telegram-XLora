# ARCHITECTURE

## System Foundation
- **Base Project**: forkgram/TelegramAndroid (Official Forkgram).
- **Language**: Java / C++.
- **Platform**: Android.

## CI/CD Pipeline (GitHub Actions)
- **Goal**: Automated Assembly of the Release APK.
- **Environment**: Ubuntu Latest, JDK 21, NDK r21e.
- **Native Build**: FFmpeg, VPX, BoringSSL within `TMessagesProj/jni`.
- **Secrets Management**:
  - `API_ID`: GitHub Secret.
  - `API_HASH`: GitHub Secret.

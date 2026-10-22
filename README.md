# 미디어 볼륨 녹음기 (Media Volume Recorder)

갤럭시(안드로이드) 폰에서 **미디어 볼륨으로 나오는 소리만** 녹음하는 앱입니다.

- 녹음됨: 음악, 동영상, 게임 등 *미디어 볼륨* 으로 조절되는 소리
  (`USAGE_MEDIA`, `USAGE_GAME`, `USAGE_UNKNOWN`)
- 녹음 안 됨: 마이크(주변 소리), 통화, 알림음, 벨소리, 알람, 시스템 소리
- 화면을 꺼도/다른 앱으로 이동해도 계속 녹음 (알림에서 일시정지·중지 가능)
- M4A(AAC 192kbps, 48kHz 스테레오)로 `내장 메모리/Music/MediaVolumeRecorder` 에 저장
- 앱에서 바로 재생·공유·삭제 (길게 누르기)

## 요구 사항

- Android 10(One UI 2) 이상

## 설치

1. 갤럭시 폰 브라우저에서 이 저장소의 **Releases → 최신 릴리스** 를 열고 `MediaVolumeRecorder.apk` 를 내려받습니다.
   (바로가기: `https://github.com/Hoooon22/audio-record/releases/latest/download/MediaVolumeRecorder.apk`)
2. 내려받은 APK 를 열고 "출처를 알 수 없는 앱 설치"를 허용한 뒤 설치합니다.

직접 빌드하려면 Android SDK 가 설치된 환경에서:

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## 사용 방법

1. **녹음 시작** → "마이크" 권한과 알림 권한을 허용합니다.
   마이크 권한은 안드로이드가 내부 소리 캡처에 요구하는 권한일 뿐, 실제 마이크 소리는 녹음하지 않습니다.
2. "화면 녹화/공유를 시작할까요?" 창에서 **시작** 을 누릅니다.
   (안드로이드가 내부 소리 캡처를 *화면 공유* 권한으로 묶어 두었기 때문입니다. 화면은 저장되지 않습니다.)
3. 녹음할 앱(음악, 유튜브 등)에서 소리를 재생합니다.
4. 앱이나 알림에서 **녹음 중지** → 파일이 저장됩니다.

## 알아 둘 점

- 소리를 내는 앱이 내부 녹음을 막아 두면(`allowAudioPlaybackCapture=false`, 또는 Android 10 이전 대상 앱)
  그 앱의 소리는 녹음되지 않습니다. 일부 스트리밍/DRM 앱이 여기에 해당합니다.
- 녹음이 시작될 때마다 "화면 녹화/공유" 확인 창이 다시 뜹니다(안드로이드 14 이상의 보안 정책).

## 구조

| 파일 | 역할 |
| --- | --- |
| `MainActivity.kt` | 권한 요청, 녹음 시작/중지 UI, 녹음 파일 목록 |
| `RecorderService.kt` | `mediaProjection` 포그라운드 서비스, 알림 제어 |
| `MediaVolumeRecorder.kt` | `AudioPlaybackCaptureConfiguration` → `AudioRecord` → AAC 인코딩 → MediaStore 저장 |

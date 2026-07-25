# Game Space cho iodéOS / A21s

Skeleton app "Game Space" kiểu crDroid/AxionAOSP/Infinity-X, chạy được trên ROM
đã build sẵn (không cần source AOSP), dùng root từ KernelSU sẵn có trên Atlas
kernel để chỉnh sysfs hiệu năng theo từng game.

## Kiến trúc

- `MainActivity` — thêm/xóa app vào danh sách "game", xin quyền (usage access,
  overlay, root).
- `GameWatcherService` — foreground service, poll app đang hiển thị mỗi giây
  qua `UsageStatsManager`, so với danh sách game.
- `OverlayBubbleService` — bong bóng nổi kéo-thả được, mở ra panel với 3 nút:
  chặn thông báo (DND), hiệu năng cao, tiết kiệm pin.
- `PerfProfileManager` — ghi/khôi phục sysfs (governor CPU, tần số GPU) qua
  `libsu`, dùng quyền root do KernelSU cấp.

## Việc cần làm trước khi build thật

1. **Đường dẫn sysfs đã xác nhận qua Termux trên chính máy A21s của bạn**:
   - CPU: `policy0` (cluster Little) và `policy4` (cluster big) dưới
     `/sys/devices/system/cpu/cpufreq/`
   - GPU: `/sys/kernel/gpu/gpu_max_clock` và `/sys/kernel/gpu/gpu_governor`

   Còn 2 việc cần làm thủ công trước khi build:
   - Kiểm tra giá trị hợp lệ của `gpu_governor` (chạy
     `cat /sys/kernel/gpu/gpu_available_governor` — nếu không có chuỗi
     `"performance"` trong danh sách, sửa lại `applyGameProfile()` cho khớp
     tên governor thật).
   - Xác nhận `scaling_max_freq` tối đa hợp lệ cho `policy0`
     (little cluster) bằng `cat policy0/scaling_available_frequencies` —
     `1600000` trong code hiện là đoán, không phải số đã xác nhận.

2. **Sinh Gradle wrapper** (chưa có sẵn trong skeleton này) — trong Codespaces:
   ```
   gradle wrapper --gradle-version 8.7
   ```
   rồi commit `gradlew`, `gradlew.bat`, `gradle/wrapper/*` vào repo. CI trong
   `.github/workflows/build.yml` cần các file này để chạy `./gradlew`.

3. **Icon app** — manifest đang dùng icon hệ thống tạm
   (`@android:drawable/sym_def_app_icon`). Thay bằng `mipmap/ic_launcher` khi
   có icon riêng.

## Cấp quyền root cho app (KernelSU)

Sau khi cài APK, mở **KernelSU Manager** trên máy → cấp quyền Superuser cho
`com.quandzv23.gamespace`. Không cấp thì mọi lệnh ghi sysfs trong
`PerfProfileManager` sẽ thất bại âm thầm (không crash, chỉ không áp dụng).

## Cài đặt

APK build ra không cần patch ROM hay đóng gói Magisk module — cài như app
thường (`adb install app-debug.apk`), rồi cấp 3 quyền khi được yêu cầu lúc mở
app lần đầu: usage access, hiển thị đè lên ứng dụng khác, root.

Nếu muốn app khởi động cùng máy tự động bật theo dõi nền, có thể thêm
`BroadcastReceiver` lắng nghe `BOOT_COMPLETED` (chưa có trong skeleton này).

## Giới hạn hiện tại (chưa xử lý)

- Chưa có khóa độ sáng màn hình trong game (cần `WRITE_SETTINGS` +
  `Settings.System.SCREEN_BRIGHTNESS`).
- Chưa có nút ghi màn hình (`MediaProjection`).
- Poll foreground app mỗi giây tốn pin nhẹ — có thể chuyển sang lắng nghe qua
  `AccessibilityService` nếu muốn tức thời hơn và ít poll hơn, đánh đổi lại là
  phải xin quyền Accessibility (dễ bị Play Protect/OEM cảnh báo hơn).

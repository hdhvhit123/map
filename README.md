# TerraTrace

**Bản đồ không cần sóng — tự động xếp ảnh thành hành trình kỷ niệm.**

Ứng dụng Android đọc bản đồ OpenStreetMap ngoại tuyến, ghi lộ trình GPS, nhập/xuất GPX‑KML,
tự ghim ảnh lên đúng cung đường nhờ EXIF, phát lại hành trình và xuất poster/video để chia sẻ.
Toàn bộ xử lý diễn ra trên máy: không tài khoản, không máy chủ, không theo dõi.

- Đặc tả sản phẩm đầy đủ: [`docs/SPEC.md`](docs/SPEC.md)
- Kiến trúc & hướng dẫn phát triển: bên dưới

## Tính năng (MVP)

| Nhóm | Có gì |
|---|---|
| Bản đồ offline | MapLibre Native + OpenFreeMap (OSM). Tải vùng đang xem theo 3 mức chi tiết, ước lượng dung lượng, cảnh báo mạng di động, tạm dừng/tiếp tục/xoá |
| Ghi GPS | Foreground service, 2 chế độ pin, lọc nhiễu (độ chính xác, điểm nhảy, đứng yên), segment khi mất sóng, độ cao có ngưỡng trễ, khôi phục khi app bị kill |
| GPX / KML | Nhập từ file picker hoặc "Mở bằng" từ app khác; ghim làm đường mẫu màu đỏ; xuất GPX 1.1 / KML và chia sẻ |
| Độ cao chính xác | Áp kế + dữ liệu địa hình offline (Terrain Tiles) + GPS quy về mực nước biển; hiệu chỉnh độ cao chuyến đi |
| Ảnh → lộ trình | Quét ảnh theo thời gian chuyến đi, đọc GPS gốc (ACCESS_MEDIA_LOCATION), nội suy vị trí cho ảnh thiếu GPS, nhật ký ảnh theo khoảng ngày |
| Xem lại | Phát lại theo thời gian (hoặc quãng đường), camera bám theo, ảnh bật lên, thanh ảnh đồng bộ bản đồ, biểu đồ độ cao |
| Chia sẻ | Poster 1080×1350 PNG, video dọc 720×1280 MP4 (MediaCodec), watermark "Created with TerraTrace" |
| Riêng tư | Vùng riêng tư cắt lộ trình khi chia sẻ, không sao lưu đám mây, xoá toàn bộ dữ liệu |

## Cài đặt APK

Mỗi lần push, GitHub Actions build APK: vào tab **Actions → Android build → lần chạy mới nhất → Artifacts → `terratrace-apk`**.
File `app-release.apk` đã được ký bằng khoá debug để cài trực tiếp (bật "Cài ứng dụng không rõ nguồn gốc").

## Build tại máy

Yêu cầu JDK 17 và Android SDK (platform 35).

```bash
./gradlew testDebugUnitTest   # unit test: lọc GPS, thống kê, GPX/KML
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

## Cấu trúc mã nguồn

```
app/src/main/java/app/terratrace/
├── geo/          GeoMath (khoảng cách, thống kê, Douglas‑Peucker, nội suy, vùng riêng tư), TrackFilter
├── io/           Bộ đọc/ghi GPX & KML (SAX, stream)
├── data/         Room: trips, track_points, photos, waypoints, privacy_zones; Settings
├── recording/    RecordingService (foreground, LocationManager) + Recorder (StateFlow)
├── photos/       PhotoScanner (MediaStore + ExifInterface)
├── dem/          TerrainStore: tải & tra cứu độ cao địa hình offline
├── map/          TrailMap (Compose ↔ MapLibre), OfflineRegions
├── export/       SceneBuilder (MapSnapshotter), PosterRenderer, VideoRenderer
└── ui/           Theme (Forest Green / Terra Cotta / Stone), components, screens
```

Phần `geo/` và `io/` là Kotlin thuần, có unit test chạy trên JVM.

## Giấy phép dữ liệu

Bản đồ © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright) (ODbL), tile từ [OpenFreeMap](https://openfreemap.org), render bởi [MapLibre](https://maplibre.org). Độ cao địa hình: [Terrain Tiles](https://registry.opendata.aws/terrain-tiles/) (SRTM/NASA, GMTED, ETOPO1; Mapzen).

# TerraTrace — Đặc tả sản phẩm (bản hoàn thiện v1.0)

> "Bản đồ không cần sóng — Tự động xếp ảnh thành hành trình kỷ niệm sống động."
> Tài liệu này mở rộng bản đặc tả gốc: chốt quyết định kỹ thuật, mô hình dữ liệu, luồng màn hình,
> các tình huống biên (edge case), cam kết riêng tư và phạm vi MVP so với các giai đoạn sau.

---

## 1. Người dùng mục tiêu & việc họ cần làm

| Chân dung | Bối cảnh | Việc cần làm (Job‑to‑be‑done) |
|---|---|---|
| **Dân phượt xe máy** (Hà Giang, Tây Bắc) | Mất sóng trên đèo, pin sạc dự phòng hạn chế | Xem bản đồ không cần mạng, ghi lại cung đường, khoe hành trình sau chuyến đi |
| **Trekker / leo núi** (Tà Xùa, Lảo Thẩn, Fansipan) | Tay đeo găng, trời lạnh, đường mòn nhỏ | Đi theo file GPX của nhóm, biết mình còn bao xa, độ cao bao nhiêu |
| **Du lịch nước ngoài** | Tắt roaming | Bản đồ offline thành phố + ghép ảnh vào lộ trình |
| **Chạy trail / đạp xe** | Thi đấu theo file GPX của BTC | Nạp GPX giải, ghi lộ trình, xuất GPX nộp lên Strava |

Nguyên tắc sản phẩm (dùng để giải quyết tranh cãi khi thiết kế):
1. **Riêng tư mặc định** — không tài khoản, không máy chủ, không SDK phân tích. Mạng chỉ dùng để tải bản đồ khi người dùng bấm tải.
2. **Hoạt động khi không có sóng** — mọi tính năng trừ "tải bản đồ" phải chạy ở chế độ máy bay.
3. **Dùng được bằng găng tay** — nút chính ≥ 64dp, chữ số lớn, tương phản cao dưới nắng.
4. **Không bao giờ mất dữ liệu chuyến đi** — mỗi điểm GPS được ghi ngay xuống đĩa; app bị kill vẫn khôi phục được.

---

## 2. Quyết định kỹ thuật đã chốt

| Hạng mục | Lựa chọn | Lý do |
|---|---|---|
| Nền tảng | Android, Kotlin, Jetpack Compose, minSdk 26, targetSdk 35 | 26 đủ cho `lockHardwareCanvas`, MediaCodec ổn định, phủ >97% máy |
| Bản đồ | **MapLibre Native Android 11** | Render vector GPU, có sẵn Offline Region, MapSnapshotter (dùng cho poster/video), mã nguồn mở. Mapsforge chỉ render CPU, không có snapshot. |
| Nguồn tile | **OpenFreeMap** (dữ liệu OpenStreetMap, schema OpenMapTiles, không cần API key) | Miễn phí, không theo dõi người dùng; có thể thay bằng file `.mbtiles/.pmtiles` tự host ở v1.1 |
| Định vị | `LocationManager` (GPS_PROVIDER) — **không dùng Google Play Services** | Chạy được trên máy không có GMS, không gửi dữ liệu cho bên thứ ba, phát hành được trên F‑Droid |
| Lưu trữ | Room (SQLite): bảng điểm GPS thô + thống kê tính sẵn | Đơn giản, đủ nhanh cho 100k điểm/chuyến; Spatialite không cần thiết ở MVP |
| Đọc ảnh | MediaStore + `androidx.exifinterface` + `ACCESS_MEDIA_LOCATION` | Không giải mã ảnh, không sao chép ảnh; chỉ lưu URI |
| GPX/KML | Bộ đọc SAX tự viết (stream, không tải hết DOM) | File 50MB vẫn không tràn bộ nhớ |
| Video | MediaCodec (H.264) + `Surface.lockHardwareCanvas` + MediaMuxer | 100% offline, không cần FFmpeg (tiết kiệm ~20MB APK) |
| Ảnh poster | MapSnapshotter (dùng tile đã cache) + Canvas | Có bản đồ thật nếu vùng đã tải; fallback nền địa hình cách điệu |

---

## 3. Mô hình dữ liệu

```
trips
  id PK, name, kind {RECORDED | IMPORTED | PHOTO_JOURNAL}
  startTime, endTime (epoch ms, nullable với file import không có thời gian)
  distanceM, movingTimeMs, elevationGainM, elevationLossM, maxElevationM, maxSpeedMps
  color (ARGB), note, createdAt
  isRecording (bool)   -- để khôi phục khi app bị kill
  pinnedOnMap (bool)   -- hiện như "đường mẫu" màu đỏ trên bản đồ chính

track_points            index(tripId, time)
  id PK, tripId FK(cascade), segment, time?, lat, lon, ele?, accuracy?, speed?

photos                  unique(tripId, uri)
  id PK, tripId FK(cascade), uri, takenAt, lat, lon,
  locationSource {EXIF | INTERPOLATED}, favorite

waypoints
  id PK, tripId FK(cascade), name, lat, lon, ele?, time?

privacy_zones
  id PK, name, lat, lon, radiusM
```

Vùng bản đồ offline do MapLibre tự quản lý trong cơ sở dữ liệu riêng; metadata (tên, mức chi tiết, ngày tải) lưu dạng JSON trong trường metadata của region.

---

## 4. Tính năng chi tiết & quy tắc nghiệp vụ

### 4.1 Bản đồ offline
- **Tải vùng đang xem**: người dùng kéo bản đồ tới khu vực, đặt tên, chọn mức chi tiết:
  - *Tổng quan* (zoom 0–10) — cho cả quốc gia, vài chục MB.
  - *Đường phố* (zoom 0–12).
  - *Trekking* (zoom 0–14) — đầy đủ lối mòn, suối, đỉnh núi. Trên z14 MapLibre tự phóng to (overzoom) nên vẫn xem chi tiết tới z18.
- Trước khi tải hiển thị **ước lượng số tile & dung lượng**. Chặn vùng > 120.000 tile (gợi ý giảm mức chi tiết hoặc chia nhỏ vùng).
- Cảnh báo khi đang dùng dữ liệu di động (mạng tính phí).
- Theo dõi tiến độ, tạm dừng/tiếp tục, xoá vùng. Khi không có mạng, MapLibre tự lấy tile từ cache.
- Ba kiểu bản đồ: *Outdoor* (Liberty), *Sáng* (Positron), *Nổi bật* (Bright). Vùng tải theo kiểu nào thì kiểu đó dùng offline.

### 4.2 Ghi lộ trình GPS
- Foreground service loại `location`, thông báo thường trực có nút **Tạm dừng / Kết thúc**.
- Hai chế độ: **Chính xác** (1 giây/2 m) và **Tiết kiệm pin** (5 giây/10 m).
- **Bộ lọc nhiễu** (lớp `TrackFilter`, có unit test):
  - Bỏ điểm có độ chính xác > 35 m (ngoại trừ điểm đầu tiên sau khi mất tín hiệu > 2 phút, chấp nhận tới 60 m).
  - Bỏ điểm "nhảy" có vận tốc ngầm > 70 m/s (252 km/h).
  - Bỏ điểm cách điểm trước < max(2 m, accuracy/2) khi chưa quá 30 giây (đứng yên → không làm bẩn track).
  - Thời gian di chuyển chỉ cộng khi vận tốc đoạn > 0,5 m/s và khoảng cách giữa 2 điểm < 60 giây.
  - Độ cao tích luỹ dùng **ngưỡng trễ 4 m** để loại dao động độ cao GPS (sai số GPS dọc thường 10–15 m).
- Mất tín hiệu > 5 phút (hầm, hang) → tự tạo **segment** mới, không nối đường thẳng xuyên núi.
- Tạm dừng/Tiếp tục → segment mới.
- **Khôi phục**: mỗi điểm ghi ngay vào DB. Khi mở app mà còn chuyến `isRecording` nhưng service không chạy → banner *"Chuyến đi đang dở — Tiếp tục ghi / Kết thúc"*.
- HUD dạng đồng hồ thể thao trong suốt: tốc độ hiện tại, độ cao, quãng đường, thời gian.
- Khi có "đường mẫu" (GPX đã ghim), đường mẫu hiển thị **đỏ** dưới vệt đường đang đi (cam đất).

### 4.3 GPX / KML Hub
- Nhập: GPX 1.0/1.1 (`trk/trkseg/trkpt`, `rte/rtept`, `wpt`), KML (`LineString`, `gx:Track`, `Point` → waypoint). Mở trực tiếp từ trình quản lý file / Zalo / Messenger (intent filter `.gpx`, `.kml`).
- File lỗi định dạng / không có điểm → báo lỗi tiếng Việt rõ ràng, không crash.
- File không có thời gian → thống kê chỉ có quãng đường, độ cao; phát lại theo quãng đường thay vì thời gian.
- Xuất: GPX 1.1 (kèm `ele`, `time`, waypoint) hoặc KML; chia sẻ qua share sheet hoặc lưu vào thư mục tuỳ chọn.
- **Áp dụng vùng riêng tư** khi xuất (bật mặc định): cắt bỏ các điểm nằm trong vùng.

### 4.4 Ghim ảnh tự động (Photo‑to‑Trail)
- Với chuyến đã ghi/nhập: quét ảnh trong khoảng `[start − 30 phút, end + 30 phút]`.
- Với **Nhật ký ảnh** (không có track): người dùng chọn khoảng ngày (vd 15/10–18/10) → app tạo chuyến từ ảnh, nối các điểm chụp theo thời gian thành đường nét đứt.
- Thứ tự lấy toạ độ:
  1. EXIF GPS từ bản gốc (`MediaStore.setRequireOriginal`, cần quyền `ACCESS_MEDIA_LOCATION`, nếu không Android sẽ xoá GPS khỏi EXIF).
  2. Nếu ảnh không có GPS (máy ảnh rời, tắt định vị camera) → **nội suy theo thời gian** từ track (chấp nhận khi có điểm track trong ±10 phút). Ảnh nội suy được đánh dấu khác màu.
  3. Không xác định được → bỏ qua, báo số ảnh bị bỏ qua.
- Thời gian chụp: `DATE_TAKEN` (UTC) của MediaStore; nếu thiếu, dùng `DateTimeOriginal` + `OffsetTimeOriginal`; nếu thiếu múi giờ, dùng múi giờ máy.
- Không trùng ảnh (unique theo URI); quét lại chỉ thêm ảnh mới.
- Android 14 "chỉ chọn một số ảnh" → quét những ảnh được cấp, hiện gợi ý cấp thêm.
- Ảnh **không bao giờ bị sao chép hay tải lên**; chỉ lưu URI. Ảnh bị xoá khỏi máy → tự ẩn.

### 4.5 Xem lại hành trình (Timeline Trail Player)
- Nút **Play**: vẽ dần vệt đường trong ~20 giây, camera bám theo đầu đường, ảnh "bung" ra đúng thời điểm chụp kèm giờ chụp. Có thanh tua, tạm dừng, tốc độ 1×/2×.
- **Thanh ảnh đáy màn hình**: lướt ảnh → bản đồ bay tới vị trí chụp; chạm ghim trên bản đồ → thanh ảnh cuộn tới ảnh đó.
- Biểu đồ độ cao theo quãng đường, có con trỏ đồng bộ với phát lại.

### 4.6 Poster & Video chia sẻ
- **Poster** 1080×1350 (tỉ lệ 4:5, chuẩn Instagram/Facebook): bản đồ nền + vệt đường + điểm đầu/cuối, tên chuyến, ngày, 3 chỉ số lớn (quãng đường, độ cao tích luỹ, thời gian di chuyển), dải 4 ảnh (ưu tiên ảnh được đánh dấu ★), watermark *"Created with TerraTrace"*.
- **Video** 720×1280, 30fps, ~15 giây (dọc, cho Reels/Shorts/TikTok): đường vẽ dần, chỉ số chạy số, ảnh bay ra tại điểm chụp, khung kết thúc giữ 1,5 giây.
- Cả hai áp dụng vùng riêng tư và lưu vào `Pictures/TerraTrace`, `Movies/TerraTrace`, sau đó mở share sheet.

### 4.7 Vùng riêng tư (bổ sung so với bản gốc)
- Người dùng đặt vùng (nhà, cơ quan) với bán kính 200 m / 500 m / 1 km.
- Điểm trong vùng bị loại khỏi: file GPX/KML xuất ra, poster, video. Dữ liệu gốc trong máy giữ nguyên.
- Vì đường bị cắt ở mép vùng nên người xem không suy ra được vị trí nhà.

---

## 5. Màn hình

1. **Bản đồ** (tab chính): bản đồ toàn màn hình; HUD trong suốt góc trên; cột nút tròn lớn bên phải (vị trí của tôi, kiểu bản đồ, nhập GPX); nút ghi to màu cam đất ở đáy. Khi ghi: thanh điều khiển *Tạm dừng / Kết thúc* + HUD mở rộng.
2. **Hành trình**: danh sách thẻ chuyến (tên, ngày, km, ↑m, số ảnh, biểu tượng loại). FAB: *Nhập GPX/KML*, *Tạo nhật ký ảnh*.
3. **Chi tiết chuyến**: bản đồ (60% màn hình) + thanh ảnh; tab *Thống kê* (lưới chỉ số + biểu đồ độ cao); hàng hành động: Phát lại · Quét ảnh · Poster · Video · Xuất GPX/KML · Ghim lên bản đồ · Đổi tên · Xoá.
4. **Bản đồ offline**: bản đồ chọn vùng + danh sách vùng đã tải với tiến độ.
5. **Cài đặt**: chế độ GPS, kiểu bản đồ, vùng riêng tư, xoá toàn bộ dữ liệu, giấy phép (© OpenStreetMap contributors, OpenFreeMap, MapLibre).

## 6. Hệ thống thiết kế

| Token | Sáng | Tối | Dùng cho |
|---|---|---|---|
| Forest Green | `#2F5D3A` | `#8BC49A` | primary: nút chính, tab đang chọn |
| Terra Cotta | `#C8643B` | `#F09A72` | ghi hình, vệt đường đang đi, điểm nhấn |
| Đỏ đường mẫu | `#D93A2B` | `#FF6B5B` | GPX nhập để đi theo |
| Stone 50 | `#F5F3EF` | `#151816` | nền |
| Stone 100 | `#ECE8E1` | `#1F2320` | bề mặt phụ |
| Stone 600 | `#6B675F` | `#A8A39A` | chữ phụ |

- Chữ số dùng tabular figures (`tnum`) để HUD không "nhảy" khi số thay đổi.
- Bo góc 20–28 dp, nút tròn 56–72 dp, khoảng cách lưới 8 dp.
- HUD: nền đen 55% + viền trắng 12% ("kính khói"), đọc tốt trên mọi kiểu bản đồ.
- Hỗ trợ chế độ tối theo hệ thống; màu bản đồ không đảo để giữ đọc địa hình.

## 7. Edge case & cách xử lý (tóm tắt)

| Tình huống | Xử lý |
|---|---|
| Từ chối quyền vị trí | Bản đồ vẫn dùng được; nút ghi hiện giải thích + đường tới Cài đặt |
| Từ chối quyền thông báo (Android 13+) | Vẫn ghi được; service vẫn chạy (thông báo bị ẩn) |
| Android kill app khi ghi | Foreground service + điểm ghi ngay vào DB + banner khôi phục |
| GPS lạnh, điểm đầu sai lệch lớn | Bỏ điểm accuracy > 35 m |
| Vào hầm/hang | Gap > 5 phút → segment mới |
| Đứng chờ đèn đỏ / nghỉ chân | Không cộng điểm, không cộng thời gian di chuyển |
| Độ cao GPS dao động | Ngưỡng trễ 4 m |
| File GPX 50MB | Đọc dạng stream, đơn giản hoá (Douglas‑Peucker) khi vẽ |
| GPX không có thời gian | Phát lại theo quãng đường |
| Ảnh bị xoá GPS do quyền | Xin `ACCESS_MEDIA_LOCATION`, nếu vẫn thiếu thì nội suy theo thời gian |
| Ảnh chụp lệch múi giờ | Ưu tiên `DATE_TAKEN` UTC, `OffsetTimeOriginal` |
| Bộ nhớ đầy khi tải bản đồ | Bắt lỗi từ MapLibre, báo người dùng, giữ phần đã tải |
| Vùng quá lớn | Chặn > 120k tile, gợi ý giảm zoom |
| Thiết bị không hỗ trợ encoder H.264 720p | Báo lỗi, gợi ý xuất poster |

## 8. Phạm vi phát hành

### MVP (bản trong repo này)
- Bản đồ vector OSM, 3 kiểu, tải vùng offline, quản lý vùng.
- Ghi GPS foreground service, bộ lọc nhiễu, thống kê, khôi phục.
- Nhập GPX/KML (file picker + mở từ app khác), ghim đường mẫu đỏ, xuất GPX/KML + share.
- Quét ảnh EXIF + nội suy, Nhật ký ảnh theo khoảng ngày, thanh ảnh, ghim ảnh, xem ảnh lớn.
- Phát lại hành trình, biểu đồ độ cao.
- Poster PNG, video MP4 720p.
- Vùng riêng tư, xoá toàn bộ dữ liệu.

### v1.1
- Nhập `.mbtiles/.pmtiles` tự host; gói bản đồ theo tỉnh dựng sẵn.
- Đường đồng mức & hillshade (DEM offline).
- Dẫn đường theo GPX: cảnh báo lệch tuyến, khoảng cách còn lại, ETA.
- Widget màn hình khoá, Wear OS.

### v2
- Video 3D (pitch + terrain), 1080p/4K 60fps.
- Bản đồ vệ tinh offline.
- Gói TerraTrace Explorer (Play Billing): tải không giới hạn, 4K, kiểu bản đồ chuyên sâu, không watermark.
- Sao lưu mã hoá đầu‑cuối do người dùng giữ khoá (tuỳ chọn).

## 9. Kiếm tiền (giữ nguyên định hướng, chốt chi tiết)
- **Miễn phí**: 1 vùng tải ≤ "1 quốc gia/khu vực lớn" (tính theo tổng tile), ghi GPS không giới hạn, 100 ảnh/chuyến, poster & video 720p có watermark.
- **Explorer**: $3.99 (1 năm cập nhật bản đồ) hoặc $9.99 trọn đời. Không quảng cáo ở cả hai bản — quảng cáo mâu thuẫn với lời hứa riêng tư.
- MVP **chưa** bật giới hạn; các giới hạn được thiết kế để thêm ở v2 mà không đổi mô hình dữ liệu.

## 10. Chỉ số thành công
- Tỉ lệ chuyến ghi kết thúc thành công (không mất dữ liệu) ≥ 99,5%.
- ≥ 30% chuyến có poster/video được xuất (vòng lặp lan truyền).
- Pin tiêu hao khi ghi ở chế độ tiết kiệm ≤ 4%/giờ (màn hình tắt).

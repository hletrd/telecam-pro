# Google Play listing — TeleCam Pro

Copy-paste source for the Play Console listing. Character limits noted; everything here is within them.

---

## App details

| Field | Value |
|---|---|
| **App name** (≤30) | `TeleCam Pro` |
| **Package name** | `me.hletrd.telecampro` |
| **Category** | Photography |
| **Content rating** | Everyone (no user-generated content shared, no ads, no data collection) |
| **Contact email** | `01@0101010101.com` |
| **Privacy policy URL** | `https://hletrd.github.io/telecam-pro/privacy-policy/` |
| **Source code URL** | `https://github.com/hletrd/telecam-pro` |
| **Ads** | No |
| **In-app purchases** | No |
| **Price** | Free (set in Play Console pricing, not in title or short description) |

## Short description (≤80 chars)

```
Open-source manual camera for periscope telephoto + teleconverters.
```

## Release notes (≤500 chars per language)

Notes for the NEXT upload — **v1.0.1 (`versionCode 2`)**; `versionCode 1` is spent (see
`play-console-submit.md`). Paste into the Play Console "Release notes" field, language tags
included — and like the full description, do not re-wrap it.

```
<en-US>
The interface is now available in Korean.

Tablets and other large screens now use a landscape layout with a side control rail.

Much smaller download: release builds are now minified.
</en-US>
```

### Shipped v1.0 notes (`versionCode 1` — published 2026-08-04; spent, do not reuse)

```
<en-US>
First release.

TeleCam Pro is a manual camera for shooting through clip-on afocal teleconverters on a phone's periscope telephoto. It corrects the 180-degree flip those converters introduce, in the viewfinder and in saved photos and video.

Manual focus tuned near infinity, PASM exposure, RAW/DNG, focus peaking, zebra, histogram and waveform.

Open source. No ads, no analytics, no account, and no network permission.
</en-US>
```

Deliberately makes no HDR or 10-bit claim: the capture source is the ISP's display-referred SDR
stream, and the HLG/log profiles are curves applied to it (see CLAUDE.md). Saying otherwise in store
copy would be a stronger claim than the pipeline can back.

## Full description (≤4000 chars)

**Paste this block VERBATIM — it is deliberately NOT hard-wrapped.** Play renders the description
literally, so a source file wrapped for readability puts a line break in the middle of every
sentence. Each bullet and paragraph is therefore one long line; let the console wrap it.


```
TeleCam Pro is an open-source manual camera for shooting through clip-on afocal teleconverters on a phone's periscope telephoto. It corrects the 180-degree flip those converters introduce, and gives you the manual controls long-lens work needs.

OPEN SOURCE
• Public and auditable under Apache 2.0: github.com/hletrd/telecam-pro
• No ads, analytics, in-app purchases, account, or cloud sync.

TELECONVERTER MODE
• The Teleconverter toggle pins the lens your converter clamps onto and corrects the afocal 180-degree flip in preview, photos, and video. Lens presets stay independent, so changing framing never silently toggles the converter.
• Tell the app which converter you mounted: pick your phone, then the optics that fit it. Presets cover the Hasselblad 300 mm and 230 mm, ZEISS 200 mm and 400 mm, generic clip-ons, and a custom magnification. Each preset's magnification is computed from the host lens it was designed for, so the focal readout and EXIF report what the combination produces — not the number on the barrel.
• Uses the device's Camera2 OIS and video stabilization.

LENSES
• Focal presets filtered to the ones your phone can reach, with the live effective focal length in the viewfinder. A preset reachable only by cropping is labelled zoom, not lens.

FULL MANUAL CONTROL
• Manual focus on a nonlinear slider tuned near infinity — essential for a collimated converter.
• ISO, shutter (speed or cine angle), white balance (presets, measured custom, manual Kelvin/tint), exposure compensation, metering, and drive modes (single, burst, bracketing, timelapse) on snapping dials.
• Vibration-free hardware controls: volume keys, and where the phone delivers them, the camera button's full press, light press, and slide — each reassignable.

PHOTO
• HEIF (where the phone can encode it), JPEG and RAW (DNG) — separately or together, on any rear lens advertising RAW. Saved files use device-orientation-aware rotation.

VIDEO
• HEVC or H.264. HEVC adds HLG and app-rendered S-Log3 / S-Log3.Cine / LogC3 alongside SDR where the encoder supports 10-bit; where it does not, SDR only — never a file that misdescribes itself.
• Open Gate records the full sensor readout, not a 16:9 crop.
• Hardware OIS + EIS to cut motion blur at long focal length.
• Sound Focus / Sound Stage audio scenes, passed to the device's audio system where its HAL supports it.
• Up to 4K UHD at standard and fractional NTSC rates, to ~99 Mbps at 4K30 Max. Resolutions and rates come from what the selected camera and encoder advertise.

FRAMING AND MONITORING
• Focus peaking, zebra, false color, grid, level, punch-in loupe, histogram, waveform, and pinch-to-zoom review to check focus.
• Separate photo/video Fn menus, My Menu, and MR memory banks.

PRIVACY
• No ads, analytics, tracking, or internet permission.
• Microphone: only for enabled video audio and the visible level meter while Video mode is armed. Meter input is processed locally and never saved.
• Photo and video access is optional, asked only when the in-app gallery is opened with nothing to show, so it can find captures saved before a reinstall. Declining changes nothing else.
• Nothing is uploaded, collected by the developer, or shared with third parties.

TeleCam Pro is independent and is not affiliated with, endorsed by, or sponsored by any hardware or format owner. Names are used only to describe compatibility. Hasselblad is a trademark of Victor Hasselblad AB; OPPO of Guangdong OPPO Mobile Telecommunications Corp., Ltd.; ZEISS of Carl Zeiss AG; vivo of vivo Mobile Communication Co., Ltd.; S-Log of Sony Group Corporation; LogC of Arnold & Richter Cine Technik GmbH & Co. Betriebs KG. The log profiles are this app's own implementations of the published curves. Apache License 2.0.

Requires Android 13 or newer. Measured on the OPPO Find X9 Ultra (PMA110); the app resolves lenses and features from each device's advertised Camera2 capabilities rather than a model list, so other devices are supported best-effort.
```

Every concrete claim above was audited against the shipping source on 2026-08-03; 15 inaccuracies
were found and corrected. The ones worth remembering, because they were all the same mistake —
copy written for one phone and one kit, left standing after the app became multi-device:

- "Requires Android 16" (the floor is `minSdk 33`), and "other Android 16 phones".
- "One tap selects the 3x periscope lens and enables teleconverter mode" — lens presets deliberately
  do NOT bundle the converter; `onToggleTeleconverter` owns that state.
- "Focal presets for every rear lens your phone advertises" — the rail is filtered by what the optics
  can reach, and an unreachable preset is spoken as zoom.
- HEVC-only (H.264 is offered too), HLG/log unconditional (gated on a 10-bit encoder), RAW on "any
  lens" (only lenses advertising RAW), HEIF unconditional (gated on an HEVC encoder).
- No mention of photo/video access in PRIVACY, though the manifest declares it.

## Korean listing (`ko-KR`) — 한국어 스토어 등록정보

Play serves this to Korean-locale users while `en-US` stays the default. It is a translation of the
audited English copy above, not a separate pitch: every gating clause ("where the phone can encode
it", "where the encoder supports 10-bit", "advertising RAW"), the trademark attributions, the
Android floor, and the best-effort wording are carried over deliberately. **If the English copy
changes, change this too** — a listing that promises more in one language than the other is the
same defect as promising more than the app does.

Brand names stay in Latin script because that is how the app's own converter presets render them; a
transliterated "하셀블라드" would not match what the operator sees on screen.

### Short description — 간단한 설명 (≤80자)

```
클립온 텔레컨버터로 페리스코프 망원을 쓰기 위한 오픈소스 수동 카메라.
```

### Release notes — 출시 노트 (≤500자)

v1.0.1 (`versionCode 2`) 출시 노트입니다. 영어본과 같은 규칙: 줄바꿈을 그대로 두고 붙여넣으세요.

```
<ko-KR>
한국어 UI를 추가했습니다.

태블릿 같은 큰 화면에서는 옆쪽 컨트롤 레일이 있는 가로 레이아웃을 씁니다.

다운로드 용량을 크게 줄였습니다.
</ko-KR>
```

#### v1.0 notes (superseded — `versionCode 1` is spent; this translation postdates the v1.0 publication and was never pasted)

```
<ko-KR>
첫 번째 릴리스입니다.

TeleCam Pro는 휴대폰의 페리스코프 망원 렌즈에 클립온 아포컬 텔레컨버터를 물려 촬영하는 수동 카메라입니다. 이런 컨버터가 만들어내는 180도 반전을 뷰파인더와 저장되는 사진, 영상에서 함께 바로잡습니다.

무한대 부근에 맞춘 수동 초점, PASM 노출, RAW/DNG, 포커스 피킹, 제브라, 히스토그램과 파형을 갖췄습니다.

오픈소스입니다. 광고, 분석 도구, 계정, 네트워크 권한이 없습니다.
</ko-KR>
```

### Full description — 자세한 설명 (≤4000자)

**이 블록도 줄바꿈을 그대로 두고 붙여넣으세요.** 영어본과 같은 이유입니다. Play는 설명의
줄바꿈을 있는 그대로 렌더링하므로, 읽기 좋게 줄을 접으면 문장 한가운데가 잘린 채 게시됩니다.

```
TeleCam Pro는 휴대폰의 페리스코프 망원 렌즈에 클립온 아포컬 텔레컨버터를 물려 촬영하는 오픈소스 수동 카메라입니다. 이런 컨버터가 만들어내는 180도 반전을 바로잡고 망원 촬영에 필요한 수동 제어를 갖췄습니다.

오픈소스
• Apache License 2.0으로 공개해 누구나 코드를 확인할 수 있습니다: github.com/hletrd/telecam-pro
• 광고, 분석 도구, 인앱 결제, 계정, 클라우드 동기화가 없습니다.

텔레컨버터 모드
• 텔레컨버터 토글은 컨버터를 물린 렌즈를 고정하고 아포컬 180도 반전을 뷰파인더와 사진, 영상에서 함께 바로잡습니다. 렌즈 프리셋은 이와 독립적으로 동작하므로 화각을 바꿔도 컨버터 설정이 조용히 따라 바뀌지 않습니다.
• 어떤 컨버터를 물렸는지는 사용자가 고릅니다. 휴대폰을 먼저 고르면 그 기기에 물릴 수 있는 광학계만 나옵니다. Hasselblad 300 mm와 230 mm, ZEISS 200 mm와 400 mm, 범용 클립온, 직접 입력하는 배율을 지원합니다. 각 프리셋의 배율은 그 컨버터가 설계된 호스트 렌즈를 기준으로 계산하므로 초점거리 표시와 EXIF에는 경통에 적힌 숫자가 아니라 실제 조합이 만들어내는 값이 남습니다.
• 기기의 Camera2 OIS와 동영상 손떨림 보정을 씁니다.

렌즈
• 초점거리 프리셋은 사용 중인 휴대폰이 실제로 도달할 수 있는 것만 나오고, 뷰파인더에 현재 환산 초점거리를 함께 보여 줍니다. 크롭으로만 도달하는 프리셋은 렌즈가 아니라 줌으로 표시합니다.

완전 수동 제어
• 무한대 부근에 조작 해상도를 몰아준 비선형 슬라이더 방식의 수동 초점. 빛이 평행하게 들어오는 컨버터에서는 꼭 필요합니다.
• ISO, 셔터(속도 또는 시네 앵글), 화이트 밸런스(프리셋, 실측 커스텀, 수동 켈빈/틴트), 노출 보정, 측광, 드라이브 모드(단사, 연사, 브래킷, 타임랩스)를 단계마다 걸리는 다이얼로 조작합니다.
• 흔들림 없는 하드웨어 조작: 볼륨 키를 쓰고, 기기가 전달해 주면 카메라 버튼의 완전 누름과 반누름, 슬라이드까지 각각 원하는 기능에 재할당할 수 있습니다.

사진
• HEIF(기기가 인코딩할 수 있는 경우), JPEG, RAW(DNG)를 따로 또는 함께 저장합니다. RAW는 이를 지원한다고 알린 후면 렌즈에서 쓸 수 있습니다. 촬영 당시 기기 방향에 맞춰 돌려서 저장합니다.

동영상
• HEVC 또는 H.264. 인코더가 10비트를 지원하는 기기에서는 HEVC에서 SDR과 함께 HLG, 그리고 앱이 직접 렌더링하는 S-Log3 / S-Log3.Cine / LogC3를 쓸 수 있습니다. 지원하지 않는 기기에서는 SDR만 쓸 수 있습니다. 내용과 다르게 표기된 파일은 만들지 않습니다.
• 오픈 게이트는 16:9로 자르지 않고 센서 판독 영역 전체를 기록합니다.
• 긴 초점거리에서 흔들림을 줄이려고 하드웨어 OIS와 EIS를 씁니다.
• 기기 HAL이 지원하면 Sound Focus / Sound Stage 오디오 씬을 전달합니다.
• 표준과 NTSC 소수점 프레임레이트로 최대 4K UHD, 4K30 Max에서 약 99 Mbps까지. 해상도와 프레임레이트는 선택한 카메라와 인코더가 알린 값에서 가져옵니다.

프레이밍과 모니터링
• 포커스 피킹, 제브라, 폴스 컬러, 그리드, 수평계, 펀치인 루페, 히스토그램, 파형, 그리고 초점을 확인하는 핀치 줌 확대 보기.
• 사진과 동영상이 분리된 Fn 메뉴, 마이 메뉴, MR 메모리 뱅크.

개인정보
• 광고, 분석, 트래킹, 인터넷 권한이 없습니다.
• 마이크: 동영상 오디오를 켰을 때와 동영상 모드에서 입력 레벨 미터가 화면에 보이는 동안에만 씁니다. 미터 입력은 기기 안에서만 처리하고 저장하지 않습니다.
• 사진과 동영상 접근 권한은 선택 사항이며, 앱 안의 갤러리를 열었는데 보여 줄 것이 없을 때만 요청합니다. 재설치 이전에 저장해 둔 촬영물을 다시 찾는 용도입니다. 거부해도 나머지 기능은 그대로입니다.
• 어떤 자료도 업로드하지 않고 개발자가 수집하지 않으며 제3자와 공유하지 않습니다.

TeleCam Pro는 독립적으로 개발한 앱이며, 어떤 하드웨어 제조사나 포맷 권리자와도 제휴, 보증, 후원 관계가 없습니다. 상표명은 호환성을 설명하려고만 썼습니다. Hasselblad는 Victor Hasselblad AB, OPPO는 Guangdong OPPO Mobile Telecommunications Corp., Ltd., ZEISS는 Carl Zeiss AG, vivo는 vivo Mobile Communication Co., Ltd., S-Log는 Sony Group Corporation, LogC는 Arnold & Richter Cine Technik GmbH & Co. Betriebs KG의 상표입니다. 로그 프로파일은 공개된 커브를 이 앱이 자체 구현한 것입니다. Apache License 2.0.

Android 13 이상이 필요합니다. OPPO Find X9 Ultra(PMA110)에서 측정했습니다. 모델 목록이 아니라 각 기기가 알리는 Camera2 기능에서 렌즈와 기능을 판별하므로 다른 기기도 최선을 다해 지원하지만 동작을 보장하지는 않습니다.
```

번역에서 의도적으로 지킨 것:

- **HDR과 10비트를 주장하지 않습니다.** 촬영 소스가 ISP의 display-referred SDR 스트림이고 HLG와
  로그는 거기에 적용한 커브입니다. 영어본과 마찬가지로 그 이상을 말하지 않습니다.
- **조건절을 빠짐없이 옮겼습니다.** "기기가 인코딩할 수 있는 경우", "인코더가 10비트를 지원하는
  기기에서는", "지원한다고 알린 후면 렌즈에서". 영어본은 과장 15건을 감사로 걷어낸 결과입니다.
  번역에서 문장을 다듬는다며 이 조건절을 빠뜨리면 그 감사가 무효가 됩니다.
- **텔레컨버터가 특정 초점거리로 고정되지 않는다**는 점을 유지했습니다. 컨버터는 선택형 조합입니다.
- **상표 귀속 문장을 전부 옮겼습니다.** Apache-2.0은 상표권을 부여하지 않으므로(6조) 이 문장은
  법적 고지이지 장식이 아닙니다.

## Required graphic assets

| Asset | Spec | Source |
|---|---|---|
| Hi-res app icon | 512×512 PNG, 32-bit with alpha | `docs/assets/play/icon-512.png` (generated) |
| Feature graphic | 1024×500 PNG/JPG, no alpha | `docs/assets/play/feature-graphic.png` (generated) |

Regenerating the feature graphic from its SVG (macOS; the SVG is a 1024×1024 canvas whose design
sits in the centre 500-row band, so it renders edge-to-edge and is then cropped):

```bash
cd docs/assets/play
qlmanage -t -s 1024 -o /tmp/fg feature-graphic.svg
python3 - <<'EOF'
from PIL import Image
im = Image.open('/tmp/fg/feature-graphic.svg.png')
# RGB, not RGBA: Play REJECTS a feature graphic that carries an alpha channel.
im.crop((0, 262, 1024, 762)).convert('RGB').save('feature-graphic.png', optimize=True)
EOF
```

The tagline names no specific optic. It said "300 mm afocal teleconverter" while the app targeted
one phone and one kit; the converter became a selectable pair, so a fixed focal length on the
store's largest asset advertised a limit the app does not have.

| Phone screenshots | >=2, PNG/JPG, 320-3840 px, max side <=2x min side, no alpha | `docs/assets/play/screenshots/` |

### Screenshots captured on PMA110

> **CURRENT — recaptured 2026-07-27 (cycle 9).** Six 1440 x 2880 (exactly 2:1) 24-bit no-alpha
> PNGs. Play rejects anything taller than 2:1 and this panel is 1440 x 3168, so the crop box
> `(0, 168) → (1440, 3048)` strips the OS status bar and gesture bar only. Provenance is mixed on
> purpose: the viewfinder frames are handheld captures against real subjects, the menu frames come
> from the signed release build. Capture PORTRAIT — the app counter-rotates glyphs by the
> gravity-derived orientation, so a landscape-held capture renders the focal rail sideways. Full
> rationale and the per-file measurement live in `docs/play-console-submit.md`.

1. `docs/assets/play/screenshots/01-main-viewfinder.png` - photo viewfinder against a real subject: OSD row, focal rail, mode carousel, shutter.
2. `docs/assets/play/screenshots/02-pro-settings.png` - Shooting tab: output format, aspect, zoom, JPEG quality, drive mode, self-timer.
3. `docs/assets/play/screenshots/03-focus-tools.png` - Focus tab: AF modes, spot size, AF lock, peaking level/colour.
4. `docs/assets/play/screenshots/04-video-controls.png` - video viewfinder: REC control with the live HLG / STEADY encoder OSD.
5. `docs/assets/play/screenshots/05-lens-and-tele.png` - TELE engaged: the device-derived `13x / 30x / 60x` rail and the Loupe Overview with its framing hint. The one black-scene frame; swap in a handheld TELE capture to match the others.
6. `docs/assets/play/screenshots/06-video-settings.png` - Video tab: codec, Open Gate, resolution, FPS, bitrate, live encoder summary, transfer curves.

### Tablet screenshots

Optional in Play, but without them a tablet user sees a "may not be designed for this device"
notice. Four captured from the Lenovo TB331FC (1200x1920, smallestWidth 941dp — Play's 10-inch
bucket), system bars cropped, alpha stripped because `screencap` emits RGBA and Play rejects
screenshots carrying an alpha channel:

`docs/assets/play/screenshots/tablet/` — `02-shooting`, `03-focus`, `04-video-settings`, `05-lens`.

Both tablet slots ("7-inch" and "10-inch") accept any image meeting the size rules, and no tablet on
hand is under sw720dp, so the same set serves both. The two VIEWFINDER frames were discarded rather
than shipped: the tablet was pointing at nothing and they read as a black screen. Replace them with
captures against a real subject before relying on this set for large-screen featuring.

## Release checklist

1. Create the upload keystore and `keystore.properties` (see `keystore.properties.example`).
2. `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`; this now fails
   fast when signing credentials are missing so an unsigned bundle cannot be uploaded by mistake.
3. Play Console → create app → upload the signed AAB to an **internal testing** track.
4. Set pricing to **free**, paste the descriptions above, set category/rating, complete the Data Safety form from [`play-data-safety.md`](play-data-safety.md).
5. Add the privacy policy URL, contact email, icon, feature graphic, and screenshots.
6. Device catalog → see play-console-submit.md; the app is no longer restricted to two model codes.
7. Roll out internal testing, review the pre-launch report, then promote to production. A closed test
   remains optional for this 2015 developer account.

Console-ready summary: [`docs/play-console-submit.md`](play-console-submit.md).

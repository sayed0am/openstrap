# 02 – Historical Data Sync (GDSP)

Status: clean-room specification, written only from static reading of the decompiled official Zepp Android app (`work/jadx/sources`, `work/jadx/resources`). Nothing was executed and no third-party implementation was consulted.

Scope: how the app pulls stored health and workout history from the Amazfit Helio Strap (codename "Stuttgart", device source `10289410` / `10289411`) **after** the link is authenticated. Doc 01 covers transport, channel framing, auth and the services list. This doc builds on those.

Conventions

* `d/xyz.java:N` means `work/jadx/sources/defpackage/xyz.java`, line N. Other paths are relative to `work/jadx/sources/`.
* All multi-byte integers are **little-endian** unless stated otherwise.
* `u8/u16/u32` are unsigned; `i8/i16/i32` are signed.
* "HUAMI(xxxx)" is the 128-bit UUID `0000xxxx-0000-3512-2118-0009af100700` (`d/w4j.java:24,280-283`).
* **UNVERIFIED** marks anything the decompiled code did not show conclusively. The reason is given each time.

---

## 0. Summary for implementers

1. All history types on Zepp OS-era bands go through one generic engine that the app calls **GDSP** ("generic data sync protocol"). Each data kind has a **one-byte type code** (§2). The fetch flow is always the same:
   `START(type, since-time)` → header `{size, start time}` → `TRANSFER` → indexed data packets → transfer-complete status → `ACK`.
2. **Control path.** The control path is either:
   * legacy GATT: CP = HUAMI(0004), DATA = HUAMI(0005); or
   * on Zepp OS devices, the **channel endpoint `0x004B` (75, "GDSP module")**. In that case the control commands travel over the channel and the bulk **data still arrives as notifications on HUAMI(0005)**.
3. The phone stores a **per-device, per-type "last synced" timestamp**. Each fetch asks for records newer than it. The app loops on a type (fetch → parse → advance the cursor to just after the last record) until the device returns size 0.
4. Most payloads are fixed-size little-endian records. Workout summaries are **protobuf** (`sport_summary2.proto`, schema in §3.12). Workout details are a **TLV node stream** (§3.13).
5. Several "smart" metrics (BioCharge, Readiness, stress threshold, EDA, PAI channel) do **not** use GDSP. They come from other channel modules (§4).

---

## 1. Fetch mechanism

### 1.1 Endpoints

| Role | Legacy GATT | Zepp OS channel (preferred when listed) | Evidence |
|---|---|---|---|
| Control point (commands and responses) | HUAMI(0004), write + notify | Channel module **0x004B** (id 75). The same command bytes are carried as the channel payload. | `d/ktk.java:11-12`; `d/y4j.java:28-57`; `d/vyi.java:7`; `d/hyi.java:13-20`; `d/i8h0.java:194-211` |
| Bulk data | HUAMI(0005), notify | **Still HUAMI(0005)**. The `ktk` constructor always binds the data role to characteristic 0005 (or to a mapped "virtual" characteristic of the same UUID on non-GATT connectors), even when control uses the channel. | `d/ktk.java:15-43,205-219`; `d/y4j.java:28-35`; `d/bok.java:565-568` |
| Service | `FEE0` (`d/qck.java:13`) | – | |

How the app chooses:

* If the device's channel services list contains module 75 (`vyi`), the app uses the channel control API ("GDSPControlApiV3", class `i8h0`). Otherwise it writes to HUAMI(0004) ("GDSPControlApiV2", class `ryi`) (`d/ktk.java:30-40`).
* Several newer jobs **only run** when module 75 exists **and** the type code appears in the device's GDSP `dataTypeMap` (§1.6) (`d/xqi.java:1850-1864`; `d/to30.java:27-38`). So for the strap, the channel path is the one the app expects.

Implement both paths, and prefer the channel.

### 1.2 Commands (phone → device)

All commands are one opcode byte followed by parameters.

| Opcode | Name | Payload after opcode | Evidence |
|---|---|---|---|
| `0x00` | QUERY_VERSION | none | `d/myi.java:5`; `d/ryi.java:110-122`; `d/hyi.java:22-35` |
| `0x01` | START (request data header) | see §1.3 | `d/jyi.java:5`; `d/ryi.java:22-41`; `d/hyi.java:18-20` |
| `0x02` | TRANSFER (start streaming data) | none | `d/nyi.java:5`; `d/ryi.java:105-107`; `d/i8h0.java:600-602` |
| `0x03` | ACK / STOP (finish this fetch) | optional 1 byte (see §1.5) | `d/oyi.java:5`; `d/ryi.java:145-149`; `d/i8h0.java:739-746`; `d/qaw.java:283-286` |
| `0x05` | QUERY_DATA_STATUS (**UNVERIFIED**: the opcode exists as a command object, and a `GDSPDataStatus{code, status-map}` result type exists, but no decompiled caller was found) | – | `d/lyi.java:5`; `d/syi.java` |

Device → phone, unsolicited (channel path only):

| Byte 0 | Meaning | Evidence |
|---|---|---|
| `0x11` | **Data-available notification**: `11 <type u8>`. The app reacts by starting an on-demand sync. Type 5 or 6 triggers the workout types {5, 6, 79}. Type 70 triggers recovery HR. Type 96 triggers life-load audio. | `d/kyi.java:5`; `d/ccf.java:668-695` |

### 1.3 START request (opcode 0x01)

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | `0x01` | opcode |
| 1 | 1 | `type` | data-type code (§2) |
| 2 | 2 | `year` u16 | local calendar year of the "since" instant |
| 4 | 1 | `month` | 1..12 |
| 5 | 1 | `day` | 1..31 |
| 6 | 1 | `hour` | 0..23 |
| 7 | 1 | `minute` | |
| 8 | 1 | `second` | |
| 9 | 1 | `tz` i8 | phone UTC offset **at that instant (DST included)**, in **15-minute units** |
| 10 | 1 | `flag` (optional) | Present when the GDSP "second version" is ≥ 2 on GATT. **Always present on the channel path.** The app sends `0x01` for every health/workout type, and `0x00` only for some log or raw-file pulls. Meaning: **UNVERIFIED** (see §1.5). |

Evidence: `d/ryi.java:22-41`; `d/x9h0.java:3823-3842` (date split and tz from `getOffset(t)`); `d/b9h0.java:1373-1378` (minutes → quarter-hours, sign kept); `d/hyi.java:18-20` and `d/i8h0.java:200` (channel form always appends the flag); `d/z5j.java:123-127` (flag only if the second version is ≥ 2); `d/k49.java:123` with `d/wyi.java:15-34` (flag = `true` for health jobs); `d/iqd.java:133` and `d/nqc.java:85` (flag `false` for EDA raw and some log pulls).

Timestamp semantics: the date fields are wall-clock time in the zone that `tz` gives. `epoch_seconds = civil_to_days(Y,M,D)*86400 + h*3600 + m*60 + s − tz*900` (`d/kug0.java:2675-2682`).

### 1.4 Responses (device → phone)

Every control response has this layout:

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `0x10` (response flag) |
| 1 | 1 | echoed opcode |
| 2 | 1 | status (`0x01` = success, see table below) |
| 3.. | n | data |

Evidence: `d/srk.java:18-44,64-72`; `d/ryi.java:46-50`.

**START response data (status 1):**

| Offset in data | Size | Field |
|---|---|---|
| 0 | 4 | `size` u32. Total payload bytes the device will stream. 0 = nothing newer. |
| 4 | 2 | `year` u16 |
| 6 | 1 | month |
| 7 | 1 | day |
| 8 | 1 | hour |
| 9 | 1 | minute |
| 10 | 1 | second |
| 11 | 1 | `tz` i8, quarter-hours |
| 12.. | n | optional "expansion" bytes. Only kept when the request carried the flag byte. |

The time in the header is the **timestamp of the first record in this chunk**. Minute-series types (activity, all-day stress, today-ext) give record *i* the time `header_time + i minutes`.

Evidence: `d/ryi.java:51-68`; `d/ktk.java:66-96`; `d/tva.java`.

The first expansion byte is turned into a completion hint that is passed to the job: 0→2, 1→1, 2→4, other→3, absent→1 (`d/k49.java:195-214`). Its meaning is **UNVERIFIED**. A plausible reading is "more data pending / done"; do not rely on it.

**Status codes** (enum `tyi`, `d/tyi.java`). Device-side values: 1 success, 2 unknown command, 3 invalid parameter, 4 operation failed, 5 invalid state, 6 packet-index error, 7 busy, 8 CRC mismatch, 9 cancelled by app, 10 content-parse error, 11 unauthorized, 12 compress failed, 13 decompress failed, 14 data-size mismatch, 48 music running, 49 speech running, 50 timeout (app-side), 51 fast-recharge mode on. Negative values are app-internal.

**QUERY_VERSION response:**

* GATT: data byte 0 is the GDSP version (`d/ryi.java:110-122`).
* Channel: the response decodes to `{version, supportDataUpload(bool), supportQueryDataStatus(bool), dataTypeMap}` (`d/p3d0.java:5-32`). The app uses the map keys as the set of type codes this device supports (`d/xqi.java:1860-1863`). The byte layout of the map is **UNVERIFIED**: the decoder (`d/apd.java:719`) did not decompile. Capture it from a device and parse it defensively.
* If the query fails, the app assumes version 3 (`d/p3d0.java:30-32`).
* The app first decides whether to send the query at all from a band-feature bit (feature-set 1, bit 4) → "GDSP version" 0 or 1 (`d/lhc.java:17-23`; `d/nqc.java:35`). It only queries when this is ≥ 1 (`d/z5j.java:81-98`).

### 1.5 Data transfer and acknowledgement

Sequence for one fetch (one "chunk"):

```
phone → CP : 01 type Y Y M D h m s tz [flag]
dev   → CP : 10 01 01 size(4) Y Y M D h m s tz [exp...]
  (size == 0 → done with this type; no TRANSFER needed)
phone → CP : 02
dev   → DATA (0005) notifications: idx(1) payload(...)   (repeated)
dev   → CP : 10 02 <status> [...]                          (transfer complete)
phone → CP : 03 [param]
dev   → CP : 10 03 01
```

* **Data packet**: byte 0 is a rolling packet index (0..255, wraps to 0). The rest is payload. The app concatenates payloads in order. A packet whose index is not previous+1 (mod 256) is logged as a gap ("Miss data" / "missing package"). In the newer engine, a gap ends that fetch, which is then retried (`d/b0c.java:2593-2609`; `d/vyk.java:179-200`; `d/mxk.java:183-193`).
* **Completeness**: the app requires `bytes received == size` from the header. Otherwise the fetch fails ("data miss") and is retried. The retry count is 3 (`d/e24.java:216-225`; `d/xq3.java:9`; `d/k49.java:160-164`; `d/xyk.java:99-121`).
* **End**: a `10 02 xx` notification on the control path ends the stream. `xx = 01` means success (`d/i4c.java:949-952`; `d/ccf.java:692-694`). The app also gives up after **20 s without data** ("long time no data") (`d/bwh.java:1042-1078`; timeout constant `20000`).
* **CRC**: the newer engine (`h5j`) keeps a `java.util.zip.CRC32` per fetch, and status 8 means "CRC mismatch". So a CRC-32 (zlib polynomial) is verified somewhere. **UNVERIFIED**: where the device's CRC value travels and what it covers. The routine did not decompile (`d/h5j.java:10-20,104-115`; `d/w5j` lambdas in `d/z5j.java:133-210`). The most likely carrier is extra bytes after the status in the `10 02 01` notification, covering the concatenated payload without index bytes. Implement it like this: if `10 02 01` carries ≥ 4 extra bytes, compare them (u32 LE) to CRC-32 of the payload. Log mismatches but do not discard until this has been confirmed on hardware.
* **ACK (0x03)** tells the device the chunk was consumed. The app sends it after every completed transfer (`d/qaw.java:283-286`; `d/ktk.java:185-187`). In the readable fetcher, a size-0 header ends the fetch **without** TRANSFER or ACK (`d/e24.java:216-220`). Whether the newer engine ACKs after an empty header is **UNVERIFIED**, and it is harmless either way. The optional single parameter byte (`oyi.c(n)`, `d/ryi.java:145-149`) is used by the undecompiled engine. Its value and semantics are **UNVERIFIED**.
* **"Do not delete" option**: nothing in the readable code shows an explicit "keep data on device" flag. The candidates are the START `flag` byte (§1.3; `true` for all health data, `false` for EDA-raw/log pulls) and the ACK parameter. **UNVERIFIED** which one, if either, controls deletion. The app never relies on deletion: it always moves its own cursor (§5) and asks for "records after T". So a client that always re-requests from its own cursor still gets correct results. Recommendation: send the START flag `0x01`, and send `03 01` after every START, including size-0 replies. A bare `03` is ignored and an un-ACKed START blocks the next one (see "Verified on hardware" below).

  **Verified on hardware (3.25.0.3, 2026-10-18): `03 01` deletes nothing.** Records acknowledged with `03 01` in one sync were returned again 15 minutes later by a fetch from 60 days back: four HRV records and the newest sleep record, byte for byte. The ACK parameter doesn't control deletion, and no "keep" value (Gadgetbridge sends `03 09`) is needed.

### 1.6 The fetch loop per type

Generic engine `k49` (`d/k49.java:62-215`):

1. `cursor = stored last-sync time for (device, type)` (§5).
2. START(type, cursor). If the header size is 0, stop.
3. TRANSFER, collect, verify, ACK.
4. Parse the payload with the type's parser (§3). `next = parser.nextStart(header, records)`. For most types this is **last record time + 1 s**. For minute series it is `header_time + N minutes (+1 s)` (`d/p40.java:159-279`; `d/l5j.java:60-88`; `d/a5j.java` per variant).
5. If `next` is later than the cursor, set the cursor to `next` and go to step 2. Otherwise stop. Some types (sports-effect 59, PROTO) never loop (`d/p40.java:283-288`; `d/t5j.java:37-42`).

---

## 2. Data-type codes

The app has two code tables that agree: the sync-time key enum `b290` (`d/b290.java:18-87`) and the GDSP job set (`gdspType()` of each job). The enum labels for 57, 61, 66, 67, 71, 74, 75, 83, 86, 87 and 89 were garbled by the decompiler. Names marked with † were reconstructed from the job that uses the code.

Strap column: **Y** = strap-relevant and expected (the job is in the band loader for this device class, and the metric exists on the strap). **?** = the job runs only if the device lists the type in `dataTypeMap`. **–** = not applicable (needs a screen, GPS, ECG, scale, and so on).

| Code | Name | Strap | Parser / job (evidence) | Record format |
|---|---|---|---|---|
| 1 | ACTIVITY (minute samples) | Y | `d/wak.java:10-13`, `d/p40.java:305-311`, `d/ihz.java` | §3.1 |
| 2 | HEART_RATE (manual/spot HR log) | ? | `d/gqm.java:27-40`, `d/a5j.java:340-344`, `d/o40.java:111-143` | §3.4 |
| 3 | ECG | – | `d/b290.java` | – |
| 4 | TEMPERATURE (legacy) | ? | `d/g9h.java:616`, `d/a5j.java:357-361`, `d/z4j.java:174-200` | §3.10 |
| 5 | SPORT_SUMMARY | Y | `d/fyk.java`, `d/xyk.java:91-110` | §3.12 |
| 6 | SPORT_DETAIL | Y | `d/xyk.java:120-133` | §3.13 |
| 7 | LOG (device log file) | – | `d/nqc.java:85` | opaque file |
| 8 | PPG_RR / AF RR | – | `d/dot.java:434` | – |
| 11, 17, 30 | AF PPG / AF result / AF ACC | – | `d/dot.java:434` | – |
| 13 | PAI | Y | `d/jsk.java`, `d/l5j.java:15-20`, `d/azk.java:7-70` | §3.9 |
| 14 | SPORT_SUMMARY (alt code for some sources) | – | `d/xyk.java:91` (only device list `d/xgk.java:17-21`, which excludes the strap) | §3.12 |
| 18 | PRESSURE_SINGLE (manual stress) | Y | `d/ysk.java`, `d/l5j.java:100-105`, `d/o5j.java:38-58` | §3.6 |
| 19 | PRESSURE_ALL_DAY (stress per minute) | Y | same | §3.6 |
| 37 | SPO2_SINGLE (spot / auto) | Y | `d/ah60.java:11`, `d/wg60.java:101-136` | §3.5 |
| 38 | SPO2_OSA (sleep SpO2 samples) | Y | `d/ah60.java:11`, `d/wg60.java:60-99` | §3.5 |
| 39 / 43 | SPO2_OSA_EVENT (43 only on one source) | ? | `d/eh60.java:20-58`, `d/wg60.java:138-180` | §3.5 |
| 44 | DEVICE_STATISTIC | – | `d/d1d.java` | – |
| 46 / 47 / 48 | BODY_TEMPERATURE all-day / single / 80 s | ? | sync-time keys only (`d/o00.java:808`). No GDSP job found. | **UNVERIFIED** |
| 52–55, 62 | Blood-pressure family | – | `d/dot.java:520`, `d/z33.java`, `d/b43.java` | – |
| 56 | SLEEP_BREATH_RATE_DATA (sleep respiratory rate) | Y | `d/avk.java`, `d/p40.java:18-50`, `d/o40.java:52-79` | §3.8 |
| 57 | SLEEP_RESPIRATORY_RATE_EVENT† | ? | `d/p40.java:23-27`, `d/o40.java:80-110` | §3.8 |
| 58 | RESTING_HEART_RATE (daily) | Y | `d/m520.java:34`, `d/p40.java:49-53`, `d/o40.java:220-247` | §3.7 |
| 59 | SPORT_EFFECT | ? | `d/mn70.java` | not specified |
| 61 | DAILY_MAX_EXERCISE_HR† | ? | `d/y4t.java:69`, `d/p40.java:37-41`, `d/o40.java:145-173` | §3.7 |
| 66 | SLEEP_PLAN† | ? | `d/xq50.java`, `d/a5j.java:346-349`, `d/vp50.java:35-50` | not specified |
| 70 | RECOVERY_HEART_RATE | Y | `d/quk.java:70`, `d/p40.java:43-47`, `d/o40.java:174-219` | §3.11 |
| 71 / 82 | HLD / HLD_COMPOSITION (body composition) | – | `d/nak.java`, `d/lak.java`, `d/z4j.java:202-258` | – |
| 72 | SLEEP_RESULT (on-device sleep staging) | Y | `d/n590.java:12-35`, `d/z4j.java:35-95,536-590` | §3.3 |
| 73 | HRV (RMSSD) | Y | `d/w2l.java`, `d/a5j.java:362-366`, `d/z4j.java:259-290` | §3.7 |
| 74 | OSA_RESULT† (ODI) | ? | `d/lwv.java`, `d/z4j.java:20-33,397-424` | §3.5 |
| 75 / 77 | JS weight v1 / v2† | – | `d/nll.java`, `d/o0p.java` | – |
| 76 | SEC_HR (per-second HR) | ? | `d/to30.java`, `d/z4j.java:449-535` | §3.2 |
| 78 | HRV_SDNN | ? | `d/a3l.java`, `d/z4j.java:291-318` | §3.7 |
| 79 | SPORT_VOICE_RECORD | – | `d/tm70.java` | – |
| 86 | REST_HR† (real-time resting HR + baseline) | ? | `d/s520.java`, `d/z4j.java:425-448` | §3.7 |
| 87 | SEC_HR_V2† | ? | `d/wo30.java`, `d/vo30.java:139-230` | §3.2 |
| 88 | TODAY_ACTIVITY_EXTENDED | ? | `d/n40.java`, `d/o40.java:28-51` | §3.1.3 |
| 89 | SOLAR_INTENSITY† | – | `d/l03.java`, `d/t760.java` | – |
| 96 | LIFELOAD_AUDIO | – | `d/orq.java` | – |
| 84 | EDA_RAW_DATA (raw file) | ? | `d/iqd.java:133` | opaque file |

Device-feature gates the app checks before running a job: feature-set 2 bit 65 → 72, bit 66 → 73, bit 68 → 74, bit 63/72 → 71/82, bit 25 → "versioned SpO2 payload", bit 52 → 61. The `dataTypeMap` membership check applies to 76, 78, 86, 87, 88 (`d/n590.java:24-31`; `d/w2l.java`; `d/lwv.java`; `d/eh60.java:43-48`; `d/xqi.java:1850`). Feature-set bytes are parsed by `d/qjk.java:8-56` (doc 01 covers how they are read).

App-side static capability config for the strap: `d/vl80.java:15-83` (both variants, device class `fqk`). `d/zhk.java:646-647` maps sources 10289410/10289411 to `MILI_STUTTGART(_W)`. The app sync list for this family is the "MILI" loader (`d/dot.java:429-551`).

---

## 3. Record formats

General rules:

* `ts` = u32 UTC epoch **seconds**.
* `tz` = i8 quarter-hours (`d/b9h0.java:1384-1395`; `d/kug0.java:2714-2729`).
* Parsers loop `while position < size − 1` over the concatenated payload. A trailing partial record is dropped. If a record fails to parse, the app discards **the whole chunk** for that type (`d/z4j.java:262-288` and the pattern repeats).

### 3.1 ACTIVITY (type 1)

**Verified on hardware (Helio Strap 3.20.0.1):**
- Records are 8 bytes. The START size field counts records, not bytes.
- **The first byte packs two values.** Low nibble = `mode`, high nibble = a firmware state that matches the `me:` field of the strap's `ALG_SED` log line. 0x73 = not worn and 0x76 = not worn while charging (both carry HR 0xFF); 0x70, 0x50, 0x60 and 0x40 are worn minutes.
- A chunk can end in all-0xFF records for minutes not yet written. Parsing stops at the first one, and the next START resumes at the strap's next written minute.
- Byte 4 (`retain`) was 5, 7 or 13 and bytes 5–7 were 0 while awake.

Payload = N back-to-back records, one per minute. Record *i* has time `header_time + i min`.

Record size `R` = band feature parameter key 0 (`vvg.e[0]`, from the feature blob, `d/qjk.java:44-52`; `d/vvg.java:14-20`). If the key is absent or ≤ 4, `R = 4`. One legacy product uses 3 (`d/ihz.java:43-69`).

| Byte | Field | Notes |
|---|---|---|
| 0 | `mode` (activity category) | constants: 0 silent, 1 walking, 2 running, 3 not worn, 4 REM, 5 NREM, 6 charging, 7 on-bed, 8 dream, 9 user sleep, 126 blank (`com/xiaomi/hm/health/dataprocess/DataConstant.java:5-28`) |
| 1 | `intensity` u8 | |
| 2 | `steps` u8 | |
| 3 | `hr` u8 | bpm. The app's "no HR" default is 254. **UNVERIFIED**: whether 255 also means invalid. Treat ≥ 254 as missing. |
| 4 | `retain` u8 | only if R ≥ 8 |
| 5 | `sleepPostureAndProb` u8 | only if R ≥ 8 |
| 6 | `sleepProbDeep` u8 | only if R ≥ 8 |
| 7 | `sleepProbRem` u8 | only if R ≥ 8 |

Evidence: `d/qz.java:6-9` and `d/fv.java:20-40` (argument reordering), `d/jv.java:11-19`, `d/wim.java:351-357` (mapping into `SportData(timeIndex, mode, activity, step, hr, retain, sleepPostureAndProb, sleepProbDeep, sleepProbRem)`), `com/xiaomi/hm/health/dataprocess/SportData.java:21-128`.

Bytes 4..7 feed the phone sleep algorithm (§4). Their bit-level meaning is not exposed in Java: **UNVERIFIED**.

Cursor: the next START time is `header_time + N minutes` (`d/p40.java:211-224`). After the whole loop, the app stores the end of the last fragment as the activity sync time (`d/wim.java:383-419`).

#### 3.1.3 TODAY_ACTIVITY_EXTENDED (type 88)

12-byte records, one per minute from the header time. If the payload length is not a multiple of 12, the whole payload is rejected.

| Byte | Field |
|---|---|
| 0 | version |
| 1 | distanceType |
| 2 | floorCount |
| 3 | runDuration |
| 4 | fatBurnDuration |
| 5 | standDuration |
| 6 | walkDuration |
| 7 | sitDuration |
| 8 | step |
| 9 | calories |
| 10–11 | distance u16, centimetres |

Evidence: `d/o40.java:28-51`; field names from `d/l40.java:20-40` (the toString order matches the constructor order). Units of the durations are **UNVERIFIED** (probably seconds within the minute).

### 3.2 Per-second heart rate

**Verified on hardware:** type 87 arrives zlib-compressed in 306-byte blocks (`ts u32, count u16, 300 slots`) holding about 89 real samples each. A short block sometimes repeats the next block's first second; keep the later value. Type 85 (no Zepp job) carries the same samples as 5-byte `ts u32, hr u8` records and isn't fetched.

**SEC_HR (76)**: 5-byte records `ts u32, hr u8` (`d/z4j.java:449-466`).

**SEC_HR_V2 (87)**: a sequence of blocks:

| Field | Size |
|---|---|
| `start_ts` u32 | 4 |
| `count` u16 | 2 |
| `hr[count]` u8 | `count`. Sample *k* has time `start_ts + k` s. Trailing `0xFF` bytes are padding and are trimmed. |

A block with `count = 0` is skipped (`d/vo30.java:139-230`). The app writes the payload to a temp file first because it can be large.

### 3.3 SLEEP_RESULT (type 72), on-device sleep staging

**Resolved from the decompiled app** (`SleepMode`, `ydn.java:365-450`, `wg50.java:125-131`, `mt50.java`):
- **Stage codes:** 4 = LIGHT, 5 = DEEP, 7 = AWAKE, 8 = REM. Any other code is an unused slot.
- **Time base:** all i16 times are minutes from local midnight of the day *before* the record's day, so `epoch = midnight(day) + (raw − 1440)·60`.
- **Record day:** `day = date(belongTime + 2 h)` in the phone's zone.
- **Version 0:** `mainSleepEnd` is one minute early; add 1.
- **Stage stop is inclusive** (seen on hardware, 2026-10-16): `stop` is the last minute a stage covers and the next stage starts at `stop + 1`, so a stage ends at `stop + 1`. One-minute stages have `start == stop`.
- **Naps can be withdrawn:** the strap may report a stretch of broken night sleep as a nap, then re-send the day with it folded into the main sleep and no naps (`hasShortSleep = 0`). Replace a day's naps on every re-send, and ignore naps that overlap the main sleep.
- **Main sleep vs naps:** main stages are the first `mainStageCount` slots. Naps are *every* nap slot with a valid code; the nap stage count is ignored.
- **Re-sends:** the strap re-sends a day as it updates it, so the last record for a day wins.

On hardware the strap writes an empty record at local midnight (`hasMainSleep = 0`, `mainSleepEnd = 0xFFFF`).

The strap computes sleep sessions itself when feature bit 65 is set (it is in the strap's static config list, `d/vl80.java:37`). Each record is 594 bytes, little-endian:

| Off | Size | Field |
|---|---|---|
| 0 | 4 | `timestamp` u32 |
| 4 | 4 | `belongTime` u32. The day the sleep is attributed to (epoch s). |
| 8 | 1 | `version` u8 |
| 9 | 1 | `hasMainSleep` bool (byte == 1) |
| 10 | 2 | `mainSleepStart` i16 |
| 12 | 2 | `mainSleepEnd` i16 |
| 14 | 2 | `onBedTime` i16 |
| 16 | 2 | `offBedTime` i16 |
| 18 | 1 | `isRemSupported` bool |
| 19 | 1 | `isShortSleepSupported` bool |
| 20 | 1 | `hasShortSleep` bool |
| 21 | 1 | `aveHr` u8 |
| 22 | 1 | `sleepScore` u8 |
| 23 | 1 | `shortSleepCount` i8 |
| 24 | 60 | 10 × ShortSleep `{start i16, stop i16, length i16}` |
| 84 | 1 | `mainStageCount` i8 |
| 85 | 1 | `shortStageCount` i8 |
| 86 | 250 | 50 × main-sleep Stage `{start i16, stop i16, type u8}` |
| 336 | 250 | 50 × short-sleep (nap) Stage `{start i16, stop i16, type u8}` |
| 586 | 2 | `remSleepLength` i16 |
| 588 | 2 | `lightSleepLength` i16 |
| 590 | 2 | `deepSleepLength` i16 |
| 592 | 2 | `wakeLength` i16 |

Evidence: `d/z4j.java:35-95` (reader), `d/xs50.java:31-56` and its toString (names), `d/ju40.java`, `d/ev50.java`.

Only the first `*Count` entries of each fixed array are meaningful.

**UNVERIFIED**:

* The unit and origin of the i16 times. They are probably minutes relative to `belongTime` or minutes of day: 16 bits cannot hold epoch values. Calibrate on hardware.
* The numeric mapping of stage `type` to light/deep/REM/awake. No decompiled consumer was found.
* How to handle versions: only `version` is exposed. The layout above was read for all versions.

### 3.4 Manual / spot HR (type 2)

6-byte records:

| Field | Size |
|---|---|
| `ts` u32 | 4 |
| `tz` i8 | 1 |
| `hr` u8 | 1 |

The whole payload is rejected if its length is not a multiple of 6 (`d/o40.java:111-143`).

### 3.5 SpO2 (types 37, 38, 39/43, 74)

If band feature bit 25 is set ("versioned"), **payload byte 0 is a version** `v` and the records follow it. Otherwise there is no version byte, and the legacy layout applies (`d/wg60.java:183-196`; feature check `d/og60.java:39`, `d/eh60.java:55`).

**Verified on hardware (Helio Strap 3.20.0.1):** type 37 arrives versioned (`02` + 65-byte records). unstrap does not read the feature bit yet, so it detects the version byte: byte 0 is 1 or 2 and the u32 at offset 1 is within two days of the START time.

| Type | Layout without version byte, or v = 1 | v = 2 | Evidence |
|---|---|---|---|
| 37 spot/auto | 5 B: `ts u32`, `b u8`. SpO2 = `b & 0x7F`. **bit 7 = isAuto** (automatic vs on-demand). | 65 B: `ts u32`, `b u8` (same split), `history[60]` u8 (60 preceding samples) | `d/wg60.java:101-136`; `d/tt00.java:41-52` (bit 7 → `isAuto`). The app keeps only values 80..100 and ts ≤ now (`d/tt00.java:150-162`). |
| 38 sleep SpO2 | 6 B: `ts u32`, `spo2 u8`, `time u8` | 30 B: `ts u32`, `spo2 u8`, `time u8`, `high[6]`, `low[6]`, `signalQuality[8]`, `extend[4]` | `d/wg60.java:60-99`; `d/gh60.java`, `d/hh60.java` |
| 39 OSA event | no version byte, or v = 2: 485 B: `ts u32`, `spo2Decrease u8`, `spo2[240]`, `hr[240]`. v = 1: 5 B `ts u32`, `descend u8` | – | `d/wg60.java:138-180` |
| 43 | 5 B `ts u32`, `descend u8` | – | `d/wg60.java:199-205` |
| 74 OSA result | per record: `belongTime u32`, `odiValidTime u32`, `odiRefined u32`, `odiScore u8`, `odiDispCode u8`, `version u8`, `odNumRefined u8`, then 240 × `{odTime u32, odValue i32, odDuration i32}` (2896 B total) | – | `d/z4j.java:20-33`; `d/kwv.java` |

Other versions are logged as "not support" and dropped.

**UNVERIFIED**: the meaning of `time` in type 38 (probably seconds of the measurement), and the meaning of the v2 sub-arrays beyond their names.

### 3.6 Stress (types 18, 19)

| Type | Layout | Evidence |
|---|---|---|
| 18 single | 5 B records `ts u32`, `stress u8`. The payload is rejected if its length is not a multiple of 5. | `d/o5j.java:38-58` |
| 19 all-day | 1 byte per minute: `stress u8` at `header_time + i min`. The cursor advances by N minutes + 1 s. | `d/o5j.java:22-36`; `d/l5j.java:60-88` |

Invalid-value sentinel: **UNVERIFIED**. The app stores the raw byte. Treat 0 and ≥ 254 as gaps in UI only after checking on hardware.

### 3.7 HRV and resting HR family

| Type | Record | Size | Evidence |
|---|---|---|---|
| 73 HRV | `ts u32`, `tz i8`, `rmssd u8` | 6 | `d/z4j.java:259-290`; names `d/v2l.java` (toString: timestamp, timezone, rmssd) |
| 78 HRV_SDNN | `ts u32`, `tz i8`, `sdnn u32` in 0.01 ms (verified on hardware; jadx shows tz first because R8 reordered the constructor) | 9 | `d/z4j.java:291-318`; `d/pum.java:9-13,31`; `d/n2i.java:167` (÷100) |
| 86 REST_HR | `ts u32`, `tz i8`, `realtimeRestHr u8`, `restHrBaseline u8`, `level u8` | 8 | `d/z4j.java:425-448`; `d/r520.java:11-37` |
| 58 RESTING_HEART_RATE | `ts u32`, `tz i8`, `hr u8` | 6 | `d/o40.java:220-247`; `d/f520.java` |
| 61 DAILY_MAX_HR | `ts u32`, `tz i8`, `maxHr u8` | 6 | `d/o40.java:145-173`; `d/u4t.java` ("MaxHeartRate") |

Units: RMSSD/SDNN are probably milliseconds (**UNVERIFIED**). `level` semantics are **UNVERIFIED**.

### 3.8 Sleep respiratory rate (types 56, 57)

* **56**: 8-byte records `ts u32`, `tz i8`, `breathRate u8`, `snr u8`, `channel u8` (`d/o40.java:52-79`; `d/bu5.java`).
* **57**: opaque 569-byte event records whose first five bytes are `ts u32`, `tz i8`. The app stores them raw for cloud processing (`d/o40.java:80-110`).

### 3.9 PAI (type 13)

**One record per fetch**: the whole payload is one structure. The engine loops with cursor = record time + 1 s (`d/l5j.java:40-58`). Byte 0 is the version `v`.

| Off | Size | Field |
|---|---|---|
| 0 | 1 | `version` |
| 1 | 4 | `time` u32 (epoch s) |
| 5 | 4 | `timezone` u32 (as read) |
| 9 | 28 | `activityScores` 7 × f32 |
| 37 | 4 | `lowZonePai` f32 |
| 41 | 4 | `mediumZonePai` f32 |
| 45 | 4 | `highZonePai` f32 |
| 49 | 2 | `lowZoneMinutes` u16 |
| 51 | 2 | `mediumZoneMinutes` u16 |
| 53 | 2 | `highZoneMinutes` u16 |
| 55 | 4 | `dailyPai` f32 |
| 59 | 4 | `totalPai` f32 |
| 63 | 2 | `restHr` u16 |
| 65 | 2 | `maxHr` u16 |
| 67 | 1 | `index` u8 |
| 68 | 1 | `type` u8 |
| 69 | 28 | `nextActivityScores` 7 × f32 (v ≥ 2) |
| +0 | 1 | `gender` (v ≥ 4) |
| +1 | 1 | `age` (v ≥ 4) |
| +2..+4 | 3 | `lowZoneLimit`, `mediumZoneLimit`, `highZoneLimit` u8 (v ≥ 5) |

`+0` is offset 69 when v < 2 and offset 97 when v ≥ 2. f32 = IEEE-754 LE. Evidence: `d/azk.java:7-70`; names from the `d/hsk.java` toString order. The app also logs and ignores a "realtime" record dated today (`d/l5j.java:44-56`).

### 3.10 Legacy temperature (type 4)

**Type 46 BODY_TEMPERATURE_ALL_DAY (verified on hardware):** one 8-byte record per minute from the START time: `env s16, touch s16, reserved[4]` (reserved is always `5A5A5A5A`). Values are in 0.01 °C, and 0x7FFF means no reading. `touch` is skin temperature and matches the strap's `HEA_TEMP min thermo` log lines. `env` was always 0x7FFF.

Byte 0 is skipped (presumably a version byte). It is followed by 5-byte records `temp u8`, `ts u32` (`d/z4j.java:174-200`). The temperature unit and offset are **UNVERIFIED**. The strap's wrist-temperature types 46/47/48 have sync-time keys but **no parser was found**: **UNVERIFIED** whether the strap uses GDSP for temperature at all.

### 3.11 Recovery heart rate (type 70)

Repeated blocks: `trackId u32` (workout id), `count u32`, then `hr[count] u8`. Parsing stops at the first truncated block (`d/o40.java:174-219`; `d/b910.java`). The cursor becomes `(trackId + 1)` seconds (`d/p40.java:240-251`), which implies `trackId` is the workout start epoch.

### 3.12 Workout summary (type 5): protobuf

Sync procedure (`d/xyk.java:60-150`):

1. START(5, cursor) → TRANSFER. If header size > 0, exactly **one workout summary** must arrive (bytes == size).
2. Then START(**6**, *same cursor*) → TRANSFER → that workout's **detail**.
3. Cursor := summary header time + 1 s. Repeat until the summary size is 0.

Summary payload framing (`d/psu.java:80-110`):

| Off | Size | Field |
|---|---|---|
| 0 | 2 | `summaryVersion` u16 |
| 2.. | n | body |

If `summaryVersion == 0x8000` (32768), the body is a protobuf `SportSummaryMessage` (`d/mph.java:1405-1430`). Other values (258, 516–520, ≥ 259) select legacy binary layouts, which are not documented here. The app overwrites the summary start time with the header time, and keeps the end time as start + (end − start) from the body (`d/psu.java:100-108`). Expect the strap to send 0x8000 (**UNVERIFIED**, but every Zepp OS-era path routes there).

Schema (proto2, from `resources/sport_summary2.proto`), restated as field-number tables. Top-level `SportSummaryMessage`:

| # | Name | Type |
|---|---|---|
| 1 | version | string (default "2.1"), required |
| 2 | record | Record, required |
| 3 | type | Type |
| 4 | distance | Distance |
| 5 | number | Number |
| 6 | downhill | Downhill |
| 7 | times | Time |
| 8 | floors | Floors |
| 9 | swim | Swim |
| 10 | pace | Pace |
| 11 | steps | Steps |
| 13 | altitude | Altitude |
| 14 | slope | Slope |
| 15 | swing | Swing |
| 16 | calories | Calories |
| 17 | rowing | Rowing |
| 18 | frequency | Frequency |
| 19 | heartRate | HeartRate |
| 20 | temp | Temp |
| 21 | other | Other |
| 22 | hrZone | HeartrateZone |
| 23 | slopeChart | SlopeChart |
| 26 | marathon | repeated Marathon |
| 27 | pressure | Pressure |
| 28 | goal | Goal |
| 29 | golf | Golf |
| 30 | power | Power |
| 31 | dive | Dive |
| 32 | skyDiving | SkyDiving |
| 33 | cadence | Cadence |
| 34 | pbInfo | repeated Pb |
| 35 | runPower | RunPower |
| 36 | climbing | Climbing |
| 37 | surfing | Surfing |
| 38 | fishing | Fishing |
| 39 | barometer | Barometer |
| 40 | timeMs | TimeMs |
| 41 | phn | PHN |
| 42 | assessScore | AssessScore |
| 43 | hyrox | repeated Hyrox |
| 44 | weather | Weather |
| 45 | runPosture | RunPosture |
| 46 | golfCourse | GolfCourse |
| 47 | hyroxZone | HyroxZone |
| 48 | crossfit | repeated Crossfit |
| 49 | recDive | RecDive |
| 50 | stryd | Stryd |
| 51 | thirdPartyData | ThirdPartyData |
| 52 | ltThData | LactateThreshold |
| 53 | equipmentData | SportEquip |
| 54 | extDevData | repeated ExternalDevice |
| 55 | hyroxSimulate | HyroxSimulate |

Sub-messages that matter for the strap ("req" = required; everything else optional):

* **Record**: 1 trackId u32 (req), 2 parentId u32 (req), 3 zone sint32 (req), 5 latitude sint32, 6 longitude sint32, 7 altitude sint32, 8 maxLatitude int32, 9 minLatitude int32, 10 maxLongitude int32, 11 minLongitude int32, 12 courseTitle string, 13 featureSwitch u64.
* **Type**: 1 type u32 (req, sport type), 2 subtype u32, 3 runningType u32, 4 runningProgram u32.
* **Distance**: 1 distance float (m), 2 displayType bool, 3 unit u32, 4 equivDistance u32, 5 expeditionDistance float.
* **Time**: 1 totalTime, 2 exerciseTime, 3 pauseTime, 4 restTimeBetweenGroups, 5 swimmingTime, 6 cyclingTime, 7 runningTime, 8 switchTime1, 9 switchTime2, 12 arDelayTime, 13 superMarathonRestTime, 14 superMarathonRunTime. All u32.
* **TimeMs**: 1..15 u64. These are millisecond versions of the time fields, in this order: durationOfDownhill, avgDurationOfDownhill, totalTime, exerciseTime, pauseTime, restTimeBetweenGroups, surfingTotalTime, maxDiveTime, totalDiveTime, avgDiveTime, avgSurfaceTime, totalSurfaceTime, durationOfClimb, superMarathonRestTime, superMarathonRunTime.
* **HeartRate**: 1 average, 2 maximum, 3 minimum. All u32.
* **HeartrateZone**: 1 type u32 (req), 2 heart repeated u32, 3 zone repeated u32.
* **Calories**: 1 cal u32.
* **Steps**: 1 averageCadence float, 2 maximumCadence float, 3 averageStride u32, 4 steps u32.
* **Pace**: 1 averagePace float, 2 bestPace float, 3 superMarathonAveragePace float, 4 avgEquivPace u32, 5 bestEquivPace u32, 6 bestLapPace u32.
* **Speed**: 1 average float, 2 maximum float. This message is defined, but no top-level field number references it.
* **Other**: 1 totalGroups u32, 2 maximumSpO2 u32, 3 minimumSpO2 u32, 4 aerobicTrainingEffect float, 5 anaerobicTrainingEffect float, 6 trainingLoadOfThisActivity u32, 7 VO2max u32, 8 bestRTPC int32, 9 worstRTPC int32, 10 averageRTPC float, 11 treadmillCaliFactor float, 12 weight float, 13 stepFreqFactor float, 14 ruckingWeight u32, 15 ruckingWeightUnit u32, 16 manualRPE u32.
* **Temp**: 1..12 float, in this order: avg/high/low ambient, avg/high/low water, avg/high/low body, avg/high/low air.
* **Altitude**: 1 highest sint32, 2 lowest sint32, 3 average sint32, 4 totalClimbDistance u32, 5 elevationGain u32, 6 elevationLoss u32.
* **Number**: 1 totalNumberOfJumps, 2 totalTimesOfRowing, 3 numberOfBreak, 4 maxNumberOfOnceJumps, 5 numberOfCrossfitRounds. All u32.
* **Pressure / Frequency / Cadence / RunPower / Barometer**: average / maximum (/ minimum) u32, numbered 1..3 in that order.
* **Goal**: 1 type u32 (req), 2 data float (req).
* **Pb**: 1 type u32 (req), 2 value u32 (req), 3 exactValue float.
* **Hyrox**: 1 hyroxType u32 (req), 2 finished bool (req), 3 useTime u32, 4 avgPace u32, 5 elapsedTime u32, 6 insight u32, 7 repeatIndex u32.
* **HyroxSimulate**: 1 totalMuscularExertion float, 2 totalCardiacExertion float, 3 totalExertion float, 4 calibDone u32, 5 totalInsight u32.
* **Crossfit**: 1 actionName string, 2 content string.
* **ExternalDevice**: 1 deviceID u32, 2 deviceName string, 3 deviceAddress string.
* **SportEquip**: 1 equipID string. **ThirdPartyData**: 1 dataSource string, 2 dataID string.

The remaining sub-messages (Downhill, Floors, Swim, Slope, Swing, Rowing, SlopeChart, Marathon, Golf, Power, Dive, SkyDiving, Climbing, Surfing, Fishing, PHN, AssessScore, Weather, RunPosture, GolfCourse, HyroxZone, RecDive, Stryd, LactateThreshold) are GPS/screen/sport-specific. Take their field lists from the proto resource in the same manner when needed. All their fields are optional scalars except `Marathon` (3 required u32: type, timeUsage, distance).

Treat unknown fields as skippable (standard protobuf rules). The app rejects bodies of ≤ 2 bytes and summaries without `type` (`d/mph.java:1405-1408`; `d/jm70.java:75-78`).

### 3.13 Workout detail (type 6): TLV node stream

For `summaryVersion == 0x8000`, the detail parser is "SportDetail800DataParser" (`d/sp60.java:41-60`). The payload is a flat sequence of nodes:

```
node := tag  length  value[length]
```

* **tag** (`d/isl.java:419-432,507-519`): read byte `b0`.
  * If `(b0 & 0x1F) != 0x1F`, then `tag = b0 & 0x1F` (single byte).
  * Otherwise keep reading bytes until one has bit 7 clear. Then `tag = Σ_k (byte_k & 0x7F) << (8·k)` over **all** tag bytes, including `b0`. Example: `1F 01` → 0x011F = 287.
* **length** (`d/p84.java:41-87`): read byte `L0`.
  * If bit 7 is clear, `length = L0`.
  * Otherwise `n = L0 & 0x7F` (≤ 3), and `length` is the next `n` bytes **big-endian**.

Node tags (`d/jm70.java:17-50`): 1 TimeStamp, 2 Base, 3 DiffGps, 4 Event, 5 Pace1, 6 Pace2, 7 Alt, **8 HeartRate**, **9 SpO2**, 10 FixedLap, 11 Lap, 12 Pressure, **13 Temperature**, 14 AltitudeCalibration, 15 StrengthGroup, 16 GolfGroup, 17 GolfRt, 18 RtPower, 19 Playground, 20 SwimTrip, 21 DownhillClimb, 22 DiveGroup, 23 Rtpc, 24 LabRunning, 25 LapSkyDiving, 26 RtDownSpeed, **27 RtCadence**, 28 RtDepth, 29 RunningPower, 30 ClimbingGroup, 31 RtBarometer, 287 DelayEvent, 543 Surfing, 799 LapFishing, 1055 AirPressure, **1311 RestTime**, 1567 AssessScore, 1823 RunPosture, 2079 GolfCourseGroup, **2335 SecLevelData**, 2591 Stryd, 2847 Equivalent, 3103 AltitudeSegOffset, 3359 HyroxLap, 3615 HyroxRepLap, 3871 HyroxBlock, 4127 HyroxBlockRound, 4383 HyroxBlockExercise, 4639 HyroxResult, 4895 HyroxCounting, 5151 ExpeditionData. Unknown tags are skipped ("Not support node", `d/sp60.java:414-418`).

Node values the strap is likely to use:

| Tag | Value layout | Evidence |
|---|---|---|
| 1 TimeStamp | 12 bytes: 4 unknown bytes, then `base u64` at offset 4. This sets the current base time for the following nodes. | `d/t570.java:183-185`; `d/fba0.java` |
| 8 HeartRate | `offset u16`, `hr u8`. Sample time = base + offset. | `d/im70.java:305-308`; `d/m3m.java` |
| 9 SpO2 | `offset u16`, `spo2 u8` | `d/im70.java:309-312`; `d/ch60.java` |

The unit of `base` and `offset` (ms vs s) is **UNVERIFIED**. The app adds them directly, and elsewhere divides the sum by 1000 to get seconds (`d/sp60.java:172-176`), which suggests milliseconds. Field layouts of the other node types are in `d/t570.java` / `d/im70.java` (one `case` per parser). They are out of scope for a GPS-less strap.

---

## 4. What is computed where

| Metric | Where | Inputs / notes | Evidence |
|---|---|---|---|
| Steps, intensity, per-minute HR, activity mode | Strap | type 1 | §3.1 |
| Sleep stages / sessions | **Strap** (type 72) when feature bit 65 is set (it is in the strap config). Otherwise the phone's native `libdataProcess` derives sleep from minute activity, including bytes 4..7. | phone algorithm inputs: activity minute arrays, HR arrays, user info, 30-day sleep history | `d/n590.java:24-31`; `com/xiaomi/hm/health/dataprocess/DataAnalysis.java:112-175` |
| SpO2, stress values, HRV (RMSSD/SDNN), resting HR, max HR, respiratory rate, per-second HR, recovery HR | Strap (raw results synced) | §3 | |
| PAI | Strap (type 13), **or** the PAI channel job `PaiChannelDataJob` (`pmx`) when `q9b0.o(device)`. A phone-side `libpai_jni` also exists. | phone algorithm inputs: per-sample HR/activity, user resting/max HR | `d/dot.java:482-500`; `com/huami/algorithm/pai/PaiAlgorithm.java:29-41` |
| BioCharge (body energy) | Fetched from the strap over **channel module 0x0051 (81)** with commands 0x00 (version), 0x02, 0x04 (data since ts), 0x06, 0x08, 0x0A, 0x0C, 0x0E, 0x10, 0x12, 0x14. Protocol versions 1/8/9. A phone native library `libcharge_jni` (`runChargeAlgo`) also exists. **UNVERIFIED** which side is authoritative for the strap. | phone algorithm inputs: per-minute stress, HR, activity, sleep flag and sleep stage arrays, resting HR, age, gender, UTC time/tz, previous charge, sleep summaries, naps, events, sports batch | `d/pi4.java:16`; `d/nh4.java`… `d/vh4.java`; `d/b63.java:186-201`; `d/zh4.java:20-22`; `com/huami/algorithm/charge/ChargeAlgorithm.java:18-22` |
| Readiness score / dynamic readiness / mental & physical battery | Jobs `ReadinessScoreJob`, `ReadinessDataJob`, `ReadinessDynamicScoreJob` (non-GDSP channel jobs) plus phone native `libreadiness` | phone algorithm inputs: stress, HR, activity, sleep flags and stages, stress thresholds, resting-HR history, age, nightly HRV / skin-temperature / RHR / AHI series, sleep scores | `d/yt00.java`, `d/ur00.java`, `d/ys00.java`; `com/huami/algorithm/readiness/ReadinessAlgorithm.java:20-26` |
| Stress threshold, EDA | Non-GDSP channel jobs `StressThresholdJob` (`ii80`) and `EDAJob` (`rke`). EDA raw file = GDSP type 84. | – | `d/ii80.java`; `d/rke.java`; `d/iqd.java:133` |

The wire format of these non-GDSP modules is **not covered in this document**. A future doc 03 should cover channel modules 0x0051 and the readiness/PAI modules.

---

## 5. Sync order and per-type cursors

### 5.1 Order used by the app for this device family

From the MILI job loader (`d/dot.java:426-551`; run in order by `com/huami/device/core/sync/impl/sync/mili/MiliSyncCenterImpl.java`). The order is:

1. SLEEP_PLAN 66 (if supported)
2. **SLEEP_RESULT 72**
3. AF group (–)
4. **ACTIVITY 1**
5. REST_HR 86
6. ReadinessScore
7. BioCharge group (wake-up, data, cal-data, insight)
8. HR manual 2
9. (`fqk` only) female-health precursor `ljk`
10. **SPORT 5+6**
11. StressThreshold
12. ReadinessDynamic
13. ReadinessData
14. **HRV 73**
15. **STRESS 18/19** (if the app-side flag is set)
16. **SpO2 37/38/39**
17. BioCharge life-load
18. LifeLoad audio 96
19. BioCharge exertion
20. **PAI 13** (or the PAI channel job)
21. FirstBeat or SPORT_EFFECT 59
22. female health
23. **SLEEP_BREATH_RATE 56/57**
24. blood-pressure group
25. **RECOVERY_HR 70** (if supported)
26. ZeppCoach
27. MAX_HR 61 (+57)
28. HLD 71
29. HLD comp 82
30. OSA 74
31. JSWeight 75
32. **SEC_HR 76**
33. **SEC_HR_V2 87**
34. JSWeightV2 77
35. Solar 89
36. **TODAY_EXT 88**
37. Voice memo 79
38. **HRV_SDNN 78**
39. statistic 44

Jobs skip themselves when their feature or `dataTypeMap` gate fails (§2).

**Recommended order for unstrap** (bold = strap-relevant):

1. ACTIVITY 1
2. SLEEP_RESULT 72
3. HRV 73
4. REST_HR 86 / RHR 58
5. SpO2 37, 38
6. STRESS 19, 18
7. SLEEP_BREATH_RATE 56
8. SEC_HR_V2 87 (or 76)
9. TODAY_EXT 88
10. HRV_SDNN 78
11. PAI 13
12. workouts 5/6
13. RECOVERY_HR 70
14. MAX_HR 61
15. OSA 39/74

Before running a type, gate it on the `dataTypeMap` from QUERY_VERSION. Run types sequentially: the engine is single-flight per device (`d/x9c.java:66-81` "syncing now").

### 5.2 Cursor storage

* **Table** `DeviceSyncTimeBean(deviceId TEXT, dataType INT, syncTime INT, localTime INT, timeZoneOffset INT, id PK)`. `dataType` is the GDSP code from §2. Negative codes are used for non-GDSP jobs (−1 readiness raw, −2 readiness score, −3 stress threshold, −4 EDA, −6..−10 BioCharge, −11..−14 body phase) (`d/f32.java:1067`; `d/b290.java:19-34`; `d/p3d.java`).
* The cursor is also mirrored to the cloud user setting `huami.mifit.user.settings.devices.synctime` as a JSON array of `{deviceId, dataType, syncTime, timeZone}`. On a fresh install the app seeds cursors from this setting (`d/u940.java:16-77`; `d/c790.java:79-150`).
* **Default** when no cursor exists: now − 2 days, with the hour, minute, second and ms set to 0 (`d/c790.java:219-233`). The job layer also falls back to the device's generic last-sync time (`d/m590.java:54-63`).
* **Update rule**: after a successful job, the cursor becomes the time returned by the type's `nextStart` (§1.6). That is last record + 1 s, or chunk start + N min for minute series. For activity it is the end of the last fragment (`d/wim.java:383-419`). Store `syncTime` in epoch ms together with the phone tz at write time.
* If the device returns records **older** than the cursor, the app keeps the cursor and stops (`d/k49.java:150-158`). If a header time repeats ("cyclic sync"), the app stops the loop without retrying (`d/k49.java:155-160`).

---

## 6. Implementation checklist

- [ ] After auth, read the services list. If module 0x004B is present, use channel GDSP; otherwise use GATT HUAMI(0004)/(0005).
- [ ] Subscribe to HUAMI(0005) notifications in **both** modes. Subscribe to HUAMI(0004) in GATT mode.
- [ ] Send `00` (QUERY_VERSION). Keep the `dataTypeMap`. Log the raw bytes, because the map layout is unverified.
- [ ] Implement START `01 type Y(2) M D h m s tz [01]`, with tz in quarter-hours including DST. Always append the flag `01` on the channel path, and on GATT when the version is ≥ 2.
- [ ] Parse `10 01 01 size(4) Y(2) M D h m s tz [exp]`. Treat status ≠ 1 as an error and map it with the `tyi` table.
- [ ] If size == 0, send `03 01` and finish this type. Skipping the ACK makes the next START fail with status 5.
- [ ] Send `02`. Collect DATA notifications: strip index byte 0 and check continuity mod 256. Stop on `10 02 xx` or after 20 s of silence.
- [ ] Verify `received == size`. If `10 02 01` carries 4 extra bytes, also check CRC-32 (log-only until confirmed).
- [ ] Send `03 01`. Expect `10 03 01`.
- [ ] Parse each type with §3. Compute the next cursor (§1.6). Loop until size 0. Retry a failed chunk up to 3 times.
- [ ] Persist the cursor per (device, type) only after the parsed records are committed to storage.
- [ ] Activity: get the record size from feature parameter key 0 (default 4, strap likely 8). Minute time = header time + i.
- [ ] SpO2: honour the version byte when feature bit 25 is set.
- [ ] Workouts: run summary 5 and detail 6 back-to-back with the same cursor. Handle the summary header `u16 version`, with 0x8000 meaning protobuf (§3.12). Parse the detail TLV (§3.13).
- [ ] Handle the `11 <type>` notification on module 0x004B by scheduling a sync of that type.
- [ ] Handle the open items from the hardware tests:
  - Sleep-result time units and stage codes
  - Activity bytes 4..7
  - HR-invalid sentinel
  - CRC location
  - Temperature types 46–48
  - `dataTypeMap` layout
  - Detail timestamp units

---

## Verified on hardware (Helio Strap, firmware 3.20.0.1, 2026-10-16)

* Control runs over channel endpoint `0x004B`. Data arrives on HUAMI(0005).
* **QUERY_VERSION reply layout:** `10 00 01 | version u8 | flag u8 | count u16 | count × {type u8, flag u8}`. The strap offers 35 types: 1, 2, 5, 6, 7, 8, 11, 13, 17, 18, 19, 30, 37, 38, 39, 44, 46, 48, 56, 57, 58, 59, 61, 63, 64, 66, 70, 72, 73, 74, 76, 78, 85, 87, 88.
* **START `size` counts records, not bytes, for activity (type 1) only.** For activity, size 2 means 16 bytes. Activity records are **8 bytes**. Every other type counts bytes, including skin temperature (46), which is also 8 bytes per minute: size 240 = 30 minutes. Measured on 3.25.0.3 (2026-10-18) for 13 PAI, 19 stress, 37 SpO2, 38 sleep SpO2, 46 skin temperature, 56 respiratory rate, 58 resting HR, 59 sports effect, 61 max HR, 72 sleep result, 73 HRV, 78 HRV SDNN, 87 per-second HR (inflated length) and 88 today-ext.
* **TRANSFER end `10 02 01 <u32>`:** the u32 is the CRC-32 (zlib) of the concatenated payload without index bytes. Confirmed on every type.
* **ACK must carry a parameter.** A bare `03` is ignored, and the strap later ends the transfer with `10 02 32` (timeout). `03 01` gets `10 03 01`.
* **Every START must be closed with an ACK, including size-0 replies.** Otherwise the strap keeps that type open and answers every other START with status 5 (INVALID_STATE).
* Stress values `ff` while on the charger, i.e. no reading.

### Verified on hardware (2026-10-16, worn strap)

- **ACTIVITY padding:** the transfer can end with all-`0xFF` 8-byte records for minutes not yet written. Drop them. Reading them as data gives 255 steps each and moves the cursor past real minutes.
- **SEC_HR_V2 is zlib-compressed:** the payload starts `78 01`, and the CRC covers the compressed bytes. The START size is the **inflated** byte count. After inflating, the §3.2 block layout matches: blocks of `count = 300` samples, with unwritten seconds as `0xFF`.

### Verified on hardware: retention (firmware 3.25.0.3, 2026-10-18)

One fetch per type from 60 days back (since 2026-08-19 09:11 UTC). The oldest record each type returned:

| Type | Oldest record (UTC) | Holds |
|---|---|---|
| 19 stress, 46 skin temperature, 88 today-ext | 08-19 09:11 | ≥ 60 days (the whole window asked for) |
| 72 sleep result | night of 08-19 | ≥ 60 days |
| 37 SpO2 | 10-15 20:50 | < 41 days |
| 73 HRV | 10-15 22:59 | < 41 days |
| 56 respiratory rate | 10-15 23:00 | < 41 days |
| 78 HRV SDNN | 10-15 23:28 | < 41 days |
| 38 sleep SpO2 | 10-15 23:30 | < 41 days |
| 58 resting HR | 10-16 02:09 | < 41 days |
| 1 activity | 10-16 14:05 | **about 43 hours** |

* The strap wasn't worn from 09-08 to 10-15 20:24. The long types still returned their August data from before that gap; the "< 41 days" types returned none of it, so they hold less than the 41 days back to 09-07.
* Activity is the shortest: it started at 10-16 14:05 although the strap was worn from 10-15 20:24. Sync per-minute activity at least daily, or minutes are lost.
* Whether the "< 41 days" types are short by design, or were cleared around 10-15 (for example by a firmware update), needs a repeat of this fetch after a few weeks of continuous wear.

# 01 – Transport, Authentication and Real-time Basics

Target device: **Amazfit Helio Strap**, codename *Stuttgart*. BLE names are "Amazfit Helio Strap" or "Stuttgart". Device sources are `10289410` and `10289411` (the latter is the `_W` variant).

This is a **clean-room** specification. Its only source is a static reading of the decompiled official Zepp Android app. Each table cites its evidence in an *Evidence* column, written as `file:line` relative to `work/jadx/sources/`. Almost all files are in `defpackage/`, which is abbreviated as `d/`. You do not need to open those files to implement this spec. The citations are there so a reviewer can check the claims.

Conventions used throughout:

* All multi-byte integers are **little-endian** unless stated otherwise.
* `u8/u16/u32/u64` are unsigned integers. `bool` is one byte, with `0x01` meaning true.
* `HUAMI(xxxx)` stands for the 128-bit UUID `0000xxxx-0000-3512-2118-0009af100700`.
* `SIG(xxxx)` stands for the Bluetooth-SIG base UUID `0000xxxx-0000-1000-8000-00805f9b34fb`.
* **UNVERIFIED** marks a claim that this source could not settle. The reason is always given next to it.

---

## 0. How the official app classifies the strap

The strap is **not** run through the app's Zepp OS *watch* stack. The app registers it as a member of the `MILI` (band) family (device sources 10289410 and 10289411). Its device class is the generic "MiLi Pro" device, which uses the "MiLi Pro" GATT profile. On top of that legacy profile, the device also uses the **"channel" transport** (Huami's 2nd-generation chunked transport, also used by Zepp OS) on characteristics `0x0016/0x0017`.

As a result, many basic services exist in two forms:

* a **legacy GATT characteristic** form, such as battery on `HUAMI(0006)`, and
* a **channel endpoint** form, such as the battery endpoint `0x0029`.

For each service, the app picks the channel endpoint if the device lists it in its supported-services list (§4), and otherwise falls back to the legacy characteristic. Your implementation should do the same. The *services list* is the single source of truth.

| Fact | Evidence |
|---|---|
| Device sources 10289410 / 10289411 belong to `MILI_STUTTGART` / `MILI_STUTTGART_W` in the MILI family | `d/zhk.java:646-647` |
| Strap descriptor: names "Amazfit Helio Strap" and "Stuttgart", device class `fqk` (log tag `HMMiLiProDevice`) | `d/vl80.java:22-27, 56-60` |
| The device class builds the MiLi Pro profile (`hvt`) on top of the base profile (`jv3`) | `d/fqk.java:233-238, 1006-1013`; `d/hvt.java:27` |

---

## 1. GATT layer

### 1.1 Services and characteristics used

| Service | Characteristic | Use | Properties used by app | Evidence |
|---|---|---|---|---|
| `SIG(FEE0)` (Huami main) | `HUAMI(0016)` | **Channel "down-link"**: the phone writes data chunks here. The device notifies **ACK/reply** packets here. The version query (§2.1) also goes here. | write, notify | `d/el4.java:7,30`; `d/nub0.java:612-640`; `d/dl4.java:69-75`; `d/yq70.java:545-549` |
| `SIG(FEE0)` | `HUAMI(0017)` | **Channel "up-link"**: the device notifies data chunks here. The phone writes its own ACK packets here. | write, notify | `d/el4.java:8`; `d/nub0.java:299-301, 633` |
| `SIG(FEE0)` | `HUAMI(0003)` | Legacy "config/command" characteristic. Request/response with a `0x10` reply header (§5.7). | write, notify | `d/fon.java:7,15-18`; `d/hvt.java:166-170, 350-375` |
| `SIG(FEE0)` | `HUAMI(0006)` | Legacy battery info (read and notify) | read, notify | `d/fon.java:8`; `d/hvt.java:152-160, 614-621` |
| `SIG(FEE0)` | `HUAMI(0007)` | Legacy real-time steps (not needed in v1) | read, notify | `d/fon.java:9`; `d/hvt.java:160-165` |
| `SIG(FEE0)` | `HUAMI(0008)` | Legacy user-settings (user profile) | write, notify | `d/h0l.java:19, 45-58` |
| `SIG(FEE0)` | `SIG(2A2B)` | Legacy current-time characteristic | read, write | `d/ohn.java:21`; `d/jv3.java:399-405, 709-717` |
| `SIG(FEE0)` | `SIG(FF01)` | Legacy 16/20-byte device-info blob. Only a fallback. | read | `d/gon.java:7`; `d/jv3.java:396, 322-341` |
| `SIG(180A)` (Device Information) | `SIG(2A28)` software rev, `SIG(2A27)` hardware rev, `SIG(2A25)` serial, `SIG(2A23)` system ID, `SIG(2A50)` PnP ID | Device info (§5.2) | read | `d/ohn.java:8-22`; `d/jv3.java:383-395, 222-296` |
| `SIG(180D)` (Heart Rate) | `SIG(2A37)` measurement | HR values (§5.6) | notify | `d/gok.java:8-10`; `d/eok.java:18-35` |
| `SIG(180D)` | `SIG(2A39)` control point | Huami-specific HR commands (§5.6, legacy path) | write | `d/gok.java:9`; `d/dok.java:105-127` |

`HUAMI(xxxx)` is built from the base `00000000-0000-3512-2118-0009af100700` with the 16-bit id inserted at bits 32-47 (`d/w4j.java:24, 280-283`).

### 1.2 CCCD / subscriptions

The app subscribes by setting `0x2902` to **notifications** (`01 00`) when the characteristic has the NOTIFY property, and otherwise to indications (`02 00`) (`d/s5.java:121-170`). The subscriptions it makes are:

1. `0x0016`: subscribed temporarily while querying the channel version (§2.1), then permanently by the v2 channel to receive ACK/"reply" packets (`d/dl4.java:69-75`).
2. `0x0017`: subscribed permanently to receive channel data (`d/nub0.java:633`).
3. `0x0006` (battery), `0x0003` (config, around each command) and `2A37` (during live HR) are subscribed on demand.

The app never calls `setWriteType`, so writes use the Android default for each characteristic (searched app-wide: no occurrence).

### 1.3 MTU and connection parameters

* `connectGatt(autoConnect=false, transport=LE)`, with a 45 s guard timer (`d/jv3.java:855-858`).
* **The app never calls `requestMtu` on this path.** The only `requestMtu` calls are in unrelated OTA libraries (`d/ln4.java:54`, `d/am0.java:149`). It also never calls `requestConnectionPriority` for this device (only `d/iy0.java:400`, an OTA library).
* The channel payload size starts at **20** bytes (`cfu` default, reset to 20 on each connect: `d/cfu.java:10-12`; `d/jv3.java:813-817`). It is later raised from a value the **device reports**:
  * if endpoint `0x0015` (ConnectModule) is supported: request `01` → reply `02 <u16 n>`, where ATT MTU = `n + 3` (`d/im9.java:30-32`; `d/nm9.java:5`; `d/km9.java:5`; `d/o3.java:507-530`);
  * otherwise the legacy command `0x13` on `0x0003` → reply `10 13 01 <u16 mtu>` (`d/hvt.java:232-256`).
  * The stored value becomes `mtu - 3`. Values outside 23..247 are ignored (`d/cfu.java:15-20`).
* Recommendation for our app: optionally call `requestMtu(247)`. Use the larger payload only after the device confirms it through one of the two queries above, and use 20 until then. Whether a larger *phone-requested* MTU is accepted by the strap is **UNVERIFIED**, because the official app relies on the MTU exchange the device starts itself.

### 1.4 Order of operations after the link is up

The order below is reconstructed from the BLE device manager (`d/il4.java`) and the GATT base (`d/s5.java`, `d/j5.java`). The main state-machine handler (`d/vt0.java:44`) failed to decompile, so the exact moment of steps 9-11 relative to each other is partly **UNVERIFIED**.

| # | Step | Evidence |
|---|---|---|
| 1 | Connect GATT (LE, no auto-connect). On Android 9 and later the app calls the hidden `BluetoothGatt.refresh()` and sleeps 2 s. | `d/j5.java:49-60`; `d/s5.java:107-119` |
| 2 | `discoverServices()`. Discovery has 15 s and 30 s watchdogs. | `d/j5.java:66-74`; `d/s5.java:387-388` |
| 3 | After discovery, sleep 2 s if discovery finished less than 500 ms after connect, or always on Android 14 and later. | `d/j5.java:79-92` |
| 4 | Resolve characteristics from §1.1. Init fails if `FEE0` or `0003/0006/0007` is missing. | `d/hvt.java:150-171`; `d/jv3.java:383-405, 347-381` |
| 5 | Check that `0016` and `0017` both exist ("channel supported"). | `d/el4.java:50-63`; `d/il4.java:300` |
| 6 | Query the channel version on `0016` (§2.1). | `d/el4.java:20-47` |
| 7 | Create the v1 or v2 channel object, which subscribes `0017` and, for v2, `0016`. | `d/il4.java:302-325` |
| 8 | Request the supported-services list on endpoint `0x0000` (§4). **This runs before auth, in plaintext.** | `d/il4.java:340-343`; `d/b46.java:23-29` |
| 9 | Bind only: if endpoint `0x0082` exists, read the authorization info (§3.4 step 0). | `d/il4.java:344-356` |
| 10 | Authenticate (§3). | `d/il4.java:359-363`; `d/fqk.java:637-647` |
| 11 | After auth: read device info (DIS), query MTU, sync time, set user info, and so on (§5). | `d/hvt.java:41-128, 232-256` |

First bind only: if `0016` has the READ property, the app *reads* it once to trigger OS-level BLE bonding ("invokePair") (`d/il4.java:371-406`). Whether reconnects require an existing LE bond is **UNVERIFIED**.

---

## 2. Channel transport ("2nd gen" framing)

### 2.1 Channel version query

1. Subscribe to `0016`.
2. Write `01` to `0016`.
3. The device notifies on `0016`:

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | `0x02` | reply marker. Anything else counts as failure. |
| 1 | 1 | `version` | `1` = v1 framing, `2` = v2 framing. Other values are unsupported. |
| 2 | 1 (opt) | `encryptEnable` (bool) | **If present**, the device supports the encrypted channel and the ECDH auth (§3). If absent, the value is null. |
| 3 | 1 (opt) | `supportBT` (bool) | |
| 4 | 2 (opt) | `featureFlag` u16 | bit0 tells the app to honour the "bound app ids" list in auth info (§3.4). |

Evidence: `d/el4.java:20-47`; `d/pn7.java:3-36`; `d/atf0.java:262-273`; `d/tn2.java:83-86`.

The strap is a 2024 device, so v2 with `encryptEnable` present is expected. That expectation is **UNVERIFIED**: the version is reported at runtime. The rest of this document describes **v2**. v1 differences are listed in §2.6.

### 2.2 Data chunk format (v2)

A *logical message* is `(endpoint: u16, payload: bytes)`. It is split into chunks that are written to `0016` (phone→device) or notified on `0017` (device→phone).

**First chunk** (flag bit0 set), header 11 bytes:

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `0x03` (packet type: data) |
| 1 | 1 | `flags` (see below) |
| 2 | 1 | `appId` = `0x00` |
| 3 | 1 | `sessionId` (u8) |
| 4 | 1 | `chunkIndex` (u8), always `0` for the first chunk |
| 5 | 4 | `totalLength` u32: length of the **plaintext** payload |
| 9 | 2 | `endpoint` u16 |
| 11 | n | payload bytes |

**Continuation chunk**, header 5 bytes:

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `0x03` |
| 1 | 1 | `flags` |
| 2 | 1 | `appId` = `0x00` |
| 3 | 1 | `sessionId` |
| 4 | 1 | `chunkIndex` |
| 5 | n | payload bytes |

**Flags:**

| Bit | Mask | Meaning |
|---|---|---|
| 0 | `0x01` | first chunk (extended header present) |
| 1 | `0x02` | last chunk |
| 2 | `0x04` | **ACK requested** from the receiver for this chunk |
| 3 | `0x08` | payload is **encrypted** (§3.6) |

Evidence: `d/wmh.java:17-31`; `d/ml7.java:22-33`; `d/sk7.java:146-148, 168-176`; `d/wo3.java:380-418`.

**Sender rules (phone):**

* `sessionId` is a per-device counter that increments by 1 (mod 256) for **every logical message**. The first message after the app starts uses 0 (`d/rm7.java:36-51`).
* `chunkIndex` starts at 0 and increments by 1 (mod 256) per chunk.
* Chunk payload size = `payloadMTU − 11` for the first chunk and `payloadMTU − 5` for the rest, where `payloadMTU` = ATT MTU − 3 (20 by default). Sending is refused if `payloadMTU <= 11` (`d/wo3.java:380-418`).
* `0x02` is set on the final chunk. On that chunk `0x04` is also set when "tail reply" is wanted. The app sets it for practically every request: `d/rt3.java:118-127, 162-165`, and always when encrypting (`d/sk7.java:96`).
* `0x04` is also set on every 16th chunk (chunk count `% replyFreq == 0`, `replyFreq` = 16) (`d/wo3.java:406-409`; `d/rt3.java:176-180`).
* `0x08` is set on **every** chunk of an encrypted message.

**Receiver rules (phone reassembling `0017` notifications):** `d/sk7.java:179-202`; `d/wo3.java:55-262`; `d/wsf0.java:574-595`

1. Drop the packet if byte[2] (`appId`) ≠ 0 ("not for this app"). Then remove byte[2] and parse the remaining v1-shaped header.
2. On a first chunk: start a new buffer keyed by `sessionId`, remembering `endpoint`, `totalLength` and `flags`. If the endpoint is not one we registered for, reply with ACK status `5` and drop the message.
3. A continuation chunk must have the same `sessionId` and `chunkIndex == previous+1 (mod 256)`. If not, the error is *session mismatch* (2) or *packet lost* (3).
4. Length check: for a plaintext message, accumulated bytes must never exceed `totalLength` and must equal it on the last chunk. For an encrypted message, the final size must be `ceil16(totalLength + 8)` (§3.6).
5. If a chunk carries flag `0x04`, write an ACK (§2.3) with status 1 (OK) or with the error code.
6. When the last chunk arrives: decrypt if `0x08` was set, truncate to `totalLength`, and deliver `(endpoint, sessionId, payload)` to the endpoint handler.

### 2.3 ACK packet

The phone acknowledges incoming chunks by **writing** to `0017`:

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `0x04` (packet type: ack) |
| 1 | 1 | `appId` = `0x00` |
| 2 | 1 | `sessionId` |
| 3 | 1 | `status` |
| 4 | 1 | `chunkIndex` being acknowledged |

(`d/sk7.java:174-176`; `d/nub0.java:299-301`)

The device sends its ACKs for the phone's chunks as **notifications on `0016`** (`d/dl4.java:69-75`; `d/yq70.java:545-549`). Their exact v2 layout could not be read because the v2 send/await routine did not decompile (`d/dl4.java:56-66`, `d/vo3.java:96-104`). The v1 reader expects 4 bytes `04 session status index` (`d/nub0.java:362-378`). The v2 layout is assumed to be the 5-byte format above, mirroring the phone's: **UNVERIFIED**. Write tolerant code: accept both 4- and 5-byte ACKs whose first byte is `0x04`.

Retry and timeout policy on a missing device ACK: **UNVERIFIED** (that code did not decompile). Suggested: wait 5 s for a requested ACK, then fail the message.

**ACK status codes** (wire value = name). From `d/ql7.java:17-47` with the ordinal-to-wire mapping in `d/lx6.java:143-148`:

| Code | Meaning |
|---|---|
| 0 | unknown |
| 1 | success |
| 2 | session id mismatch |
| 3 | packet lost (index not consecutive) |
| 4 | bad packet format / length |
| 5 | unknown endpoint |
| 6 | receiver busy |
| 7 | out of memory |
| 8 | not authorized |
| 9 | endpoint requires encryption |
| 10 | plaintext CRC32 mismatch (decryption failed) |
| 11 | illegal nonce |
| 12 | cannot decrypt |

### 2.4 Worked example (plaintext)

Send the services-list request (§4): endpoint `0x0000`, payload `03`, sessionId `0x00`, ACK requested on the last chunk:

```
03 07 00 00 00 01 00 00 00 00 00 03
│  │  │  │  │  └─total=1───┘ └ep┘ └payload
│  │  │  │  └ chunkIndex 0
│  │  │  └ sessionId 0
│  │  └ appId 0
│  └ flags = first|last|ack (0x07)
└ data packet
```

The device then notifies an ACK on `0016` (≈ `04 00 00 01 00`, **UNVERIFIED** layout), followed by a data message on `0017`, for example:

```
03 03 00 05 00 0A 00 00 00 00 00 04 03 00 ...
            ^sess        ^len=10 ^ep0 ^payload starts "04 ..."
```

If that chunk had `0x04` set, the phone answers on `0017` with `04 00 05 01 00`.

**Multi-chunk example:** a 52-byte payload (the auth step 1 request, §3.4) at the default payloadMTU of 20 is split into four chunks:

| Chunk | Header | Data bytes | Flags |
|---|---|---|---|
| 0 | 11 bytes | 9 | `0x01` |
| 1 | 5 bytes | 15 | `0x00` |
| 2 | 5 bytes | 15 | `0x00` |
| 3 | 5 bytes | 13 | `0x06` |

The first header carries `totalLength = 0x34`, `endpoint = 0x0082`.

### 2.5 Request/response conventions on top of the channel

Endpoints use one of two reply conventions:

* **"cmd+1" style.** Used by ConnectModule, battery, time, UserInfo, HrsModule, endpoint 0x001D, and the services list. The request payload starts with a command byte `C`. The reply payload starts with `C+1`, followed by data. Matched in `d/zbi.java:45-47`; services list `d/b46.java:68`, `d/n15.java:692-716`.
* **Auth endpoint (`0x0082`).** The request starts with a command byte `C`. The app's handler hands the caller `[status][data…]`. The wire prefix that is stripped could not be recovered: the handler `d/dv1.java:466-471` did not decompile. It is either `10 C` (the legacy `0x10`-reply convention also used elsewhere, `d/ga20.java:105-111`, `d/srk.java:19-44`) or `C+1`. **UNVERIFIED.** Implement both: if byte0 = `0x10` and byte1 = `C`, strip 2 bytes; else if byte0 = `C+1`, strip 1 byte.
* Request timeouts: 15 s for auth requests (`d/rt3.java:118-124`), 5 s for the services list (`d/a46.java:96-100`).

### 2.6 v1 differences (only if the device reports version 1)

* No `appId` byte: first header 10 bytes, continuation 4 bytes, ACK `04 session status index` (`d/tk7.java:6-31`).
* No encryption. The services list uses the v1 form (§4). Auth uses the non-ECDH variant (§3.7).

---

## 3. Authentication

### 3.1 Which auth variant applies

The variant is chosen per connection (`d/ram.java:105-123`):

| Condition | Variant |
|---|---|
| Endpoint `0x0082` **not** in the services list | "traditional" legacy auth over GATT (not channel). Not expected for this device; **not specified here**. |
| `0x0082` present **and** channel `encryptEnable` present (§2.1) | **ECDH auth + encrypted channel (§3.2-3.6)**. Expected for the strap. |
| `0x0082` present, no `encryptEnable` | Simple AES challenge (§3.7) |

### 3.2 Secrets and key material

* **Auth key**: 16 bytes, stored by the app as 32 hex characters. On the already-bound path it is hex-decoded directly (`d/ubk.java:2440-2445`). An older code path accepts a non-hex string and uses its MD5 instead (`d/fqg0.java:618-641`). Our app should accept 32 hex characters, with an optional `0x` prefix.
* In the official app the auth key is the **first 16 bytes of the ECDH shared secret computed during the cloud-signed first bind** (`d/ubk.java:2400-2417`). It does not change on later connections.
* **ECDH key pair**: the private key is **24 random bytes** (`SecureRandom`). The public key is **48 bytes**. The shared secret is at least 24 bytes, and the app uses bytes 0..23. Evidence: `d/ubk.java:1993-2016`; `d/sn2.java:50-71`.
* **Curve: NIST B-163 (sect163r2), confirmed from constants.** Point multiplication runs in native code (`libdevice-encrypt.so`, JNI `generatePublicKeyExternal(priv)` / `generatePrivateKeyExternal(priv, peerPub)`; `com/huami/core/device/feature/encrypt/impl/KeyShareApiImpl.java`, `d/iyb.java:90-93`). The library exports `ecdh_generate_keys` / `ecdh_shared_secret` and contains, back to back, B-163's base point Gx (offset 0x2a170), Gy (0x2a188), coefficient b (0x2a1c8) and order n (0x2a210). They are stored **little-endian in 24-byte slots** (163-bit values zero-padded to 24 bytes). K-163 constants are absent.
  * Field: GF(2^163) with reduction polynomial x^163 + x^7 + x^6 + x^3 + 1; curve y^2 + xy = x^3 + x^2 + b (a = 1).
  * **Private key**: 24 bytes, little-endian scalar. Only 163 bits are significant; reduce or clear the top bits (bits 163..191 zero).
  * **Public key** (48 bytes) = `X (24 bytes LE) || Y (24 bytes LE)`, with no format prefix.
  * **Shared secret**: the point `priv * peerPub`. The app uses bytes 0..23, i.e. the X coordinate, 24 bytes little-endian.
  * Byte order is inferred from how the constants are stored. Confirm it on a real strap: step 2 (§3.4) only succeeds with the right encoding.

### 3.3 Session (channel) keys derived at auth

From the shared secret `S[0..23]` and the auth key `K[0..15]` (`d/ubk.java:2018-2056`):

| Name | Derivation | Use |
|---|---|---|
| `sessionKey` (16 B) | `S[8+i] XOR K[i]` for i = 0..15 | proves the phone knows the key (step 2); base for the per-message AES key |
| `txCounter` (u32) | `S[0..3]` read as LE u32 | nonce for phone→device encrypted messages |
| `rxCounter` (u32) | `S[4..7]` read as LE u32 | last accepted device→phone nonce |

These values are only installed **after** the device answers step 2 with success. They are replaced on every authentication.

### 3.4 Already-bound reconnect (what unstrap implements)

All messages go to endpoint **`0x0082`** (decimal 130), unencrypted. Primitive: `AES(key, block)` = AES-128-ECB encryption without padding (`d/fqg0.java:470-486`).

| Step | Phone → device payload | Device → phone (after prefix strip, §2.5) | Evidence |
|---|---|---|---|
| 0 (optional, bind-time only) | `00` | `status` (1 = OK) followed by AuthorizationInfo (table below) | `d/tn2.java:36-86` |
| 1 | `04 02 00 02` + `phonePub[48]` (52 bytes) | `01` + `random[16]` + `devicePub[48]` (65 bytes) | `d/sn2.java:50-71`; `d/ubk.java:1995-2004` |
| — | Compute `S = ECDH(phonePriv, devicePub)` and derive §3.3 values | | `d/ubk.java:2005-2028` |
| 2 | `05` + `AES(K, random)` + `AES(sessionKey, random)` (33 bytes) | `status` (1 = authenticated) | `d/ubk.java:2031-2037`; `d/gj00.java:287-297` |
| — | If status = 1, install `sessionKey`, `txCounter` and `rxCounter` | | `d/ubk.java:2038-2056` |

The meaning of the constant bytes `02 00` (u16 = 2, probably an auth-protocol version) and the trailing `02` in step 1 is not documented in the app. Send them verbatim.

**AuthorizationInfo** layout (step 0 reply, after the status byte). Every field from `allowLocalBinding` on may be missing (`d/tn2.java:50-84`; `d/ip2.java:42-50`):

| Field | Size |
|---|---|
| version | u8 |
| apiLevel | u16 |
| allowLocalBinding | u8 |
| supportPrepareBind | bool |
| boundAppIdCount `n` | u8 |
| boundAppIds | `n` × u8 (honoured only if channel featureFlag bit0 is set) |
| supportDeviceReady | bool |
| supportNewBindingProcedure | bool |
| supportUnbindGotoSystemCleaningPage | bool |

**Auth status codes** (the status byte in step 1, step 2 and other replies). From `d/ag1.java:67-163`:

| Value | Name | Value | Name |
|---|---|---|---|
| 0 | OTHER | 0x20 | CANNOT_SAVE_KEY |
| 1 | SUCCESS | 0x21 | USER_REJECTED |
| 2 | INVALID_STATE | 0x22 | GENERATE_PUBLIC_KEY_HASH_FAILED |
| 3 | UNKNOWN_COMMAND | 0x23 | AES_ENCRYPTION_FAILED |
| 4 | OPERATION_FAILED | 0x24 | NO_MEMORY |
| 5 | SPORT_ONGOING | 0x25 | BINDING_AES_CIPHERTEXT_MISMATCH |
| 6 | INVALID_PARAMETER | 0x26 | AUTHORIZE_AES_CIPHERTEXT_MISMATCH (wrong auth key) |
| 7 | KEY_NOT_FOUND | 0x27 | GENERATE_ECDH_PRIVATE_KEY_FAILED |
| 8 | SIGNATURE_VERIFY_FAILED | 0x28 | GENERATE_ECDH_SHARED_SECRET_FAILED |
| 9 | API_LEVEL_UNSUFFICIENT | 0x41 | PROTECT_BINDING_ON |
| | | 0x81 | LOCAL_BIND_FORBIDDEN (cloud auth required) |

On failure the official app disconnects ("disconnect by app") (`d/dh0.java:1998-2006`).

### 3.5 Other auth-endpoint commands (informational)

| Payload | Purpose | Evidence |
|---|---|---|
| `06` | Request a 16-byte random. Reply: `status, random[16]`. Used before un-authorize. | `d/ubk.java:2087-2101` |
| `07` + `AES(K, rnd)` + `AES(sessionKey, rnd)` | De-authorize (unbind). Reply: status. | `d/sn2.java:35-43`; `d/ubk.java:2121-2147` |
| `08` | Bind-complete / "confirm" (only sent when channel version ≥ 2) | `d/ubk.java:1872-1878` |
| `09` | "Device ready?" query, sent only if `supportDeviceReady`. Status 1 means ready. | `d/sn2.java:18-33` |
| `0A` | Fire-and-forget notification (purpose unknown) | `d/sn2.java:45-48` |
| `0B` | Query. Status 1 means true (purpose unknown). | `d/sn2.java:97-101` |

When the official app sends 08/09/0A/0B during a normal reconnect is **UNVERIFIED** (the state handler `d/vt0.java:44` did not decompile). They do not appear to be required for data access. Do not send them in v1.

### 3.6 Encrypted channel messages

After auth, endpoints flagged `requireEncryption` in the services list (§4) are **always** sent encrypted, provided `encryptEnable` is true (`d/sk7.java:209-220`). The app accepts any incoming message whose flags contain `0x08`.

**Encrypt (phone→device)** (`d/tgf.java:50-69`, `d/sk7.java:86-97`):

1. `msgKey[i] = sessionKey[i] XOR sessionId` for all 16 bytes. `sessionId` is the channel sessionId of this message (§2.2) (`d/tgf.java:71-84`).
2. `plain = payload ‖ u32(txCounter) ‖ u32(CRC32(payload ‖ u32(txCounter)))`. CRC32 is the standard IEEE/zlib CRC.
3. Zero-pad `plain` to a multiple of 16 bytes.
4. `cipher = AES-128-ECB-encrypt(msgKey, plain)`.
5. Send `cipher` as the chunk data with flags `0x08` (and, on the last chunk, `0x02|0x04`). The first-chunk `totalLength` stays the **plaintext payload length**.
6. Afterwards, `txCounter = (txCounter + 1) mod 0xFFFFFFFF`.

**Decrypt (device→phone)** (`d/tgf.java:17-48`; `d/sk7.java:155-172`):

1. Reassemble until `ceil16(totalLength + 8)` bytes have arrived.
2. Decrypt with `msgKey` derived from the incoming message's sessionId.
3. Read `payload = first totalLength bytes`, then `cnt` = u32 and `crc` = u32.
4. Require `CRC32(payload ‖ u32(cnt)) == crc`.
5. Require `((cnt − rxCounter) + 0xFFFFFFFF) mod 0xFFFFFFFF ≤ 100000`, then set `rxCounter = cnt`. Otherwise reject (replay).

### 3.7 Simple AES variant (no `encryptEnable`)

Endpoint `0x0082` (`d/w2s.java:1374-1395`; `d/gj00.java:989-1012`; `d/w2s.java:332-339`):

1. Send `04 01 00 02`. Reply: `status, random[16]`.
2. Send `05 AES(K, random)`. Reply: `status`.

No channel encryption is used in this variant.

### 3.8 First bind (cloud), summary only

**This needs the Zepp cloud and is out of scope.** The already-bound path in §3.4 needs only the auth key.

1. Step 0 (AuthorizationInfo).
2. The phone sends `02 02 00 phonePub[48]`. Reply: `status, publicKeyHash[16], devicePub[48]` (65 bytes) (`d/sn2.java:74-95`).
3. The phone derives a value from the device's public key and the MAC address, hashes it with SHA-256, and has the **Zepp server sign it** (`onGetSignData`). It then sends the signature to the device. The send routine did not decompile (`d/ubk.java:2306-2365`).
4. On success, the new auth key = `ECDH(phonePriv, devicePub)[0..15]` (`d/ubk.java:2399-2417`), followed by a normal §3.4 authentication.

A cloud-free "local bind" exists only for devices that do not answer step 0. It sends `01 02 00 <16-byte key>` and the device asks the user to confirm (`d/ubk.java:2236-2258`; `d/gj00.java:110-119`). Code `0x81` says that local binding is forbidden. Whether the strap allows local binding is **UNVERIFIED**, but it presumably does not.

---

## 4. Supported-services ("module") list, endpoint 0x0000

Run this right after the channel is created, **before** auth (§1.4 step 8).

| Variant | Request | Reply payload | Evidence |
|---|---|---|---|
| v2 (when `encryptEnable` is present) | `03` | `04`, then `count` u16, then `count` × { `endpoint` u16, `requireEncryption` bool } | `d/b46.java:65-98`; `d/eu80.java` |
| v1 (no `encryptEnable`) | `00` | `01`, then `count` u16, then `count` × { `endpoint` u8 } | `d/b46.java:31-63` |

The parse is rejected if bytes remain after the list.

The device may later push changes: `05 op endpoint(u16) requireEncryption(bool)`, where op 1 = added and 2 = removed (`d/n15.java:692-708`).

Endpoint ids are the app's module ids (wire value = id). The ones relevant here and nearby:

| Endpoint | Name in app | Used in §5 |
|---|---|---|
| `0x0000` | Echo / services list | §4 |
| `0x0001` | Proxy | – |
| `0x000A` | Settings | – |
| `0x000D` | FileTransfer | – |
| `0x000F` | Alarm | – |
| `0x0015` | ConnectModule (MTU) | §1.3 |
| `0x0016` | RealtimeActivity | – |
| `0x0017` | UserInfo | §5.5 |
| `0x001D` | "OpenApp" (also real-time HR open/close) | §5.6 |
| `0x0029` | Battery | §5.1 |
| `0x0028` | (feature bits / device capability) | – |
| `0x0043` | DeviceInfo | – |
| `0x0047` | Time | §5.3 |
| `0x0048` | Hrs (HR stream subscription) | §5.6 |
| `0x0080` / `0x0082` | Auth (base / v2) | §3 |
| `0x0090` | Legacy-command tunnel (carries `0x0003`-style commands over the channel) | §5.7 |

Evidence: the `@ChannelModule(id=…)` annotations on each module class, for example `d/b46.java:10`, `d/im9.java:11`, `d/bqc0.java:16`, `d/erl.java:13`, `d/i44.java:8`, `d/yf6.java:10`, `d/mtm.java:9`, `d/sn2.java:10`, `d/o1w.java:8`, `d/fkc.java:7`, `d/mhc.java:7`. The channel module id is sent as the endpoint field (`d/kr3.java:71-75`; `d/wo3.java:396-411`).

Which endpoints the strap actually lists is **UNVERIFIED**. Log the list on first connect.

---

## 5. Basic services for v1

### 5.1 Battery

**Channel (if `0x0029` is listed)** (`d/i44.java:22-26`; `d/o34.java:20-27`; `d/b44.java`; `d/a44.java`; `d/o3.java:25-34, 288-309`; `d/lv10.java:246-248`):

| Request | Reply | Meaning |
|---|---|---|
| `01` | `02 version(u8) [supportsTrickleCharge(bool)] [supportsSolarCharge(bool)]` | Battery-module version. Only version 1 is used. |
| `03` | `04` + BatteryInfo | Get battery |
| — (device push) | `04` or `05` + BatteryInfo | Change notification |

**Legacy fallback:** read or subscribe `HUAMI(0006)`. The value is a raw BatteryInfo (`d/uwt.java:76-130`; `d/hvt.java:184-188, 614-621`).

**BatteryInfo** (`d/e44.java:30-75`):

| Field | Size | Present when |
|---|---|---|
| flags | u8 | always |
| level % | u8 | always |
| chargeState | u8 | flags & 0x01. 1 = charger connected/charging. With trickle support, 2 = full. |
| lastFullCharge | 8-byte DateTime | flags & 0x02 |
| lastCharge | 8-byte DateTime | flags & 0x04 |
| lastChargeLevel % | u8 | flags & 0x08 |
| batteryLife (minutes) | u32 | flags & 0x10 |
| chargeTime (minutes) | u16 | flags & 0x20 |

The 8-byte DateTime (`d/oqg0.java:798-805`) is: year u16, month (1-12), day, hour, minute, second, tz (signed, in 15-minute units).

### 5.2 Device info (firmware version, serial)

Read the standard Device Information Service characteristics (`d/jv3.java:222-296`):

| Characteristic | Content |
|---|---|
| `2A28` | Software revision, UTF-8 string with trailing NULs trimmed. This is the **firmware version**. |
| `2A27` | Hardware revision string |
| `2A25` | **Serial number** string |
| `2A23` | System ID. The first 8 bytes are rendered as hex. A 9th byte, if present, is a boolean flag. |
| `2A50` | PnP ID (7 bytes): source u8, vendorId u16, productId u16, productVersion u16 |

If the DIS read fails, the legacy fallback is `FEE0/FF01`: a 16- or 20-byte blob with a checksum in byte 7, and u32 fields at offsets 8, 12 and 16 (`d/jv3.java:316-341`). It is not needed if DIS works.

### 5.3 Time sync

**Time struct, 11 bytes** (`d/b9h0.java:1241-1264, 1373-1378`):

| Off | Size | Field |
|---|---|---|
| 0 | 2 | year (u16) |
| 2 | 1 | month 1-12 |
| 3 | 1 | day |
| 4 | 1 | hour |
| 5 | 1 | minute |
| 6 | 1 | second |
| 7 | 1 | day of week, 0 = Sunday … 6 = Saturday |
| 8 | 1 | milliseconds / 4 |
| 9 | 1 | `0x08` if DST is in effect now, else `0x00` |
| 10 | 1 | total UTC offset now (including DST) in **15-minute units**, signed |

Transport:

* **Channel endpoint `0x0047`** (if listed). Set: `05` + struct → reply `06 01` (1 = OK). Get: `03` → `04 01` + struct, where `01` is a status byte (`d/yf6.java:41-54`; `d/vf6.java`; `d/uf6.java`; `d/apd.java:448-472`). Verified on hardware (3.25.0.3, 2026-10-18): `04 01 ea07 0a 12 0d 0b 29 00 00 00 10` = 2026-10-18 13:11:41, Sunday, no DST, +04:00, matching the phone clock to the second.
* **Legacy fallback:** write the 11-byte struct to `FEE0/2A2B` (`d/lo3.java:14-25`; `d/jv3.java:709-717`).

### 5.4 Not applicable here

(Reserved. Language, alarms and similar are out of scope for this document.)

### 5.5 User profile

The strap computes calories and HR zones, so sending the profile is recommended. Whether it is *mandatory* is **UNVERIFIED**.

**Legacy first.** The app prefers this path whenever `FEE0/0008` exists (`d/o5d.java:50-68`). Write to `0008`, 16 bytes plus an optional alias (`d/h0l.java:123-136`; `d/b9h0.java:1266-1300`; `d/ppc0.java:22-33`):

| Off | Size | Field |
|---|---|---|
| 0 | 1 | `0x4F` |
| 1 | 1 | `0x00`, or `0x02` if an alias string follows (only for some models) |
| 2 | 1 | `0x00` |
| 3 | 2 | birth year |
| 5 | 1 | birth month (1-12) |
| 6 | 1 | birth day |
| 7 | 1 | gender: 0 male, 1 female, 2 unspecified |
| 8 | 2 | height (cm) |
| 10 | 2 | weight × 200 (weight in whole kg) |
| 12 | 4 | user id (u32) |
| 16 | n | optional alias + `00` |

**Channel endpoint `0x0017`** (if `0008` is absent). Set request: `01`, then `fieldMask` u32, then the fields in bit order (`d/bqc0.java:387-555`; `d/opc0.java:60-106`; `d/nci.java:130-141`; `d/zyr.java:13-56`; `d/vjq.java:68-79`):

| Bit | Field | Encoding |
|---|---|---|
| 0 | birthday | year u16, month u8, day u8 |
| 1 | gender | u8: 0 male, 1 female, 2 unspecified |
| 2 | height | u16 cm |
| 3 | weight | u16 = kg × 200 |
| 4 | step goal | u32 |
| 5 | wear location | u8: bits 0-6 = place (0 other, 1 chest, 2 wrist, 3 finger, 4 hand, 5 ear-lobe, 6 foot), bit7 = side (0 left, 1 right) |
| 6 | uid | u64 |
| 7 | blood pressure | 2 bytes |
| 8 | register region | lower-case ASCII + `00` |
| 9 | idc | 1 byte |
| 10 | nickname | UTF-8 + `00` |
| 11 | VIP info | u8 type + u32 end time |

The reply is `02 01` on success. Get: `03 FF 07 00 00` (all 11 bits) → `04 01 mask(u32) fields…`.

### 5.6 Real-time heart rate

The app picks the path in this order (`d/srg0.java:862-890`):

**Path A, channel (endpoint `0x0048` listed).** Evidence: `d/dh0.java:2347-2430`; `d/mtm.java:26-30`; `d/ktm.java`; `d/aq80.java:6-31`; `d/xzl.java:108-114`; `d/itm.java`; `d/ner.java:90-93`.

1. Open real-time HR on `0x001D`: `04 02`. The reply `05 00` means OK.
2. Subscribe on `0x0048`: `03 01`. Reply: `04 01` = OK.
3. The device then pushes `05 hr(u16)` messages on `0x0048`, about one per second.
4. Keep-alive: send `04 03` on `0x001D` every **10 s**, starting 10 s after the open.
5. Stop: `04 01` on `0x001D`, then `03 00` on `0x0048`.

The `0x001D` byte encoding is **UNVERIFIED** but strongly implied. The method that builds it did not decompile (`d/ar.java:64`). The app only names the action ("open/close/watchdog" = 2/1/3; `d/erl.java:127-129`, `d/dh0.java:2353, 2376, 2401`). In the same endpoint's command family, `0x04` is the only command code that is defined but never used elsewhere (`d/sel.java`). The `0x05` reply is routed into exactly the waiter that this call uses, and the reply byte `0` means success (`d/ygl.java:794-803`).

**Path B, legacy GATT** (no `0x0048`). Evidence: `d/dh0.java:937-1040`; `d/dok.java:105-127`; `d/gok.java:50-90`; `d/eok.java:18-35`; `d/b1m.java`; `d/a1m.java`; `d/z0m.java`.

1. Subscribe `180D/2A37` (notify).
2. Write `15 01 01` to `180D/2A39` (start continuous real-time).
3. Every **10 s**, write `16` to `2A39` (keep-alive). After 3 consecutive write failures, restart from step 2.
4. Stop: write `15 01 00` to `2A39` and unsubscribe `2A37`.

**HR measurement notification (`2A37`).** The app reads `bpm = byte[1]` (u8) and ignores the flags byte. This is the standard BLE HRS uint8 form. To be safe, honour flag bit0 (u16 value) per the BLE spec.

Note: in path A the app also opens `2A37` notifications (`d/gok.java:71-89`). Whether the strap streams through 2A37 or through the `0x0048` pushes when path A is active is **UNVERIFIED**. Subscribe to both and de-duplicate.

**All-day HR monitoring config** (legacy, `2A39`) (`d/dok.java:59-127`):

* `14 <minutes>` sets the all-day measurement interval. 0 = off.
* `15 00 <0|1>` disables or enables an extra mode. The app's settings name it "hrSleepEnable", i.e. sleep-time HR assist (`d/w8j.java:29`). The semantics are **UNVERIFIED**.
* `15 03 <0|1>` toggles atrial-fibrillation monitoring.

The channel-side settings equivalent (endpoint `0x000A`) is out of scope for this document.

### 5.7 Legacy command channel (`0x0003`) for reference

Write a command to `FEE0/0003` while subscribed. The reply notification is `10 <cmd> <status> <data…>`, where status 1 = OK (`d/hvt.java:350-375`; `d/srk.java:19-60`).

If endpoint `0x0090` is listed, the app tunnels these commands through the channel instead. The tunnel adds `01` in front of the legacy command (`d/hvt.java:350-353`; `d/l1w.java:224`). The tunnel's reply framing is **UNVERIFIED**.

Example: `13` → `10 13 01 <mtu u16>` (§1.3).

### 5.8 Buzzing the strap: find device (`0x001A`) and motor patterns (`0x0018`)

The strap has no screen and no notification module: `0x001E` NotificationModule is not in its services list, and Zepp leaves notifications off for it. unstrap buzzes it for phone calls and notifications with two endpoints the strap does list, both encrypted.

**`0x001A` FindDeviceModule** (`d/uhh.java`, impl `d/hhh.java`, receive `d/ccf.java:591-630`):

| Bytes | Dir | Meaning |
|---|---|---|
| `03` | P→S | Start buzzing; reply `04 01 01` (1 = OK). **Buzzes until `06`.** It does not stop by itself, and tapping the strap did not stop it (hardware, below). |
| `05 01` | P→S | Zepp's continuous find (`d/ghh.java:68-81`), used by its Find strap button with `06` to stop (`d/bgu.java`, `d/mys.java` case 7). Not answered. **The strap doesn't vibrate**, although it ACKs it and sends `07` after `06` (hardware, below). unstrap doesn't use it. |
| `06` | P→S | Stop. Not answered; the strap then sends `07`. |
| `07` | S→P | Buzzing stopped. |

**`0x0018` MotorVibrateModule** (`d/nau.java`, record `d/i0l.java`): `03 scene custom test nPairs {u16 LE}*`, reply `04 01`.

* The pattern alternates vibrate and pause milliseconds, zero-padded to an even count; `nPairs` = count / 2.
* `custom` = 1 uses the pattern; `test` = 1 plays it at once. It plays once and stops on its own.
* `scene` is the ordinal of Zepp's `VibrateType` (`com/xiaomi/hm/health/ui/smartplay/customvibrate/model/VibrateType.java`): 0 APP_NOTIFY, 1 INCOMING_CALL, 2 SMS, 3 EMAIL, 4 SPORT_REACH_GOAL, 5 ALARM, 6 LONG_SIT, 7 DISCONNECT, 8 EVENT_REMIND, 9 FIND_BAND, 10 TODO, 11 COUNT_DOWN, 12 SCHEDULE.
* Zepp's "test" sends the record with `test` = 1, then again with `test` = 0, which is the save (`d/ypk.java:396-407`). unstrap only sends the play, under scene 0, which the strap never uses on its own. So even if a play also saved, the alarm vibration is untouched.

Example: two short buzzes, 150 ms on / 150 ms off / 150 ms on: `03 00 01 01 02 9600 9600 9600 0000`.

How unstrap uses them:

* **Notification:** a `0x0018` pattern. If the strap doesn't list `0x0018`, it falls back to `0x001A` `03`, then `06` after 1.5 s.
* **Ringing call:** `0x001A` `03` when the phone starts ringing, `06` when it is answered or ends. It stops after 60 s at most.
* **No notification patterns while a call rings:** a pattern played on the motor cut off the call's buzzing (hardware, below).
* **Find strap** (strap page, shown when the strap lists `0x001A`): `03`, then `06` on Stop or after 60 s, the time Zepp's find dialog waits before it closes (`ktt.java:970`). The strap's `07` ends it too. Any `06` the app sends ends it as well, so a call ending meanwhile (which sends `06`) ends Find strap rather than leaving it until the timeout.

### 5.9 Settings the strap page uses: restart and the wear reminder (`0x000A`)

Settings writes are `05 group groupVersion 00 n {key type value}`, answered `06 01`, with the group version taken from a read just before (codemap 03 §3.6.1). unstrap uses three keys beyond the health group:

| Group / key | Type | Use |
|---|---|---|
| GENERAL `0x0A` / 45 (`0x2D`) | bool | **Restart.** Writing true restarts the strap: `05 0A 02 00 01 2D 0B 01` with GENERAL at version 2. The ack may not arrive before the link drops; the usual reconnect brings the strap back. Zepp shows Restart only when the strap reports key 45, and so does unstrap (read on connect). |
| GENERAL `0x0A` / 44 (`0x2C`) | bool | The strap's wear-reminder switch (`wearStatusAlertSwitch`). Zepp turns it on for the strap; unstrap does on connect while its Off-body reminder setting is on, writing only when the strap doesn't already report it on. |
| DEVICE_INFORMATION `0x00` / 16 (`0x10`) | bool | `wearStatus`: false while the strap is off-body. Read with the rest of the group on connect, and pushed in a `04` when it changes (codemap 07 §4 N5). unstrap logs the first push raw (`wear status push …`). |

None of the three has been checked on a strap yet: whether it reports keys 44 and 45, restarts on 45, and pushes key 16.

---

## 6. Keep-alive, disconnect, reconnect

* **No channel-level heartbeat** was found. The only periodic traffic is the 10 s HR watchdog while live HR runs (§5.6).
* Connect watchdog: the whole connect/init/auth sequence must finish within **120 s**, otherwise a CONNECTING_TIMEOUT is raised (`d/il4.java:490-506`). Each GATT operation has a 30 s timeout (`d/jv3.java:770-790`).
* On disconnect: the GATT is closed. If auto-reconnect is enabled and the adapter is on, the app waits **2 s** and reconnects from scratch (`d/jv3.java:138-160`). Status 133-style failures cause a re-scan before reconnecting (`d/il4.java:677-688`).
* All channel state (sessionId counter excepted), the session key and both counters are per-connection. A new connection needs a fresh services list and a fresh auth (§3.3).

---

## Implementation checklist

1. Scan for name "Amazfit Helio Strap" / "Stuttgart". Connect with LE transport, autoConnect=false, then discover services. Wait 2 s after discovery on Android 14 and later.
2. Resolve `FEE0{0016,0017,0003,0006,0008,2A2B}`, `180A{2A28,2A25,…}` and `180D{2A37,2A39}`.
3. Subscribe to `0016`, write `01`, and parse the version. Require v2. Note whether `encryptEnable` is present.
4. Keep `0016` (ACKs) and `0017` (data) subscribed. Implement the v2 chunker/reassembler, the ACK writer, the sessionId counter, and the 20-byte payload MTU (§2).
5. Send `03` to endpoint `0x0000` and store `{endpoint → requireEncryption}`.
6. Auth on `0x0082`:
   * send `04 02 00 02 pub48`, receive `random16 + devPub48`;
   * compute ECDH on B-163 (sect163r2), little-endian 24-byte coordinates and derive `sessionKey`, `tx` and `rx` (§3.3);
   * send `05 AES(K,rnd) AES(sessionKey,rnd)`;
   * require status 1, or report the code from §3.4.
7. Turn on AES-ECB + CRC32 + counter encryption for endpoints flagged `requireEncryption` (§3.6).
8. Optionally query the MTU via `0x0015` (`01` → `02 n`), then grow chunks to `n`.
9. Read DIS `2A28` (firmware) and `2A25` (serial).
10. Battery: use `0x0029` (`03` → `04 info`, plus `04/05` pushes) if listed, else `0006`.
11. Time: use `0x0047` `05`+struct if listed, else write the struct to `2A2B`.
12. User profile: use `0008` (`4F …`) if present, else `0x0017` `01 mask …`.
13. Live HR: use path A (`0x001D` + `0x0048`, 10 s keep-alive) if `0x0048` is listed, else path B (`2A39` `15 01 01` / `16` every 10 s). Read the bpm from `2A37`.
14. Log every UNVERIFIED point on a real device: ECDH byte order, auth reply prefix, device ACK layout, `0x001D` bytes, the strap's endpoint list.

---

## Verified on hardware (Helio Strap, firmware 3.20.0.1, 2026-10-16)

The first real connection, from unstrap on an Android 16 phone, confirmed the following:

* Channel version reply: v2 with `encryptEnable = true`.
* The services list arrives before auth. The strap lists `0000, 0081*, 0015, 0047, 0029, 0017*, 0028, 0043, 000a*, 0030*, 000d, 0048, 0016, 0022, 0049, 0032, 0036*, 001d, 0031, 0018*, 0082, 0025, 004b, 000c*, 004d*, 0019*, 000f, 001a*` (`*` = requires encryption).
* **ECDH on B-163, with 24-byte little-endian coordinates, works.** The §3.4 handshake succeeds with the Zepp-issued auth key.
* MTU query on `0x0015` → payload MTU 20.
* DIS `2A28` firmware string = `3.20.0.1`.
* Battery on `0x0029` works.
* Time set on `0x0047` → reply `06 01`.

### Verified on hardware: real-time HR and steps (2026-10-16)

- **Real-time HR (§5.6, path A):**
  - Open `04 02` on `0x001D` replies `05 03`, not `05 00`. Each `04 03` keep-alive replies `05 02`. Neither is an error.
  - Subscribe `03 01` on `0x0048` replies `04 01`.
  - Pushes `05 hr u16` arrive on `0x0048`, and standard `2A37` notifications arrive in parallel.
- **Real-time steps (endpoint `0x0016`, RealtimeActivity, `d/fz00.java`, `d/hz00.java`):**
  - Query `03` replies `04 01 record`. Notify on `05 01` replies `06 01 01`; off is `05 00`.
  - Record, little-endian: `flags u8, steps u32`, then:
    - `[bit0: running u32]`
    - `[bit1: walking u32]`
    - `[bit2&3: distance m u32, calories kcal u32]`
    - `[bit4: gait info]`
  - Totals count from local midnight. The strap pushes an update roughly every second while you walk.
  - Pushes use opcode **`07`** + the same record, e.g. `07 0c ed000000 b1000000 0e000000` = 237 steps, 177 m, 14 kcal (3.25.0.3, 2026-10-18).

### Verified on hardware: buzzing (firmware 3.25.0.3, Android 16, 2026-10-20)

- **`0x001A` `03`** → `04 01 01`. The strap buzzed until `06` was sent, and tapping the strap several times did not stop it. After `06` the strap sent `07`.
- **`0x001A` `05 01`** gave no reply, and the strap did not vibrate. It was retested alone on 2026-10-21: `05 01` (transport ACK OK), 10 s with nothing else sent, then `06`, answered by `07`. No vibration at any point.
- **`0x0018` with `test` = 1** → `04 01`. Patterns were felt as sent (e.g. `03 00 01 01 02 9600 9600 9600 0000` = two short buzzes), and each stopped on its own.
- **Latency:** within range, `03` was answered 0.3 s after the call started ringing, on a 30 ms connection interval, and `07` came 30 ms after `06`. Away from the phone, both took about 5 s, and the encrypted ACK for `03` never came. Asking Android for CONNECTION_PRIORITY_HIGH on ring didn't help: the new interval took effect only 3 s later.


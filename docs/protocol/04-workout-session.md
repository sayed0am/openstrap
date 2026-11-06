# 04 · Phone-started workouts on the strap (SportModule 0x0019)

How Zepp tells the strap that a workout started on the phone, so the strap runs a session of its own alongside: it may keep its own record (summary type 5, sport effect type 59) and signal when your heart rate is back to normal.

**Status: from static analysis of Zepp 10.9.0 only. Not yet verified on hardware.** unstrap implements it behind an experimental setting, off by default (`SportSession`, `StrapManager.sportSession`). Debug builds turn it on with:

```
adb shell am start -n org.openstrap.app/.MainActivity --ez strap_sessions true
```

Source: the teardown's codemap, `analysis/codemap/05-realtime-and-workouts.md` §5. Zepp classes: module `kb70`, state map `lb70`, SportInfo codec `vy60`, version model `q3d0`, caller `uv60` ("SportDeviceManager").

## 1. Framing

Endpoint `0x0019`. Every reply to a phone command is `10 <request opcode> <status> <data…>`; status 1 is OK. All numbers are little-endian.

| Opcode | Direction | Meaning |
|---|---|---|
| `01` | phone → strap | Version query |
| `02` | phone → strap | SportConfig: prepare a workout |
| `03` | phone → strap | State: START, PAUSE, RESUME, STOP, ABANDON |
| `05` | phone → strap | Keep-alive, every 5 s while paused |
| `06 00` | phone → strap | Query the strap's sport status (track id, sport type) |
| `11 <state>` | strap → phone | The strap changed the state itself |
| `12`/`14` | strap → phone | Live data (SportInfo) |
| `13 <status>` | strap → phone | Status; `13 00` = heart rate back to normal |

## 2. Version: `01`

Reply `10 01 01 <ver> <save> <report> <recoveryHr> <configFlag u32>`. Zepp accepts versions 1–3. `save` counts only from version 2. Missing trailing bytes read as 0.

## 3. SportConfig: `02 <type> 00 [save] [recoveryHr]`

- `type`: the strap's sport id. From version 3 it is the app sport id as is (the same ids as the summary's `Type.type`). Version 1–2 modules know only 1 run, 2 treadmill, 3 cycling, 4 walking, and Zepp maps walk 3→4 and cycling 4→3.
- The middle byte is Zepp's "needDeviceClock", always 0.
- `save` is sent only if the module supports it. Zepp sets it for version 3 and types 4 and up: the strap keeps its own record where the phone has no GPS track to keep.
- `recoveryHr` is sent only if the module supports it.

Example, outdoor run on a version 3 strap with save and recovery HR: `02 01 00 00 01`.

## 4. State: `03 <state> <SportInfo>`

States: 1 START, 2 PAUSE, 3 RESUME, 4 STOP, 5 ABANDON (sent as STOP to a version 1 module).

SportInfo is a `u32` mask followed by the fields that are present. START carries start time and elapsed seconds (mask `0x06`); the other states carry elapsed only (`0x04`).

```
03 01 06000000 <start u32> <elapsed u32>    START
03 02 04000000 <elapsed u32>                PAUSE (then 05 every 5 s)
03 03 04000000 <elapsed u32>                RESUME
03 04 04000000 <elapsed u32>                STOP
```

After a reconnect Zepp sends the config and START again with the **original** start time and the current elapsed time, then PAUSE if the workout is paused. The strap resumes its session rather than starting a new one. unstrap does the same.

## 5. What the strap sends

- `13 00`: heart rate back to normal. Zepp then asks "Do you need to end the workout?". unstrap shows its "Looks like you've stopped" question.
- `11 <state>`: the strap paused or stopped the workout itself.
- `12 <x> <mask u32> <fields>`: live data. Fields follow in bit order: bits 0–3 are `u32` each, bit 5 is the heart rate (`u8`). Only the first mask is meaningful.

## 6. To verify on hardware

1. Does the strap answer `01`, and with which version and flags (fw 3.20, 3.25)?
2. After `02` + `03 01` and a workout of 10+ minutes, does the next sync bring a type 5 workout and a type 59 sport effect for it? For which sport types (run 1 vs strength 52)?
3. Does the strap raise its heart-rate sampling during a session?
4. When does `13 00` come, and how often?
5. Does a STOP after a disconnect still close the strap's session cleanly?

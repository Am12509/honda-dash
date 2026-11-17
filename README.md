# Honda Dash

Dashboard and logger for a Honda Accord CL9 (K24A RAA, J730 ECU with the v5.x tune), for the Android 13 head unit (1280x720).
Talks to the ECU through the Bluetooth ELM327 adapter on the K-line.

## Screens
- **Drive**: rpm, speed, coolant gauges, gear, mode, pops level, lock-up state, timing removed, trims.
- **Engine**: map vs final timing, trims, lambda, MAP, load, VTEC, closed loop, fan, A/C.
- **Pops & modes**: pop level buttons, signal on/off, pop timing and window steppers, Eco/Normal/Sport, button shifting, fans, pop lamps.
- **Gearbox**: engine rpm vs mainshaft, live converter slip, lock-up state, gear from the shaft ratio.
- **Timing**: the timing words that carry the 14-16 deg removed at load.
- **Fuel**: ECU fuel chain values (base map, corrected, per injection). Pulse width in ms once its scale is proven.
- **Wheels**: four wheel speeds for VSA checks.
- **Diag**: read and clear engine codes, terminal for raw adapter commands.
- **Settings**: adapter choice, demo mode, fast mode, event log.

## Speed
- One request per ECU address: values that share an address are read once.
- Fast mode: the adapter stops waiting after the first reply (turns itself off if the adapter does not support it).
- Only the visible screen is polled. Logging adds a core set (rpm, speed, throttle, gear, coolant, timing).

## Logs
CSV files go to `Download/HondaDash` on the head unit, one row per poll cycle.

## Build
GitHub Actions builds `HondaDash.apk` on every push to `main` and publishes it as the release named `latest`.
`tools/gen_pids.py` regenerates `PidTable.kt` from the Torque PID list. `tools/jvmtest/Test.kt` runs off-car checks.

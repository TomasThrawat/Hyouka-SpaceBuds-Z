Hyouka SpaceBuds Z

Native Kotlin + Jetpack Compose companion app for Oraimo SpaceBuds Z OTW-625.

The app detects supported earbuds that are already connected through Android Bluetooth profiles, then attempts GATT diagnostics in the background. It does not scan for Bluetooth devices.

Bluetooth connection and GATT availability are separate: a SpaceBuds Z device can be connected for audio while exposing no usable GATT server to the app. In that case the app still reports the earbuds as connected and marks GATT as unavailable.

The feature surfaces for ANC, Sound360, HavyBass, Game Mode, and similar vendor controls remain informational/disabled because Oraimo's public product documentation does not publish a verified vendor control protocol. The app does not send undocumented commands.

Target: Android 12+.

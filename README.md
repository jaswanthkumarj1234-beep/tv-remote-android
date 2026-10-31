# TV Remote for Android (Samsung smart TVs)

A real Android app. Your phone talks **straight to the TV over home Wi-Fi**. No computer, no cloud, no account.

Power on/off, touchpad, arrow keys, volume/channel, app shortcuts (YouTube, Netflix, Prime Video, Hotstar), live typing into TV search boxes, a YouTube keyboard helper and a number pad.

## Download

**[Download TVRemote.apk](https://github.com/jaswanthkumarj1234-beep/tv-remote-android/releases/latest/download/TVRemote.apk)**

1. Open the link above on your Android phone and download the file.
2. Open it. If Android asks, allow "Install unknown apps" for your browser, then tap Install.
3. Open **TV Remote**. Under **TV settings**, enter the TV's IP address (on the TV: Settings > Network) and tap **Save and reconnect**.
4. When the TV shows **Allow**, press it with the TV's own remote. This is only needed once per phone.

## Notes

- Phone and TV must be on the same Wi-Fi.
- **Power on** uses Wake-on-LAN, so it needs the TV's MAC address in TV settings and Wake-on-LAN enabled on the TV (often called "Power On with Mobile").
- Samsung does not allow a system-wide mouse pointer, so there is none.
- To update, download the new APK and install over the old one. If Android refuses (different signing key), uninstall the old app first.
- The app is built automatically by GitHub Actions from this source code.

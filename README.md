# TV Remote for Android (Samsung smart TVs)

Turn your Android phone into a remote for a Samsung Tizen TV. It runs **on the phone itself** and talks straight to the TV over home Wi-Fi: no computer, no cloud, no account.

Features: power on/off, touchpad, arrow keys, volume/channel, app shortcuts (YouTube, Netflix, Prime Video, Hotstar), live typing into TV search boxes, a YouTube keyboard helper, and a number pad.

## Install (about 5 minutes)

1. Install **Termux** and **Termux:Boot** from **F-Droid** (not the Play Store). Open each once.
2. In Termux, paste this one line:

   ```
   curl -fsSL https://raw.githubusercontent.com/jaswanthkumarj1234-beep/tv-remote-android/main/setup.sh | tr -d '\r' > setup.sh && bash setup.sh
   ```
3. Open Chrome on the same phone and go to `http://localhost:8767`, then menu > **Add to Home screen**.
4. Open **TV settings** on that page and enter the TV's IP address (TV: Settings > Network) and, for Power on, its MAC address. Tap **Save and reconnect**.
5. When the TV shows **Allow**, press it with the TV's own remote.
6. In Android settings, turn off battery optimization for Termux so it keeps running.

After this the app starts by itself whenever the phone boots.

## Notes

- The phone and TV must be on the same Wi-Fi.
- **Power on** uses Wake-on-LAN and only works if the TV has it enabled (often "Power On with Mobile" in network settings).
- It cannot show a mouse pointer system-wide: Samsung's TV software does not allow it.
- The app only listens on the phone (127.0.0.1) and rejects requests from other websites, so others on your Wi-Fi cannot use it.
- Each phone appears on the TV as "AndroidPad" and asks for Allow once.

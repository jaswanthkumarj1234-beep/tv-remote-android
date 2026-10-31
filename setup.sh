#!/data/data/com.termux/files/usr/bin/bash
# One-time setup for TV Remote inside Termux.
set -e
RAW="https://raw.githubusercontent.com/jaswanthkumarj1234-beep/tv-remote-android/main"

echo "== Installing Python =="
pkg update -y
pkg install -y python curl

echo "== Installing aiohttp (no compiling needed) =="
AIOHTTP_NO_EXTENSIONS=1 MULTIDICT_NO_EXTENSIONS=1 YARL_NO_EXTENSIONS=1 FROZENLIST_NO_EXTENSIONS=1 pip install aiohttp

echo "== Downloading TV Remote =="
mkdir -p ~/tvremote ~/.termux/boot
cd ~/tvremote
curl -fsSL "$RAW/tvremote.py" -o tvremote.py

echo "== Auto-start on phone boot =="
cat > ~/.termux/boot/tvremote.sh <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock
cd ~/tvremote
while true; do
  python tvremote.py >> tvremote.log 2>&1
  sleep 5
done
EOF
chmod +x ~/.termux/boot/tvremote.sh

echo "== Starting now =="
termux-wake-lock 2>/dev/null || true
pkill -f tvremote.py 2>/dev/null || true
nohup sh -c 'cd ~/tvremote; while true; do python tvremote.py >> tvremote.log 2>&1; sleep 5; done' >/dev/null 2>&1 &
sleep 3
echo
echo "Done. Open Chrome on this phone and go to:  http://localhost:8767"
echo "Then Chrome menu > Add to Home screen."

#!/bin/bash
# Indic Voice — laptop test kit for macOS. Double-click me.
# Uses ./jre if present (no Java install needed), links ./packs into ~/.indicvoice/packs if present,
# then runs bin/start-msite.sh, which (re)starts the local server and opens http://localhost:8080/m.
cd "$(dirname "$0")"
if [ -x jre/Contents/Home/bin/java ]; then export JAVA_HOME="$PWD/jre/Contents/Home"; export PATH="$JAVA_HOME/bin:$PATH"; fi
export JAVA_TOOL_OPTIONS=""
xattr -dr com.apple.quarantine jre bin build third_party 2>/dev/null || true
# Optional: Sarvam (tier 3: most natural Indian voices, strong Hinglish STT). Put the API key in sarvam-key.txt (git-ignored).
[ -f sarvam-key.txt ] && export SARVAM_API_KEY="$(tr -d ' \n\r' < sarvam-key.txt)" && echo "== Sarvam key found"
mkdir -p "$HOME/.indicvoice/packs"
# Link ./packs into ~/.indicvoice/packs; re-point old links (e.g. after the folder was moved), never touch real folders.
if [ -d packs ]; then for p in packs/*; do t="$HOME/.indicvoice/packs/$(basename "$p")"; if [ -L "$t" ] || [ ! -e "$t" ]; then ln -sfn "$PWD/$p" "$t"; fi; done; fi
echo "== Java: $(java -version 2>&1 | head -1)"
echo "== model packs: $(ls "$HOME/.indicvoice/packs" | tr '\n' ' ')"
bin/start-msite.sh || { echo; echo "Something failed — see build/proxy.log"; read -r -p "Press Enter to close"; exit 1; }
echo "Server log follows. Closing this window keeps the server running; STOP.command stops it."
echo
tail -f build/proxy.log

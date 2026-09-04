#!/bin/zsh
# mpcheck.sh — multiplayer (dedicated server) test digest.
# Companion to avacheck.sh, which covers the single-player/client side.
#
#   ./mpcheck.sh          read the server log already copied to $S, plus the local client log
#   ./mpcheck.sh -f       scp the server log down from holly first, then read it
#
# The server log lives on holly at $REMOTE. Copy it to $S yourself, or use -f.

S="/tmp/holly-latest.log"
C="/Users/adevivo/curseforge/minecraft/Instances/AI Companion 1.21.11/logs/latest.log"
REMOTE="holly:/home/adevivo/projects/minecraft_server/minecraft_companion_mod_1.21.11/logs/latest.log"
REMOTE_CFG="/home/adevivo/projects/minecraft_server/minecraft_companion_mod_1.21.11/config/aicompanion-server.json"
REMOTE_MEM="/home/adevivo/projects/minecraft_server/minecraft_companion_mod_1.21.11/config/aicompanion/memories"

if [[ "$1" == "-f" ]]; then
  echo "fetching $REMOTE -> $S"
  scp "$REMOTE" "$S" || echo "  !! fetch failed; falling back to whatever is already at $S"
  echo
fi

[[ -f "$S" ]] || { echo "no server log at $S — copy it there or run with -f"; exit 1; }

echo "=== SERVER: $S  ($(stat -f '%Sm' "$S"))"
echo "=== CLIENT: $C  ($(stat -f '%Sm' "$C" 2>/dev/null || echo missing))"
echo

echo "=== SERVER CRASHED THE PLAYER? ==="
grep -aE "Ticking player|Internal server error|NoClassDefFoundError|ClassNotFoundException" "$S" | sort | uniq -c | sort -rn | head -8
echo "  ^ blank = no tick crash. class_746=LocalPlayer / class_636=MultiPlayerGameMode are"
echo "    CLIENT-ONLY classes; seeing them here means a client mixin got applied server-side."
echo

echo "=== MIXINS THAT DID NOT APPLY ==="
grep -aE "@Mixin target .* was not found|Error loading class" "$S" | sed 's/^\[[^]]*\] //' | sort -u | head -8
echo "  ^ a client-only mixin listed under \"mixins\" instead of \"client\" shows up here"
echo

echo "=== COMPANION LIFECYCLE (spawn / park / restore / re-attach) ==="
grep -aE "\[aicompanion\] (restored|parked|re-attached|announced)|companion spawn|no companion out" "$S" \
  | sed 's/^\(\[[0-9:]*\]\).*INFO\]: /\1 /' | tail -12
echo "  ^ a restore->crash->park loop repeating per reconnect = the player cannot stay on"
echo

echo "=== BRAIN PLACEMENT ==="
grep -aE "Brain: .* (can think|cannot think)|brain=|LLM pool sized" "$S" | sed 's/^\(\[[0-9:]*\]\).*INFO\]: /\1 /' | tail -6
echo

echo "=== SERVER POLICY / CONFIG AS LOADED ==="
grep -aE "\[aicompanion\] (config loaded|server policy)" "$S" | sed 's/^\(\[[0-9:]*\]\).*INFO\]: /\1 /' | tail -2
echo

echo "=== CONNECT / DISCONNECT PAIRS ==="
grep -aE "logged in with entity id|lost connection|joined the game|left the game" "$S" \
  | sed 's/^\(\[[0-9:]*\]\).*INFO\]: /\1 /' | tail -14
echo "  ^ 'lost connection: Internal server error' right after a join = crash on first tick"
echo

echo "=== LLM TURN LATENCY (reads whichever log actually did the thinking) ==="
lat() {  # $1=log  $2=start pattern  $3=end pattern  $4=label
  [[ -f "$1" ]] || return
  awk -v lbl="$4" '
    function secs(x) { return substr(x,2,2)*3600 + substr(x,5,2)*60 + substr(x,8,2) }
    $0 ~ st  { t = secs($1); stamp = $1 }
    $0 ~ en && t { d = secs($1) - t; if (d < 0) d += 86400
                   printf "  %-8s %s -> %s  %4ds\n", lbl, stamp, $1, d
                   n++; sum += d; if (d > max) max = d; t = 0 }
    END { if (n) printf "  ---- %s: %d turn(s), avg %.0fs, slowest %.0fs\n", lbl, n, sum/n, max }
  ' st="$2" en="$3" "$1"
}
lat "$S" "Calling LLM: history=" "LLMCompleter returned"           "server"
lat "$C" "Called complete conversation" "Finished complete conversation" "client"
echo "  ^ first call of a session is cold and unrepresentative. Which row appears tells you"
echo "    where the brain really ran, independently of what the boot line claims."
echo

echo "=== MEMORY RECALL (client-side corpus, when clientBrain is on) ==="
if [[ -f "$C" ]]; then
  echo "  turns WITH facts recalled: $(grep -ac 'memories : \\"[^\\]' "$C")"
  grep -ao 'memories : \\"[^\\]*' "$C" | sed 's/memories : \\"/    /' | sort -u | cut -c1-110 | tail -5
  echo "  extractor kept/dropped:"
  grep -aoE "Memory: learned nothing[^.]*|Memory: learned [0-9]+[^.]*|dropping \"[^\"]*\" — [a-z ]*" "$C" | cut -c1-105 | tail -5
fi
echo "  ^ 'turns WITH facts' counts only turns where the memories field had CONTENT; the"
echo "    instruction preamble is always present and is not evidence of recall. A dropped"
echo "    extraction is usually correct: it stops the companion learning its own reply back."
echo

echo "=== TTS (server-side — these do NOT appear in the client log on multiplayer) ==="
echo "  speak requests: $(grep -ac 'asked the owner.s client to speak' "$S")"
echo "  acks released:  $(grep -ac 'finished speaking; released' "$S")"
echo "  stall warnings: $(grep -ac 'queued but not being processed' "$S")"
grep -aE "asked the owner.s client to speak|finished speaking; released" "$S" | sed 's/^\(\[[0-9:]*\]\).*: /\1 /' | tail -6
echo "  ^ speak requests == acks released means TTS is keeping up. A shortfall means the"
echo "    client never acked and the speaker lock is stuck."
echo

echo "=== CONVERSATION QUEUE WAITS ==="
grep -aoE "queued=[0-9]+, msWaiting=[0-9]+, msSinceLastProcess=[0-9]+" "$S" | tail -6
echo "  ^ NOT necessarily a fault: a turn already in flight, or the companion mid-speech,"
echo "    both park the next message here. Correlate against LLM TURN LATENCY above."
echo

echo "=== BRAIN — WHICH MACHINE ACTUALLY THOUGHT ==="
grep -aoE "brain=[a-z-]+( \(clientBrain IGNORED[^)]*\))?" "$S" | tail -1 | sed 's/^/  boot line says: /'
echo "  handshake accepted: $(grep -ac 'can think client-side' "$S") join(s) (re-announced per reconnect, not distinct players)"
echo "  client turns that FAILED:   $(grep -acE 'Brain: the client could not think|unparseable result from the client|could not think for' "$S")"
echo "  of those, server answered:  $(grep -ac 'thinking on the server' "$S")"
echo "  of those, turn ABANDONED:   $(grep -ac 'does not answer for guests' "$S")"
grep -aoE "Brain: (the client could not think \([^)]*\)|unparseable result[^.]*|[0-9a-f-]+ could not think for [0-9a-f-]+)" "$S" | cut -c1-110 | tail -4
echo "  ^ 'brain=server' means the SERVER thought every turn using ITS OWN llm endpoint and"
echo "    ITS OWN memory corpus — the client's openrouter model and corpus are not consulted."
echo "    'brain=client-when-able' + handshake accepted + 0 failures = the client is thinking."
echo "    A client failure is only SILENT when server.serverAnswersWhenClientFails=true — then"
echo "    the local model answers and nothing says so. With it false the turn is abandoned and"
echo "    the player is told in chat, so a visible 'Ava could not think' is the loud variant."
echo

echo "=== CONFIG THAT DECIDES IT (server copy is the one that counts) ==="
if [[ -n "$REMOTE_CFG" ]]; then
  ssh "${REMOTE%%:*}" "python3 -c \"
import json
d=json.load(open('$REMOTE_CFG'))
b=d.get('brain',{}); ws=b.get('whenServer',{})
print('  brain.mode          =', b.get('mode','(absent -> client)'))
print('  brain.clientTimeout =', b.get('clientTimeoutMs'))
w=ws.get('llm',{}); m=ws.get('memory',{}); e=ws.get('embeddings',{})
tag='  <- read ONLY when mode=server'
print('  whenServer.llm      =', w.get('endpoint'), tag)
print('  whenServer.memory   =', m.get('enabled'), tag)
print('  whenServer.embed    =', e.get('enabled'), tag)
print('  serverAnswersWhenClientFails =', d.get('server',{}).get('serverAnswersWhenClientFails'))
\"" 2>/dev/null || echo "  (ssh failed — skipping)"
fi
echo "  ^ mode=client means each player's own machine thinks, with THEIR model and THEIR"
echo "    corpus, and everything under whenServer is inert. Do NOT 'fix' a missing memory by"
echo "    populating this server's corpus — with mode=client it is never opened."
echo

echo "=== OTHER SERVER ERRORS ==="
grep -aE "ERROR|WARN|Exception" "$S" \
  | grep -avE "Ticking player|Caused by:|@Mixin target|Error loading class|Failed to handle packet|Automatone settings|queued but not being processed" \
  | sed 's/^\(\[[0-9:]*\]\).*: /\1 /' | sort -u | tail -8
echo "  ^ blank = clean"
echo

if [[ -f "$C" ]]; then
  echo "=== CLIENT VIEW ==="
  echo "  disconnects:   $(grep -ac 'Client disconnected with reason' "$C")"
  grep -aE "Client disconnected with reason|Connecting to" "$C" | sed 's/^\(\[[0-9:]*\]\).*: /\1 /' | tail -8
  echo
  echo "  skin:          $(grep -aE 'companion skin' "$C" | tail -1 | sed 's/.*INFO\]: //')"
  echo "  client errors: $(grep -acE 'ERROR|Exception' "$C")"
fi

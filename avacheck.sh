#!/bin/zsh
L="/Users/adevivo/curseforge/minecraft/Instances/AI Companion 1.21.11/logs/latest.log"
echo "=== EATING (word-boundary match; 'creating' no longer counts as 'eating') ==="
grep -aE "FoodChain: (started|finished)|\bate\b" "$L" | tail -8
echo "  ^ blank = never got hungry enough to eat"
echo
echo "=== lowest food level seen ==="
grep -aoE "food [0-9]+/20" "$L" | sort -t/ -k1.6n | head -1
echo
echo "=== SKIN ==="
grep -aE "companion skin" "$L" | tail -4
echo
echo "=== ERRORS / AI disabled ==="
grep -aE "ERROR|failed [0-9]+ times|Exception" "$L" | grep -avE "Info log when linking|shader" | tail -8
echo "  ^ blank = clean"
echo
echo "=== TTS ==="
echo "  acks released:  $(grep -ac 'finished speaking; released' "$L")"
echo "  stall warnings: $(grep -ac 'queued but not being processed' "$L")"

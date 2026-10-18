#!/usr/bin/env python3
"""Generate deterministic synthetic fixtures for detector/decode smoke tests."""
import math, random, struct, wave
from pathlib import Path

ROOT = Path(__file__).parent
RATE = 16000
random.seed(20261018)

def write(name, samples):
    with wave.open(str(ROOT / name), "wb") as output:
        output.setnchannels(1); output.setsampwidth(2); output.setframerate(RATE)
        output.writeframes(b"".join(struct.pack("<h", max(-32768, min(32767, int(v * 32767)))) for v in samples))

def tone(amplitude, frequencies=(440,), seconds=3):
    return [amplitude * sum(math.sin(2 * math.pi * f * i / RATE) for f in frequencies) / len(frequencies)
            for i in range(RATE * seconds)]

def write_compressed_quality(name):
    rate = 8000
    samples = [.2 * math.sin(2 * math.pi * 440 * i / rate) for i in range(rate * 3)]
    with wave.open(str(ROOT / name), "wb") as output:
        output.setnchannels(1); output.setsampwidth(1); output.setframerate(rate)
        output.writeframes(bytes(max(0, min(255, round(128 + value * 127))) for value in samples))

write("clean.wav", [(.05 if (i // 4000) % 2 == 0 else .4) * math.sin(2 * math.pi * 440 * i / RATE)
                    for i in range(RATE * 3)])
write("clipping.wav", [1 if i % 2 == 0 else -1 for i in range(RATE * 3)])
write("inaudible.wav", tone(.004))
write_compressed_quality("unclear.wav")
write("background-noise.wav", [v + .12 * math.sin(2 * math.pi * 2400 * i / RATE) for i, v in enumerate(tone(.16))])
write("missing.wav", [])
write("disruptive-sound.wav", [v + (.7 if i % 2400 < 100 else 0) for i, v in enumerate(tone(.15))])
write("overlapping-speech.wav", tone(.2, (320, 510)))

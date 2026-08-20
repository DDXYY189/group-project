import sys
import os
import subprocess
import pysilk

def convert_mp3_to_silk(mp3_path, silk_path):
    pcm_path = mp3_path.replace('.mp3', '.pcm')

    # 1. MP3 -> raw PCM (16-bit, 16kHz, mono) using ffmpeg
    result = subprocess.run([
        'ffmpeg', '-y', '-i', mp3_path,
        '-ar', '16000', '-ac', '1', '-f', 's16le',
        pcm_path
    ], capture_output=True, text=True)

    if not os.path.exists(pcm_path):
        print(f"ERROR:ffmpeg failed: {result.stderr[:500]}")
        sys.exit(1)

    # 2. PCM -> Silk using pysilk
    with open(pcm_path, 'rb') as f:
        pcm_data = f.read()

    silk_data = pysilk.encode(pcm_data, 16000)

    with open(silk_path, 'wb') as f:
        f.write(silk_data)

    os.remove(pcm_path)

    print(f"OK:{len(silk_data)}")

if __name__ == '__main__':
    if len(sys.argv) != 3:
        print("Usage: python mp3_to_silk.py input.mp3 output.silk")
        sys.exit(1)
    convert_mp3_to_silk(sys.argv[1], sys.argv[2])

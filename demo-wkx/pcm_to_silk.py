import sys
import os
import pysilk

def convert_pcm_to_silk(pcm_path, silk_path):
    with open(pcm_path, 'rb') as f:
        pcm_data = f.read()

    silk_data = pysilk.encode(pcm_data, 16000, sample_rate=16000)

    with open(silk_path, 'wb') as f:
        f.write(silk_data)

    duration_ms = int(len(pcm_data) / (16000 * 2) * 1000)
    print(f"OK:{len(silk_data)}:{duration_ms}")

if __name__ == '__main__':
    if len(sys.argv) != 3:
        print("Usage: python pcm_to_silk.py input.pcm output.silk")
        sys.exit(1)
    convert_pcm_to_silk(sys.argv[1], sys.argv[2])

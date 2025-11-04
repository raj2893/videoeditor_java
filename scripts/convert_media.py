#!/usr/bin/env python
import subprocess
import sys
import os

def convert_video(input_path, output_path, target_format):
    try:
        target_format = target_format.lower()
        # Ensure correct extension
        output_path = f"{os.path.splitext(output_path)[0]}.{target_format}"

        # FFmpeg command for video conversion
        command = [
            "C:\\Users\\raj.p\\Downloads\\ffmpeg-2025-02-17-git-b92577405b-full_build\\bin\\ffmpeg.exe",
            "-i", input_path,
            "-c:v", "copy",  # Copy video stream to avoid re-encoding
            "-c:a", "copy",  # Copy audio stream
            output_path
        ]

        # Run FFmpeg command
        result = subprocess.run(command, capture_output=True, text=True, check=True)
        print(f"Conversion successful: {output_path}")
        return 0
    except subprocess.CalledProcessError as e:
        print(f"Error during conversion: {e.stderr}", file=sys.stderr)
        return 1
    except Exception as e:
        print(f"Unexpected error: {str(e)}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    if len(sys.argv) != 4:
        print("Usage: python convert_media.py <input_path> <output_path> <target_format>", file=sys.stderr)
        sys.exit(1)

    input_path, output_path, target_format = sys.argv[1:4]
    sys.exit(convert_video(input_path, output_path, target_format))
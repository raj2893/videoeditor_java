import sys
import whisper
import json
import os
import io

class StdoutToStderr(io.StringIO):
    def write(self, text):
        sys.stderr.write(text)
        sys.stderr.flush()
    def flush(self):
        sys.stderr.flush()

def generate_subtitles(audio_path):
    try:
        print(f"Loading Whisper model, audio path: {audio_path}", file=sys.stderr)
        if not os.path.exists(audio_path):
            print(f"Audio file does not exist: {audio_path}", file=sys.stderr)
            sys.exit(1)
        model = whisper.load_model("base")
        print("Model loaded, starting transcription", file=sys.stderr)

        # Redirect stdout to stderr during transcription
        original_stdout = sys.stdout
        sys.stdout = StdoutToStderr()
        try:
            result = model.transcribe(audio_path, word_timestamps=False, verbose=False)
        finally:
            sys.stdout = original_stdout  # Restore stdout

        print("Transcription complete", file=sys.stderr)
        subtitles = [
            {
                "start": max(0.0, segment["start"]),
                "end": segment["end"],
                "text": segment["text"].strip()
            }
            for segment in result["segments"]
            if segment["text"].strip() and segment["end"] > segment["start"]
        ]
        print(f"Generated {len(subtitles)} subtitles", file=sys.stderr)
        for i, subtitle in enumerate(subtitles):
            print(f"Subtitle {i+1}: start={subtitle['start']:.3f}, end={subtitle['end']:.3f}, text={subtitle['text']}", file=sys.stderr)
        return subtitles
    except Exception as e:
        print(f"Error during transcription: {str(e)}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python whisper_subtitle.py <audio_path>", file=sys.stderr)
        sys.exit(1)

    audio_path = sys.argv[1]
    subtitles = generate_subtitles(audio_path)
    print(json.dumps(subtitles))
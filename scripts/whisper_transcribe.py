import whisper
import sys
import json
import argparse
import logging
import warnings
import tqdm

# Suppress Whisper warnings
warnings.filterwarnings("ignore")

# Suppress tqdm progress bars
tqdm.tqdm.write = lambda *args, **kwargs: None

# Suppress logging output
logging.getLogger().setLevel(logging.CRITICAL)

def transcribe_audio(input_path, output_format):
    model = whisper.load_model("base")  # Use 'small' or 'medium' for better accuracy
    result = model.transcribe(input_path, verbose=False)
    segments = [
        {
            "start": segment["start"],
            "end": segment["end"],
            "text": segment["text"].strip()
        } for segment in result["segments"]
    ]
    if output_format == "json":
        print(json.dumps(segments))
    else:
        for segment in segments:
            print(f"{segment['start']} - {segment['end']}: {segment['text']}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, help="Path to audio file")
    parser.add_argument("--output_format", default="json", help="Output format: json or text")
    args = parser.parse_args()
    transcribe_audio(args.input, args.output_format)
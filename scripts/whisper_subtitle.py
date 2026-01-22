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
        model = whisper.load_model("medium")
        print("Model loaded, starting transcription", file=sys.stderr)

        # Redirect stdout to stderr during transcription
        original_stdout = sys.stdout
        sys.stdout = StdoutToStderr()
        try:
            # ✅ KEY CHANGE: Enable word_timestamps
            result = model.transcribe(audio_path, word_timestamps=True, verbose=False)
        finally:
            sys.stdout = original_stdout

        print("Transcription complete", file=sys.stderr)

        # Process segments to create subtitles with word-level timestamps
        subtitles = []
        for segment in result["segments"]:
            text = segment["text"].strip()
            if not text or segment["end"] <= segment["start"]:
                continue

            # ✅ NEW: Extract word-level timestamps if available
            words_data = []
            if "words" in segment and segment["words"]:
                for word_info in segment["words"]:
                    word_text = word_info.get("word", "").strip()
                    if word_text:
                        words_data.append({
                            "word": word_text,
                            "start": max(0.0, word_info.get("start", segment["start"])),
                            "end": word_info.get("end", segment["end"])
                        })

            # Create chunks of 2–3 words with word timestamps
            chunk_size = 3
            i = 0
            word_list = text.split() if not words_data else [w["word"] for w in words_data]

            while i < len(word_list):
                remaining_words = len(word_list) - i
                current_chunk_size = min(chunk_size, remaining_words)

                if current_chunk_size == 1 and i > 0:
                    if subtitles:
                        subtitles[-1]["text"] += " " + word_list[i]
                        if words_data and i < len(words_data):
                            subtitles[-1]["words"].append(words_data[i])
                            subtitles[-1]["end"] = words_data[i]["end"]
                        i += 1
                        continue
                elif current_chunk_size == 2 and remaining_words == 2:
                    current_chunk_size = 2

                # Create chunk with word timestamps
                chunk_text = " ".join(word_list[i:i + current_chunk_size])
                if not chunk_text.strip():
                    i += current_chunk_size
                    continue

                # Get timing from word timestamps or calculate
                if words_data and i < len(words_data):
                    chunk_start = words_data[i]["start"]
                    chunk_end = words_data[min(i + current_chunk_size - 1, len(words_data) - 1)]["end"]
                    chunk_words = words_data[i:i + current_chunk_size]
                else:
                    # Fallback to calculated timing
                    start_time = max(0.0, segment["start"])
                    end_time = segment["end"]
                    duration = end_time - start_time
                    chunk_start = start_time + (duration * i / len(word_list))
                    chunk_end = start_time + (duration * (i + current_chunk_size) / len(word_list))
                    chunk_words = []

                if chunk_end <= chunk_start:
                    i += current_chunk_size
                    continue

                subtitles.append({
                    "start": chunk_start,
                    "end": chunk_end,
                    "text": chunk_text,
                    "words": chunk_words  # ✅ NEW: Include word-level timestamps
                })
                i += current_chunk_size

        print(f"Generated {len(subtitles)} subtitles with word timestamps", file=sys.stderr)
        for i, subtitle in enumerate(subtitles):
            word_count = len(subtitle.get("words", []))
            print(f"Subtitle {i+1}: start={subtitle['start']:.3f}, end={subtitle['end']:.3f}, words={word_count}, text={subtitle['text']}", file=sys.stderr)
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
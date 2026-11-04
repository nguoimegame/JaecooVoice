"""Writes the golden phonemes EspeakPhonemizerTest compares against.

The reference is piper_phonemize.phonemize_espeak, the phonemizer Piper models were trained with.
Run it in its own venv (Python 3.11), outside the repository:

    pip install piper_phonemize -f https://k2-fsa.github.io/icefall/piper_phonemize.html
    python -I tools/phonemes/make_golden.py app/src/androidTest/assets/phonemes/golden.json [espeak-ng-data]

Pass the espeak-ng-data of a downloaded TTS model archive to use exactly the data the app uses;
without it the copy inside the piper_phonemize wheel is used (identical for sherpa-onnx's
tts-models archives as of 2026-11-04).
"""

import json
import sys

from piper_phonemize import phonemize_espeak

CASES = [
    ("vi", "Vị trí hiện tại: Trời quang đãng, 25 độ C, cảm giác 32 độ C"),
    ("vi", "Không nghe thấy gì. Hãy nói lại rõ hơn"),
    ("vi", "Tôi đang nghe đây!"),
    ("vi", "Bạn có muốn mở bản đồ không?"),
    ("vi", "Mở Spotify"),
    ("vi", "Thiếu quyền micro; thử lại sau"),
    ("en-us", "Hello, how are you?"),
]


def main() -> None:
    if len(sys.argv) not in (2, 3):
        sys.exit("usage: make_golden.py OUT.json [espeak-ng-data]")
    out = sys.argv[1]
    data_path = sys.argv[2] if len(sys.argv) == 3 else None
    entries = []
    for voice, text in CASES:
        sentences = ["".join(s) for s in phonemize_espeak(text, voice, data_path=data_path)]
        if not sentences or not all(s.strip() for s in sentences):
            sys.exit(f"empty phonemes for {voice}: {text}")
        entries.append({"voice": voice, "text": text, "sentences": sentences})
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(entries, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"wrote {len(entries)} entries to {out}")


if __name__ == "__main__":
    main()

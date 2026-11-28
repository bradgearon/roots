"""Local 48 kHz mono streaming reference adapter. No audio files or network API.

Request: little-endian uint32 sample count (1..4800), followed by float32 PCM.
Zero count explicitly flushes and ends; EOF cancels without flushing.
Ready: b"RVI1" + uint32 rate + uint32 model algorithmic latency in samples.
Response per request: uint64 first source sample, uint32 count, then count
interleaved (isolated_voice, original_minus_voice) float32 pairs. Count may
be zero. Source sample indices remain contiguous even when the model buffers.
The caller must drain replies and enforce deadlines; on error restore original.
"""
import argparse
import array
import contextlib
import math
import os
import struct
import sys
from collections import deque
from importlib.metadata import version
from pathlib import Path

RATE = 48000
MAX_BLOCK = 4800
MAX_PENDING = RATE * 2
HEADER = struct.Struct("<QI")
COUNT = struct.Struct("<I")
READY = struct.Struct("<4sII")


def read_exact(stream, size, allow_eof=False):
    result = bytearray()
    while len(result) < size:
        part = stream.read(size - len(result))
        if not part:
            if allow_eof and not result:
                return None
            raise EOFError("truncated frame")
        result.extend(part)
    return bytes(result)


def float_array(data):
    samples = array.array("f")
    if samples.itemsize != 4:
        raise RuntimeError("float32 required")
    samples.frombytes(data)
    if sys.byteorder != "little":
        samples.byteswap()
    if not all(math.isfinite(x) for x in samples):
        raise ValueError("nonfinite PCM")
    return samples


def float_bytes(samples):
    if sys.byteorder != "little":
        samples.byteswap()
    return samples.tobytes()


def serve(source, sink, session, latency_samples):
    if not 0 <= latency_samples <= RATE:
        raise ValueError("invalid latency")
    sink.write(READY.pack(b"RVI1", RATE, latency_samples))
    sink.flush()
    pending = deque()
    first = 0
    while True:
        raw = read_exact(source, COUNT.size, allow_eof=True)
        if raw is None:
            return  # cancel; don't manufacture a tail
        count, = COUNT.unpack(raw)
        if count > MAX_BLOCK:
            raise ValueError("oversized PCM block")
        if count:
            pcm = float_array(read_exact(source, count * 4))
            if len(pending) + count > MAX_PENDING:
                raise RuntimeError("separator backlog exceeded")
            pending.extend(pcm)
            isolated = session.push(pcm)
        else:
            isolated = session.flush()
        if len(isolated) > len(pending):
            raise RuntimeError("model emitted beyond input")
        output = array.array("f")
        for voice in isolated:
            voice = float(voice)
            if not math.isfinite(voice):
                raise RuntimeError("nonfinite model output")
            original = pending.popleft()
            output.extend((voice, original - voice))
        if not all(math.isfinite(x) for x in output):
            raise RuntimeError("nonfinite residual")
        if not count and pending:
            raise RuntimeError("model flush left input unmatched")
        sink.write(HEADER.pack(first, len(isolated)))
        sink.write(float_bytes(output))
        sink.flush()
        first += len(isolated)
        if count == 0:
            return


class InworldSession:
    def __init__(self, model, torch):
        self.session = model.stream()
        self.torch = torch

    def push(self, samples):
        data = self.torch.tensor(samples, dtype=self.torch.float32)
        return self.session.push(data).detach().cpu().float().reshape(-1).tolist()

    def flush(self):
        return self.session.flush().detach().cpu().float().reshape(-1).tolist()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", choices=["cpu", "cuda"], required=True)
    parser.add_argument("--lookahead", type=int, choices=[0, 2, 4], default=2)
    parser.add_argument("--checkpoint", type=Path)
    parser.add_argument("--allow-download", action="store_true")
    args = parser.parse_args()
    # Provisioning is explicit; live inference must not fetch any model silently.
    if not args.allow_download:
        os.environ["HF_HUB_OFFLINE"] = "1"
    sink = sys.stdout.buffer
    try:
        with contextlib.redirect_stdout(sys.stderr):
            if version("inworld-voice-isolation") != "1.0.0":
                raise RuntimeError("unexpected Inworld package version")
            import torch
            from inworld import voice_isolation
            if args.device == "cuda" and not torch.cuda.is_available():
                raise RuntimeError("CUDA unavailable")
            if args.checkpoint and (not args.checkpoint.is_file() or
                                    args.checkpoint.suffix != ".safetensors"):
                raise ValueError("local safetensors checkpoint required")
            model = voice_isolation.VoiceIsolator(
                str(args.checkpoint.resolve()) if args.checkpoint else "inworld/voice-isolator",
                mode="streaming", lookahead_frames=args.lookahead, device=args.device)
            if model.sample_rate != RATE:
                raise RuntimeError("unexpected sample rate")
            with torch.inference_mode():
                serve(sys.stdin.buffer, sink, InworldSession(model, torch), model.latency_samples())
        return 0
    except Exception as error:
        # Diagnostics deliberately contain no raw PCM or transcript.
        print("Roots Inworld worker failed: " + type(error).__name__, file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

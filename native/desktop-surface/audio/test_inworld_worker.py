import io
import math
import struct
import unittest
from inworld_worker import READY, HEADER, COUNT, MAX_BLOCK, serve


class Delayed:
    def __init__(self):
        self.tail = []

    def push(self, x):
        values = self.tail + list(x)
        self.tail = values[-2:]
        return [v * 0.25 for v in values[:-2]]

    def flush(self):
        result = [v * 0.25 for v in self.tail]
        self.tail = []
        return result


def block(samples):
    return COUNT.pack(len(samples)) + struct.pack("<" + "f" * len(samples), *samples)


class ProtocolTests(unittest.TestCase):
    def test_delayed_residual_and_final_indices(self):
        samples = [0.1, 0.2, -0.3, 0.8, -0.1]
        output = io.BytesIO()
        serve(io.BytesIO(block(samples[:1]) + block(samples[1:]) + COUNT.pack(0)), output, Delayed(), 3024)
        output.seek(0)
        self.assertEqual(READY.unpack(output.read(READY.size)), (b"RVI1", 48000, 3024))
        index = 0
        pairs = []
        for expected in [0, 3, 2]:
            first, count = HEADER.unpack(output.read(HEADER.size))
            self.assertEqual((first, count), (index, expected))
            values = struct.unpack("<" + "f" * (count * 2), output.read(count * 8))
            pairs.extend(zip(values[::2], values[1::2]))
            index += count
        self.assertEqual(output.read(), b"")
        for (speech, background), original in zip(pairs, samples, strict=True):
            self.assertAlmostEqual(speech + background, original, places=6)
            self.assertAlmostEqual(speech, original * 0.25, places=6)

    def test_truncated_or_invalid_pcm_rejected(self):
        for data in [COUNT.pack(MAX_BLOCK + 1), COUNT.pack(1), block([math.nan])]:
            with self.subTest(data=data), self.assertRaises((ValueError, EOFError)):
                serve(io.BytesIO(data), io.BytesIO(), Delayed(), 3024)

    def test_cancel_does_not_flush(self):
        out = io.BytesIO()
        serve(io.BytesIO(block([0.5])), out, Delayed(), 3024)
        self.assertEqual(len(out.getvalue()), READY.size + HEADER.size)


if __name__ == "__main__":
    unittest.main()

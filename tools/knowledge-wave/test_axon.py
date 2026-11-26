import argparse
import csv
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest

import axon

def fixture(path, value):
    def string(s):
        b=s.encode();return struct.pack("<Q",len(b))+b
    h=b"GGUF"+struct.pack("<IQQ",3,1,0)
    h+=string("fixture.weight")+struct.pack("<IQQIQ",2,32,64,8,0)
    h+=bytes((-len(h))%32)
    path.write_bytes(h+bytes([value])*34*64)

def write_run(path, sources, inv, share, voice, lang, seed):
    path.mkdir()
    t=inv["talker"]["fixture.weight"]
    trace=path/"flow.tsv"
    with trace.open("w",newline="",encoding="utf-8") as f:
        w=csv.writer(f,delimiter="\t")
        w.writerow("step stage source tensor offset row_bytes rows first count kind share rms".split())
        prefix=[0,"talker.prefill","talker","fixture.weight",t["offset"],34,64]
        w.writerow(prefix+[0,64,"dependency",0,0])
        w.writerow(prefix+[0,32,"flow",share,1])
        w.writerow(prefix+[32,32,"flow",1-share,1])
    axon.write_new(path/"run.json",dict(schema="roots.element-flow.qwen/1",status="complete",
        sources=sources,voice=voice,language=lang,trace_sha256=axon.digest(trace),
        executable_sha256="a"*64,qwen_revision=axon.QWEN,ggml_revision=axon.GGML,
        greedy=False,max_new=512,seed=seed))

class AxonTests(unittest.TestCase):
    def test_compile_and_materialize(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);talker=p/"talker.gguf";codec=p/"codec.gguf"
            fixture(talker,12);fixture(codec,17)
            models={"talker":talker,"codec":codec};sources=axon.source_hashes(models)
            inv={k:axon.inventory(v) for k,v in models.items()}
            target=p/"target";control=p/"control"
            write_run(target,sources,inv,.9,"Elusiaa","English",42)
            write_run(control,sources,inv,.1,"Elusiaa","Japanese",42)
            desc=p/"elusiaa.en.axon"
            args=argparse.Namespace(talker=talker,codec=codec,target=[target],control=[control],out=desc,budget=1088,peer=["b"*64])
            axon.compile_axon(args)
            doc=json.loads(desc.read_text())
            self.assertEqual(doc["version"],2);self.assertTrue(doc["requires_dense_model"])
            self.assertEqual(len(doc["pieces"]),1)
            piece=doc["pieces"][0]
            self.assertEqual(piece["first_row"],0);self.assertEqual(piece["bytes"],1088)
            self.assertAlmostEqual(piece["element_share"],1)
            out=p/"pieces"
            mat=argparse.Namespace(talker=talker,codec=codec,axon=desc,out=out)
            axon.materialize(mat)
            data=(out/piece["sha256"]).read_bytes()
            self.assertEqual(data,bytes([12])*1088)
            self.assertEqual(hashlib.sha256(data).hexdigest(),piece["sha256"])
            self.assertEqual(axon.source_hashes(models),sources)
            (out/piece["sha256"]).write_bytes(b"corrupt")
            with self.assertRaises(ValueError):axon.materialize(mat)
            # Descriptor can't redirect reads outside its tensor.
            piece["offset"]+=1
            desc.write_text(json.dumps(doc))
            with self.assertRaises(ValueError):axon.materialize(mat)
    def test_bad_gguf(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"bad.gguf";fixture(p,1)
            p.write_bytes(p.read_bytes()[:-1])
            with self.assertRaises(ValueError):axon.inventory(p)

if __name__=="__main__":unittest.main()

import unittest
import atlas_core as a

class AtlasChecks(unittest.TestCase):
    def test_ranges(self):
        t={f"blk.0.{k}.weight":{"shape":[640,2048],"size":2048*680,"offset":i*2000000,"type":"Q8_0"} for i,k in enumerate(("ffn_gate","ffn_up"),1)}
        r=a.byte_ranges(t,0,2)
        self.assertEqual(r[0]["offset"],2000000+64*680)
        self.assertEqual(r[0]["length"],32*680)
        with self.assertRaises(ValueError):a.byte_ranges(t,0,64)
        p,n=a.plan({"0:2":1,"0:3":.5},t,43520)
        self.assertEqual(n,43520)
        self.assertEqual([x["group"] for x in p],["0:2"])
    def test_wave(self):
        p=[{"features":{"f":1},"groups":{"0:0":.9,"0:1":.1}},
           {"features":{"g":1},"groups":{"0:0":.1,"0:1":.9}}]
        m=a.learn(p);s,w=a.rank({"f":1},m)
        self.assertGreater(s["0:0"],s["0:1"])
        self.assertAlmostEqual(sum(s.values()),1)
        self.assertAlmostEqual(sum(w.values()),1)
        unknown,_=a.rank({"x":1},m)
        self.assertEqual(unknown,m["baseline"])
    def test_mass(self):
        self.assertEqual(a.normalized({"bad":float("nan"),"negative":-1,"good":3}),{"good":1})
        self.assertEqual(a.coverage([{"group":"x"}],{"x":.3,"y":.7}),.3)
if __name__=="__main__":unittest.main()

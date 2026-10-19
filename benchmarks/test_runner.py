import os,pathlib,subprocess,sys,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parent.parent
class RunnerTest(unittest.TestCase):
    def test_previous_results_are_rejected_before_external_tools(self):
        with tempfile.TemporaryDirectory() as d:
            previous=pathlib.Path(d)/'previous.json';previous.write_text('previous-evidence')
            env={**os.environ,'PATH':''}
            r=subprocess.run([sys.executable,str(ROOT/'benchmarks/run.py'),'--suite','acceptance','--output',d],env=env,text=True,capture_output=True)
            self.assertNotEqual(0,r.returncode)
            self.assertIn('output directory must be empty',r.stderr.lower())
            self.assertEqual('previous-evidence',previous.read_text())
    def test_invalid_workload_rejected_before_external_tools(self):
        for flags in [['--rates','0'],['--warmup','-1'],['--seconds','3','--bucket-seconds','2'],['--vus','0']]:
            r=subprocess.run([sys.executable,str(ROOT/'benchmarks/run.py'),'--suite','acceptance',*flags],env={**os.environ,'PATH':''},text=True,capture_output=True)
            self.assertEqual(2,r.returncode)
            self.assertIn('error:',r.stderr)
if __name__=='__main__':unittest.main()

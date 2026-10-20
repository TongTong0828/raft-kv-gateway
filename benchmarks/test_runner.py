import contextlib,io,json,os,pathlib,runpy,subprocess,sys,tempfile,unittest
from unittest.mock import patch
ROOT=pathlib.Path(__file__).resolve().parent.parent
class RunnerTest(unittest.TestCase):
    def test_duplicate_rates_rejected_before_external_tools(self):
        r=subprocess.run([sys.executable,str(ROOT/'benchmarks/run.py'),'--suite','acceptance','--rates','8400,08400'],env={**os.environ,'PATH':''},text=True,capture_output=True)
        self.assertEqual(2,r.returncode)
        self.assertIn('duplicate rates',r.stderr.lower())
    def test_previous_results_are_rejected_before_external_tools(self):
        with tempfile.TemporaryDirectory() as d:
            previous=pathlib.Path(d)/'previous.json';previous.write_text('previous-evidence')
            env={**os.environ,'PATH':''}
            r=subprocess.run([sys.executable,str(ROOT/'benchmarks/run.py'),'--suite','acceptance','--output',d],env=env,text=True,capture_output=True)
            self.assertNotEqual(0,r.returncode)
            self.assertIn('output directory must be empty',r.stderr.lower())
            self.assertEqual('previous-evidence',previous.read_text())
    def test_generator_memory_bounds_rejected_before_external_tools(self):
        for value in ['255','4097','0','-1']:
            with self.subTest(value=value):
                r=subprocess.run([sys.executable,str(ROOT/'benchmarks/run.py'),'--generator-memory-mib',value],env={**os.environ,'PATH':''},text=True,capture_output=True)
                self.assertEqual(2,r.returncode)
                self.assertIn('generator memory must be between 256 and 4096 mib',r.stderr.lower())
    def test_generator_memory_requires_an_integer_before_external_tools(self):
        for value in ['512.5','abc']:
            with self.subTest(value=value):
                r=subprocess.run([sys.executable,str(ROOT/'benchmarks/run.py'),'--generator-memory-mib',value],env={**os.environ,'PATH':''},text=True,capture_output=True)
                self.assertEqual(2,r.returncode)
                self.assertIn('invalid int value',r.stderr.lower())
    def test_generator_memory_used_by_docker_matches_recorded_metadata(self):
        from metadata import SERVICES
        profile=json.loads((ROOT/'examples/orders.json').read_text())
        actual=[{'Id':s,'Name':'/'+s,'Image':'sha256:app','State':{'Running':True},'Config':{'Labels':{'com.docker.compose.service':s,'org.opencontainers.image.revision':'commit'}},'HostConfig':{'Memory':123,'NanoCpus':1000000000}} for s in sorted(SERVICES)]
        for value,expected in [(None,1536),(256,256),(1024,1024),(4096,4096)]:
            with self.subTest(value=value),tempfile.TemporaryDirectory() as d:
                commands=[]
                def fake_run(command,**kwargs):
                    if command==[str(ROOT/'scripts/k6.sh'),'provenance']:result={'imageId':'sha256:generator'}
                    elif command==['docker','image','inspect','raft-kv-gateway:local']:result=[{'Id':'sha256:app','Config':{'Labels':{'org.opencontainers.image.revision':'commit'}}}]
                    elif command==['git','rev-parse','HEAD']:result='commit'
                    elif command==['git','status','--porcelain']:result=''
                    elif command[:2]==['sysctl','-n']:result='test CPU'
                    elif command[:2]==['docker','info']:result={}
                    elif command[:3]==['docker','compose','config']:result={'services':{'gateway':{'ports':[{'target':9090,'published':9090}]}}}
                    elif command[:3]==['docker','compose','ps']:result='\n'.join(sorted(SERVICES))
                    elif command[:2]==['docker','inspect']:result=actual
                    elif command[:3]==['docker','compose','run']:result={'revision':'17','service':profile}
                    elif command[:2]==['docker','run']:
                        commands.append(command)
                        result=''
                    else:raise AssertionError('unexpected external command: '+str(command))
                    return subprocess.CompletedProcess(command,0,stdout=result if isinstance(result,str) else json.dumps(result),stderr='')
                out=pathlib.Path(d)
                (out/'gateway-100-r1.json').write_text('{}')
                argv=[str(ROOT/'benchmarks/run.py'),'--suite','gateway','--rates','100','--repeats','1','--warmup','0','--seconds','1','--output',d]
                if value is not None:argv+=['--generator-memory-mib',str(value)]
                previous=pathlib.Path.cwd()
                try:
                    with patch.object(sys,'argv',argv),patch('subprocess.run',side_effect=fake_run),patch('platform.platform',return_value='test-platform'),patch('platform.processor',return_value='test CPU'),patch('threading.Thread'),patch('urllib.request.urlopen',side_effect=lambda *a,**k:io.BytesIO(b'gateway_config_revision 17\n')),patch('summary.summarize',return_value={}) as summarize,contextlib.redirect_stdout(io.StringIO()):
                        runpy.run_path(str(ROOT/'benchmarks/run.py'),run_name='__main__')
                        summarize.assert_called_once_with({},100,1)
                finally:os.chdir(previous)
                metadata=json.loads((out/'environment.json').read_text())
                self.assertEqual(expected,metadata['generatorLimits']['memoryMiB'])
                self.assertEqual(4,metadata['generatorLimits']['cpus'])
                self.assertEqual('standard',metadata['workloadProfile']['name'])
                self.assertEqual(100,metadata['workloadProfile']['applied']['service']['maxInflight'])
                self.assertEqual(1,len(commands))
                command=commands[0]
                self.assertEqual(str(expected)+'m',command[command.index('--memory')+1])
                self.assertEqual('4',command[command.index('--cpus')+1])
    def test_invalid_workload_rejected_before_external_tools(self):
        for flags in [['--rates','0'],['--warmup','-1'],['--seconds','3','--bucket-seconds','2'],['--vus','0']]:
            r=subprocess.run([sys.executable,str(ROOT/'benchmarks/run.py'),'--suite','acceptance',*flags],env={**os.environ,'PATH':''},text=True,capture_output=True)
            self.assertEqual(2,r.returncode)
            self.assertIn('error:',r.stderr)
if __name__=='__main__':unittest.main()

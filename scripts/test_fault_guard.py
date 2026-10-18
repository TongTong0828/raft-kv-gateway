import pathlib,subprocess,sys,tempfile,unittest,uuid
from fault_ownership import ensure_unused,claim_project,release_lease
ROOT=pathlib.Path(__file__).resolve().parent.parent
class FaultOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.project='kv-guard-'+uuid.uuid4().hex[:12];self.volume=self.project+'_cp1-data';self.container=None
    def run_docker(self,*args):return subprocess.run(['docker',*args],cwd=ROOT,text=True,capture_output=True,check=True).stdout.strip()
    def tearDown(self):
        subprocess.run(['docker','compose','-p',self.project,'down','--volumes'],cwd=ROOT,capture_output=True,timeout=30)
        if self.container:subprocess.run(['docker','rm','-f',self.container],capture_output=True)
        subprocess.run(['docker','volume','rm',self.volume],capture_output=True)
    def test_rejected_existing_stack_keeps_its_volume_and_container(self):
        self.run_docker('volume','create','--label','com.docker.compose.project='+self.project,'--label','com.docker.compose.volume=cp1-data',self.volume)
        self.run_docker('compose','-p',self.project,'up','-d','backend-a')
        with tempfile.TemporaryDirectory(dir=ROOT/'work') as temp:
            result=subprocess.run([sys.executable,'scripts/faults.py','--project',self.project,'--output',temp+'/result.json'],cwd=ROOT,capture_output=True,timeout=30)
        self.assertNotEqual(0,result.returncode)
        self.assertEqual(0,subprocess.run(['docker','volume','inspect',self.volume],capture_output=True).returncode,'rejected ownership must never delete existing data')
        self.assertTrue(self.run_docker('compose','-p',self.project,'ps','-q','backend-a'))
    def test_preserved_volume_without_containers_is_occupied(self):
        self.run_docker('volume','create','--label','com.docker.compose.project='+self.project,self.volume)
        with self.assertRaises(RuntimeError):ensure_unused(self.project)
    def test_two_fault_runs_cannot_claim_the_same_empty_project(self):
        lease=claim_project(self.project)
        try:
            with self.assertRaises(RuntimeError):claim_project(self.project)
        finally:release_lease(lease)
    def test_stopped_container_and_unlabelled_name_collision_are_occupied(self):
        self.container=self.run_docker('create','--label','com.docker.compose.project='+self.project,'raft-kv-gateway:local','-version')
        with self.assertRaises(RuntimeError):ensure_unused(self.project)
        self.run_docker('rm',self.container);self.container=None
        self.run_docker('volume','create',self.volume)
        with self.assertRaises(RuntimeError):ensure_unused(self.project)
if __name__=='__main__':unittest.main()

import copy,unittest
from metadata import SERVICES,validate_actual
def fixture():return [{'Id':service,'Name':'/test-'+service,'Image':'sha256:new','State':{'Running':True},'Config':{'Labels':{'com.docker.compose.service':service,'org.opencontainers.image.revision':'new-commit'}},'HostConfig':{'Memory':123,'NanoCpus':1000000000}} for service in sorted(SERVICES)]
class MetadataTest(unittest.TestCase):
    def test_records_actual_running_images(self):
        result=validate_actual(fixture(),'sha256:new','new-commit');self.assertEqual(SERVICES,{r['service'] for r in result});self.assertTrue(all(r['imageId']=='sha256:new' for r in result))
    def test_rebuilt_tag_does_not_legitimize_old_running_gateway(self):
        old=fixture();next(c for c in old if c['Id']=='gateway')['Image']='sha256:old'
        with self.assertRaises(ValueError):validate_actual(old,'sha256:new','new-commit')
    def test_recreated_backend_revision_and_missing_services_are_checked(self):
        old=fixture();next(c for c in old if c['Id']=='backend-a')['Config']['Labels']['org.opencontainers.image.revision']='old-commit'
        with self.assertRaises(ValueError):validate_actual(old,'sha256:new','new-commit')
        with self.assertRaises(ValueError):validate_actual(fixture()[:-1],'sha256:new','new-commit')
if __name__=='__main__':unittest.main()

import copy,unittest
import metadata

class WorkloadProfileTest(unittest.TestCase):
    def setUp(self):
        self.expected={'serviceId':'orders','pathPrefix':'/orders','endpoints':['backend-a:8081','backend-b:8081'],'requestTimeoutMs':2000,'maxInflight':256,'maxConnectionsPerEndpoint':16}
        self.actual={'revision':'17','service':copy.deepcopy(self.expected)}
    def validate(self,actual):
        fn=getattr(metadata,'validate_workload_profile',None)
        self.assertIsNotNone(fn,'runner must verify the configuration actually applied')
        return fn(actual,self.expected)
    def test_applied_capacity_is_recorded_and_stale_limits_fail_closed(self):
        self.assertEqual(self.actual,self.validate(self.actual))
        for field,value in [('maxInflight',100),('maxConnectionsPerEndpoint',32),('requestTimeoutMs',3000),('endpoints',['backend-a:8081'])]:
            bad=copy.deepcopy(self.actual);bad['service'][field]=value
            with self.assertRaises(ValueError):self.validate(bad)
    def test_missing_or_uncommitted_profile_is_rejected(self):
        for value in [{}, {'revision':'17'}, {'revision':'0','service':self.expected}, {'revision':True,'service':self.expected}]:
            with self.assertRaises(ValueError):self.validate(value)

if __name__=='__main__':unittest.main()

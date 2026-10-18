import unittest
from summary import summarize
class SummaryTest(unittest.TestCase):
    def test_failures_and_drops_remain_visible(self):
        data={'metrics':{'measured_started':{'values':{'count':90}},'measured_success':{'values':{'count':75}},'measured_503':{'values':{'count':10}},'measured_transport_errors':{'values':{'count':5}},'dropped_iterations{scenario:measured}':{'values':{'count':10}},'measured_latency':{'values':{'p(99)':125}},'measured_success_latency':{'values':{'p(99)':40}}}}
        result=summarize(data,10,10)
        self.assertEqual(7.5,result['successfulRps']);self.assertEqual(100,result['offered']);self.assertEqual(15,result['failed']);self.assertEqual(10,result['dropped']);self.assertEqual(125,result['allP99Ms']);self.assertEqual(40,result['successP99Ms'])
    def test_missing_success_counter_is_zero_and_missing_core_metrics_fail(self):
        d={'metrics':{'measured_started':{'values':{'count':3}},'dropped_iterations{scenario:measured}':{'values':{'count':0}}}}
        self.assertEqual(3,summarize(d,1,3)['failed']);self.assertRaises(ValueError,summarize,{},1,3)
if __name__=='__main__':unittest.main()

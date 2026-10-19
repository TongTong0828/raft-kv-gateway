import copy,math,unittest
try:
    from acceptance import summarize_acceptance
except ModuleNotFoundError:
    summarize_acceptance=None

def fixture():
    return {'metrics':{**{name:{'values':{'count':value}} for name,value in {
        'window_started':161000,'window_success':160000,'window_failed':0,'late_success':1000,'late_failed':0,'invalid_completions':0,'window_latency_samples':160000,'window_success_b00':80000,'window_success_b01':80000,'dropped_iterations':23}.items()},
        'window_success_latency_ms':{'values':{'p(99)':15}},'clock_skew_ms':{'values':{'max':1}}}}

class AcceptanceTest(unittest.TestCase):
    def summarize(self,data):
        self.assertIsNotNone(summarize_acceptance,'strict acceptance validator must exist')
        return summarize_acceptance(data,8400,20,8000,15,10)
    def test_exact_thresholds_pass_and_late_success_never_inflates_rate(self):
        r=self.summarize(fixture());self.assertTrue(r['passed']);self.assertEqual(8000,r['successfulRps']);self.assertEqual(1000,r['lateSuccess']);self.assertEqual([8000,8000],r['bucketSuccessfulRps']);self.assertEqual(23,r['totalExperimentDropped'])
    def test_error_during_drain_fails_even_when_window_meets_rate(self):
        d=fixture();d['metrics']['late_success']['values']['count']=999;d['metrics']['late_failed']['values']['count']=1
        r=self.summarize(d);self.assertFalse(r['passed']);self.assertEqual(1,r['failed'])
    def test_interrupted_attempt_cannot_disappear(self):
        d=fixture();d['metrics']['window_started']['values']['count']=161001
        r=self.summarize(d);self.assertFalse(r['passed']);self.assertEqual(1,r['unaccounted'])
    def test_average_cannot_hide_a_slow_ten_second_bucket(self):
        d=fixture();d['metrics']['window_success_b00']['values']['count']=79999;d['metrics']['window_success_b01']['values']['count']=80001
        self.assertFalse(self.summarize(d)['passed'])
    def test_missing_bucket_and_missing_latency_fail_closed(self):
        for key in ['window_success_b01','window_success_latency_ms']:
            d=fixture();del d['metrics'][key]
            with self.assertRaises(ValueError):self.summarize(d)
    def test_malformed_counts_and_percentiles_fail_closed(self):
        for value in [-1,1.5,float('inf'),float('nan'),True,'160000']:
            d=fixture();d['metrics']['window_success']['values']['count']=value
            with self.assertRaises(ValueError):self.summarize(d)
        for value in [float('nan'),float('inf'),-1,'15',True]:
            d=fixture();d['metrics']['window_success_latency_ms']['values']['p(99)']=value
            with self.assertRaises(ValueError):self.summarize(d)
    def test_inconsistent_buckets_or_latency_samples_fail(self):
        for key in ['window_success_b00','window_latency_samples']:
            d=fixture();d['metrics'][key]['values']['count']-=1
            self.assertFalse(self.summarize(d)['passed'])
    def test_clock_jump_or_invalid_completion_invalidates_experiment(self):
        d=fixture();d['metrics']['clock_skew_ms']['values']['max']=1001
        self.assertFalse(self.summarize(d)['passed'])
        d=fixture();d['metrics']['late_success']['values']['count']=999;d['metrics']['invalid_completions']['values']['count']=1
        self.assertFalse(self.summarize(d)['passed'])
    def test_zero_success_has_no_valid_latency(self):
        d=fixture()
        for k in ['window_started','window_success','late_success','window_latency_samples','window_success_b00','window_success_b01']:d['metrics'][k]['values']['count']=0
        d['metrics']['window_success_latency_ms']['values']['p(99)']=0
        r=self.summarize(d);self.assertFalse(r['passed']);self.assertIsNone(r['successP99Ms'])
if __name__=='__main__':unittest.main()

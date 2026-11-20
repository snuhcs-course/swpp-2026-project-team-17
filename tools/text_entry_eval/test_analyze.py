import unittest
from analyze import summarize

class SummaryTests(unittest.TestCase):
    def test_no_trials_is_not_success(self):
        self.assertEqual([],summarize({'session':'s','trials':[]})['groups'])
    def test_failures_interruptions_and_assistance_are_separate(self):
        def row(outcome,success=False,assisted=False):
            return dict(method='음성입력',input_mode='시선으로 조작',touch_assistance_reported=assisted,
                        outcome=outcome,success=success,duration_ms=2000,cer=0,
                        wrong_field_change_events=0,events=[dict(event='voice_ready')])
        data={'session':'s','trials':[row('completed',True),row('failed'),row('interrupted_background'),row('completed',True,True)]}
        groups=summarize(data)['groups']
        self.assertEqual(2,len(groups));self.assertEqual(.5,groups[0]['task_success_rate'])
        self.assertEqual(1,groups[0]['interrupted']);self.assertEqual(2000,groups[0]['median_completed_duration_ms'])

if __name__=='__main__':unittest.main()
